package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.ui.BufferScreen
import com.mocharealm.foundation.fabric.buffer.ui.BufferTheme
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import com.mocharealm.foundation.fabric.buffer.data.device.BufferWire
import com.mocharealm.foundation.fabric.buffer.domain.CalendarPriority
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.FindCardUseCase
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncRecovery
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.RecordingDestination
import com.mocharealm.foundation.fabric.buffer.domain.RecordingFinalizer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBridgeServer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.ui.VoiceButton
import com.mocharealm.foundation.fabric.buffer.ui.actionLabel
import com.mocharealm.foundation.fabric.buffer.ui.cardStateLabel
import com.mocharealm.foundation.fabric.buffer.ui.healthCategoryLabel
import com.mocharealm.foundation.fabric.buffer.ui.modeLabel
import android.widget.EditText
import android.widget.Button as AndroidButton
import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.onClick
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private data class WritableCalendar(
    val id: Long,
    val label: String,
)

class MainActivity : ComponentActivity() {
    private lateinit var repository: BufferRepository
    private lateinit var bridge: VelaBridgeServer
    private lateinit var fabricClient: FabricClient
    private lateinit var velaDiscovery: VelaDiscovery
    private lateinit var healthExporter: HealthConnectExporter
    private val pairingExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var reminderCardId by mutableStateOf<String?>(null)
    private var reminderCard by mutableStateOf<BufferCard?>(null)
    private var cards by mutableStateOf(emptyList<BufferCard>())
    private var shelfCards by mutableStateOf(emptyList<BufferCard>())
    private var answerBoxCards by mutableStateOf(emptyList<BufferCard>())
    private var lifeLogCards by mutableStateOf(emptyList<BufferCard>())
    private var weeklySummary by mutableStateOf(BufferWeeklySummary(0, 0, 0, 0, 0, emptyList()))
    private var pendingAudio by mutableStateOf(emptyList<BufferCapture>())
    private var textInput by mutableStateOf("")
    private var quickCaptureTarget by mutableStateOf<QuickCaptureTarget?>(null)
    private var status by mutableStateOf("正在启动 Buffer")
    private var bedtimeSchedule by mutableStateOf("")
    private var bridgeRunning by mutableStateOf(false)
    private var fabricConnected by mutableStateOf(false)
    private var mode by mutableStateOf("normal")
    private var researchSession by mutableStateOf<RabbitHoleSession?>(null)
    private var pendingCaptures by mutableStateOf(0)
    private var eventCount by mutableStateOf(0)
    private var discoveredVelas by mutableStateOf(emptyList<VelaPeer>())
    private var pairedVelaIds by mutableStateOf(emptyList<String>())
    private var pairingCode by mutableStateOf("")
    private var scanningVelas by mutableStateOf(false)
    private var pairingVela by mutableStateOf(false)
    private var activeRecording: ActiveRecording? = null
    private var pendingCalendarCard: BufferCard? = null
    private var pendingCalendarAction: String? = null
    private var pendingHealthCard: BufferCard? = null
    private val healthSyncInFlight = mutableSetOf<String>()
    private val calendarWriteInFlight = mutableSetOf<String>()
    private var fabricAbilityRegistered = false
    private var fabricConnectRequested = false

