package com.mocharealm.foundation.fabric.buffer.data.device
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

internal data class EnrollmentView(val message: String, val canConfigure: Boolean = false,
    val active: Boolean = true, val complete: Boolean = false)

/** Serializes GATT operations on the main looper; no credentials are logged. */
@SuppressLint("MissingPermission") // Entry is permission-gated; revocation reaches fail().
internal class VelaGattEnrollment(context: Context, private val repository: BufferRepository,
    private val update: (EnrollmentView) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var resultCharacteristic: BluetoothGattCharacteristic? = null
    private var receiverRegistered = false
    private var passkeyRequested = false
    private var knownDeviceId: String? = null
    private var pending: PendingVelaEnrollment? = null
    private var operation = ""
    private var reader = VelaEnrollmentResultReader()
    private var fragments = emptyList<ByteArray>()
    private var fragmentIndex = 0
    private var submitted = false
    private var retryRequired = false
    private var waitingLan = false
    private var session = 0L
    private var timeout: Runnable? = null

    private fun report(message: String, configure: Boolean = false) = update(EnrollmentView(message, configure))
    private fun guard(block: () -> Unit) {
        try { block() } catch (error: Exception) { fail(error.message ?: "添加设备失败") }
    }
    private fun deadline(ms: Long, message: String) {
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable { fail(message) }.also { handler.postDelayed(it, ms) }
    }
    private fun finishOperation() { timeout?.let(handler::removeCallbacks); timeout = null; operation = "" }
    private fun later(block: () -> Unit) {
        val current = session
        handler.postDelayed({ if (session == current && gatt != null) guard(block) }, 750)
    }

    fun connect(peer: VelaBlePeer) = guard {
        close()
        passkeyRequested = false; submitted = false; retryRequired = false; waitingLan = false
        pending = null; fragmentIndex = 0
        knownDeviceId = repository.velaEnrollments.forAddress(peer.address)?.deviceId
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(peer.address)
            ?: throw IOException("手机蓝牙不可用")
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        report("正在连接 ${peer.name}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("无法启动蓝牙连接")
        deadline(30_000, "连接超时，请确认 S1 仍在添加模式")
        handler.postDelayed({ fail("添加超时，已保存待确认记录；请检查 S1 与手机网络后重试") }, 180_000)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = guard {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return@guard
            val current = gatt ?: return@guard
            if (device.address != current.device.address) return@guard
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    // AOSP's protected broadcast encodes passkey ENTRY as 1 (hidden SDK constant).
                    if (intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1) != 1)
                        throw IOException("设备未请求输入屏幕验证码，已停止添加")
                    passkeyRequested = true
                    report("请在系统蓝牙配对窗口输入 S1 屏幕上的蓝牙验证码")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> if (operation == "bond") authenticated()
                    BluetoothDevice.BOND_NONE -> if (operation == "bond") throw IOException("蓝牙认证未完成，请重试")
                }
            }
        }
    }

    private fun dispatch(source: BluetoothGatt, block: () -> Unit) {
        handler.post { if (source === gatt) guard(block) }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(source: BluetoothGatt, status: Int, state: Int) = dispatch(source) {
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (waitingLan) finishOperation()
                else if (submitted && pending != null) {
                    finishOperation(); waitingLan = true
                    report("蓝牙已断开，正在等待 S1 通过局域网确认配网结果")
                    pollLan()
                } else throw IOException("蓝牙连接已断开，请重新选择设备")
            } else if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("蓝牙连接失败（$status）")
            else if (state == BluetoothProfile.STATE_CONNECTED) {
                finishOperation(); operation = "discover"
                if (!source.discoverServices()) throw IOException("无法查找配网服务")
                deadline(15_000, "查找配网服务超时")
            }
        }
        override fun onServicesDiscovered(source: BluetoothGatt, status: Int) = dispatch(source) {
            if (operation != "discover") return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("查找配网服务失败（$status）")
            val service = source.getService(VelaBleDiscovery.SERVICE_UUID) ?: throw IOException("设备未提供配网服务")
            writeCharacteristic = service.getCharacteristic(WRITE_UUID) ?: throw IOException("缺少配网写入服务")
            resultCharacteristic = service.getCharacteristic(RESULT_UUID) ?: throw IOException("缺少配网结果服务")
            finishOperation()
            operation = "bond"
            deadline(90_000, "蓝牙验证码确认超时")
            if (source.device.bondState == BluetoothDevice.BOND_BONDED) authenticated()
            else {
                report("请在系统配对窗口输入 S1 屏幕上的蓝牙验证码")
                if (!source.device.createBond()) throw IOException("无法启动蓝牙认证")
            }
        }
        override fun onCharacteristicWrite(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            status: Int) = dispatch(source) {
            if (characteristic.uuid != WRITE_UUID || operation !in setOf("select", "config")) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("蓝牙写入失败（$status）")
            val was = operation; finishOperation()
            if (was == "config") {
                fragments[fragmentIndex].fill(0); fragmentIndex++
                if (fragmentIndex < fragments.size) writeFragment()
                else { fragments = emptyList(); report("配置已发送，等待 S1 联网"); readStatus() }
            } else {
                operation = "read"
                if (!source.readCharacteristic(requireNotNull(resultCharacteristic))) throw IOException("无法读取配网结果")
                deadline(15_000, "读取配网结果超时")
            }
        }
        override fun onCharacteristicRead(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int) = readCallback(source, characteristic, value.clone(), status)
        @Deprecated("Legacy callback for Android before 13")
        override fun onCharacteristicRead(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) readCallback(source, characteristic, characteristic.value?.clone() ?: byteArrayOf(), status)
        }
    }

    private fun authenticated() {
        if (!passkeyRequested && knownDeviceId == null)
            throw IOException("此蓝牙绑定尚未由 Buffer 验证，请在系统蓝牙设置中取消旧绑定后重试")
        finishOperation(); report("正在验证设备身份"); readStatus()
    }
    private fun readStatus() { reader = VelaEnrollmentResultReader(); write(reader.selector(), "select") }
    private fun readCallback(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        dispatch(source) {
            if (characteristic.uuid != RESULT_UUID || operation != "read") return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("读取配网结果失败（$status）")
            finishOperation()
            val result = reader.accept(value)
            if (result == null) write(reader.selector(), "select") else handleResult(result)
        }
    }
    private fun handleResult(result: VelaEnrollmentResult) {
        if (knownDeviceId != null && knownDeviceId != result.deviceId) throw IOException("设备身份与之前的添加记录不同")
        if (pending == null) {
            if (repository.pairedVelaToken(result.deviceId) != null) {
                complete(result.deviceId); return
            }
            pending = repository.velaEnrollments.begin(result.deviceId, requireNotNull(gatt).device.address)
            knownDeviceId = result.deviceId
        }
        val row = requireNotNull(pending)
        if (result.deviceId != row.deviceId) throw IOException("配网过程中设备身份发生变化")
        if (result.state == "invalid_request" || result.state == "request_conflict") throw IOException("设备拒绝配置请求（${result.error}）")
        if (result.state == "network_ready" && result.requestId == row.requestId) {
            repository.velaEnrollments.networkReady(row.deviceId, row.requestId)
            waitingLan = true; report("S1 已联网，正在验证手机连接"); pollLan(); return
        }
        if (result.state == "failed" && result.requestId == row.requestId) {
            retryRequired = true; submitted = false
            report("S1 未能完成配置（${result.error}），请检查 Wi-Fi 后重试", true); return
        }
        if (!submitted && result.state in setOf("idle", "failed")) {
            retryRequired = result.state == "failed"
            report("设备已验证，请填写 S1 要连接的 Wi-Fi", true); return
        }
        if (result.requestId.isNotEmpty() && result.requestId != row.requestId) throw IOException("设备正在处理其他配置请求")
        report("等待 S1 联网…"); later { readStatus() }
    }

    fun submit(ssid: String, password: String, useExistingWifi: Boolean = false) {
      val current = pending ?: return
      if (operation.isNotEmpty() || submitted || waitingLan) return
      fun encode(row: PendingVelaEnrollment) = if (useExistingWifi)
          VelaEnrollmentProtocol.encodeExisting(row.requestId, repository.deviceId(), row.token)
          else VelaEnrollmentProtocol.encode(row.requestId, repository.deviceId(), row.token, ssid, password)
      try {
        encode(current).fill(0)
      } catch (error: IllegalArgumentException) {
        report(error.message ?: "请检查 Wi-Fi 信息", true)
        return
      }
      guard {
        var row = current
        if (retryRequired) row = repository.velaEnrollments.retry(row.deviceId, row.requestId).also { pending = it }
        val request = encode(row)
        fragments = VelaEnrollmentProtocol.fragments(request, 23); request.fill(0)
        fragmentIndex = 0; submitted = true; retryRequired = false
        report("正在发送 Wi-Fi 配置"); writeFragment()
      }
    }
    private fun writeFragment() = write(fragments[fragmentIndex], "config")
    private fun write(value: ByteArray, kind: String) {
        val connection = gatt ?: throw IOException("蓝牙已断开")
        val characteristic = requireNotNull(writeCharacteristic)
        operation = kind
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            connection.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value.clone()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            connection.writeCharacteristic(characteristic)
        }
        if (!ok) throw IOException("无法提交蓝牙操作")
        deadline(15_000, "蓝牙操作超时")
    }
    private fun pollLan() {
        val row = pending ?: return
        if (repository.pairedVelaToken(row.deviceId) == row.token) complete(row.deviceId)
        else later { pollLan() }
    }
    private fun complete(deviceId: String) { close(); update(EnrollmentView("已添加 $deviceId", active = false, complete = true)) }
    fun cancel() {
        try {
            pending?.let {
                if (!repository.velaEnrollments.cancel(it.deviceId, it.requestId) &&
                    repository.pairedVelaToken(it.deviceId) != null) {
                    complete(it.deviceId); return
                }
            }
        } catch (_: Exception) { fail("无法保存取消状态，请重新打开应用检查设备状态"); return }
        close(); update(EnrollmentView("已取消添加", active = false))
    }
    private fun fail(message: String) { close(); update(EnrollmentView(message, active = false)) }
    override fun close() {
        session++; handler.removeCallbacksAndMessages(null); timeout = null; operation = ""
        fragments.forEach { it.fill(0) }; fragments = emptyList()
        val old = gatt; gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        try { old?.close() } catch (_: SecurityException) { }
        if (receiverRegistered) { context.unregisterReceiver(receiver); receiverRegistered = false }
        writeCharacteristic = null; resultCharacteristic = null
    }
    companion object {
        val WRITE_UUID: UUID = UUID.fromString("487e1001-7d32-4e0a-9c6b-3f62bd52b101")
        val RESULT_UUID: UUID = UUID.fromString("487e1002-7d32-4e0a-9c6b-3f62bd52b101")
    }
}
