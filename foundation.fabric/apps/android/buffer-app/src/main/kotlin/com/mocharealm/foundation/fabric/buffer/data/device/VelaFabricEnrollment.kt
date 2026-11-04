package com.mocharealm.foundation.fabric.buffer.data.device

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.app.ActivityManager
import android.util.Log
import com.mocharealm.foundation.fabric.buffer.data.coordinator.*
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.sdk.FabricClient
import java.io.IOException
import java.util.UUID
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Application protocol on the framework Hub's owner/ability-authorized port.
 * All keys/trust/GATT/session operations run in Hub UID. This app persists only
 * a non-authoritative discovery hint and its own configuration request IDs.
 * Existing enrollment controls also perform explicit SAS confirmation; there is
 * no automatic acceptance and no fallback to legacy transport for credentials.
 */
internal class VelaFabricEnrollment(
    private val context: Context,
    private val repository: BufferRepository,
    private val client: () -> FabricClient?,
) : AutoCloseable {
    private val thread = HandlerThread("VelaApplicationMessages").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private data class Inbound(val generation: Int,val connection: Long,val bytes: ByteArray)
    private val inbound = ArrayBlockingQueue<Inbound>(8)
    private val draining = AtomicBoolean(false)
    @Volatile private var connection = 0L
    @Volatile private var connectionClient: FabricClient? = null
    @Volatile private var generation = 0
    @Volatile private var active = false
    @Volatile private var closing=false
    private var maximum = 12288
    private var code = -1
    private var address = ""
    private var device = ""
    private var expectedDevice: String? = null
    private var assistantHandoff: VelaAssistantHandoff.Connection? = null
    private var receiver: VelaProcessedCaptureReceiver? = null
    private var sourcePull: VelaVisibleSourceEventPull? = null
    private var sourcePush: VelaVisibleSourceEventPush? = null
    private var sourceParents: VelaSourceEventParents? = null
    private var sourceBlobs: VelaSourceBlobPull? = null
    private var sourceBlobPush: VelaSourceBlobPush? = null
    private var sourceTurn = 0
    private var replicas: VelaCardReplicaSender? = null
    private var calendarReadyRequest: String? = null
    private var calendarRefreshRequest: String? = null
    private var calendarRefreshState = "error"
    private var runtime: VelaRuntimeSender? = null
    private var inputReceiver: VelaPendingCaptureReceiver? = null
    private var coordinator: BufferCoordinatorRuntimeAdapter? = null
    @Volatile private var coordinatorAttachment: BufferCoordinatorAttachment? = null
    private var controllerView: VelaControllerView? = null
    private companion object {
        val modelReadiness = BufferModelReadiness()
        val syncWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work,"VelaSyncWatchdog").apply { isDaemon=true }
        }
    }
    private data class SyncPhase(val name: String,val began: Long)
    @Volatile private var syncPhase=SyncPhase("idle",0)
    private val diagnostics=context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var lastTrace=0L
    private var traceSent=0L
    private var traceBlocked=0L
    private val traceKinds=linkedMapOf<String,Long>()
    private var lastStall=0L
    private val syncWatch=if(diagnostics)syncWatchdog.scheduleWithFixedDelay({
        val phase=syncPhase
        if(!closing&&phase.began>0&&phase.began!=lastStall&&SystemClock.elapsedRealtime()-phase.began>=3000){
            lastStall=phase.began
            Log.w("VelaSync","stalled phase=${phase.name} elapsed_ms=${SystemClock.elapsedRealtime()-phase.began}")
            Log.w("VelaSync",thread.stackTrace.take(18).joinToString("\n"))
        }
    },2,2,java.util.concurrent.TimeUnit.SECONDS)else null
    private inline fun <T> traceWork(name: String,work: ()->T): T {
        if(!diagnostics)return work()
        val previous=syncPhase;val phase=SyncPhase(name,SystemClock.elapsedRealtime());syncPhase=phase
        try{return work()}finally{
            val elapsed=SystemClock.elapsedRealtime()-phase.began
            if(elapsed>=1000)Log.i("VelaSync","phase=$name elapsed_ms=$elapsed")
            syncPhase=previous
        }
    }
    private val receipts = linkedMapOf<String, ByteArray>()
    private var heartbeat: ByteArray? = null
    private var sendAfter = 0L
    private var reconnectVersion = 0
    private var reconnectAttempts = 0
    private var ssid = ""
    private var password = ""
    private var existingWifi = true
    // Survives the pairing -> authenticated Session reconnect. A new pair
    // must choose its network once; ordinary reconnects use the saved network.
    private var networkChoiceRequired = false
    private var networkSubmitted = false
    private var agentSubmitted = false
    private var pending: ByteArray? = null
    private var request = ""
    private var revision = 0L
    private var operation = ""
    private var attempts = 0
    private var deadline = 0L
    private var nextPoll = 0L
    @Volatile var boundDevice: String? = null
        private set
    @Volatile private var lastView: EnrollmentView? = null
    private val viewEpoch=AtomicLong()
    @Volatile private var lastViewEpoch=0L
    @Volatile var listener: ((EnrollmentView) -> Unit)? = null
        set(value) {
            field = value
            val snapshot = lastView
            val epoch=lastViewEpoch
            if (value != null && snapshot != null) main.post {
                if (listener === value && epoch==viewEpoch.get()) value(snapshot)
            }
        }
    private val crdtReceiver = VelaCrdtReceiver(repository)
    private fun authorized(expectedGeneration: Int): Boolean = !closing && active && generation==expectedGeneration &&
        VelaAssistantHandoff.isCurrent(assistantHandoff) && connectionClient===client() && connectionClient?.isConnected==true
    val authenticatedDevice: String? get()=if(authorized(generation))boundDevice else null
    private fun transport(): FabricClient = connectionClient?.takeIf { it===client() && it.isConnected }
        ?:throw IOException("设备传输连接已改变")
    private fun report(message: String, configure: Boolean = false, complete: Boolean = false, running: Boolean = true,
        step: EnrollmentStep? = null, pairingCode: Int? = null, error: Boolean = false) {
        val snapshot = EnrollmentView(message, configure, running, complete,
            step ?: when {
                complete -> EnrollmentStep.COMPLETE
                configure -> EnrollmentStep.NETWORK
                running -> EnrollmentStep.CONNECT
                else -> EnrollmentStep.DISCOVER
            }, pairingCode, error)
        lastView = snapshot
        val epoch=viewEpoch.get();lastViewEpoch=epoch
        main.post { if(epoch==viewEpoch.get())listener?.invoke(snapshot) }
    }
    fun resume(route: Pair<String,String>? = repository.velaFabricRoute()) {
        if(closing)return
        route ?: return
        if(!authorized(generation))viewEpoch.incrementAndGet()
        worker.post { if (connection == 0L) open(route.first, route.second) }
    }
    fun connect(peer: VelaBlePeer, locator: VelaFabricQrLocator? = null) {
      viewEpoch.incrementAndGet()
      worker.post {
        reconnectVersion++; reconnectAttempts = 0
        networkChoiceRequired = false
        closeConnection()
        // A user-selected scan starts identity discovery again. A reset board
        // keeps its BLE address but creates a new Fabric key. Only an explicit
        // QR locator pins this attempt; saved-route resume remains pinned.
        // Unknown keys still require the Hub's two-sided SAS confirmation.
        expectedDevice = locator?.deviceId
        open(peer.address, expectedDevice, locator?.copyPublicKey())
      }
    }
    private fun open(target: String, expected: String?, qrPublicKey: ByteArray? = null) {
        traceWork("open_session") { openImpl(target,expected,qrPublicKey) }
    }
    private fun openImpl(target: String, expected: String?, qrPublicKey: ByteArray?) {
        val hub = client()
        if (hub?.isConnected != true) { fail("请先连接 Device Fabric Hub，再重试添加"); return }
        address = target.uppercase(java.util.Locale.ROOT); expectedDevice = expected
        active = false; code = -1; generation++
        connectionClient=hub
        val current = generation
        try {
            connection = hub.openBleMessages(address, qrPublicKey, BufferConstants.ability,
                "buffer-channel01".toByteArray(), "buffer-phone-v1!".toByteArray(), "buffer-vela--v1!".toByteArray(),
                12288, object : FabricClient.MessageCallback {
                    override fun onState(id: Long, state: String, shortCode: Int, maxMessageBytes: Int, peerDeviceId: ByteArray?) {
                        worker.post { if (!closing && current == generation && id == connection) {
                            if(connectionClient!==hub || client()!==hub || !hub.isConnected){fail("设备传输连接已更新，正在重新核对身份");return@post}
                            try { traceWork("state:$state") { state(state, shortCode, maxMessageBytes, peerDeviceId) } }
                            catch (_: Exception) { fail("设备安全状态校验失败，请重试") }
                        } }
                    }
                    override fun onMessage(id: Long, payload: ByteArray) {
                        if (current != generation || id != connection) return
                        if(payload.size !in 1..12288){worker.post { if(current==generation && id==connection)fail("设备消息超过接收预算，请重新连接") };return}
                        val copy=payload.copyOf()
                        if (!inbound.offer(Inbound(current,id,copy))) {
                            copy.fill(0)
                            worker.post { if (current == generation) fail("设备消息超过接收预算，请重新连接") }; return
                        }
                        drain()
                    }
                })
            if (connection == 0L) fail("无法打开设备消息连接")
            else { report("正在通过 Device Fabric 连接设备"); tick(current) }
        } catch (error: Exception) {
            fail(if(error.message?.lineSequence()?.firstOrNull()=="BLE Session quota exceeded")
                "Hub 连接容量已满，请先断开一台设备后重试" else "无法建立安全连接，请检查 Hub 版本、权限与设备状态")
        }
        finally { qrPublicKey?.fill(0) }
    }
    private fun state(value: String, shortCode: Int, maximum: Int, peer: ByteArray?) {
        when (value) {
            "ConfirmPairing" -> {
                require(shortCode in 0..999999)
                code = shortCode
                networkChoiceRequired = true
                report("请核对两端数字", step = EnrollmentStep.CONFIRM, pairingCode = code)
            }
            "Paired" -> {
                if (peer?.size != 32) { fail("配对设备身份不符合要求"); return }
                val pairedDevice = peer.joinToString("") { "%02x".format(it) }
                if (expectedDevice != null && expectedDevice != pairedDevice) {
                    fail("附近设备身份已改变，已停止配置"); return
                }
                // Pairing has committed trust in the Hub. Retain this discovery
                // hint before the intentional reconnect to the application Session,
                // so a transient disconnect retries this newly paired device.
                expectedDevice = pairedDevice
                repository.saveVelaFabricRoute(address, pairedDevice)
                code = -1
                report("配对已保存，正在建立应用安全连接")
            }
            "Pairing", "Negotiating", "Connecting" -> {
                // Transport progress must not replace the user's SAS decision
                // with a spinner, or hide the device-side confirmation action.
                if (code < 0) report("正在连接设备", step =
                    if (lastView?.step == EnrollmentStep.DEVICE_PAIRING)
                        EnrollmentStep.DEVICE_PAIRING else EnrollmentStep.CONNECT)
            }
            "Active" -> {
                if (peer?.size != 32 || maximum !in 1024..12288) { fail("设备身份或消息能力不符合要求"); return }
                device = peer.joinToString("") { "%02x".format(it) }
                if (expectedDevice != null && expectedDevice != device) { fail("附近设备身份已改变，已停止配置"); return }
                if(active && VelaAssistantHandoff.isCurrent(assistantHandoff) && assistantHandoff?.deviceId==device)return
                this.maximum = maximum
                BufferCoordinatorJobGate.revokeSource(device)
                active = true; boundDevice = device
                assistantHandoff = VelaAssistantHandoff.attach(device)
                reconnectAttempts = 0
                repository.saveVelaFabricRoute(address, device)
                receiver?.close()
                val current = generation
                receiver = VelaProcessedCaptureReceiver(repository, device) {
                    authorized(current)
                }
                sourcePull?.close()
                sourcePull = VelaVisibleSourceEventPull(repository, device, maximum,
                    { authorized(current) }, ::trySend) { state ->
                    Log.w("VelaSourceEvents", "Original history synchronization deferred: $state")
                }
                sourcePush?.close()
                sourcePush = VelaVisibleSourceEventPush(repository, device, maximum,
                    { authorized(current) }, ::trySend) { state ->
                    Log.w("VelaSourceEvents", "Original history delivery deferred: $state")
                }
                sourceParents?.close()
                sourceParents = VelaSourceEventParents(repository, device, maximum,
                    { authorized(current) }, ::trySend,
                    { sourcePull?.refresh() }) { state ->
                    Log.w("VelaSourceEvents", "Original parent discovery deferred: $state")
                }
                sourceBlobs?.close()
                sourceBlobs = VelaSourceBlobPull(repository, device, maximum,
                    { authorized(current) }, ::trySend) { state ->
                    Log.w("VelaSourceEvents", "Original audio delivery deferred: $state")
                }
                sourceBlobPush?.close()
                sourceBlobPush = VelaSourceBlobPush(repository, device, maximum,
                    { authorized(current) }, ::trySend) { state ->
                    Log.w("VelaSourceEvents", "Local original audio delivery deferred: $state")
                }
                runtime?.close()
                runtime = VelaRuntimeSender({ VelaModeProtocol.snapshot(repository) }, ::trySend).also { sender ->
                    sender.requireAuthority { BufferCoordinatorJobGate.deviceTermIfCurrent(coordinatorAttachment) }
                }
                replicas?.close()
                replicas = VelaCardReplicaSender({ repository.velaReplicaPage(it) }, documentMode = true,
                    authorizeSnapshot = { repository.mayShareCardSnapshotToPeer(it, device) },
                    resumeAfter = repository.velaReplicaResume(device),
                    priorityPage = { repository.velaLiveCalendarReplicaPage(it) },
                    priorityResumeAfter = repository.velaCalendarReplicaResume(device),
                    savePriorityProgress = { repository.saveVelaCalendarReplicaResume(device,it) },
                    saveProgress = { repository.saveVelaReplicaResume(device,it) },
                    syncFinished = { request, complete ->
                        if (calendarReadyRequest == request) queueReceipt(JSONObject()
                            .put("type", "buffer.calendar.status").put("protocol", 1).put("request_id", request)
                            .put("state", if (complete) "ready" else "error").toString().toByteArray(Charsets.UTF_8))
                    }, send = { trySend(it) })
                inputReceiver?.close()
                inputReceiver = VelaPendingCaptureReceiver(repository, device) {
                    authorized(current)
                }
                val local = transport().localDeviceId.also { require(it.size == 32) }
                    .joinToString("") { "%02x".format(it) }
                fun readiness(): CoordinatorReadiness {
                    val profile = repository.llmConfig()
                    modelReadiness.request(profile)
                    val process = ActivityManager.RunningAppProcessInfo()
                    ActivityManager.getMyMemoryState(process)
                    val available = process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                    val mesh = runCatching { repository.meshGatewayCapabilities(device) }.getOrDefault(emptySet())
                    val localReady = modelReadiness.ready(profile)
                    val capable = if (localReady) CoordinatorCapability.entries.toSet() else mesh
                    val completePipeline = capable.containsAll(setOf(CoordinatorCapability.TRANSCRIBE, CoordinatorCapability.CLASSIFY))
                    // A gateway may own device input while qualified members
                    // execute its pure model stages. Device actions still use
                    // this authenticated BLE owner and its current term.
                    return CoordinatorReadiness(profile.ready || completePipeline, localReady || completePipeline, available,
                        true, completePipeline, capable)
                }
                val localClass = if (context.resources.configuration.smallestScreenWidthDp >= 600) CoordinatorDeviceClass.TABLET else CoordinatorDeviceClass.PHONE
                fun pauseOwnership() {
                    coordinator = null
                    heartbeat?.fill(0); heartbeat = null
                    BufferCoordinatorJobGate.detach(coordinatorAttachment);coordinatorAttachment=null
                    if(authorized(current))repository.clearVelaControllerView(device)
                }
                pauseOwnership()
                controllerView = VelaControllerView(repository, local, device, ::trySend, ::pauseOwnership) { members, membership ->
                    check(authorized(current))
                    val election = BufferCoordinatorElection("buffer-v1-$membership", membership,
                        CoordinatorMember(local, localClass), members.map { id ->
                            CoordinatorMember(id, when (id) {
                                local -> localClass
                                device -> CoordinatorDeviceClass.VELA
                                // The roster authenticates identity, not device class.
                                // Only the device authority receives each peer's
                                // own readiness/class announcement on its real link.
                                else -> CoordinatorDeviceClass.OTHER
                            })
                        }, readiness(), SharedPreferencesCoordinatorStateStore(context), missedHeartbeats = 10)
                    coordinatorAttachment=BufferCoordinatorJobGate.attach(election, device, local, membership) { authorized(current) }
                    coordinator = BufferCoordinatorRuntimeAdapter(election, ::readiness,
                        { payload ->
                            // BLE owner negotiation adds the actual local class;
                            // the separate mesh heartbeat schema stays unchanged.
                            val frame=try { JSONObject(payload.toString(Charsets.UTF_8)).put("device_class",localClass.name)
                                .toString().toByteArray(Charsets.UTF_8) } finally { payload.fill(0) }
                            heartbeat?.fill(0);heartbeat=frame;true
                        },
                        { target, payload -> if (target == device) { queueReceipt(payload); true } else false },
                        { inputReceiver != null })
                }
                report("设备已绑定，正在检查联网及 Agent 配置")
                configureNetworkOrAgent()
            }
            else -> fail("设备安全连接已中断（$value），已保存的绑定保持有效")
        }
    }
    fun confirmPairing() = worker.post {
        val displayed = code
        if (displayed < 0 || connection == 0L) return@post
        try {
            val session = transport()
            session.confirmBlePairing(connection, displayed, true)
            code = -1
            report("请在设备上确认相同数字", step = EnrollmentStep.DEVICE_PAIRING)
        } catch (_: Exception) { fail("配对确认失败，请重试") }
    }
    fun submit(network: String, secret: String, useExisting: Boolean = false) = worker.post {
        ssid = network; password = secret; existingWifi = useExisting
        if (!useExisting && ssid.isBlank()) { report("请填写 Wi-Fi 名称或使用已保存网络", configure = true); return@post }
        networkChoiceRequired = false
        val displayed = code
        if (displayed >= 0) {
            try { transport().confirmBlePairing(connection, displayed, true); code = -1
                report("手机已确认数字，请在 S1 上确认配对") }
            catch (_: Exception) { fail("配对确认失败，请重试") }
        } else if (active) { networkSubmitted = false; agentSubmitted = false; configureNetworkOrAgent() }
    }
    private fun configureNetworkOrAgent() {
        if (networkChoiceRequired || (!networkSubmitted && !existingWifi && ssid.isBlank())) {
            report("选择设备使用的网络", configure = true)
            return
        }
        if (!networkSubmitted && !existingWifi) {
            networkSubmitted = true
            try { queue("network") { id, rev -> VelaOobeApplicationProtocol.encodeWifiConfiguration(id, rev, ssid, password) } }
            catch (_: Exception) { networkSubmitted = false; report("Wi-Fi 配置格式无效，请检查网络名称及密码", configure = true) }
            return
        }
        password = ""
        if (!agentSubmitted) {
            agentSubmitted = true
            val profile = repository.llmConfig()
            if (!profile.ready || profile.apiKey.isBlank()) {
                queue("skip") { id, rev -> VelaOobeApplicationProtocol.encodeSkipAgentConfiguration(id, rev) }
            } else {
                try { queue("agent") { id, rev -> VelaOobeApplicationProtocol.encodeAgentConfiguration(id, rev, profile,
                    TimeZone.getDefault().id, java.util.Locale.getDefault().toLanguageTag()) } }
                catch (_: Exception) { agentSubmitted = false; report("已绑定；手机服务配置不适用于设备，请检查 HTTPS 地址及凭据", configure = true) }
            }
        } else if (pending == null) queueStatus()
    }
    private fun queueStatus() = queue("status") { id, rev -> VelaOobeApplicationProtocol.encodeStateRequest(id, rev) }
    private fun queue(kind: String, encode: (String, Long) -> ByteArray) {
        pending?.fill(0); pending = null
        if (kind != "status") {
            val probe = encode("0".repeat(32), 1)
            val hash = try { java.security.MessageDigest.getInstance("SHA-256").digest(probe)
                .joinToString("") { "%02x".format(it) } } finally { probe.fill(0) }
            val stable = repository.velaConfigurationRequest(device, kind, hash)
            request = stable.first; revision = stable.second
        } else {
            request = UUID.randomUUID().toString().replace("-", "")
            revision = System.currentTimeMillis().coerceAtLeast(revision + 1)
        }
        operation = kind; attempts = 0; deadline = 0
        pending = encode(request, revision).also { require(it.size <= maximum) { "negotiated message budget too small" } }
    }
    private fun drain() {
        if (draining.compareAndSet(false, true)) worker.post {
            draining.set(false)
            while (true) {
                val frame = inbound.poll() ?: break
                val bytes=frame.bytes
                var incomingType = "unparsed"
                val previousPhase=syncPhase
                try {
                    if (frame.generation!=generation || frame.connection!=connection || !authorized(frame.generation)) continue
                    val type = JSONObject(bytes.toString(Charsets.UTF_8)).optString("type")
                    incomingType = type.takeIf { it.matches(Regex("[a-z_.]{1,64}")) } ?: "unknown"
                    if(diagnostics)syncPhase=SyncPhase("receive:$incomingType",SystemClock.elapsedRealtime())
                    if (type == "buffer.vela.members" || type == "buffer.vela.members.accepted") controllerView?.receive(bytes)
                    else if (type == "vela.capture.assistant.reply") assistantHandoff?.receive(bytes)
                    else if (type.startsWith("buffer.events.")) {
                        val now = SystemClock.elapsedRealtime()
                        sourcePull?.receive(bytes, now)
                        sourcePush?.receive(bytes, now)
                        sourceParents?.receive(bytes, now)
                        sourceBlobs?.receive(bytes, now)
                        sourceBlobPush?.receive(bytes, now)
                    }
                    else if (type == "vela.oobe.reply") reply(bytes)
                    else if (type == "coordinator_heartbeat" || type == "coordinator_lease_grant") {
                        if (type == "coordinator_heartbeat") {
                            controllerView?.legacyHeartbeat(bytes)
                            BufferCoordinatorJobGate.onDeviceHeartbeat(coordinatorAttachment,bytes,SystemClock.elapsedRealtime())
                        }
                        coordinator?.onAuthenticatedMessage(device, bytes, SystemClock.elapsedRealtime())
                    }
                    else if (type == "vela.capture.input.part") {
                        val ack = requireNotNull(inputReceiver).receiveAuthenticated(device,bytes)
                        queueReceipt(ack)
                    }
                    else if (type == "buffer.mode.change") {
                        queueReceipt(VelaModeProtocol.apply(bytes, repository) { authorized(frame.generation) })
                        main.post {
                            (context.applicationContext as? com.mocharealm.foundation.fabric.buffer.BufferApplication)
                                ?.careScheduler?.onAlarm()
                        }
                    }
                    else if (type == "buffer.runtime.reply") runtime?.acknowledge(bytes, SystemClock.elapsedRealtime())
                    else if (type == "buffer.sync.request") {
                        val request = JSONObject(bytes.toString(Charsets.UTF_8))
                        val requestId = request.getString("request_id")
                        val range = request.optJSONObject("calendar_range")
                        if (range != null) {
                            if (calendarRefreshRequest != requestId) {
                                val result = runCatching { com.mocharealm.foundation.fabric.buffer.BufferCalendarStore.refresh(context, repository, range.getLong("start"), range.getLong("end")) }
                                calendarRefreshState = if (result.isFailure) "error" else if (result.getOrDefault(false)) "transferring" else "permission_required"
                                calendarRefreshRequest = requestId
                            }
                            val state = calendarRefreshState
                            calendarReadyRequest = if (state == "transferring") requestId else null
                            queueReceipt(JSONObject().put("type", "buffer.calendar.status").put("protocol", 1).put("request_id", requestId).put("state", state).toString().toByteArray(Charsets.UTF_8))
                        }
                        replicas?.requestSync(bytes, SystemClock.elapsedRealtime())
                    }
                    else if (type == "buffer.crdt.part") {
                        val current = generation
                        queueReceipt(crdtReceiver.receive(bytes, device) { authorized(current) })
                    }
                    else if (type == "buffer.crdt.reply") replicas?.acknowledge(bytes, SystemClock.elapsedRealtime())
                    else if (type == "buffer.card.replica.reply") replicas?.acknowledge(bytes, SystemClock.elapsedRealtime())
                    else if (type == "buffer.card.action") {
                        val ack = VelaCardActionProtocol.apply(bytes, repository,device) {authorized(frame.generation)}
                        queueReceipt(ack)
                    }
                    else if (type == "buffer.card.state.change") {
                        val ack = VelaCardStateProtocol.apply(bytes, device, repository) {authorized(frame.generation)}
                        queueReceipt(ack)
                    }
                    else {
                        val ack = requireNotNull(receiver).receiveAuthorized(bytes)
                        queueReceipt(ack)
                    }
                } catch (error: Exception) {
                    val reasons = setOf("unexpected capture input fields", "invalid WAV capture size or duration",
                        "invalid capture timestamp", "invalid Buffer mode", "invalid capture chunk size or encoding",
                        "invalid capture chunk encoding", "invalid capture offset", "invalid capture hash",
                        "Fabric receive authorization required", "unexpected authenticated Fabric peer")
                    val safe = error.message?.takeIf { it in reasons } ?: error.javaClass.simpleName
                    Log.w("VelaApplication", "Message rejected type=$incomingType reason=$safe")
                    report("设备消息校验失败；未确认的记录会保留在设备上")
                }
                finally { syncPhase=previousPhase;bytes.fill(0) }
            }
        }
    }
    private fun reply(bytes: ByteArray) {
        if (pending == null) return
        val value = VelaOobeApplicationProtocol.decodeReply(bytes, request, revision)
        pending?.fill(0); pending = null
        if (value.state == "failed") {
            password = ""
            report("设备配置未完成，请检查设备提示后重试", configure = true, error = true)
            return
        }
        if (operation == "network") { password = ""; configureNetworkOrAgent(); return }
        when (value.state) {
            "ready" -> {
                if (value.setupComplete) {
                    report("设备设置已完成，可以开始使用", complete = true, running = false)
                } else {
                    report("请在设备上点击“开始使用”", complete = false, step = EnrollmentStep.DEVICE_FINISH)
                    nextPoll = SystemClock.elapsedRealtime() + 2000
                }
            }
            "skipped" -> report("请先在手机设置 AI 服务", configure = true, complete = false, running = false,
                step = EnrollmentStep.SERVICE_SETUP)
            "network_pending" -> report("设备尚未联网，请配置 Wi-Fi 后继续", configure = true)
            else -> { report("正在检查设备服务", step = EnrollmentStep.VERIFY); nextPoll = SystemClock.elapsedRealtime() + 2000 }
        }
    }
    private fun tick(current: Int) { traceWork("tick") { tickImpl(current) } }
    private fun tickImpl(current: Int) {
        if (current != generation || connection == 0L) return
        if(connectionClient!==client() || connectionClient?.isConnected!=true){fail("设备传输连接已更新，正在重新核对身份");return}
        if(active && !VelaAssistantHandoff.isCurrent(assistantHandoff)) {
            reconnectVersion++;closeConnection();report("此设备已由新的本机连接接管",running=false);return
        }
        try {
            val now = SystemClock.elapsedRealtime()
            if(diagnostics&&now-lastTrace>=5000){lastTrace=now;Log.i("VelaSync",
                "active=$active pending=${pending!=null} operation=$operation attempts=$attempts receipts=${receipts.size} heartbeat=${heartbeat!=null} inbound=${inbound.size} sent=$traceSent blocked=$traceBlocked types=$traceKinds replica=${replicas?.diagnosticState()}")}
            if (active) {
                traceWork("controller") { controllerView?.tick(now) }
                // A processing worker can advance the shared domain clock
                // after this loop sampled now. Retry that tick without
                // tearing down the authenticated transport.
                try { traceWork("coordinator") { coordinator?.tick(now) } } catch (_: IllegalArgumentException) { }
                coordinatorAttachment?.let { traceWork("coordinator_gate") { BufferCoordinatorJobGate.tick(it,SystemClock.elapsedRealtime()) } }
            }
            if (active && receipts.isNotEmpty()) {
                val first = receipts.entries.first()
                if (trySend(first.value)) { receipts.remove(first.key); first.value.fill(0) }
            } else if (active) heartbeat?.let {
                if (trySend(it)) {
                    BufferCoordinatorJobGate.recordDeviceAuthorityRequest(coordinatorAttachment,it,now)
                    it.fill(0); heartbeat = null
                }
            }
            if (active && receipts.isEmpty() && heartbeat == null) assistantHandoff?.tick(now, ::trySend)
            val message = pending
            if (active && receipts.isEmpty() && message != null && now >= deadline) {
                if (attempts >= 12) { pending?.fill(0); pending = null; report("设备回执超时，绑定保持有效，请重试设置", configure = true) }
                else if (trySend(message)) { attempts++; deadline = now + 5000 }
                else deadline = now + 100
            } else if (active && message == null && nextPoll > 0 && now >= nextPoll) { nextPoll = 0; queueStatus() }
            if (active && pending == null && receipts.isEmpty() && heartbeat == null) {
                try {
                    traceWork("runtime") { runtime?.tick(now) }
                    repeat(6) { index ->
                        val lane = (sourceTurn + index) % 6
                        // Finish the current UI calendar pass before asking the
                        // NAND-backed peer to scan original-history/blob indexes.
                        // Control traffic and incoming durable receipts still drain
                        // above; retained history resumes after this bounded pass.
                        if (lane != 0 && replicas?.prioritizingCurrentCalendar == true) return@repeat
                        when (lane) {
                            0 -> traceWork("replicas") { replicas?.tick(now) }
                            1 -> traceWork("source_pull") { sourcePull?.tick(now) }
                            2 -> traceWork("source_push") { sourcePush?.tick(now) }
                            3 -> traceWork("source_parents") { sourceParents?.tick(now) }
                            4 -> traceWork("source_blobs") { sourceBlobs?.tick(now) }
                            else -> traceWork("source_blob_push") { sourceBlobPush?.tick(now) }
                        }
                    }
                    sourceTurn = (sourceTurn + 1) % 6
                }
                catch (error: Exception) {
                    Log.w("VelaSync","scheduler failed=${error.javaClass.simpleName}\n"+error.stackTrace.take(10).joinToString("\n"))
                    report("卡片副本暂未完成，原记录已保留；稍后重试")
                }
            }
        } catch (_: Exception) { fail("安全连接不可用，请稍后重试") }
        if (current == generation && connection != 0L) worker.postDelayed({ tick(current) }, 100)
    }
    private fun trySend(bytes: ByteArray): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!authorized(generation) || now < sendAfter) return false
        val queued = traceWork("transport_send") { connectionClient?.sendBleMessage(connection, bytes) == true }
        if(queued){
            traceSent++
            if(diagnostics){
                val type=runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("type") }.getOrDefault("unknown")
                    .takeIf { it.matches(Regex("[a-z_.]{1,64}")) }?:"unknown"
                traceKinds[type]=(traceKinds[type]?:0L)+1
            }
        }else traceBlocked++
        if (queued) sendAfter = now + 100
        return queued
    }
    private fun queueReceipt(bytes: ByteArray) {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(receipts.size < 16 || key in receipts) { "receipt budget exceeded" }
        receipts.put(key, bytes)?.fill(0)
    }
    fun cancel() {
      viewEpoch.incrementAndGet()
      worker.post {
        reconnectVersion++
        if (code >= 0) runCatching { connectionClient?.confirmBlePairing(connection, code, false) }
        closeConnection(); report("已取消添加；已保存的绑定与记录不会删除", running = false)
      }
    }
    private fun fail(message: String) {
        Log.w("VelaApplication", "Connection lost: $message")
        val attemptedAddress = address
        val attemptedDevice = expectedDevice
        closeConnection(); report(message, running = false, error = true)
        val route = try { repository.velaFabricRoutes().firstOrNull { it.first.equals(attemptedAddress,true) &&
            (attemptedDevice==null || it.second==attemptedDevice) } } catch (_: Exception) { return } ?: return
        // A failed attempt to add another device must not silently switch back
        // to a previously saved peer and report its setup as the new result.
        if (!route.first.equals(attemptedAddress,true) ||
            (attemptedDevice != null && route.second != attemptedDevice)) return
        val retry = ++reconnectVersion
        val delay = (1000L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempts++
        worker.postDelayed({
            if (retry == reconnectVersion && connection == 0L) open(route.first, route.second)
        }, delay)
    }
    private fun closeConnection() { traceWork("close_session") { closeConnectionImpl() } }
    private fun closeConnectionImpl() {
        crdtReceiver.close()
        controllerView = null
        if(VelaAssistantHandoff.isCurrent(assistantHandoff))repository.clearVelaControllerView(device)
        generation++; active = false; code = -1
        val id = connection; connection = 0
        val transport=connectionClient;connectionClient=null
        if (id != 0L) runCatching { transport?.closeBleMessages(id) }
        VelaAssistantHandoff.detach(assistantHandoff); assistantHandoff = null
        receiver?.close(); receiver = null
        while(true){val frame=inbound.poll()?:break;frame.bytes.fill(0)}
        replicas?.close(); replicas = null; calendarReadyRequest = null; calendarRefreshRequest = null
        sourcePull?.close(); sourcePull = null
        sourcePush?.close(); sourcePush = null
        sourceParents?.close(); sourceParents = null
        sourceBlobs?.close(); sourceBlobs = null
        sourceBlobPush?.close(); sourceBlobPush = null
        sourceTurn = 0
        runtime?.close(); runtime = null
        inputReceiver?.close(); inputReceiver = null; coordinator = null
        BufferCoordinatorJobGate.detach(coordinatorAttachment);coordinatorAttachment=null
        receipts.values.forEach { it.fill(0) }; receipts.clear()
        heartbeat?.fill(0); heartbeat = null; sendAfter = 0
        pending?.fill(0); pending = null; password = ""; ssid = ""
        networkSubmitted = false; agentSubmitted = false; existingWifi = true; nextPoll = 0
    }
    override fun close() {
        closing=true;syncWatch?.cancel(false);viewEpoch.incrementAndGet()
        // Serialize explicit disconnect with the short task commit fence.
        // A replacement connection owns a different attachment and survives.
        BufferCoordinatorJobGate.detach(coordinatorAttachment)
        worker.post { reconnectVersion++; closeConnection(); thread.quitSafely() }
    }
}
