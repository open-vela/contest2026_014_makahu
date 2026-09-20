package com.mocharealm.foundation.fabric.buffer.data.device
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import java.util.UUID

/** Discovery hints are untrusted until the device authenticates. */
internal data class VelaBlePeer(val address: String, val name: String, val rssi: Int)

internal class VelaBleDiscovery(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var stopScan: (() -> Unit)? = null
    private var generation = 0L

    @SuppressLint("MissingPermission") // Checked here; permission revocation is handled too.
    fun scan(onPeers: (List<VelaBlePeer>) -> Unit, onFinished: (String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        if (permissions().any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            onFinished("请允许附近设备扫描权限")
            return
        }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                onFinished("请先打开手机蓝牙")
                return
            }
            if (Build.VERSION.SDK_INT < 31 && context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
                onFinished("当前 Android 版本需要开启定位服务才能扫描蓝牙")
                return
            }
            val scanner = adapter.bluetoothLeScanner ?: run {
                onFinished("手机蓝牙扫描暂不可用")
                return
            }
            val session = generation
            val peers = linkedMapOf<String, VelaBlePeer>()
            fun finish(message: String) {
                if (session != generation) return
                close()
                onFinished(message)
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (session != generation || stopScan == null) return@post
                        if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) != true) return@post
                        try {
                            val address = result.device.address
                            if (address !in peers && peers.size >= 32) return@post
                            peers[address] = VelaBlePeer(address,
                                result.scanRecord?.deviceName?.take(40) ?: "Gemini S1", result.rssi)
                            onPeers(peers.values.sortedByDescending { it.rssi })
                        } catch (_: SecurityException) { finish("蓝牙权限已撤销") }
                    }
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(0, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post { finish("蓝牙扫描失败（$errorCode），请稍后重试") }
                }
            }
            stopScan = { scanner.stopScan(callback) }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed({ finish(if (peers.isEmpty()) "未发现待添加设备，请确认 S1 已进入添加模式" else "扫描完成") }, 10_000)
        } catch (_: SecurityException) {
            close()
            onFinished("蓝牙权限不可用，请在系统设置中授权")
        } catch (_: IllegalStateException) {
            close()
            onFinished("蓝牙已关闭或暂不可用")
        }
    }

    override fun close() {
        generation++
        handler.removeCallbacksAndMessages(null)
        val stop = stopScan
        stopScan = null
        try { stop?.invoke() } catch (_: SecurityException) {
            // Revocation already stops access; generation excludes stale callbacks.
        } catch (_: IllegalStateException) {
            // Adapter may have been disabled during discovery.
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("487e1000-7d32-4e0a-9c6b-3f62bd52b101")
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
