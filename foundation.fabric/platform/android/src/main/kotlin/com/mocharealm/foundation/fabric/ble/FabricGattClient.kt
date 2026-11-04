package com.mocharealm.foundation.fabric.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Actual Android central GATT packet IO for Fabric portable-v1.
 * It does not establish identity/trust by Bluetooth bonding. Feed packets into
 * FabricBleNative pairing/session host on this worker and enforce host policy.
 * Listener callbacks run on the dedicated worker, never the Bluetooth/UI thread.
 * They must be bounded and must enqueue application work to its own executor. */
class FabricGattClient(context: Context, private val listener: Listener, expectedPublicKey: ByteArray?, localPublicKey: ByteArray?) : Closeable {
    constructor(context: Context, listener: Listener, expectedPublicKey: ByteArray? = null) : this(context, listener, expectedPublicKey, null)
    private val qrPin = expectedPublicKey?.copyOf().also { require(it == null || it.size == 32) }
    private val localLocator = localPublicKey?.copyOf().also { require(it == null || it.size == 32) }
    interface Listener {
        fun onIdentity(publicKey: ByteArray, deviceId: ByteArray) {}
        fun onReady(packetBytes: Int)
        fun onPacket(packet: ByteArray)
        fun onClosed(reason: String)
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("7f6d0001-6b4e-4a30-9e8f-464142524943")
        val RX: UUID = UUID.fromString("7f6d0002-6b4e-4a30-9e8f-464142524943")
        val IDENTITY: UUID = UUID.fromString("7f6d0004-6b4e-4a30-9e8f-464142524943")
        /** Optional public-key routing hint. It never replaces authentication
         * or application approval and is absent on portable-v1 peripherals. */
        val PEER_SELECTOR: UUID = UUID.fromString("7f6d0005-6b4e-4a30-9e8f-464142524943")
        val TX: UUID = UUID.fromString("7f6d0003-6b4e-4a30-9e8f-464142524943")
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val app = context.applicationContext
    private var selector: BluetoothGattCharacteristic? = null
    private var selectorOffset = 0
    private var selectorPart = 0
    private val thread = HandlerThread("FabricGattPackets").apply { start() }
    private val worker = Handler(thread.looper)
    private val inbound = ArrayBlockingQueue<ByteArray>(64)
    private val outbound = ArrayBlockingQueue<ByteArray>(32)
    private val drainPosted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var inFlight: ByteArray? = null
    private var attempts = 0
    private var writeGeneration = 0L
    @Volatile private var ready = false
    @Volatile private var packetBytes = 20
    private var connecting = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        worker.post {
            if (closed.get()) return@post
            if (connecting || gatt != null) { stop("already connected"); return@post }
            if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                stop("Bluetooth connect permission denied"); return@post
            }
            connecting = true
            try {
                gatt = device.connectGatt(app, false, callbacks, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) stop("GATT unavailable")
                worker.postDelayed({ if (!ready && !closed.get()) stop("GATT setup timeout") }, 15_000)
            } catch (_: SecurityException) { stop("Bluetooth connect permission denied") }
        }
    }

    /** Copies opaque fragments into a bounded queue. false = backpressure or
     * disconnected; caller retains the packet and retries after draining. */
    fun sendPacket(packet: ByteArray): Boolean {
        if (!ready || closed.get() || packet.size !in 6..packetBytes) return false
        if (!outbound.offer(packet.copyOf())) return false
        scheduleDrain()
        return true
    }
    fun isIdle(): Boolean = ready && !closed.get() && outbound.isEmpty() && inFlight == null

    private fun scheduleDrain() {
        if (drainPosted.compareAndSet(false, true)) worker.post {
            drainPosted.set(false)
            if (closed.get()) return@post
            while (true) {
                val packet = inbound.poll() ?: break
                try { listener.onPacket(packet) } catch (_: RuntimeException) { stop("packet consumer failed"); return@post }
            }
            writeNext()
        }
    }
    private fun receive(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (closed.get() || characteristic.uuid != TX) return
        if (bytes.size !in 6..512 || !inbound.offer(bytes.copyOf())) {
            worker.post { if (g === gatt) stop("GATT inbound budget exceeded") }; return
        }
        scheduleDrain()
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeNext() {
        if (!ready || closed.get() || inFlight != null) return
        inFlight = outbound.poll() ?: return
        attempts = 0
        writeCurrent()
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCurrent() {
        val g = gatt ?: return
        val c = rx ?: return
        val packet = inFlight ?: return
        attempts++
        val generation = ++writeGeneration
        val started = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = packet
                g.writeCharacteristic(c)
            }
        } catch (_: SecurityException) { stop("Bluetooth write permission denied"); return }
        if (!started) { retryWrite(); return }
        worker.postDelayed({ if (inFlight != null && generation == writeGeneration) stop("GATT write timeout") }, 5_000)
    }
    private fun retryWrite() {
        if (attempts >= 3) { stop("GATT write failed"); return }
        worker.postDelayed({ if (!closed.get() && inFlight != null) writeCurrent() }, 50)
    }
    private val callbacks = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            worker.post {
                if (g !== gatt || closed.get()) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    stop("GATT disconnected status=$status state=$state"); return@post
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    try { if (!g.requestMtu(247) && !g.discoverServices()) stop("service discovery failed") }
                    catch (_: SecurityException) { stop("Bluetooth setup permission denied") }
                }
            }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            worker.post {
                if (g !== gatt || closed.get()) return@post
                packetBytes = if (status == BluetoothGatt.GATT_SUCCESS) (mtu - 3).coerceIn(20, 512) else 20
                try { if (!g.discoverServices()) stop("service discovery failed") }
                catch (_: SecurityException) { stop("Bluetooth setup permission denied") }
            }
        }
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            worker.post {
                if (g !== gatt || closed.get()) return@post
                val service = g.getService(SERVICE)
                rx = service?.getCharacteristic(RX)
                tx = service?.getCharacteristic(TX)
                selector = service?.getCharacteristic(PEER_SELECTOR)?.takeIf {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                }
                val c = tx
                val descriptor = c?.getDescriptor(CCC)
                if (status != BluetoothGatt.GATT_SUCCESS || rx == null || c == null || descriptor == null ||
                    rx!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                    c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                    stop("Fabric data service unsupported status=$status"); return@post
                }
                try {
                if (!g.setCharacteristicNotification(c, true)) { stop("notifications unsupported"); return@post }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
                if (!started) stop("notification subscription failed")
                } catch (_: SecurityException) { stop("Bluetooth subscription permission denied") }
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            worker.post {
                if (g !== gatt || closed.get() || descriptor.uuid != CCC) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { stop("notification subscription failed status=$status"); return@post }
                val identity = g.getService(SERVICE)?.getCharacteristic(IDENTITY)
                if (identity != null) {
                    @SuppressLint("MissingPermission")
                    val started = try { g.readCharacteristic(identity) } catch (_: SecurityException) { false }
                    if (!started) stop("Fabric identity read failed")
                } else {
                    // A QR pin is a locator only; the native pairing host
                    // still requires the signed exchange and both confirmations.
                    val pin = qrPin
                    if (pin == null) { stop("Fabric identity locator unavailable"); return@post }
                    try { listener.onIdentity(pin.copyOf(),MessageDigest.getInstance("SHA-256").digest(pin)) }
                    catch (_: RuntimeException) { stop("identity consumer failed"); return@post }
                    markReady()
                }
            }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readIdentity(g,c,c.value ?: byteArrayOf(),status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readIdentity(g,c,value,status)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            worker.post {
                if (g === gatt && !closed.get() && c.uuid == PEER_SELECTOR && !ready) {
                    if (status != BluetoothGatt.GATT_SUCCESS || selectorPart == 0) { stop("Fabric peer selection failed"); return@post }
                    selectorOffset += selectorPart; selectorPart = 0
                    markReady(); return@post
                }
                if (g !== gatt || closed.get() || c.uuid != RX || inFlight == null) return@post
                writeGeneration++
                if (status == BluetoothGatt.GATT_SUCCESS) { inFlight = null; writeNext() } else retryWrite()
            }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            receive(g, c, c.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receive(g, c, value)
        }
    }
    private fun readIdentity(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray, status: Int) {
        val copy = data.copyOf()
        worker.post {
            if (g !== gatt || closed.get() || c.uuid != IDENTITY) return@post
            if (status != BluetoothGatt.GATT_SUCCESS || copy.size != 64 ||
                (qrPin != null && !MessageDigest.isEqual(qrPin,copy.copyOfRange(0,32))) ||
                !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(copy.copyOfRange(0,32)),copy.copyOfRange(32,64))) {
                stop("Fabric identity binding invalid status=$status bytes=${copy.size}"); return@post
            }
            try { listener.onIdentity(copy.copyOfRange(0,32),copy.copyOfRange(32,64)) }
            catch (_: RuntimeException) { stop("identity consumer failed"); return@post }
            markReady()
        }
    }
    private fun markReady() {
        if (selector != null && localLocator != null && selectorOffset < 32) { writeSelector(); return }
        ready = true
        try { listener.onReady(packetBytes) } catch (_: RuntimeException) { stop("ready consumer failed"); return }
        writeNext()
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeSelector() {
        val g = gatt ?: return
        val characteristic = selector ?: return
        val publicKey = localLocator ?: return
        selectorPart = minOf(packetBytes - 1, 32 - selectorOffset)
        val packet = byteArrayOf(selectorOffset.toByte()) + publicKey.copyOfRange(selectorOffset, selectorOffset + selectorPart)
        val started = try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; characteristic.value = packet; g.writeCharacteristic(characteristic) }
        } catch (_: SecurityException) { false }
        if (!started) stop("Fabric peer selector unavailable")
    }
    @SuppressLint("MissingPermission")
    private fun stop(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        worker.removeCallbacksAndMessages(null)
        inbound.clear(); outbound.clear(); inFlight = null
        val previous = gatt
        gatt = null
        try { previous?.disconnect() } catch (_: SecurityException) { /* permission revoked */ }
        try { previous?.close() } catch (_: SecurityException) { /* closed locally */ }
        gatt = null
        try { listener.onClosed(reason) } finally { thread.quitSafely() }
    }
    override fun close() { worker.post { stop("closed") } }
}
