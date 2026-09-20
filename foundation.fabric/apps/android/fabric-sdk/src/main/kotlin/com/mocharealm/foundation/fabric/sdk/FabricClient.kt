package com.mocharealm.foundation.fabric.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.mocharealm.foundation.fabric.ipc.IFabricAbility
import com.mocharealm.foundation.fabric.ipc.IFabricHub
import com.mocharealm.foundation.fabric.ipc.IFabricResultCallback

/**
 * Client for a Device Fabric hub running on this Android device.
 *
 * This wraps the hub's AIDL contract and service binding — the AIDL files, the
 * [ServiceConnection], the bind [Intent], and the async result-callback plumbing
 * — so an app no longer copies any of it. Call [connect] once, use [invoke] /
 * [openStream] / [devices] / [registerAbility] while connected, and [close] when
 * done.
 *
 * All hub calls run on the binder thread pool; the callbacks below are invoked
 * off the main thread, so marshal to the UI thread yourself as needed.
 */
class FabricClient(private val context: Context) {

    /** Receives the reply (or error) of an [invoke]. */
    fun interface ResultCallback {
        fun onResult(payload: ByteArray?, error: String?)
    }

    /** Notified when the connection to the hub is established or lost. */
    fun interface ConnectionCallback {
        fun onConnectionChanged(connected: Boolean)
    }

    /** Handles invocations and stream opens for an ability this app provides. */
    interface AbilityHandler {
        fun onInvoke(requestId: Long, payload: ByteArray)
        fun onStreamOpen(
            requestId: Long,
            ability: String,
            streamName: String,
            readFromRemote: ParcelFileDescriptor,
            writeToRemote: ParcelFileDescriptor,
        )
    }

    private var hub: IFabricHub? = null
    private var connectionCallback: ConnectionCallback? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            hub = IFabricHub.Stub.asInterface(service)
            connectionCallback?.onConnectionChanged(true)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            hub = null
            connectionCallback?.onConnectionChanged(false)
        }
    }

    /** True once bound to the hub service. */
    val isConnected: Boolean get() = hub != null

    /**
     * Binds the hub service, invoking [onConnection] when the connection is
     * established or dropped. Returns false if the bind could not be initiated
     * (e.g. the hub app is not installed).
     */
    fun connect(onConnection: ConnectionCallback): Boolean {
        connectionCallback = onConnection
        val intent = Intent(HUB_ACTION).setComponent(ComponentName(HUB_PACKAGE, HUB_SERVICE))
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return bound
    }

    /** Unbinds from the hub service. */
    fun close() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        hub = null
    }

    private fun requireHub(): IFabricHub =
        hub ?: throw IllegalStateException("not connected to the hub; call connect() first")

    /** The device IDs the hub knows about. */
    fun devices(): Array<String> = requireHub().devices()

    /** The hub's health/status string. */
    fun status(): String = requireHub().status()

    /**
     * Invokes [ability] on [deviceId] with [payload]; [callback] receives the
     * reply. Returns the request id. Use `"local"` for an ability on this hub.
     */
    fun invoke(
        deviceId: String,
        ability: String,
        payload: ByteArray,
        callback: ResultCallback,
    ): Long = requireHub().invoke(
        deviceId,
        ability,
        payload,
        object : IFabricResultCallback.Stub() {
            override fun onResult(requestId: Long, resultPayload: ByteArray?, error: String?) {
                callback.onResult(resultPayload, error)
            }
        },
    )

    /**
     * Opens a named stream to [ability] on [deviceId], returning the
     * `[readEnd, writeEnd]` file descriptors from the hub.
     */
    fun openStream(
        deviceId: String,
        ability: String,
        streamName: String,
    ): Array<ParcelFileDescriptor> = requireHub().openStream(deviceId, ability, streamName)

    /**
     * Registers [ability] for this app, dispatching incoming invocations and
     * streams to [handler]. Complete an invocation with [complete].
     */
    fun registerAbility(ability: String, handler: AbilityHandler) {
        requireHub().registerAbility(
            ability,
            context.packageName,
            object : IFabricAbility.Stub() {
                override fun onInvoke(requestId: Long, payload: ByteArray?) {
                    handler.onInvoke(requestId, payload ?: ByteArray(0))
                }

                override fun onStreamOpen(
                    requestId: Long,
                    streamAbility: String?,
                    streamName: String?,
                    readFromRemote: ParcelFileDescriptor?,
                    writeToRemote: ParcelFileDescriptor?,
                ) {
                    if (streamAbility != null &&
                        streamName != null &&
                        readFromRemote != null &&
                        writeToRemote != null
                    ) {
                        handler.onStreamOpen(
                            requestId,
                            streamAbility,
                            streamName,
                            readFromRemote,
                            writeToRemote,
                        )
                    }
                }
            },
        )
    }

    /** Unregisters a previously [registerAbility]-ed ability. */
    fun unregisterAbility(ability: String) {
        requireHub().unregisterAbility(ability, context.packageName)
    }

    /** Completes an invocation delivered to an [AbilityHandler]. */
    fun complete(requestId: Long, payload: ByteArray) {
        requireHub().complete(requestId, payload)
    }

    companion object {
        /** DeviceId that routes to an ability served on this hub. */
        const val LOCAL_DEVICE = "local"

        private const val HUB_PACKAGE = "com.mocharealm.foundation.fabric"
        private const val HUB_SERVICE = "com.mocharealm.foundation.fabric.FabricHubService"
        private const val HUB_ACTION = "com.mocharealm.foundation.fabric.BIND_HUB"
    }
}
