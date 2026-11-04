package com.mocharealm.foundation.fabric.buffer.data.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.sdk.FabricClient

/** One authenticated Hub connection and protocol worker per device. Selecting
 * a setup screen does not replace another device's synchronization channel. */
internal class VelaFabricConnections(
    private val context: Context,
    private val repository: BufferRepository,
    private val client: () -> FabricClient?,
) : AutoCloseable {
    private companion object { const val MAX_CONNECTIONS=8 }
    private class Slot(var address: String,var expected: String?,val endpoint: VelaFabricEnrollment)
    private val lock=Any()
    private val main=Handler(Looper.getMainLooper())
    private val slots=mutableListOf<Slot>()
    private var selected: Slot?=null
    private var selectionChosen=false
    private var closed=false
    private var selection=0L
    @Volatile private var observer: ((EnrollmentView)->Unit)?=null
    var listener: ((EnrollmentView)->Unit)?
        get()=observer
        set(value){observer=value;synchronized(lock){selected?.let(::watch)}}
    val boundDevice: String? get()=synchronized(lock){selected?.endpoint?.boundDevice?:selected?.expected}
        ?:runCatching { repository.velaFabricRoute()?.second }.getOrNull()
    val boundDevices: List<String> get()=(runCatching { repository.velaFabricRoutes().map { it.second } }.getOrDefault(emptyList())+
        synchronized(lock){slots.mapNotNull { it.endpoint.boundDevice }}).distinct()
    val connectedDevices: Set<String> get()=synchronized(lock){slots.mapNotNull { it.endpoint.authenticatedDevice }.toSet()}

    private fun dispatch(view: EnrollmentView,slot: Slot?,version: Long) {
        main.post {
            val callback=synchronized(lock){if(closed || version!=selection || selected!==slot)null else observer}
            callback?.invoke(view)
        }
    }
    private fun watch(slot: Slot) {
        slot.endpoint.listener={view ->
            val version=synchronized(lock){
                if(closed || slot !in slots)return@synchronized null
                slot.endpoint.authenticatedDevice?.let { identity ->
                    slot.expected=identity
                    // A new authenticated connection supersedes only another
                    // connection for this same device, never a different Vela.
                    val old=slots.filter { it!==slot && (it.expected==identity || it.endpoint.boundDevice==identity) }
                    old.forEach { slots.remove(it);it.endpoint.listener=null;it.endpoint.close() }
                }
                if(selected===slot)selection else null
            }
            if(version!=null)dispatch(view,slot,version)
        }
    }
    private fun obtain(address: String,expected: String?): Slot {
        val found=slots.firstOrNull { expected!=null && (it.expected==expected || it.endpoint.boundDevice==expected) }
            ?:slots.firstOrNull { it.address.equals(address,true) && (expected==null || it.expected==null || it.expected==expected) }
        if(found!=null){found.address=address;if(expected!=null)found.expected=expected;return found}
        check(slots.size<MAX_CONNECTIONS) { "device connection capacity exceeded" }
        return Slot(address,expected,VelaFabricEnrollment(context,repository,client)).also { slots+=it;watch(it) }
    }
    fun resume() {
        try {
            val routes=repository.velaFabricRoutes();val preferred=repository.velaFabricRoute()?.second
            synchronized(lock){
                if(closed)return
                routes.sortedBy { if(it.second==preferred)0 else 1 }.forEach { route ->
                    val existing=slots.firstOrNull { it.expected==route.second || it.endpoint.boundDevice==route.second }
                    if(existing!=null || slots.size<MAX_CONNECTIONS)obtain(route.first,route.second).endpoint.resume(route)
                }
                if(selected==null&&!selectionChosen){selected=slots.firstOrNull { it.expected==preferred }?:slots.firstOrNull();selection++;selected?.let(::watch)}
            }
        } catch (_: Exception) { synchronized(lock){dispatch(EnrollmentView("设备连接记录暂时无法读取，请稍后重试",active=false,error=true),selected,selection)} }
    }
    fun connect(peer: VelaBlePeer,locator: VelaFabricQrLocator?=null) {
        try {
            val expected=locator?.deviceId
            synchronized(lock){
                if(closed)return
                val slot=obtain(peer.address,expected)
                slot.endpoint.connect(peer,locator)
                selected=slot;selectionChosen=true;selection++;watch(slot)
                dispatch(EnrollmentView("正在核对所选设备的安全连接"),slot,selection)
            }
        } catch (_: Exception) {
            synchronized(lock){selected=null;selection++;dispatch(EnrollmentView("暂时无法打开所选连接；最多同时连接8台设备，可先断开一台或稍后重试",active=false,error=true),null,selection)}
        }
    }
    fun confirmPairing(){synchronized(lock){selected?.endpoint?.confirmPairing()}}
    fun clearSelection(){synchronized(lock){selected=null;selectionChosen=true;selection++}}
    fun connectSaved(device: String) {
        try {
            val route=repository.velaFabricRoutes().single { it.second==device }
            synchronized(lock){
                if(closed)return
                val slot=obtain(route.first,device);selected=slot;selectionChosen=true;selection++;watch(slot);slot.endpoint.resume(route)
                dispatch(EnrollmentView("正在恢复所选设备的安全连接"),slot,selection)
            }
        } catch (_: Exception) { synchronized(lock){dispatch(EnrollmentView("暂时无法连接；请核对设备记录，或在连接满8台时先断开一台",active=false,error=true),selected,selection)} }
    }
    fun disconnect(device: String){synchronized(lock){
        val removed=slots.filter { it.expected==device || it.endpoint.boundDevice==device }
        removed.forEach { slots.remove(it);it.endpoint.listener=null;it.endpoint.close() }
        if(removed.any { it===selected }){selected=null;selectionChosen=true;selection++;dispatch(EnrollmentView("已断开所选设备，已保存的记录与绑定保留",active=false),null,selection)}
    }}
    fun submit(ssid: String,password: String,useExistingWifi: Boolean=false){synchronized(lock){selected?.endpoint?.submit(ssid,password,useExistingWifi)}}
    fun cancel(){synchronized(lock){
        val slot=selected
        if(slot!=null){slots.remove(slot);slot.endpoint.listener=null;slot.endpoint.cancel();slot.endpoint.close()}
        selected=null;selectionChosen=true;selection++
        dispatch(EnrollmentView("已停止所选设备连接；其他设备连接与已保存记录保持有效",active=false),null,selection)
    }}
    override fun close(){synchronized(lock){
        if(closed)return
        closed=true;selection++;slots.forEach { it.endpoint.listener=null;it.endpoint.close() };slots.clear();selected=null;observer=null
    }}
}
