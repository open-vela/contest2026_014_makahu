package com.mocharealm.foundation.fabric.buffer.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentStep
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaFabricQrLocator
import com.mocharealm.foundation.fabric.buffer.data.device.VelaFabricQrImageDecoder
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStatus
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private fun hasUsableCamera(context: Context): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: return false
    return try {
        cameraManager.cameraIdList.isNotEmpty()
    } catch (_: CameraAccessException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddVelaPanel(
    onConfigureAi: () -> Unit = {},
    legacyContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val app = context.applicationContext as BufferApplication
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<ComponentActivity>().first()
    val model = remember(activity, app) {
        ViewModelProvider(activity, VelaEnrollmentViewModel.Factory(app, app.repository))[VelaEnrollmentViewModel::class.java]
    }
    LaunchedEffect(model) {
        while (true) { model.refreshPaired(); kotlinx.coroutines.delay(1000) }
    }
    val view = model.view
    val paired = model.paired
    val scanning = model.scanning
    var adding by rememberSaveable { mutableStateOf(false) }
    var searchStarted by rememberSaveable { mutableStateOf(false) }
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var otherMethods by remember { mutableStateOf(false) }
    var legacyVisible by remember { mutableStateOf(false) }
    var existingNetwork by rememberSaveable { mutableStateOf(true) }
    var pendingCameraScan by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember(context) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraAvailable by remember(context) {
        mutableStateOf(hasUsableCamera(context))
    }
    var qrImageBusy by remember { mutableStateOf(false) }
    var qrImageMessage by remember { mutableStateOf<String?>(null) }
    var qrDecodeJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val permissions = remember {
        VelaBleDiscovery.permissions() + if (Build.VERSION.SDK_INT >= 37)
            arrayOf("android.permission.ACCESS_LOCAL_NETWORK") else emptyArray()
    }
    var pendingQrLocator by remember { mutableStateOf<VelaFabricQrLocator?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            val locator = pendingQrLocator
            pendingQrLocator = null
            model.scan(locator)
        } else {
            pendingQrLocator = null
            model.permissionDenied()
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        cameraAvailable = hasUsableCamera(context)
        pendingCameraScan = granted && cameraAvailable
        qrImageMessage = when {
            !granted -> "未授予相机权限，可从图片识别或查找附近设备。"
            !cameraAvailable -> "无可用相机，可从图片识别或查找附近设备。"
            else -> null
        }
    }
    fun startNearbyScan(locator: VelaFabricQrLocator? = null) {
        adding = true
        searchStarted = true
        selectedAddress = null
        qrImageMessage = null
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            model.scan(locator)
        } else {
            pendingQrLocator = locator
            permission.launch(permissions)
        }
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            model.qrScanCancelled()
        } else {
            val locator = VelaFabricQrLocator.parse(contents)
            if (locator == null) {
                model.invalidQrLocator()
            } else {
                startNearbyScan(locator)
            }
        }
    }
    fun launchQrScanner() {
        adding = true
        if (!cameraPermissionGranted) cameraPermission.launch(Manifest.permission.CAMERA)
        else qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("扫描设备配对码").setBeepEnabled(false).setOrientationLocked(false))
    }
    LaunchedEffect(pendingCameraScan) {
        if (pendingCameraScan) {
            pendingCameraScan = false
            launchQrScanner()
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) {
            qrImageBusy = false
            qrImageMessage = null
        } else {
            qrDecodeJob?.cancel()
            qrImageBusy = true
            qrImageMessage = null
            val newDecodeJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                val thisDecodeJob = coroutineContext[Job]
                try {
                    val locator = withContext(Dispatchers.IO) {
                        val decodeJob = currentCoroutineContext()[Job]
                        VelaFabricQrImageDecoder.decode(
                            context.contentResolver,
                            context.cacheDir,
                            uri,
                            VelaFabricQrImageDecoder.CancellationCheck {
                                if (decodeJob?.isActive == false) {
                                    throw CancellationException("QR image decode cancelled")
                                }
                            },
                        )
                    }
                    qrImageBusy = false
                    qrImageMessage = null
                    startNearbyScan(locator)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: VelaFabricQrImageDecoder.DecodeException) {
                    qrImageBusy = false
                    qrImageMessage = when (error.failure) {
                        VelaFabricQrImageDecoder.Failure.FILE_TOO_LARGE ->
                            "图片文件超过 8 MiB 限制，请选取较小图片。"
                        VelaFabricQrImageDecoder.Failure.IMAGE_TOO_LARGE ->
                            "图片尺寸过大，请选择较小图片。"
                        VelaFabricQrImageDecoder.Failure.INVALID_IMAGE ->
                            "无法读取这张图片，请换一张图片再试。"
                        VelaFabricQrImageDecoder.Failure.QR_NOT_FOUND ->
                            "图片中没有可识别的二维码，请换图或使用附近设备。"
                        VelaFabricQrImageDecoder.Failure.INVALID_LOCATOR ->
                            "不支持此配对码，请换图或查找附近设备。"
                    }
                } catch (error: Exception) {
                    qrImageBusy = false
                    qrImageMessage = "读取图片失败，请重新选择或使用附近设备。"
                } finally {
                    if (qrDecodeJob === thisDecodeJob) qrDecodeJob = null
                }
            }
            qrDecodeJob = newDecodeJob
            newDecodeJob.start()
        }
    }
    var resumeTarget by remember { mutableStateOf<VelaEnrollmentStatus?>(null) }
    val resumePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = resumeTarget
        resumeTarget = null
        if (granted && target != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.resumePending(target)
        else model.permissionDenied()
    }
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.stopScan()
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                cameraAvailable = hasUsableCamera(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.stopScan()
            qrDecodeJob?.cancel()
        }
    }
    val overview = paired.isNotEmpty() && !adding
    val step = view.step
    val selectedPeer = model.peers.firstOrNull { it.address == selectedAddress }
    val busy = !overview && (qrImageBusy || (view.active && step !in setOf(EnrollmentStep.CONFIRM, EnrollmentStep.NETWORK, EnrollmentStep.SERVICE_SETUP)))
    val title = when {
        overview -> "我的设备"
        step == EnrollmentStep.CONFIRM -> "核对数字"
        step == EnrollmentStep.NETWORK -> "连接网络"
        step == EnrollmentStep.VERIFY -> "检查服务"
        step == EnrollmentStep.DEVICE_FINISH -> "在设备上完成"
        step == EnrollmentStep.DEVICE_PAIRING -> "在设备上确认"
        step == EnrollmentStep.SERVICE_SETUP -> "设置 AI 服务"
        step == EnrollmentStep.COMPLETE -> "设备已就绪"
        step == EnrollmentStep.CONNECT -> "连接设备"
        searchStarted -> "选择设备"
        else -> "添加设备"
    }
    Column(Modifier.fillMaxWidth().height(540.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            if (!overview) Text(title, style = MaterialTheme.typography.headlineSmall)
            if (adding && !view.complete) TextButton(onClick = {
                qrDecodeJob?.cancel(); qrImageBusy = false
                model.stopScan()
                if (view.active) model.cancel()
                adding = false; searchStarted = false; selectedAddress = null
            }) { Text("取消") }
        }
        // The same content and footer slots are retained through the entire flow.
        // Transient transport reports never insert more buttons or resize the page.
        // Stage titles describe the real step; the protocol reports no
        // completion fraction for discovery, pairing or service verification.
        if (!overview) HorizontalDivider(modifier = Modifier.fillMaxWidth())
        else Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                overview -> LazyColumn {
                    items(paired,key={it}) { device ->
                        val connected=device in model.connectedFabricDevices
                        ListItem(headlineContent = { Text("Gemini S1") },
                            supportingContent = { Text("${device.take(12)}… · ${if(connected)"已认证连接" else "已配对"}") },
                            trailingContent = {
                                if(device in model.fabricDevices)TextButton(onClick={
                                    if(connected)model.disconnectFabric(device) else {
                                        adding=true;searchStarted=false;selectedAddress=null;model.connectSavedFabric(device)
                                    }
                                }){Text(if(connected)"断开" else "连接")}
                            })
                    }
                }
                step == EnrollmentStep.CONFIRM -> Column(
                    Modifier.fillMaxWidth().align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(view.pairingCode?.let { "%06d".format(it) }.orEmpty(),
                        style = MaterialTheme.typography.displayLarge)
                    Text("与设备屏幕上的数字一致吗？", style = MaterialTheme.typography.bodyLarge)
                }
                step == EnrollmentStep.NETWORK -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().clickable { existingNetwork = true },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(existingNetwork, onClick = { existingNetwork = true })
                        Text("使用现有网络")
                    }
                    Row(Modifier.fillMaxWidth().clickable { existingNetwork = false },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!existingNetwork, onClick = { existingNetwork = false })
                        Text("连接 Wi-Fi")
                    }
                    if (!existingNetwork) {
                        OutlinedTextField(model.ssid, { model.ssid = it },
                            Modifier.fillMaxWidth(), label = { Text("Wi-Fi 名称") }, singleLine = true)
                        OutlinedTextField(model.password, { model.password = it },
                            Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                }
                step in setOf(EnrollmentStep.CONNECT, EnrollmentStep.DEVICE_PAIRING, EnrollmentStep.VERIFY, EnrollmentStep.DEVICE_FINISH, EnrollmentStep.SERVICE_SETUP) ->
                    Column(Modifier.align(Alignment.Center).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (step !in setOf(EnrollmentStep.DEVICE_FINISH, EnrollmentStep.DEVICE_PAIRING, EnrollmentStep.SERVICE_SETUP)) CircularProgressIndicator(Modifier.size(32.dp))
                        Text(when (step) {
                            EnrollmentStep.VERIFY -> "正在检查设备服务"
                            EnrollmentStep.DEVICE_PAIRING -> "请在设备上确认相同数字"
                            EnrollmentStep.SERVICE_SETUP -> "先在手机设置 AI 服务，再继续验证设备。"
                            EnrollmentStep.DEVICE_FINISH -> "请在设备上点击“开始使用”"
                            else -> "请保持设备在附近"
                        }, style = MaterialTheme.typography.bodyLarge)
                    }
                step == EnrollmentStep.COMPLETE -> Text("可以开始使用了", Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge)
                searchStarted -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (scanning) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在查找")
                        }
                    }
                    items(model.peers, key = { it.address }) { peer ->
                        ListItem(headlineContent = { Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { RadioButton(peer.address == selectedAddress,
                                onClick = { selectedAddress = peer.address }) },
                            modifier = Modifier.clickable { selectedAddress = peer.address })
                    }
                    if (!scanning && model.peers.isEmpty()) item { Text("没有找到设备，请确认设备已打开配对页面。") }
                }
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("打开设备上的配对页面", style = MaterialTheme.typography.bodyLarge)
                    model.pending.firstOrNull()?.let { pending ->
                        TextButton(onClick = {
                            adding = true
                            if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                                resumeTarget = pending
                                resumePermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                            } else model.resumePending(pending)
                        }) { Text("继续上次设置") }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            val errorText = qrImageMessage ?: view.message.takeIf { view.error }
            if (!errorText.isNullOrBlank()) Text(errorText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Button(modifier = Modifier.fillMaxWidth(),
            enabled = !qrImageBusy && !busy && (step != EnrollmentStep.CONFIRM || view.pairingCode != null) &&
                (step != EnrollmentStep.NETWORK || existingNetwork || (model.ssid.isNotBlank() && model.password.length >= 8)),
            onClick = {
                when {
                    overview -> { adding = true; startNearbyScan() }
                    step == EnrollmentStep.CONFIRM -> model.confirmPairing()
                    step == EnrollmentStep.NETWORK -> if (existingNetwork) model.useExistingWifi() else model.submit()
                    step == EnrollmentStep.COMPLETE -> { adding = false; searchStarted = false }
                    step == EnrollmentStep.SERVICE_SETUP -> onConfigureAi()
                    selectedPeer != null -> { adding = true; model.connect(selectedPeer) }
                    scanning -> model.stopScan()
                    searchStarted -> startNearbyScan()
                    cameraAvailable -> launchQrScanner()
                    else -> startNearbyScan()
                }
            }) {
            Text(when {
                overview -> "添加设备"
                qrImageBusy -> "正在识别"
                step == EnrollmentStep.CONFIRM -> "数字一致"
                step == EnrollmentStep.NETWORK -> "继续"
                step == EnrollmentStep.COMPLETE -> "完成"
                step == EnrollmentStep.SERVICE_SETUP -> "设置 AI 服务"
                step == EnrollmentStep.DEVICE_PAIRING -> "等待设备确认"
                step == EnrollmentStep.DEVICE_FINISH -> "等待设备确认"
                step == EnrollmentStep.VERIFY -> "检查中"
                busy -> "连接中"
                selectedPeer != null -> "连接"
                scanning -> "停止查找"
                searchStarted -> "重新查找"
                cameraAvailable -> "扫描配对码"
                else -> "查找附近设备"
            })
        }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            if (step == EnrollmentStep.SERVICE_SETUP) {
                TextButton(onClick = { model.useExistingWifi() }) { Text("已设置，继续") }
            } else if (!overview && step == EnrollmentStep.DISCOVER) {
                TextButton(enabled = !qrImageBusy, onClick = { otherMethods = true }) { Text("其他方式") }
                DropdownMenu(expanded = otherMethods, onDismissRequest = { otherMethods = false }) {
                    DropdownMenuItem(text = { Text("附近设备") }, onClick = { otherMethods = false; startNearbyScan() })
                    DropdownMenuItem(text = { Text("从图片识别") }, onClick = {
                        otherMethods = false; adding = true
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    if (cameraAvailable) DropdownMenuItem(text = { Text("扫描二维码") },
                        onClick = { otherMethods = false; launchQrScanner() })
                    DropdownMenuItem(text = { Text("局域网连接") }, onClick = { otherMethods = false; legacyVisible = true })
                }
            }
        }
    }
    if (legacyVisible) ModalBottomSheet(onDismissRequest = { legacyVisible = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) { legacyContent() }
    }
}
