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
    var pending by mutableStateOf(repository.velaEnrollments.activeStatuses())
        private set
    private var waitingDeviceId: String? = null
    // Memory only: never put the Wi-Fi password in SavedStateHandle or saved instance state.
    var ssid by mutableStateOf("")
    var password by mutableStateOf("")
    private val client = VelaGattEnrollment(this.context, repository) {
        view = it
        if (it.complete || !it.active) password = ""
        if (it.complete) { peers = emptyList(); paired = repository.pairedVelaIds() }
    }

    fun refreshPaired() {
        pending = repository.velaEnrollments.activeStatuses()
        paired = repository.pairedVelaIds()
        val waiting = waitingDeviceId
        if (waiting != null && waiting in paired) {
            waitingDeviceId = null
            view = EnrollmentView("已通过局域网确认添加 $waiting", active = false, complete = true)
        } else if (waiting != null && pending.none { it.deviceId == waiting }) {
            waitingDeviceId = null
            view = EnrollmentView("待添加记录已改变，请检查设备状态", active = false)
        }
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
            view = EnrollmentView("等待 ${status.deviceId} 的认证回执，请让手机与 S1 处于同一局域网。若 S1 尚未完成配网，请重新扫描。", active = false)
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
                cancelled -> EnrollmentView("已取消 ${status.deviceId} 的待添加授权", active = false)
                status.deviceId in paired -> EnrollmentView("设备已经添加，未撤销正式绑定", active = false, complete = true)
                else -> EnrollmentView("待添加记录已改变，请刷新后重试", active = false)
            }
        } catch (_: Exception) {
            view = EnrollmentView("取消未能保存，请重试", active = false)
        }
    }

    fun scan() {
        if (view.active) return
        waitingDeviceId = null
        peers = emptyList(); scanning = true
        view = EnrollmentView("正在查找附近的 S1", active = false)
        discovery.scan({ peers = it }, { scanning = false; view = EnrollmentView(it, active = false) })
    }
    fun stopScan() {
        discovery.close()
        if (scanning) view = EnrollmentView("扫描已停止", active = false)
        scanning = false
    }
    fun permissionDenied() {
        if (!view.active) view = EnrollmentView("需要附近设备权限；联网后的连接验证还需要局域网权限", active = false)
    }
    fun connect(peer: VelaBlePeer) {
        if (view.active) return
        stopScan()
        waitingDeviceId = null
        try {
            ContextCompat.startForegroundService(context, Intent(context, BufferBridgeService::class.java))
            client.connect(peer)
        } catch (_: Exception) {
            client.close()
            password = ""
            view = EnrollmentView("无法启动设备连接服务，请检查权限后重试", active = false)
        }
    }
    fun submit() = client.submit(ssid, password)
    fun useExistingWifi() { password = ""; client.submit("", "", useExistingWifi = true) }
    fun cancel() { client.cancel(); password = "" }
    override fun onCleared() {
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