    private val fabricAbilityHandler = object : FabricClient.AbilityHandler {
        override fun onInvoke(requestId: Long, payload: ByteArray) {
            val response = try {
                BufferWire.handle(repository, JSONObject(payload.decodeToString()))
            } catch (error: Exception) {
                JSONObject().put("type", "error").put("code", "invalid_payload")
                    .put("detail", error.message ?: "invalid payload")
            }
            try {
                fabricClient.complete(requestId, response.toString().encodeToByteArray())
            } catch (error: Exception) {
                runOnUiThread { status = "Fabric ability 回包失败：${error.message}" }
            }
            runOnUiThread(::refresh)
        }

        override fun onStreamOpen(
            requestId: Long,
            ability: String,
            streamName: String,
            readFromRemote: android.os.ParcelFileDescriptor,
            writeToRemote: android.os.ParcelFileDescriptor,
        ) {
            // Buffer v1 uses bounded invoke payloads on the local Hub. Keep
            // the stream contract explicit and close unsupported streams.
            readFromRemote.close()
            writeToRemote.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as BufferApplication
        repository = application.repository
        bridge = application.bridge
        fabricClient = FabricClient(this)
        velaDiscovery = VelaDiscovery()
        healthExporter = HealthConnectExporter(this)
        repository.reconcileRabbitSession(System.currentTimeMillis())
        researchSession = repository.rabbitSession()
        mode = repository.mode()
        bedtimeSchedule = repository.bedtimeSchedule()
        refresh()
        textInput = savedInstanceState?.getString(STATE_CAPTURE_DRAFT).orEmpty()
        if (savedInstanceState == null) {
            acceptReminderIntent(intent)
            acceptQuickCaptureIntent(intent)
        } else {
            quickCaptureTarget = QuickCaptureTarget.fromAction(savedInstanceState.getString(STATE_CAPTURE_TARGET))
            reminderCardId = savedInstanceState.getString("reminder_card_id")
            reminderCard = reminderCardId?.let(repository::findCard)
        }

        setContent {
            BufferTheme {
                if (reminderCardId != null) {
                    val card = reminderCard
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { reminderCardId = null },
                        title = { Text(card?.title ?: "提醒事项") },
                        text = {
                            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                                Text(card?.summary ?: "这张卡片已不存在。")
                                card?.let { Text("当前状态：${cardStateLabel(it.state)}") }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { reminderCardId = null }) { Text("关闭") }
                        },
                        dismissButton = {
                            if (card != null && card.state !in setOf("done", "archived")) {
                                Column {
                                    card.actions.filter { it in setOf("done", "later", "remind") }.forEach { action ->
                                        androidx.compose.material3.TextButton(onClick = {
                                            reminderCardId = null
                                            performAction(card, action)
                                        }) { Text(actionLabel(action)) }
                                    }
                                }
                            }
                        },
                    )
                }
                val microphoneLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    status = if (granted) "麦克风已授权，长按录音按钮开始" else "需要麦克风权限才能录音"
                }
                val localNetworkLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    val denied = grants.any { !it.value }
                    startBridgeIfAllowed()
                    status = if (denied) "部分权限未授予；请至少允许局域网访问" else "局域网桥接已就绪"
                    connectFabric()
                }
                val calendarLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    val card = pendingCalendarCard
                    val action = pendingCalendarAction
                    pendingCalendarCard = null
                    pendingCalendarAction = null
                    if (card != null &&
                        grants[Manifest.permission.WRITE_CALENDAR] == true &&
                        grants[Manifest.permission.READ_CALENDAR] == true
                    ) {
                        if (action == "remove_calendar") {
                            removeCalendarEvent(card)
                        } else {
                            prepareCalendarWrite(card)
                        }
                    } else if (card != null) {
                        status = "未授予日历权限，任务仍保留在 Buffer 中"
                    }
                }
                val healthLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract(),
                ) { granted ->
                    val card = pendingHealthCard
                    pendingHealthCard = null
                    if (card == null) return@rememberLauncherForActivityResult
                    val required = HealthConnectExporter.requiredPermissions(card.healthCategory)
                    if (!granted.containsAll(required)) {
                        repository.recordHealthSyncFailed(card, "用户未授予所需 Health Connect 权限")
                        status = "未授予健康写入权限，记录仍保留在本机"
                    } else if (!repository.isClassificationConfirmed(card.cardId)) {
                        repository.recordHealthSyncFailed(card, "分类尚未确认，未写入健康数据")
                        status = "请先确认分类，再写入健康数据"
                    } else {
                        syncHealthCard(card)
                    }
                }
                LaunchedEffect(Unit) {
                    if (quickCaptureTarget == null) {
                        requestLocalNetworkPermission(localNetworkLauncher)
                        startBridgeIfAllowed()
                        connectFabric()
                    }
                    while (true) {
                        refresh()
                        delay(1_000)
                    }
                }
                quickCaptureTarget?.let { target ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            stopRecording()
                            quickCaptureTarget = null
                        },
                        title = { Text(if (target == QuickCaptureTarget.TEXT) "记下此刻" else "语音捕获") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (target == QuickCaptureTarget.TEXT) {
                                    OutlinedTextField(
                                        value = textInput, onValueChange = { textInput = it },
                                        label = { Text("写一句也可以") }, minLines = 3,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Text("按住开始，松开保存。首次使用需允许麦克风权限。")
                                    VoiceButton(activeRecording != null, onStartRecording = {
                                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            false
                                        } else startRecording()
                                    }, onStopRecording = ::stopRecording)
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            if (target == QuickCaptureTarget.TEXT) {
                                Button(enabled = textInput.isNotBlank(), onClick = {
                                    saveText()
                                    if (textInput.isEmpty()) quickCaptureTarget = null
                                }) { Text("保存") }
                            } else {
                                Button(onClick = {
                                    stopRecording()
                                    quickCaptureTarget = null
                                }) { Text("完成") }
                            }
                        },
                        dismissButton = {
                            if (target == QuickCaptureTarget.TEXT) {
                                Button(onClick = { quickCaptureTarget = null }) { Text("稍后再写") }
                            }
                        },
                    )
                }
                BufferScreen(
                    cards = cards,
                    shelfCards = shelfCards,
                    answerBoxCards = answerBoxCards,
                    lifeLogCards = lifeLogCards,
                    weeklySummary = weeklySummary,
                    pendingAudio = pendingAudio,
                    textInput = textInput,
                    status = status,
                    bedtimeSchedule = bedtimeSchedule,
                    bridgeRunning = bridgeRunning,
                    fabricConnected = fabricConnected,
                    mode = mode,
                    pendingCaptures = pendingCaptures,
                    eventCount = eventCount,
                    phoneAddress = localAddress(),
                    discoveredVelas = discoveredVelas,
                    pairedVelaIds = pairedVelaIds,
                    pairingCode = pairingCode,
                    scanningVelas = scanningVelas,
                    pairingVela = pairingVela,
                    recording = activeRecording != null,
                    onTextChanged = { textInput = it },
                    onSaveText = ::saveText,
                    onEditCard = { card, title, body ->
                        try {
                            repository.editCardContent(card, title, body)
                            refresh(); status = "收藏已更新"; true
                        } catch (error: Exception) { status = error.message ?: "修改失败"; false }
                    },
                    onDeleteCards = { ids ->
                        try {
                            repository.deleteCards(ids)
                            refresh()
                            (application as BufferApplication).careScheduler.onAlarm()
                            status = "收藏已删除"; true
                        } catch (error: Exception) { status = error.message ?: "删除失败"; false }
                    },
                    onGenerateWeeklySummary = ::generateWeeklySummary,
                    onBedtimeScheduleChanged = { bedtimeSchedule = it },
                    onSaveBedtimeSchedule = ::saveBedtimeSchedule,
                    onManualTranscription = ::completeManualTranscription,
                    onStartRecording = {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            startRecording()
                        }
                    },
                    onStopRecording = ::stopRecording,
                    onAction = { card, action ->
                        if (action == "accept_classification") {
                            reviewClassification(card, ClassificationDecision.ACCEPT)
                        } else if (action == "edit_classification") {
                            showClassificationEditor(card)
                        } else if (action == "reject_classification") {
                            AlertDialog.Builder(this)
                                .setTitle("拒绝这次分类？")
                                .setMessage("原始内容会保留，卡片回到待分类状态，可重新编辑分类。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("拒绝分类") { _, _ ->
                                    reviewClassification(card, ClassificationDecision.REJECT)
                                }.show()
                        } else if (action == "calendar" || action == "remove_calendar") {
                            requestCalendarAction(card, action, calendarLauncher)
                        } else if (action == "health_sync") {
                            requestHealthSync(card, healthLauncher)
                        } else {
                            performAction(card, action)
                        }
                    },
                    onAnswer = ::answerQuestion,
                    onResearchNote = ::saveResearchNote,
                    onExternalLink = ::addExternalLink,
                    onTags = ::saveCardTags,
                    onAssociateCard = ::linkRelatedCard,
                    onUnlinkRelatedCard = ::unlinkRelatedCard,
                    onQuickAnswer = ::requestQuickAnswer,
                    onHealthSync = { card -> requestHealthSync(card, healthLauncher) },
                    onModeChanged = ::changeMode,
                    onLoadFocusSettings = repository::focusReminderConfig,
                    onSaveFocusSettings = ::saveFocusReminderSettings,
                    researchSession = researchSession,
                    onResearch = ::showResearchSession,
                    onResearchRecord = ::showResearchRecord,
                    onResearchHistory = { showResearchHistory() },
                    onPairingCodeChanged = { pairingCode = it },
                    onScanVelas = ::scanVelas,
                    onPairVela = ::pairVela,
                    onToggleBridge = {
                        if (bridge.isRunning) {
                            bridge.stop()
                            stopService(Intent(this@MainActivity, BufferBridgeService::class.java))
                        } else {
                            startBridgeIfAllowed()
                        }
                        refresh()
                    },
                    onFindCard = FindCardUseCase(repository)::invoke,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refresh()
        acceptReminderIntent(intent)
        acceptQuickCaptureIntent(intent)
    }

    private fun acceptReminderIntent(incoming: Intent) {
        val id = CardReminderNavigation.cardId(incoming) ?: return
        reminderCardId = id
        reminderCard = repository.findCard(id)
        incoming.action = Intent.ACTION_MAIN
        incoming.data = null
    }

    private fun acceptQuickCaptureIntent(incoming: Intent) {
        val target = QuickCaptureTarget.fromAction(incoming.action) ?: return
        incoming.action = Intent.ACTION_MAIN
        if (activeRecording == null) quickCaptureTarget = target
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("reminder_card_id", reminderCardId)
        outState.putString(STATE_CAPTURE_DRAFT, textInput)
        outState.putString(STATE_CAPTURE_TARGET, quickCaptureTarget?.action)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopRecording()
        super.onStop()
    }

    override fun onDestroy() {
        activeRecording?.let { stopRecording() }
        if (fabricAbilityRegistered) {
            try {
                fabricClient.unregisterAbility(BufferConstants.ability)
            } catch (_: Exception) {
                // The Hub may have stopped before the app.
            }
        }
        fabricClient.close()
        pairingExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestLocalNetworkPermission(
        launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    ) {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
            ) add(LOCAL_NETWORK_PERMISSION)
            if (checkSelfPermission(HUB_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                add(HUB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isNotEmpty()) launcher.launch(requested.toTypedArray())
    }

    private fun startBridgeIfAllowed() {
        if (Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
        ) {
            if (bridge.isRunning) return
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BufferBridgeService::class.java),
                )
            } catch (error: Exception) {
                bridge.start()
                status = "桥接服务启动失败，已切换到前台桥接：${error.message}"
            }
        }
    }

    private fun connectFabric() {
        if (fabricConnectRequested || fabricClient.isConnected) return
        try {
            if (checkSelfPermission(HUB_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                status = "Device Fabric Hub 权限未授予，局域网模式仍可用"
                return
            }
            fabricConnectRequested = true
            if (!fabricClient.connect { connected ->
                    runOnUiThread {
                        fabricConnected = connected
                        if (!connected) {
                            fabricConnectRequested = false
                            fabricAbilityRegistered = false
                        }
                        if (connected && !fabricAbilityRegistered) {
                            try {
                                fabricClient.registerAbility(BufferConstants.ability, fabricAbilityHandler)
                                fabricAbilityRegistered = true
                            } catch (error: Exception) {
                                status = "Buffer ability 注册失败：${error.message}"
                            }
                        }
                    }
                }) {
                status = "Device Fabric Hub 未安装，继续使用局域网桥接"
                fabricConnectRequested = false
            }
        } catch (error: Exception) {
            fabricConnectRequested = false
            status = "Fabric Hub 不可用，继续使用局域网桥接：${error.message}"
        }
    }

    private fun scanVelas() {
        if (scanningVelas) return
        scanningVelas = true
        status = "正在扫描局域网中的 Gemini S1"
        pairingExecutor.execute {
            try {
                val peers = velaDiscovery.scan()
                runOnUiThread {
                    discoveredVelas = peers
                    scanningVelas = false
                    status = if (peers.isEmpty()) {
                        "没有发现 Vela，请确认 Gemini S1 已连 Wi-Fi"
                    } else {
                        "发现 ${peers.size} 台 Vela，请输入屏幕上的 6 位配对码"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    scanningVelas = false
                    status = "Vela 扫描失败：${error.message ?: "局域网不可用"}"
                }
            }
        }
    }

    private fun pairVela(peer: VelaPeer) {
        if (pairingVela) return
        val code = pairingCode.trim()
        if (!code.matches(Regex("\\d{6}"))) {
            status = "请输入 Gemini S1 屏幕上的 6 位配对码"
            return
        }

        pairingVela = true
        status = "正在与 ${peer.name} 配对"
        val phoneDeviceId = repository.deviceId()
        pairingExecutor.execute {
            try {
                val pairing = velaDiscovery.pair(peer, code, phoneDeviceId)
                repository.pairVela(pairing.deviceId, pairing.token)
                runOnUiThread {
                    pairingVela = false
                    pairingCode = ""
                    pairedVelaIds = repository.pairedVelaIds()
                    status = "已配对 ${peer.name}，Vela 会自动同步"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pairingVela = false
                    status = "配对失败：${error.message ?: "请检查配对码"}"
                }
            }
        }
    }

    private fun refresh() {
        repository.reconcileRabbitSession(System.currentTimeMillis())
        researchSession = repository.rabbitSession()
        reminderCard = reminderCardId?.let(repository::findCard)
        mode = repository.mode()
        cards = if (mode == "bedtime") repository.cardsForMode(mode) else repository.cards()
        shelfCards = repository.shelfCards()
        answerBoxCards = repository.answerBoxCards()
        lifeLogCards = repository.lifeLogCards()
        weeklySummary = repository.weeklySummary()
        pendingAudio = repository.pendingAudioCaptures()
        pendingCaptures = repository.pendingCaptureCount()
        eventCount = repository.eventCount()
        bridgeRunning = bridge.isRunning
        pairedVelaIds = repository.pairedVelaIds()
    }

    private fun saveText() {
        val text = textInput.trim()
        if (text.isEmpty()) {
            status = "先写下想保存的内容"
            return
        }
        try {
            repository.createTextCapture(text, mode)
            textInput = ""
            (application as BufferApplication).classificationWorker.requestProcessing()
            status = "已保存，等待 AI 分类、关联和整理"
            refresh()
        } catch (error: Exception) {
            status = "文本保存失败：${error.message}"
        }
    }

    private fun generateWeeklySummary() {
        try {
            repository.generateWeeklySummary()
            status = "已提交周总结，生成后会自动保存"
            refresh()
        } catch (error: Exception) {
            status = "生成周总结失败：${error.message}"
        }
    }

    private fun saveBedtimeSchedule() {
        try {
            repository.setBedtimeSchedule(bedtimeSchedule)
            bedtimeSchedule = repository.bedtimeSchedule()
            status = if (bedtimeSchedule.isBlank()) {
                "已关闭自动睡前模式"
            } else {
                "已设置每天 $bedtimeSchedule 自动进入睡前模式"
            }
        } catch (error: Exception) {
            status = "睡前时间无效：${error.message}"
        }
    }

    private fun completeManualTranscription(capture: BufferCapture, transcript: String) {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isEmpty()) return
        status = "正在保存手动转写"
        pairingExecutor.execute {
            try {
                repository.completeAudioTranscription(capture.captureId, cleanTranscript)
                (application as BufferApplication).classificationWorker.requestProcessing()
                runOnUiThread {
                    status = "转写已保存，等待 AI 分类、关联和整理"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "手动转写失败：${error.message}"
                }
            }
        }
    }

    private fun startRecording(): Boolean {
        if (activeRecording != null) return false
        val file = File(cacheDir, "buffer-${UUID.randomUUID()}.m4a")
        var recorder: MediaRecorder? = null
        return try {
            val started = MediaRecorder().also { recorder = it }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                setMaxDuration(BufferConstants.maxPhoneRecordingMs)
                setMaxFileSize(BufferConstants.maxAudioBytes.toLong())
                setOnInfoListener { source, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                    ) finishCurrentRecording(source, "已达到录音上限")
                }
                setOnErrorListener { source, _, _ ->
                    finishCurrentRecording(source, "录音设备异常")
                }
                prepare()
                start()
            }
            activeRecording = ActiveRecording(started, file, SystemClock.elapsedRealtime())
            status = "正在录音，松开按钮保存"
            true
        } catch (error: Exception) {
            recorder?.let { runCatching { it.release() } }
            file.delete()
            status = "录音启动失败：${error.message}"
            false
        }
    }

    private fun finishCurrentRecording(recorder: MediaRecorder, reason: String) {
        runOnUiThread {
            // A delayed callback from an old recorder must not stop a new session.
            if (activeRecording?.recorder === recorder) {
                stopRecording()
                status = "$reason；$status"
            }
        }
    }

    private fun stopRecording() {
        val active = activeRecording ?: return
        activeRecording = null
        val duration = (SystemClock.elapsedRealtime() - active.startedAt)
            .coerceIn(0, BufferConstants.maxPhoneRecordingMs.toLong())
        val captureId = "android-${UUID.randomUUID()}"
        val createdAt = System.currentTimeMillis()
        val captureMode = mode
        val result = RecordingFinalizer.finish(
            stop = { active.recorder.stop() },
            release = { active.recorder.release() },
            save = {
                repository.createAudioCapture(
                    captureId = captureId, sourceDevice = "android", createdAt = createdAt,
                    mode = captureMode, audioFormat = "audio/mp4;codec=aac;rate=16000;channels=1",
                    durationMs = duration, audio = active.file.readBytes(),
                )
            },
            preserve = {
                repository.preserveAudioForRetry(
                    captureId = captureId, sourceDevice = "android", createdAt = createdAt,
                    mode = captureMode, audioFormat = "audio/mp4;codec=aac;rate=16000;channels=1",
                    durationMs = duration, sourceFile = active.file,
                )
            },
            cleanup = { active.file.delete() || !active.file.exists() },
        )
        status = when (result.destination) {
            RecordingDestination.CAPTURE -> "语音已保存，等待后续转写"
            RecordingDestination.RECOVERY -> "录音处理异常，原始音频已进入待处理队列"
            RecordingDestination.CACHE -> "语音保存失败，原始文件暂留缓存：${result.error?.message}"
        }
        if (result.cleanupFailed) status += "；缓存副本未清除"
        // UI refresh failure must not enqueue a second recovery copy of a saved capture.
        runCatching { refresh() }
    }

    private fun performAction(card: BufferCard, action: String) {
        if (action in setOf("later", "remind")) { chooseCardReturn(card, action); return }
        try {
            repository.performCardAction(card.cardId, action)
            status = "已执行：${actionLabel(action)}"
            refresh()
        } catch (error: Exception) {
            status = "卡片操作失败：${error.message}"
        }
    }

    private fun saveCardReturn(card: BufferCard, at: Long, action: String) {
        try {
            requireNotNull(repository.performCardAction(card.cardId, action, requestedReturnAt = at)) { "卡片已不存在" }
            val whenText = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm z"))
            status = "已安排 $whenText ${if (action == "remind") "提醒" else "再看"}"
            if (action == "remind" && !getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
                status += "；请在系统设置中允许 Buffer 通知，计划已保留"
            (application as BufferApplication).careScheduler.onAlarm()
            refresh()
        } catch (error: Exception) { status = "安排回流失败：${error.message}" }
    }

    private fun chooseCardReturn(card: BufferCard, action: String) {
        AlertDialog.Builder(this).setTitle(if (action == "remind") "什么时候提醒？" else "什么时候再看？")
            .setItems(arrayOf(if (action == "remind") "一小时后" else "明天 09:00", "下一个周六 09:00", "选择日期和时间")) { _, choice ->
                val now = System.currentTimeMillis()
                when (choice) {
                    0 -> saveCardReturn(card, if (action == "remind") now + 3_600_000 else CardReturnTime.tomorrow(now), action)
                    1 -> saveCardReturn(card, CardReturnTime.weekend(now), action)
                    else -> chooseCustomCardReturn(card, action)
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun chooseCustomCardReturn(card: BufferCard, action: String) {
        val zone = ZoneId.systemDefault()
        val initial = LocalDate.now(zone).plusDays(1)
        DatePickerDialog(this, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            TimePickerDialog(this, { _, hour, minute ->
                val time = LocalTime.of(hour, minute)
                val at = try { CardReturnTime.custom(date, time, zone, System.currentTimeMillis()) }
                catch (error: IllegalArgumentException) {
                    status = error.message ?: "时间无效"
                    return@TimePickerDialog
                }
                AlertDialog.Builder(this).setTitle(if (action == "remind") "确认提醒时间" else "确认回流时间")
                    .setMessage("${card.title}\n$date $time（${zone.id}）")
                    .setPositiveButton("确认") { _, _ -> saveCardReturn(card, at, action) }
                    .setNegativeButton("取消", null).show()
            }, 9, 0, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun reviewClassification(
        card: BufferCard, decision: ClassificationDecision,
        kind: String? = null, healthCategory: String? = null,
    ) {
        try {
            requireNotNull(repository.reviewClassification(card, decision, kind, healthCategory)) {
                "卡片已不存在"
            }
            status = when (decision) {
                ClassificationDecision.ACCEPT -> "已接受分类"
                ClassificationDecision.EDIT -> "已按你的选择修改分类，尚未写入日历或健康数据"
                ClassificationDecision.REJECT -> "已拒绝分类，原始内容保留"
            }
            refresh()
        } catch (error: Exception) {
            status = "分类操作失败：${error.message}"
            runCatching { refresh() }
        }
    }

    private fun showClassificationEditor(card: BufferCard) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fun label(value: String) {
            content.addView(TextView(this).apply { text = value })
        }
        fun choices(values: List<String>, selected: Int): Spinner = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            it.setSelection(selected.coerceAtLeast(0))
            content.addView(it)
        }
        label(card.summary)
        label("分类")
        val kinds = ClassificationReview.kinds.keys.toList()
        val kind = choices(ClassificationReview.kinds.values.toList(), kinds.indexOf(card.kind))
        label("健康类别（可选）")
        val categories = listOf(null) + ClassificationReview.healthCategories.toList()
        val category = choices(
            categories.map { it?.let(::healthCategoryLabel) ?: "不是健康记录" },
            categories.indexOf(card.healthCategory.takeIf { card.kind != "question_card" }),
        )
        label("确认只修改分类。日历和健康写入仍需单独操作。")
        val errorLabel = TextView(this)
        content.addView(errorLabel)
        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑分类")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setPositiveButton("确认修改", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedKind = kinds[kind.selectedItemPosition]
            val selectedCategory = categories[category.selectedItemPosition]
            try {
                ClassificationReview.decide(card, ClassificationDecision.EDIT, selectedKind, selectedCategory)
            } catch (error: IllegalArgumentException) {
                errorLabel.text = error.message
                return@setOnClickListener
            }
            dialog.dismiss()
            reviewClassification(card, ClassificationDecision.EDIT, selectedKind, selectedCategory)
        }
    }

    private fun answerQuestion(card: BufferCard, answer: String) {
        val cleanAnswer = answer.trim()
        if (cleanAnswer.isEmpty()) return
        status = "正在保存问题答案"
        pairingExecutor.execute {
            try {
                repository.saveQuestionAnswer(card.cardId, cleanAnswer)
                runOnUiThread {
                    status = "问题答案已保存"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "保存问题答案失败：${error.message}"
                }
            }
        }
    }

    private fun saveResearchNote(card: BufferCard, note: String) {
        val cleanNote = note.trim()
        if (cleanNote.isEmpty()) return
        status = "正在保存研究笔记"
        pairingExecutor.execute {
            try {
                repository.saveResearchNote(card.cardId, cleanNote)
                runOnUiThread {
                    status = "研究笔记已追加"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "保存研究笔记失败：${error.message}"
                }
            }
        }
    }

    private fun addExternalLink(card: BufferCard, link: String) {
        val cleanLink = link.trim()
        if (cleanLink.isEmpty()) return
        status = "正在关联外部链接"
        pairingExecutor.execute {
            try {
                repository.addExternalLink(card.cardId, cleanLink)
                runOnUiThread {
                    status = "外部链接已关联"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "关联外部链接失败：${error.message}"
                }
            }
        }
    }

    private fun saveCardTags(card: BufferCard, rawTags: String) {
        val tags = rawTags.split(',', '，', '\n')
        pairingExecutor.execute {
            try {
                repository.updateCardTags(card.cardId, tags)
                runOnUiThread {
                    status = "标签已保存"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "保存标签失败：${error.message}"
                }
            }
        }
    }

    private fun linkRelatedCard(card: BufferCard, target: BufferCard) {
        pairingExecutor.execute {
            try {
                if (card.kind == "spark_card") {
                    repository.linkSparkCard(card.cardId, target.cardId)
                } else {
                    repository.linkQuestionCard(card.cardId, target.cardId)
                }
                runOnUiThread {
                    status = "已关联：${target.title}"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "关联卡片失败：${error.message}"
                }
            }
        }
    }

    private fun unlinkRelatedCard(card: BufferCard, target: BufferCard) {
        pairingExecutor.execute {
            try {
                if (card.kind == "spark_card") {
                    repository.unlinkSparkCard(card.cardId, target.cardId)
                } else {
                    repository.unlinkQuestionCard(card.cardId, target.cardId)
                }
                runOnUiThread {
                    status = "已取消关联：${target.title}"
                    refresh()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = "取消关联失败：${error.message}"
                }
            }
        }
    }

    private fun requestQuickAnswer(card: BufferCard) {
        val config = runCatching { repository.llmConfig() }.getOrElse {
            status = "无法读取 LLM 配置，请在设置中重新保存密钥"; return
        }
        if (!config.ready) {
            status = "请先在设置中配置 LLM 服务"
            return
        }
        status = "正在请求问题短答案"
        repository.recordQuestionAnswerRequested(card)
        pairingExecutor.execute {
            try {
                when (val result = BufferLlmClient.answer(config, card)) {
                    is QuickAnswerResult.Answer -> {
                        requireNotNull(repository.saveQuestionAnswer(card.cardId, result.text, source = "remote", expected = card)) { "问题已不存在" }
                        runOnUiThread {
                            status = "问题已获得短答案"
                            refresh()
                        }
                    }
                    is QuickAnswerResult.Unresolved -> {
                        repository.recordQuestionAnswerDeferred(card, result.detail)
                        runOnUiThread {
                            status = "暂时未解：${result.detail}"
                            refresh()
                        }
                    }
                }
            } catch (error: Exception) {
                val receiptFailure = runCatching {
                    repository.recordQuestionAnswerFailed(card, error.message ?: "回答服务不可用")
                }.exceptionOrNull()
                runOnUiThread {
                    status = "快速回答失败：${error.message ?: "回答服务不可用"}"
                    if (receiptFailure != null) status += "；失败记录也未保存"
                }
            }
        }
    }

    private fun requestHealthSync(
        card: BufferCard,
        launcher: androidx.activity.result.ActivityResultLauncher<Set<String>>,
    ) {
        if (card.state == "health_synced" || repository.hasHealthDataWritten(card.cardId)) {
            status = "这条记录已经同步到 Health Connect"
            refresh()
            return
        }
        if (!card.classificationConfirmed) {
            status = "请先确认分类，再写入健康数据"
            return
        }
        val category = card.healthCategory
        if (!HealthConnectExporter.canExport(category)) {
            status = "${healthCategoryLabel(category)}目前只保存在 Buffer 本地"
            return
        }
        when (HealthConnectExporter.availability(this)) {
            androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE -> Unit
            androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                status = "请先安装或更新 Health Connect"
                return
            }
            else -> {
                status = "当前设备没有可用的 Health Connect"
                return
            }
        }
        pendingHealthCard = card
        repository.recordHealthSyncRequested(card)
        status = "请确认 Health Connect 的 ${healthCategoryLabel(category)} 写入权限"
        launcher.launch(HealthConnectExporter.requiredPermissions(category))
    }

    private fun syncHealthCard(card: BufferCard) {
        if (!healthSyncInFlight.add(card.cardId)) {
            status = "正在同步这条健康记录"
            return
        }
        status = "正在写入手机健康数据"
        pairingExecutor.execute {
            val outcome = HealthSyncRecovery.sync(
                alreadyWritten = { repository.hasHealthDataWritten(card.cardId) },
                restore = {
                    requireNotNull(repository.markHealthSynced(card.cardId, card.healthCategory ?: "HealthRecord")) {
                        "记录已不存在"
                    }
                },
                export = {
                    check(repository.isClassificationConfirmed(card.cardId)) { "请先确认分类，再写入健康数据" }
                    runBlocking { healthExporter.export(card) }
                },
                commit = { result ->
                    requireNotNull(repository.markHealthSynced(
                        card.cardId, result.recordType, result.recordId, result.clientRecordId,
                    )) { "记录已不存在" }
                },
                recordFailure = { repository.recordHealthSyncFailed(card, it) },
            )
            runOnUiThread {
                healthSyncInFlight.remove(card.cardId)
                status = outcome.message
                runCatching { refresh() }.onFailure { status += "；界面刷新失败，请重新打开应用" }
            }
        }
    }

    private fun requestCalendarAction(
        card: BufferCard,
        action: String,
        launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    ) {
        if (action == "calendar" && !card.classificationConfirmed) {
            status = "请先确认分类，再写入日历"
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        ) {
            if (action == "remove_calendar") removeCalendarEvent(card) else prepareCalendarWrite(card)
        } else {
            pendingCalendarCard = card
            pendingCalendarAction = action
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
            )
        }
    }

    private fun prepareCalendarWrite(card: BufferCard) {
        if (!repository.isClassificationConfirmed(card.cardId)) {
            status = "请先确认分类，再写入日历"
            return
        }
        if (!calendarWriteInFlight.add(card.cardId)) {
            status = "正在等待这条任务的日历确认"
            return
        }
        status = "正在准备日历确认"
        pairingExecutor.execute {
            try {
                val calendars = contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Calendars.ACCOUNT_NAME,
                    ),
                    "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                    arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                    "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1).orEmpty().ifBlank { "未命名日历" }
                            val account = cursor.getString(2).orEmpty()
                            add(
                                WritableCalendar(
                                    id = cursor.getLong(0),
                                    label = if (account.isBlank()) name else "$name · $account",
                                ),
                            )
                        }
                    }
                }.orEmpty()
                if (calendars.isEmpty()) throw IllegalStateException("没有可写入的日历")
                runOnUiThread { showCalendarConfirmation(card, calendars) }
            } catch (error: Exception) {
                runOnUiThread {
                    calendarWriteInFlight.remove(card.cardId)
                    status = "准备日历失败：${error.message ?: "请检查日历权限"}"
                }
            }
        }
    }

    private fun showCalendarConfirmation(card: BufferCard, calendars: List<WritableCalendar>) {
        if (isFinishing || isDestroyed) {
            calendarWriteInFlight.remove(card.cardId)
            return
        }
        var date: LocalDate? = null
        var time: LocalTime? = null
        var submitted = false
        val zone = ZoneId.systemDefault()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fun label(text: String) {
            content.addView(TextView(this).apply { this.text = text })
        }
        fun choices(labels: List<String>, selected: Int = 0): Spinner = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            it.setSelection(selected)
            content.addView(it)
        }
        label(card.title)
        label("请选择开始日期、时间和优先级。未确定时间可取消，任务会保留，暂不写入日历。")
        label("时区：${zone.id}")
        val dateButton = AndroidButton(this).apply { text = "选择日期" }
        val timeButton = AndroidButton(this).apply { text = "选择时间" }
        content.addView(dateButton)
        content.addView(timeButton)
        label("时长")
        val durations = listOf(15, 30, 60, 90, 120)
        val duration = choices(durations.map { "$it 分钟" }, 2)
        label("优先级")
        val priorities = CalendarPriority.entries
        val priority = choices(priorities.map { it.label }, priorities.indexOf(CalendarPriority.NORMAL))
        label("写入日历")
        val calendar = choices(calendars.map { it.label })
        val errorLabel = TextView(this)
        content.addView(errorLabel)
        val dialog = AlertDialog.Builder(this)
            .setTitle("确认加入手机日历")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setPositiveButton("确认加入", null)
            .create()
        dialog.setOnDismissListener {
            if (!submitted) calendarWriteInFlight.remove(card.cardId)
        }
        dateButton.setOnClickListener {
            val initial = date ?: LocalDate.now(zone)
            DatePickerDialog(this, { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day)
                dateButton.text = date.toString()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = time != null
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }
        timeButton.setOnClickListener {
            val initial = time ?: LocalTime.of(9, 0)
            TimePickerDialog(this, { _, hour, minute ->
                time = LocalTime.of(hour, minute)
                timeButton.text = time.toString()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = date != null
            }, initial.hour, initial.minute, true).show()
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener {
                val selection = try {
                    CalendarWriteSelection.confirm(
                        calendars[calendar.selectedItemPosition].id, date, time,
                        durations[duration.selectedItemPosition], priorities[priority.selectedItemPosition],
                        zone, System.currentTimeMillis(),
                    )
                } catch (error: IllegalArgumentException) {
                    errorLabel.text = error.message
                    return@setOnClickListener
                }
                submitted = true
                isEnabled = false
                dialog.dismiss()
                writeCalendarEvent(card, selection)
            }
        }
    }

    private fun writeCalendarEvent(card: BufferCard, selection: CalendarWriteSelection) {
        if (!repository.isClassificationConfirmed(card.cardId)) {
            calendarWriteInFlight.remove(card.cardId)
            status = "请先确认分类，再写入日历"
            return
        }
        status = "正在把任务加入手机日历"
        pairingExecutor.execute {
            var eventId: String? = null
            try {
                val marker = Uri.Builder().scheme("buffer").authority("calendar")
                    .appendPath(card.cardId).build().toString()
                CalendarRecovery.write(
                    findExisting = {
                        contentResolver.query(
                            CalendarContract.Events.CONTENT_URI,
                            arrayOf(CalendarContract.Events._ID),
                            "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
                                "${CalendarContract.Events.CUSTOM_APP_URI} = ? AND ${CalendarContract.Events.DELETED} = 0",
                            arrayOf(packageName, marker), null,
                        ).let { cursor ->
                            requireNotNull(cursor) { "无法查询日历，请稍后重试" }.use {
                                check(cursor.count <= 1) { "发现重复的关联日程，请先在日历中确认" }
                                if (cursor.moveToFirst()) cursor.getLong(0).toString() else null
                            }
                        }
                    },
                    insert = {
                        repository.recordCalendarWriteRequested(card.cardId, card.captureId, selection)
                        val values = ContentValues().apply {
                            put(CalendarContract.Events.CALENDAR_ID, selection.calendarId)
                            put(CalendarContract.Events.TITLE, card.title)
                            put(CalendarContract.Events.DESCRIPTION, selection.description(card.summary))
                            put(CalendarContract.Events.DTSTART, selection.startMillis)
                            put(CalendarContract.Events.DTEND, selection.endMillis)
                            put(CalendarContract.Events.EVENT_TIMEZONE, selection.timeZone)
                            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
                            put(CalendarContract.Events.CUSTOM_APP_URI, marker)
                        }
                        val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                            ?: throw IllegalStateException("日历拒绝了写入")
                        ContentUris.parseId(uri).toString()
                    },
                    commit = { id ->
                        eventId = id
                        repository.commitCalendarChange(card.cardId, card.captureId, id, deleted = false)
                    },
                )
                runOnUiThread {
                    status = "已加入日历：${card.title}"
                    refresh()
                }
            } catch (error: Exception) {
                val receiptFailure = runCatching {
                    repository.recordCalendarWrite(
                        card.cardId, card.captureId, eventId, success = false, error.message,
                    )
                }.exceptionOrNull()
                runOnUiThread {
                    status = if (eventId != null) {
                        "日历已写入，本地记录失败；重试会恢复关联日程"
                    } else {
                        "加入日历失败：${error.message ?: "请检查日历权限"}"
                    }
                    if (receiptFailure != null) status += "；失败记录也未保存"
                }
            } finally {
                runOnUiThread { calendarWriteInFlight.remove(card.cardId) }
            }
        }
    }

    private fun removeCalendarEvent(card: BufferCard) {
        status = "正在从手机日历移除任务"
        pairingExecutor.execute {
            var eventId: String? = null
            try {
                CalendarRecovery.remove(
                    findExisting = {
                        val receipt = requireNotNull(repository.calendarReceipt(card.cardId, card.captureId)) {
                            "无法确定关联日程，请先检查手机日历"
                        }
                        if (receipt.deleted) null else requireNotNull(receipt.eventId).also { eventId = it }
                    },
                    delete = { id ->
                        contentResolver.delete(
                            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong()),
                            null, null,
                        )
                    },
                    commit = { id ->
                        repository.commitCalendarChange(card.cardId, card.captureId, id, deleted = true)
                    },
                )
                runOnUiThread {
                    status = "已从日历移除：${card.title}"
                    refresh()
                }
            } catch (error: Exception) {
                val receiptFailure = runCatching {
                    repository.recordCalendarDelete(card.cardId, card.captureId, eventId, success = false, error.message)
                }.exceptionOrNull()
                runOnUiThread {
                    status = "移出日历未完成，可重试：${error.message ?: "请检查日历权限"}"
                    if (receiptFailure != null) status += "；失败记录也未保存"
                }
            }
        }
    }

    private fun showResearchHistory(offset: Int = 0) {
        try {
            val page = repository.rabbitHistory(21, offset)
            if (page.isEmpty()) { status = "还没有研究记录"; return }
            val sessions = page.take(20)
            val formatter = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            val labels = sessions.map { "${formatter.format(java.util.Date(it.startedAt))} · ${it.title}" }
            val dialog = AlertDialog.Builder(this).setTitle("研究记录 · 最近更新优先")
                .setItems(labels.toTypedArray()) { _, index ->
                    val session = sessions[index]
                    val reason = if (session.active) "研究中" else when (session.reason) {
                        "time_up" -> "时间到"; "mode_changed" -> "切换模式后结束"; else -> "已结束"
                    }
                    val elapsed = ((if (session.active) System.currentTimeMillis().coerceAtMost(session.endsAt)
                        else session.finishedAt) - session.startedAt).coerceAtLeast(0) / 60_000
                    val detail = AlertDialog.Builder(this).setTitle(session.title)
                        .setMessage("开始：${formatter.format(java.util.Date(session.startedAt))}\n计划 ${(session.endsAt - session.startedAt) / 60_000} 分钟 · 已记录 $elapsed 分钟\n$reason\n\n${session.outcome.ifBlank { "未填写研究笔记" }}")
                        .setNegativeButton("返回列表") { _, _ -> showResearchHistory(offset) }
                    if (repository.findCard(session.cardId) != null) detail.setPositiveButton("查看原主题") { _, _ ->
                        reminderCardId = session.cardId
                        refresh()
                    }
                    detail.show()
                }.setNegativeButton("关闭", null)
            if (page.size > 20) dialog.setPositiveButton("更早记录") { _, _ -> showResearchHistory(offset + 20) }
            if (offset > 0) dialog.setNeutralButton("返回最新") { _, _ -> showResearchHistory() }
            dialog.show()
        } catch (error: Exception) { status = "研究历史读取失败：${error.message}" }
    }

    private fun showResearchSession() {
        val session = repository.rabbitSession()
        if (session?.active == true) { showResearchRecord(); return }
        val candidates = repository.cardsForMode("rabbit_hole", 50)
        if (candidates.isEmpty()) { status = "先保存一个问题或灵感，再开始研究"; return }
        AlertDialog.Builder(this).setTitle("选择研究主题")
            .setItems(candidates.map { it.title }.toTypedArray()) { _, index ->
                val card = candidates[index]
                AlertDialog.Builder(this).setTitle("这次留多少时间？")
                    .setItems(arrayOf("60 分钟", "90 分钟", "120 分钟")) { _, duration ->
                        try {
                            repository.startRabbitSession(card.cardId, listOf(60, 90, 120)[duration])
                            (application as BufferApplication).careScheduler.onAlarm()
                            refresh()
                        } catch (error: Exception) { status = "研究启动失败：${error.message}" }
                    }.setNegativeButton("取消", null).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun showResearchRecord() {
        val session = repository.rabbitSession() ?: return
        val note = EditText(this).apply {
            hint = "这次发现了什么？可留空"; minLines = 3; setText(session.outcome)
            filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
        }
        AlertDialog.Builder(this).setTitle("研究记录：${session.title}")
            .setView(note).setNegativeButton("取消", null)
            .setPositiveButton(if (session.active) "结束并保存" else "保存") { _, _ ->
                try {
                    if (session.active) {
                        repository.finishRabbitSession(session.id, note.text.toString())
                        // The timer may have expired while this dialog was open.
                        repository.recordRabbitOutcome(session.id, note.text.toString())
                    } else repository.recordRabbitOutcome(session.id, note.text.toString())
                    (application as BufferApplication).careScheduler.onAlarm()
                    refresh()
                } catch (error: Exception) { status = "研究记录保存失败：${error.message}" }
            }.show()
    }

    private fun saveFocusReminderSettings(config: FocusReminderConfig): Boolean = try {
        repository.setFocusReminderConfig(config)
        (application as BufferApplication).careScheduler.onAlarm()
        status = "提醒设置已保存，Vela 联网后同步"
        refresh()
        true
    } catch (error: Exception) {
        status = "提醒设置保存失败：${error.message}"
        false
    }

    private fun changeMode(next: String) {
        try {
            repository.setMode(next)
            status = "模式已切换为 ${modeLabel(next)}"
            (application as BufferApplication).careScheduler.onAlarm()
            refresh()
        } catch (error: Exception) {
            status = "模式切换失败：${error.message}"
        }
    }

    private fun localAddress(): String {
        val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return "未连接 Wi-Fi"
        val value = wifi.connectionInfo.ipAddress
        if (value == 0) return "未连接 Wi-Fi"
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            value and 0xff,
            value shr 8 and 0xff,
            value shr 16 and 0xff,
            value shr 24 and 0xff,
        )
    }

    private data class ActiveRecording(
        val recorder: MediaRecorder,
        val file: File,
        val startedAt: Long,
    )

    companion object {
        private const val STATE_CAPTURE_DRAFT = "capture_draft"
        private const val STATE_CAPTURE_TARGET = "capture_target"
        private const val HUB_PERMISSION = "com.mocharealm.foundation.fabric.permission.CONNECT_HUB"
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
