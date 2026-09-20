package com.mocharealm.foundation.fabric.buffer.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStatus
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel
@Composable
internal fun AddVelaPanel() {
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
    val permissions = remember {
        VelaBleDiscovery.permissions() + if (Build.VERSION.SDK_INT >= 37)
            arrayOf("android.permission.ACCESS_LOCAL_NETWORK") else emptyArray()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.scan()
        else model.permissionDenied()
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
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stopScan() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("添加设备")
        Text(view.message)
        if (paired.isNotEmpty()) Text("已添加：${paired.joinToString()}")
        if (!view.active) {
            if (model.pending.isNotEmpty()) Text("待完成设备")
            model.pending.forEach { pending ->
                Text("${pending.deviceId} · " + if (pending.networkReady) "设备已报告联网，等待连接验证" else "等待配网结果或认证回执")
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                        resumeTarget = pending
                        resumePermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    } else model.resumePending(pending)
                }) { Text("继续等待连接") }
                Button(onClick = { model.cancelPending(pending) }) { Text("取消这次添加") }
            }
            Button(enabled = !scanning, onClick = {
                if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) model.scan()
                else permission.launch(permissions)
            }) { Text(if (scanning) "扫描中…" else "查找附近设备") }
            model.peers.forEach { peer ->
                Button(onClick = {
                    model.connect(peer)
                }) { Text("${peer.name} · ${peer.address}") }
            }
        }
        if (view.canConfigure) {
            Button(onClick = { model.useExistingWifi() }) { Text("使用 S1 已保存的 Wi-Fi 并添加") }
            Text("设备已配过网可直接使用；否则在下面填写新的 Wi-Fi。")
            OutlinedTextField(value = model.ssid, onValueChange = { model.ssid = it }, singleLine = true,
                label = { Text("Wi-Fi 名称") })
            OutlinedTextField(value = model.password, onValueChange = { model.password = it }, singleLine = true,
                label = { Text("Wi-Fi 密码") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(onClick = { model.submit() }) { Text("连接 Wi-Fi 并添加") }
        }
        if (view.active) Button(onClick = { model.cancel() }) { Text("取消添加") }
    }
}
