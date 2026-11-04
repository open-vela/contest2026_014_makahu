package com.mocharealm.foundation.fabric.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.mocharealm.foundation.fabric.ipc.IFabricMessageCallback
import com.mocharealm.foundation.fabric.ipc.IFabricAbility
import com.mocharealm.foundation.fabric.ipc.IFabricHub
import com.mocharealm.foundation.fabric.ipc.IFabricResultCallback

/**
 * Client for a Device Fabric hub running on this Android device.
 *
 * This wraps the hub's AIDL contract and service binding — the AIDL files, the
 * [ServiceConnection], the bind [Intent], and the async result-callback plumbing
 * — so an app no longer copies any of it. Call [connect] once, use [invoke] /
 * [openStream] / [devices] / [registerAbility] while connected, and [close] when
 * done.
 *
 * All hub calls run on the binder thread pool; the callbacks below are invoked
 * off the main thread, so marshal to the UI thread yourself as needed.
 */
class FabricClient(private val context: Context) {

    /** Receives the reply (or error) of an [invoke]. */
    fun interface ResultCallback {
        fun onResult(payload: ByteArray?, error: String?)
    }

    /** Notified when the connection to the hub is established or lost. */
    fun interface ConnectionCallback {
        fun onConnectionChanged(connected: Boolean)
    }

    /** Handles invocations and stream opens for an ability this app provides. */
    interface AbilityHandler {
        fun onInvoke(requestId: Long, payload: ByteArray)
        fun onStreamOpen(
            requestId: Long,
            ability: String,
            streamName: String,
            readFromRemote: ParcelFileDescriptor,
            writeToRemote: ParcelFileDescriptor,
        )
    }

