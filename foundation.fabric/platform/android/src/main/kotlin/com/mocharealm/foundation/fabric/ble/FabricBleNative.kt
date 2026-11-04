package com.mocharealm.foundation.fabric.ble

/** Host policy binds context to the OS app principal, registered ability/ports,
 * trusted Fabric peer and current grant. Must be synchronous and must not call
 * back into native methods. Return false on expired/revoked/stale authorization. */
fun interface FabricBlePolicy {
    fun authorize(context: ByteArray, action: Int): Boolean
}

/** Durable Fabric trust backend. Native calls only after BOTH confirmations.
 * Return true only after atomic private persistence; never infer trust from bond. */
fun interface FabricBlePairingStore {
    fun persistPeer(publicKey: ByteArray, deviceId: ByteArray): Boolean
}

/** Native device-link/embedded Session implementation shared with Vela.
 * Caller serializes on one worker; never call this on the UI/GATT callback thread.
 * A BLE bond is not a trusted Fabric identity. No seed/token conversion exists. */
object FabricBleNative {
    init { System.loadLibrary("fabric_ble_android") }
    /** Embedded host must use the SAME state directory as NativeHub (its
     * context.filesDir). Other UID apps must use authenticated Hub IPC, never
     * copy its seed or create a parallel trust authority. */
    @JvmStatic external fun identityInfo(directory: String): ByteArray?
    @JvmStatic external fun isTrusted(directory: String, publicKey: ByteArray): Boolean
    @JvmStatic external fun trustedPeer(directory: String, deviceId: ByteArray): ByteArray?
    @JvmStatic external fun requestPairingHost(directory: String, freshEntropy: ByteArray,
        expectedPeerPublic: ByteArray?, packetBytes: Int, nowMs: Long): Long
    @JvmStatic external fun pairingHostCode(handle: Long): Int
    @JvmStatic external fun pairingHostConfirm(handle: Long, code: Int, accepted: Boolean, nowMs: Long): Int
    @JvmStatic external fun pairingHostPeer(handle: Long): ByteArray?
    @JvmStatic external fun pairingHostFlushed(handle: Long): Boolean
    @JvmStatic external fun createFromIdentity(directory: String, peerPublic: ByteArray,
        freshEntropy: ByteArray, policy: FabricBlePolicy, packetBytes: Int): Long
    @JvmStatic external fun requestPairing(seed: ByteArray, freshEntropy: ByteArray,
        expectedPeerPublic: ByteArray?, store: FabricBlePairingStore, nowMs: Long): Long
    @JvmStatic external fun pairingHello(handle: Long): ByteArray?
    @JvmStatic external fun pairingReceiveHello(handle: Long, hello: ByteArray, nowMs: Long): Int
    @JvmStatic external fun pairingCode(handle: Long): Int
    @JvmStatic external fun pairingPeer(handle: Long): ByteArray?
    @JvmStatic external fun pairingConfirm(handle: Long, code: Int, accepted: Boolean, nowMs: Long): Int
    @JvmStatic external fun completePairing(handle: Long, nowMs: Long): ByteArray?
    @JvmStatic external fun pairingReceive(handle: Long, record: ByteArray, nowMs: Long): Int
    @JvmStatic external fun pairingPhase(handle: Long): Int
    @JvmStatic external fun finalizeContext(context: ByteArray): ByteArray?
    /** Returns publicKey32 || DeviceId32; never the private seed. */
    @JvmStatic external fun identityPublic(seed: ByteArray): ByteArray?
    @JvmStatic external fun create(seed: ByteArray, trustedPeerPublic: ByteArray,
        freshEntropy: ByteArray, policy: FabricBlePolicy, packetBytes: Int): Long
    @JvmStatic external fun propose(handle: Long, context: ByteArray): Int
    @JvmStatic external fun feed(handle: Long, fragment: ByteArray, nowMs: Long): Int
    @JvmStatic external fun poll(handle: Long, nowMs: Long): Int
    @JvmStatic external fun send(handle: Long, payload: ByteArray, nowMs: Long): Int
    @JvmStatic external fun takePacket(handle: Long): ByteArray?
    @JvmStatic external fun takeMessage(handle: Long): ByteArray?
    @JvmStatic external fun maxPayload(handle: Long): Int
    @JvmStatic external fun phase(handle: Long): Int
    @JvmStatic external fun close(handle: Long)
}
