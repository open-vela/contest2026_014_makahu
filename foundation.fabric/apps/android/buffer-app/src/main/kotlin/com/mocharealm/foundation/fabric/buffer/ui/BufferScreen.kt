package com.mocharealm.foundation.fabric.buffer.ui
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BufferScreen(
    cards: List<BufferCard>,
    shelfCards: List<BufferCard>,
    answerBoxCards: List<BufferCard>,
    lifeLogCards: List<BufferCard>,
    weeklySummary: BufferWeeklySummary,
    pendingAudio: List<BufferCapture>,
    textInput: String,
    status: String,
    bridgeRunning: Boolean,
    fabricConnected: Boolean,
    mode: String,
    pendingCaptures: Int,
    eventCount: Int,
    phoneAddress: String,
    discoveredVelas: List<VelaPeer>,
    pairedVelaIds: List<String>,
    pairingCode: String,
    scanningVelas: Boolean,
    pairingVela: Boolean,
    recording: Boolean,
    onTextChanged: (String) -> Unit,
    onSaveText: () -> Unit,
    onEditCard: (BufferCard, String, String) -> Boolean,
    onDeleteCards: (List<String>) -> Boolean,
    onGenerateWeeklySummary: () -> Unit,
    onManualTranscription: (BufferCapture, String) -> Unit,
    onStartRecording: () -> Boolean,
    onStopRecording: () -> Unit,
    onAction: (BufferCard, String) -> Unit,
    onAnswer: (BufferCard, String) -> Unit,
    onResearchNote: (BufferCard, String) -> Unit,
    onExternalLink: (BufferCard, String) -> Unit,
    onTags: (BufferCard, String) -> Unit,
    onAssociateCard: (BufferCard, BufferCard) -> Unit,
    onUnlinkRelatedCard: (BufferCard, BufferCard) -> Unit,
    onQuickAnswer: (BufferCard) -> Unit,
    onHealthSync: (BufferCard) -> Unit,
    onModeChanged: (String) -> Unit,
    onLoadFocusSettings: () -> FocusReminderConfig,
    onSaveFocusSettings: (FocusReminderConfig) -> Boolean,
    focusSession: FocusSession?,
    onFocus: () -> Unit,
    onFocusHistory: () -> Unit,
    onSourceHistory: () -> Unit,
    onFocusCalendar: () -> Unit,
    onCalendar: () -> Unit,
    researchSession: RabbitHoleSession?,
    onResearch: () -> Unit,
    onResearchRecord: () -> Unit,
    onResearchHistory: () -> Unit,
    onPairingCodeChanged: (String) -> Unit,
    onScanVelas: () -> Unit,
    onPairVela: (VelaPeer) -> Unit,
    onToggleBridge: () -> Unit,
    onFindCard: (String) -> BufferCard?,
) {
    var destination by rememberSaveable { mutableStateOf("capture") }
    var collection by rememberSaveable { mutableStateOf("shelf") }
    var search by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCardId by rememberSaveable { mutableStateOf<String?>(null) }
    val pageState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    var previousStatus by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        if (status != previousStatus) {
            previousStatus = status
            snackbar.showSnackbar(status)
        }
    }
    BackHandler(enabled = destination != "capture" && sheet == null && selectedCardId == null) {
        destination = if (destination == "week") "review" else "capture"
    }
    val section = if (destination == "library") collection else "home"
    val sourceCards = when (section) {
        "shelf" -> shelfCards
        "answers" -> answerBoxCards
        "life" -> lifeLogCards
        else -> cards
    }
    val visibleCards = sourceCards.filter { card ->
        card.state != "pending_transcription" && (section == "home" || search.isBlank() || card.title.contains(search, true) ||
            card.summary.contains(search, true) || card.researchNotes.orEmpty().contains(search, true) ||
            card.externalLinks.any { it.contains(search, true) } || card.tags.any { it.contains(search, true) })
    }
    val allCards = (cards + shelfCards + answerBoxCards + lifeLogCards).distinctBy { it.cardId }
    val associationCandidates = (shelfCards + lifeLogCards).distinctBy { it.cardId }
    val selectedCard = remember(selectedCardId, allCards) {
        selectedCardId?.let { id -> allCards.find { it.cardId == id } ?: onFindCard(id) }
    }

    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var deleteIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTitle by rememberSaveable { mutableStateOf("") }
    var editBody by rememberSaveable { mutableStateOf("") }
    var originalTitle by rememberSaveable { mutableStateOf("") }
    var originalBody by rememberSaveable { mutableStateOf("") }
    var editError by rememberSaveable { mutableStateOf<String?>(null) }
    fun beginEdit(card: BufferCard) {
        editingId = card.cardId
        editTitle = card.title; editBody = card.summary
        originalTitle = card.title; originalBody = card.summary
        editError = null
    }
    fun toggleSelection(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }
    LaunchedEffect(destination, collection) { selection = emptyList() }
    LaunchedEffect(allCards.map { it.cardId }) { selection = selection.filter { id -> allCards.any { it.cardId == id } } }
    BackHandler(enabled = selection.isNotEmpty() && editingId == null && deleteIds.isEmpty()) { selection = emptyList() }
    if (editingId != null) {
        val edited = allCards.find { it.cardId == editingId }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("编辑收藏") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    OutlinedTextField(editBody, { editBody = it }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = edited != null && editTitle.isNotBlank() && editBody.isNotBlank(), onClick = {
                if (edited != null && onEditCard(edited.copy(title = originalTitle, summary = originalBody), editTitle, editBody)) {
                    editingId = null; selection = emptyList()
                } else editError = "未能保存，请检查内容长度或重新打开后重试。"
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("取消") } },
        )
    }
    if (deleteIds.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { deleteIds = emptyList() },
        icon = { Icon(BufferIcons.Delete, null) },
        title = { Text("删除 ${deleteIds.size} 条收藏？") },
        text = { Text("收藏将从列表和设备展示中移除，原始记录保留在事件簿中。已写入系统日历或健康应用的记录不会被删除。") },
        confirmButton = { TextButton(onClick = {
            if (onDeleteCards(deleteIds)) {
                selection = selection - deleteIds.toSet()
                if (selectedCardId in deleteIds) selectedCardId = null
                deleteIds = emptyList()
            }
        }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteIds = emptyList() }) { Text("取消") } },
    )

    if (sheet != null || selectedCard != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null; selectedCardId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                    .imePadding().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (sheet) {
                    "text" -> {
                        Text("记下此刻", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            value = textInput, onValueChange = onTextChanged,
                            modifier = Modifier.fillMaxWidth(), minLines = 4,
                            placeholder = { Text("刚刚想到……") },
                            shape = RoundedCornerShape(20.dp),
                        )
                        Button(onClick = onSaveText, enabled = textInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存") }
                    }
                    "audio" -> PendingAudioPanel(pendingAudio, onManualTranscription)
                    "llm" -> BufferLlmSettingsPanel()
                    "capture_context" -> BufferCaptureContextPanel()
                    "capabilities" -> BufferCapabilityPanel()
                    "members" -> BufferMeshMembersPanel()
                    "focus_settings" -> BufferFocusSettingsPanel(onLoadFocusSettings, onSaveFocusSettings) { sheet = null }
                    "bedtime" -> BedtimePolicyPanel { onAction(it, "later") }
                    "diagnostics" -> StatusPanel(status, bridgeRunning, fabricConnected, mode, pendingCaptures, eventCount, phoneAddress, onToggleBridge)
                }
                selectedCard?.let { card ->
                    var more by rememberSaveable(card.cardId) { mutableStateOf(false) }
                    BufferCardDetailContent(card, { selectedCardId = it }, { onAction(card, "task_schedule") })
                    if (card.kind in setOf("spark_card", "question_card", "care_card", "weekly_card")) {
                        BedtimeReadabilityActions(card, onTags)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { more = !more }) { Text(if (more) "收起操作" else "更多操作") }
                        Row {
                            TextButton(onClick = { beginEdit(card) }) { Icon(BufferIcons.Write, null); Text(" 编辑") }
                            TextButton(onClick = { deleteIds = listOf(card.cardId) }) { Icon(BufferIcons.Delete, null); Text(" 删除") }
                        }
                    }
                    if (more) CardView(card, onAction, onAnswer, onResearchNote, onExternalLink, onTags,
                        onAssociateCard, onUnlinkRelatedCard, associationCandidates, onQuickAnswer,
                        onHealthSync, detail = true, showHeader = false)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selection.isNotEmpty()) "已选择 ${selection.size} 项" else if (destination == "week") "七天回顾" else if (destination == "capture") "buffer" else bufferDestinations.first { it.id == destination }.title,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    if (selection.isNotEmpty()) IconButton(onClick = { selection = emptyList() }) {
                        Icon(BufferIcons.Close, contentDescription = "退出选择")
                    } else if (destination in listOf("settings", "devices", "week")) IconButton(onClick = { destination = if (destination == "week") "review" else "capture" }) {
                        Icon(BufferIcons.Back, contentDescription = "返回此刻")
                    }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        if (selection.size == 1) IconButton(onClick = { allCards.find { it.cardId == selection.single() }?.let(::beginEdit) }) {
                            Icon(BufferIcons.Write, contentDescription = "编辑所选收藏")
                        }
                        IconButton(onClick = { deleteIds = selection }) { Icon(BufferIcons.Delete, contentDescription = "删除所选收藏") }
                    } else if (destination !in listOf("settings", "devices", "week")) {
                        IconButton(onClick = { destination = "devices" }) {
                            Icon(bufferDestinations[3].icon, contentDescription = "我的设备")
                        }
                        IconButton(onClick = { destination = "settings" }) {
                            Icon(bufferDestinations[4].icon, contentDescription = "设置")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (destination !in listOf("settings", "devices", "week")) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                bufferDestinations.take(3).forEach { item ->
                    NavigationBarItem(selected = destination == item.id,
                        onClick = { if (recording) onStopRecording(); destination = item.id },
                        icon = { Icon(if (destination == item.id) item.selectedIcon else item.icon, null) }, label = { Text(item.label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        pageState.SaveableStateProvider(destination) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (destination == "capture") {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINESE)),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("记下此刻", style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    item {
                        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                VoiceButton(recording, onStartRecording, onStopRecording)
                                OutlinedButton(onClick = { sheet = "text" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Icon(BufferIcons.Write, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("写文字")
                                }
                            }
                        }
                    }
                    item { BufferRhythmPanel(mode, onModeChanged) { sheet = "focus_settings" } }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            focusSession?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
                            Button(onClick = onFocus, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (focusSession?.active == true) "结束并回顾" else if (focusSession?.status == "review_pending") "回顾这次专注" else "开始专注")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = onCalendar) { Text("日历") }
                                TextButton(onClick = onFocusCalendar) { Text("日程专注") }
                                TextButton(onClick = onFocusHistory) { Text("专注记录") }
                            }
                            TextButton(onClick = onSourceHistory) { Text("原始事件簿") }
                        }
                    }
                    if (pendingAudio.isNotEmpty()) item {
                        BufferActionRow("${pendingAudio.size} 段待转写", "", BufferIcons.Mic) { sheet = "audio" }
                    }
                    if (visibleCards.isNotEmpty()) item { Text("最近记录", style = MaterialTheme.typography.titleMedium) }

                }
                if (destination == "library") {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            BufferExplorationPanel(researchSession, onResearch, onResearchRecord, onResearchHistory)
                            OutlinedTextField(value = search, onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth(), placeholder = { Text("找一个想法、问题或标签") },
                                leadingIcon = { Icon(BufferIcons.Search, null) },
                                singleLine = true, shape = RoundedCornerShape(28.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("shelf" to "灵感", "answers" to "问题", "life" to "生活").forEach { (value, label) ->
                                    FilterChip(selected = collection == value, onClick = { collection = value }, label = { Text(label) })
                                }
                            }
                        }
                    }
                }
                if (destination in listOf("capture", "library")) {
                    if (visibleCards.isEmpty() && destination == "library") item {
                        BufferEmptyState(
                            title = if (search.isNotBlank() && destination == "library") "未找到记录" else if (destination == "capture") "暂无记录" else "暂无收藏",
                            subtitle = if (search.isNotBlank() && destination == "library") "试试另一个词，或换个分类。" else if (mode == "bedtime" && destination == "capture") "暂无适合睡前回看的内容。" else "可录音或写文字。",
                        )
                    }
                    items(visibleCards, key = { it.cardId }) { card ->
                        if (section == "home" && mode == "bedtime") BedtimeCardView(card, onAction)
                        else if (destination == "library") BufferCollectionItem(
                            card = card, state = cardStateLabel(card.state), selected = card.cardId in selection,
                            selectionMode = selection.isNotEmpty(), onClick = {
                                if (selection.isNotEmpty()) toggleSelection(card.cardId) else selectedCardId = card.cardId
                            }, onLongClick = { toggleSelection(card.cardId) },
                            onEdit = { beginEdit(card) }, onDelete = { deleteIds = listOf(card.cardId) },
                        ) else BufferCardPreview(card, cardStateLabel(card.state)) {
                            if (card.state == "pending_transcription") sheet = "audio" else selectedCardId = card.cardId
                        }
                    }
                }
                if (destination == "review") {
                    item { BufferWeekPreview(weeklySummary) { destination = "week" } }

                }
                if (destination == "week") { item { WeeklySummaryPanel(weeklySummary, onGenerateWeeklySummary) } }
                if (destination == "devices") {
                    item {
                        AddVelaPanel(onConfigureAi = { sheet = "llm" }) {
                            PairingPanel(discoveredVelas, pairedVelaIds, pairingCode,
                                scanningVelas, pairingVela, onPairingCodeChanged, onScanVelas, onPairVela)
                        }
                    }
                }
                if (destination == "settings") {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("日常偏好", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BufferActionRow("睡前时段", "时间、日程与收束偏好", BufferIcons.Leaf) { sheet = "bedtime" }
                            BufferActionRow("捕获上下文", "可选位置、日程与设备共享", BufferIcons.Leaf) { sheet = "capture_context" }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("智能服务", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BufferActionRow("AI 服务", "分类、问答与周总结", BufferIcons.Leaf) { sheet = "llm" }
                            BufferActionRow("能力建议", "审核历史依据、启用与撤销", BufferIcons.Leaf) { sheet = "capabilities" }
                            BufferActionRow("协作设备", "查询、批准与撤销设备访问", BufferIcons.Leaf) { sheet = "members" }
                        }
                    }
                    item { BufferActionRow("连接与诊断", "查看设备服务状态", bufferDestinations[3].icon) { sheet = "diagnostics" } }
                }
            }
        }
    }
}