    @Volatile private var hub: IFabricHub? = null
    private val connectionEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private data class OwnedApplicationSession(val owner: IFabricHub, val epoch: Long)
    private val applicationSessions = java.util.concurrent.ConcurrentHashMap<String, OwnedApplicationSession>()
    /** Changes whenever the bound Hub connection is replaced or lost. */
    val connectionGeneration: Long get() = connectionEpoch.get()
    private var connectionCallback: ConnectionCallback? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            hub = IFabricHub.Stub.asInterface(service)
            connectionEpoch.incrementAndGet()
            connectionCallback?.onConnectionChanged(true)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            hub = null
            connectionEpoch.incrementAndGet()
            connectionCallback?.onConnectionChanged(false)
        }
    }

    /** True once bound to the hub service. */
    val isConnected: Boolean get() = hub != null

    /**
     * Hub-authenticated local Fabric DeviceId as 32 raw bytes. This is the same
     * identity used by BLE peer IDs; `LOCAL_DEVICE` remains only a route alias.
     */
    val localDeviceId: ByteArray
        get() = requireHub().localDeviceId().copyOf()

    /**
     * Binds the hub service, invoking [onConnection] when the connection is
     * established or dropped. Returns false if the bind could not be initiated
     * (e.g. the hub app is not installed).
     */
    fun connect(onConnection: ConnectionCallback): Boolean {
        connectionCallback = onConnection
        val intent = Intent(HUB_ACTION).setComponent(ComponentName(HUB_PACKAGE, HUB_SERVICE))
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return bound
    }

    /** Unbinds from the hub service. */
    fun close() {
        connectionEpoch.incrementAndGet()
        val closing = applicationSessions.entries.toList().filter { (id, session) -> applicationSessions.remove(id, session) }
        if (closing.isNotEmpty()) Thread({
            closing.forEach { (id, session) -> runCatching { session.owner.closeSession(id) } }
        }, "FabricSessionCleanup").start()
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        hub = null
        connectionEpoch.incrementAndGet()
    }

    private fun requireHub(): IFabricHub =
        hub ?: throw IllegalStateException("not connected to the hub; call connect() first")

    /** The device IDs the hub knows about. */
    fun devices(): Array<String> = requireHub().devices()

    /** The hub's health/status string. */
    fun status(): String = requireHub().status()

    /** Requires this app's live registered provider. Closing or losing this
     * Session fences further scoped invocations; there is no unscoped fallback. */
    fun openApplicationSession(deviceIds: Array<String>, ability: String, config: ByteArray): String {
        val owner = requireHub(); val epoch = connectionEpoch.get()
        val id = owner.openApplicationSession(deviceIds.copyOf(), ability, config.copyOf()).orEmpty()
        if (id.isEmpty()) return ""
        if (hub !== owner || connectionEpoch.get() != epoch) { runCatching { owner.closeSession(id) }; return "" }
        applicationSessions[id] = OwnedApplicationSession(owner, epoch)
        return id
    }
    /** Local connection fence only. The Hub also checks the live Session on
     * every scoped invocation; callers must not infer remote readiness here. */
    fun applicationSessionCurrent(sessionId: String): Boolean = applicationSessions[sessionId]?.let {
        hub === it.owner && connectionEpoch.get() == it.epoch
    } ?: false
    fun applicationSessionActive(sessionId: String): Boolean = applicationSessions[sessionId]?.let {
        applicationSessionCurrent(sessionId) && it.owner.applicationSessionActive(sessionId)
    } ?: false
    fun invocationSession(requestId: Long): String? = requireHub().invocationSession(requestId)
    fun closeSession(sessionId: String) {
        val session = applicationSessions.remove(sessionId)
        if (session != null) session.owner.closeSession(sessionId) else requireHub().closeSession(sessionId)
    }
    fun invokeSession(sessionId: String, deviceId: String, ability: String, payload: ByteArray, callback: ResultCallback): Long {
        val session = requireNotNull(applicationSessions[sessionId]) { "application Session is not owned by this client" }
        check(applicationSessionCurrent(sessionId)) { "Session Hub connection changed" }
        return session.owner.invokeSession(sessionId, deviceId, ability, payload, object : IFabricResultCallback.Stub() {
            override fun onResult(requestId: Long, resultPayload: ByteArray?, error: String?) {
                if (applicationSessions[sessionId] === session && applicationSessionCurrent(sessionId)) callback.onResult(resultPayload, error)
                else callback.onResult(null, "application Session changed")
            }
        })
    }

    /**
     * Invokes [ability] on [deviceId] with [payload]; [callback] receives the
     * reply. Returns the request id. Use `"local"` for an ability on this hub.
     */
    fun invoke(
        deviceId: String,
        ability: String,
        payload: ByteArray,
        callback: ResultCallback,
    ): Long = requireHub().invoke(
        deviceId,
        ability,
        payload,
        object : IFabricResultCallback.Stub() {
            override fun onResult(requestId: Long, resultPayload: ByteArray?, error: String?) {
                callback.onResult(resultPayload, error)
            }
        },
    )

    /**
     * Opens a named stream to [ability] on [deviceId], returning the
     * `[readEnd, writeEnd]` file descriptors from the hub.
     */
    fun openStream(
        deviceId: String,
        ability: String,
        streamName: String,
    ): Array<ParcelFileDescriptor> = requireHub().openStream(deviceId, ability, streamName)

    /**
     * Registers [ability] for this app, dispatching incoming invocations and
     * streams to [handler]. Complete an invocation with [complete].
     */
    fun registerAbility(ability: String, handler: AbilityHandler) {
        requireHub().registerAbility(
            ability,
            context.packageName,
            object : IFabricAbility.Stub() {
                override fun onInvoke(requestId: Long, payload: ByteArray?) {
                    handler.onInvoke(requestId, payload ?: ByteArray(0))
                }

                override fun onStreamOpen(
                    requestId: Long,
                    streamAbility: String?,
                    streamName: String?,
                    readFromRemote: ParcelFileDescriptor?,
                    writeToRemote: ParcelFileDescriptor?,
                ) {
                    if (streamAbility != null &&
                        streamName != null &&
                        readFromRemote != null &&
                        writeToRemote != null
                    ) {
                        handler.onStreamOpen(
                            requestId,
                            streamAbility,
                            streamName,
                            readFromRemote,
                            writeToRemote,
                        )
                    }
                }
            },
        )
    }

    /** Unregisters a previously [registerAbility]-ed ability. */
    fun unregisterAbility(ability: String) {
        requireHub().unregisterAbility(ability, context.packageName)
    }

    /** Completes an invocation delivered to an [AbilityHandler]. */
    fun complete(requestId: Long, payload: ByteArray) {
        requireHub().complete(requestId, payload)
    }

    /** Provider-only identity for this live invocation. Never substitutes for
     * an application membership approval or an execution/channel grant. */
    fun invocationPeer(requestId: Long): ByteArray? = requireHub().invocationPeer(requestId)?.also {
        check(it.size == 32) { "invalid invocation origin" }
    }?.copyOf()

    /** Events belong to the authenticated Hub UID. shortCode is only present
     * for explicit user confirmation; Active is separate from GATT write success. */
    interface MessageCallback {
        fun onState(connectionId: Long, state: String, shortCode: Int, maxMessageBytes: Int, peerDeviceId: ByteArray?)
        fun onMessage(connectionId: Long, payload: ByteArray)
    }

    fun openBleMessages(address: String, qrPublicKey: ByteArray?, ability: String,
        channelId: ByteArray, localPortId: ByteArray, peerPortId: ByteArray,
        maxMessageBytes: Int, callback: MessageCallback): Long = requireHub().openBleMessages(
            address, qrPublicKey, ability, channelId, localPortId, peerPortId, maxMessageBytes,
            object : IFabricMessageCallback.Stub() {
                override fun onState(id: Long, state: String?, code: Int, max: Int, peer: ByteArray?) {
                    callback.onState(id, state ?: "Closed", code, max, peer)
                }
                override fun onMessage(id: Long, payload: ByteArray?) {
                    if (payload != null) callback.onMessage(id, payload)
                }
            })

    fun confirmBlePairing(connectionId: Long, shortCode: Int, accepted: Boolean) =
        requireHub().confirmBlePairing(connectionId, shortCode, accepted)

    /** true means accepted by the Hub's bounded queue, not remote execution.
     * Keep application request IDs and wait for the application's result. */
    fun sendBleMessage(connectionId: Long, payload: ByteArray): Boolean =
        requireHub().sendBleMessage(connectionId, payload)

    fun closeBleMessages(connectionId: Long) = requireHub().closeBleMessages(connectionId)

    companion object {
        /** DeviceId that routes to an ability served on this hub. */
        const val LOCAL_DEVICE = "local"

        private const val HUB_PACKAGE = "com.mocharealm.foundation.fabric"
        private const val HUB_SERVICE = "com.mocharealm.foundation.fabric.FabricHubService"
        private const val HUB_ACTION = "com.mocharealm.foundation.fabric.BIND_HUB"
    }
}
