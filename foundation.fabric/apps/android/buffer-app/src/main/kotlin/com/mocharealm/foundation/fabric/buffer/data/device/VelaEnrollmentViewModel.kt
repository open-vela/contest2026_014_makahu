package com.mocharealm.foundation.fabric.buffer.data.device
import com.mocharealm.foundation.fabric.buffer.BufferBridgeService
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Activity-owned session: recomposition and configuration changes must not close GATT. */
internal class VelaEnrollmentViewModel(context: Context, private val repository: BufferRepository) : ViewModel() {
    private val context = context.applicationContext
    private val discovery = VelaBleDiscovery(this.context)
    var peers by mutableStateOf(emptyList<VelaBlePeer>())
        private set
    var scanning by mutableStateOf(false)
        private set
    var view by mutableStateOf(EnrollmentView("打开 S1 的添加设备模式，无需预先连接 Wi-Fi", active = false))
        private set
    var paired by mutableStateOf(repository.pairedVelaIds())
        private set
    var fabricDevices by mutableStateOf(emptySet<String>())
        private set
    var connectedFabricDevices by mutableStateOf(emptySet<String>())
        private set
    var pending by mutableStateOf(repository.velaEnrollments.activeStatuses())
        private set
    private var waitingDeviceId: String? = null
    private var qrLocator: VelaFabricQrLocator? = null
    private val fabric = (context.applicationContext as? com.mocharealm.foundation.fabric.buffer.BufferApplication)?.velaFabric
    private var fabricSelected = fabric?.boundDevice != null
    private var transportChosen = false
    private var setupInProgress=false
    init {
        fabric?.listener = {
            if (fabricSelected || (!transportChosen && fabric?.boundDevice != null)) {
                fabricSelected = true
                view = it
                if (it.complete || !it.active) password = ""
                if (it.complete || !it.active) setupInProgress=false
                if (it.complete) paired = (repository.pairedVelaIds() + fabric?.boundDevices.orEmpty()).distinct()
            }
        }
    }
    // Memory only: never put the Wi-Fi password in SavedStateHandle or saved instance state.
    var ssid by mutableStateOf("")
    var password by mutableStateOf("")
    private val client = VelaGattEnrollment(this.context, repository) {
        view = it
        if (it.complete || !it.active) password = ""
        if (it.complete || !it.active) setupInProgress=false
        if (it.complete) { peers = emptyList(); paired = repository.pairedVelaIds() }
    }

    fun refreshPaired() {
        pending = repository.velaEnrollments.activeStatuses()
        paired = (repository.pairedVelaIds() + fabric?.boundDevices.orEmpty()).distinct()
        fabricDevices=fabric?.boundDevices.orEmpty().toSet()
        connectedFabricDevices=fabric?.connectedDevices.orEmpty()
        val waiting = waitingDeviceId
        if (waiting != null && waiting in paired) {
            waitingDeviceId = null
            view = EnrollmentView("已通过局域网确认设备绑定", active = false, complete = true)
        } else if (waiting != null && pending.none { it.deviceId == waiting }) {
            waitingDeviceId = null
            view = EnrollmentView("待添加记录已改变，请检查设备状态", active = false)
        }
    }
    fun connectSavedFabric(device: String) {
        if(device !in fabricDevices)return
        stopScan();password="";qrLocator=null;transportChosen=true;fabricSelected=true;setupInProgress=true
        fabric?.connectSaved(device)
    }
    fun disconnectFabric(device: String) {
        if(device !in fabricDevices)return
        fabric?.disconnect(device);password="";refreshPaired()
    }

    fun resumePending(status: VelaEnrollmentStatus) {
        if (view.active) return
        stopScan()
        refreshPaired()
        if (pending.none { it.deviceId == status.deviceId && it.requestId == status.requestId }) {
            view = EnrollmentView("待添加记录已改变，请检查设备状态", active = false)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, BufferBridgeService::class.java))
            waitingDeviceId = status.deviceId
            view = EnrollmentView("等待设备连接", active = false, step = EnrollmentStep.VERIFY)
        } catch (_: Exception) {
            view = EnrollmentView("无法启动连接服务，请检查局域网权限后重试", active = false)
        }
    }

    fun cancelPending(status: VelaEnrollmentStatus) {
        if (view.active) return
        try {
            val cancelled = repository.velaEnrollments.cancel(status.deviceId, status.requestId)
            waitingDeviceId = null
            refreshPaired()
            view = when {
                cancelled -> EnrollmentView("已取消这次待添加授权", active = false)
                status.deviceId in paired -> EnrollmentView("设备已经添加，未撤销正式绑定", active = false, complete = true)
                else -> EnrollmentView("待添加记录已改变，请刷新后重试", active = false)
            }
        } catch (_: Exception) {
            view = EnrollmentView("取消未能保存，请重试", active = false)
        }
    }

    fun scan(locator: VelaFabricQrLocator? = null) {
        if (setupInProgress && view.active) return
        fabric?.clearSelection();setupInProgress=false
        waitingDeviceId = null
        qrLocator = locator
        peers = emptyList(); scanning = true
        view = EnrollmentView(
            if (locator == null) "正在查找附近的 S1"
            else "二维码已识别，正在查找附近的 Fabric 设备",
            active = false,
        )
        discovery.scan({ found ->
            peers = if (qrLocator == null) found else found.filter { it.fabricMessages }
        }, { scanning = false; view = EnrollmentView(it, active = false) })
    }
    fun invalidQrLocator() {
        if (!view.active) {
            qrLocator = null
            view = EnrollmentView("这不是设备配对码，请重新扫描", active = false, error = true)
        }
    }
    fun qrScanCancelled() {
        if (!view.active) view = EnrollmentView("已取消扫码；仍可查找附近设备", active = false)
    }
    fun stopScan() {
        discovery.close()
        if (scanning) view = EnrollmentView("扫描已停止", active = false)
        scanning = false
    }
    fun permissionDenied() {
        if (!view.active) view = EnrollmentView("请允许查找附近设备和本地网络访问", active = false, error = true)
    }
    fun connect(peer: VelaBlePeer) {
        if (view.active) return
        stopScan()
        waitingDeviceId = null
        transportChosen = true
        setupInProgress=true
        fabricSelected = peer.fabricMessages
        if (fabricSelected) {
            if (fabric == null) view = EnrollmentView("安全连接服务不可用，请重启应用", active = false)
            else fabric.connect(peer, qrLocator)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, BufferBridgeService::class.java))
            client.connect(peer)
        } catch (_: Exception) {
            client.close()
            password = ""
            view = EnrollmentView("无法启动设备连接服务，请检查权限后重试", active = false)
        }
    }
    fun confirmPairing() { if (fabricSelected) fabric?.confirmPairing() }
    fun submit() { if (fabricSelected) fabric?.submit(ssid, password) else client.submit(ssid, password) }
    fun useExistingWifi() { password = ""; if (fabricSelected) fabric?.submit("", "", true) else client.submit("", "", useExistingWifi = true) }
    fun cancel() { if (fabricSelected) fabric?.cancel() else client.cancel(); password = "";setupInProgress=false }
    override fun onCleared() {
        fabric?.listener = null
        discovery.close(); client.close(); password = ""; ssid = ""
    }
    class Factory(private val context: Context, private val repository: BufferRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == VelaEnrollmentViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return VelaEnrollmentViewModel(context, repository) as T
        }
    }
}
