package com.mocharealm.foundation.fabric

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import com.mocharealm.foundation.fabric.ble.*
import com.mocharealm.foundation.fabric.ipc.IFabricMessageCallback
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Hub-owned actor. No key, trust directory or native handle crosses Binder. */
class FabricBleBinderRuntime(private val context: Context, private val directory: String,
    private val ownership: Ownership, private val trustObserver: TrustObserver) : Closeable {
    fun interface Ownership { fun owns(uid: Int, ability: String): Boolean }
    fun interface TrustObserver { fun changed() }
    private val thread=HandlerThread("FabricBleSessions").apply {start()}
    private val worker=Handler(thread.looper)
    private val nextId=AtomicLong(1)
    private val records=ConcurrentHashMap<Long,Record>()
    private class Record(val id:Long,val uid:Int,val address:String,val pin:ByteArray?,val ability:String,
        val channel:ByteArray,val localPort:ByteArray,val peerPort:ByteArray,val maximum:Int,val callback:IFabricMessageCallback) {
        val inbound=ArrayBlockingQueue<ByteArray>(64)
        val outbound=ArrayBlockingQueue<ByteArray>(1)
        val expires=SystemClock.elapsedRealtime()+30*60*1000
        var client:FabricGattClient?=null
        var handle=0L;@Volatile var generation=0;var pairing=false;var shortCode=-1;@Volatile var active=false
        var publicKey:ByteArray?=null;var peerId:ByteArray?=null;var context:ByteArray?=null
        var packet:ByteArray?=null;var app:ByteArray?=null
        var death:IBinder.DeathRecipient?=null
    }
    fun open(uid:Int,address:String,pin:ByteArray?,ability:String,channel:ByteArray,localPort:ByteArray,peerPort:ByteArray,maximum:Int,callback:IFabricMessageCallback):Long {
        require(BluetoothAdapter.checkBluetoothAddress(address)) {"invalid Bluetooth address"}
        require(pin==null||pin.size==32) {"public key must be 32 bytes"}
        require(channel.size==16&&localPort.size==16&&peerPort.size==16) {"profile UUID must be 16 bytes"}
        require(maximum in 1..12288) {"invalid message budget"}
        check(ownership.owns(uid,ability)) {"caller does not own registered ability"}
        synchronized(records) {
            // Multi-device applications may retain several authorized links.
            // The global allocation remains bounded, and one UID can consume
            // at most half of it. Ownership/grant checks still apply per link.
            check(records.size<16&&records.values.count{it.uid==uid}<8) {"BLE Session quota exceeded"}
            val id=nextId.getAndIncrement();check(id>0) {"connection ID exhausted"}
            val r=Record(id,uid,address,pin?.copyOf(),ability,channel.copyOf(),localPort.copyOf(),peerPort.copyOf(),maximum,callback)
            val death=IBinder.DeathRecipient { worker.post {finish(r,"Disconnected")} }
            callback.asBinder().linkToDeath(death,0);r.death=death;records[id]=r
            worker.post {connect(r)}
            return id
        }
    }
    private fun owner(id:Long,uid:Int):Record {
        val r=records[id]?:throw IllegalArgumentException("unknown connection")
        if(r.uid!=uid||!authorized(r))throw SecurityException("connection owner/grant expired")
        return r
    }
    private fun authorized(r:Record):Boolean = records[r.id]===r&&SystemClock.elapsedRealtime()<r.expires&&
        ownership.owns(r.uid,r.ability)&&context.checkPermission(FabricHubService.CONNECT_PERMISSION,-1,r.uid)==PackageManager.PERMISSION_GRANTED
    fun confirm(id:Long,uid:Int,code:Int,accepted:Boolean){val r=owner(id,uid);require(code in 0..999999);worker.post {
        if(r.pairing&&r.handle!=0L&&FabricBleNative.pairingHostConfirm(r.handle,code,accepted,SystemClock.elapsedRealtime())<0)finish(r,"PairingRejected")
    }}
    fun send(id:Long,uid:Int,payload:ByteArray):Boolean {
        val r=owner(id,uid);if(!r.active||payload.isEmpty()||payload.size>r.maximum)return false
        return r.outbound.offer(payload.copyOf())
    }
    fun close(id:Long,uid:Int){val r=owner(id,uid);worker.post {finish(r,"Closed")}}
    fun revoke(ability:String,uid:Int){worker.post {records.values.filter{it.ability==ability&&it.uid==uid}.forEach{finish(it,"Revoked")}}}
    private fun entropy()=ByteArray(32).also{SecureRandom().nextBytes(it)}
    /** DeviceId32 from the Hub-owned identityInfo (publicKey32 || DeviceId32). */
    fun localDeviceId():ByteArray {
        val info=FabricBleNative.identityInfo(directory)?:error("identity unavailable")
        check(info.size==64) {"invalid Fabric identityInfo length"}
        return info.copyOfRange(32,64)
    }
    @Suppress("DEPRECATION")
    private fun connect(r:Record){
        if(!authorized(r)){finish(r,"PermissionDenied");return}
        val generation=++r.generation
        val identity=FabricBleNative.identityInfo(directory)
        if(identity==null||identity.size!=64){finish(r,"PrivateStorageOrIdentityUnavailable");return}
        val client=FabricGattClient(context,object:FabricGattClient.Listener{
            override fun onIdentity(publicKey:ByteArray,deviceId:ByteArray){worker.post {
                if(r.generation==generation){r.publicKey=publicKey.copyOf();r.peerId=deviceId.copyOf()}
            }}
            override fun onReady(packetBytes:Int){worker.post {
                if(r.generation!=generation||!authorized(r))return@post
                try {
                    val peer=r.publicKey?:r.pin?:error("missing identity locator")
                    r.publicKey=peer
                    r.peerId=MessageDigest.getInstance("SHA-256").digest(peer)
                    r.pairing=!FabricBleNative.isTrusted(directory,peer)
                    val random=entropy()
                    r.handle=if(r.pairing)FabricBleNative.requestPairingHost(directory,random,peer,packetBytes,SystemClock.elapsedRealtime())
                        else FabricBleNative.createFromIdentity(directory,peer,random,FabricBlePolicy{c,a->authorized(r)&&a in 0..2&&r.context?.contentEquals(c)==true},packetBytes)
                    random.fill(0)
                    if(r.handle==0L){finish(r,"PrivateStorageOrIdentityUnavailable");return@post}
                    if(!r.pairing){
                        r.context=buildContext(r)
                        if(FabricBleNative.propose(r.handle,r.context!!)<0){finish(r,"SessionRejected");return@post}
                    }
                    state(r,if(r.pairing)"Pairing" else "Negotiating")
                    pump(r,generation)
                }catch(error:RuntimeException){
                    Log.w("FabricBle", "BLE setup unavailable: ${error.javaClass.simpleName}")
                    finish(r,"Unsupported")
                }
            }}
            override fun onPacket(packet:ByteArray){
                if(r.generation==generation&&!r.inbound.offer(packet.copyOf()))worker.post{finish(r,"ResourceExhausted")}
            }
            override fun onClosed(reason:String){worker.post{if(r.generation==generation){
                // Fixed transport diagnostics only: no packet, key or SAS logs.
                Log.w("FabricBle", "GATT closed: $reason")
                finish(r,"Disconnected")
            }}}
        },r.pin?:r.publicKey,identity.copyOfRange(0,32))
        r.client=client
        try {
            if(Build.VERSION.SDK_INT>=31&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){finish(r,"BluetoothPermissionDenied");return}
            val adapter=BluetoothAdapter.getDefaultAdapter()?:run{finish(r,"Unsupported");return}
            @Suppress("MissingPermission")
            val device=adapter.getRemoteDevice(r.address)
            client.connect(device)
        }catch(_:SecurityException){finish(r,"BluetoothPermissionDenied")}
    }
    private fun buildContext(r:Record):ByteArray {
        val info=FabricBleNative.identityInfo(directory)?:error("identity unavailable")
        val peer=r.peerId?:error("peer missing")
        val bytes=ByteArray(140);val b=ByteBuffer.wrap(bytes)
        val id=UUID.randomUUID();b.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).putLong(1).put(r.channel)
        fun participant(device:ByteArray):ByteArray{val h=MessageDigest.getInstance("SHA-256");h.update(device);h.update(r.ability.toByteArray(Charsets.UTF_8));return h.digest().copyOf(16)}
        b.put(participant(info.copyOfRange(32,64))).put(participant(peer)).put(r.localPort).put(r.peerPort)
        b.position(136);b.putInt(r.maximum)
        return FabricBleNative.finalizeContext(bytes)?:error("invalid channel profile")
    }
    private fun pump(r:Record,generation:Int){
        if(r.generation!=generation||r.handle==0L)return
        if(!authorized(r)){finish(r,"Revoked");return}
        val now=SystemClock.elapsedRealtime()
        while(true){val p=r.inbound.poll()?:break;val result=FabricBleNative.feed(r.handle,p,now);if(result<0&&result != -9){
            Log.w("FabricBle", "Native feed failed: result=$result phase=${FabricBleNative.phase(r.handle)} pairing=${r.pairing}")
            finish(r,"ProtocolOrAuthorizationFailure");return}}
        if(r.pairing){
            val code=FabricBleNative.pairingHostCode(r.handle)
            if(code>=0&&r.shortCode!=code){r.shortCode=code;state(r,"ConfirmPairing",code)}
            if(FabricBleNative.phase(r.handle)==4&&FabricBleNative.pairingHostFlushed(r.handle)&&r.packet==null&&r.client?.isIdle()==true){
                state(r,"Paired");trustObserver.changed()
                r.generation++;r.client?.close();FabricBleNative.close(r.handle);r.handle=0;r.shortCode=-1;r.inbound.clear();r.packet=null
                worker.postDelayed({if(records[r.id]===r)connect(r)},250);return
            }
        }else{
            if(FabricBleNative.phase(r.handle)==6&&!r.active){r.active=true;state(r,"Active")}
            if(r.active){
                // Binder enqueue completes before the transport receipt is sent.
                val message=FabricBleNative.takeMessage(r.handle)
                if(message!=null){
                    Log.d("FabricBle", "Authorized message received: bytes=${message.size}")
                    try{r.callback.onMessage(r.id,message)}catch(_:RemoteException){finish(r,"Disconnected");return}}
                if(r.app==null)r.app=r.outbound.poll()
                r.app?.let{val result=FabricBleNative.send(r.handle,it,now);if(result==0){
                    Log.d("FabricBle", "Authorized message queued: bytes=${it.size}")
                    r.app=null
                } else if(result != -9){finish(r,"SendRejected");return}}
            }
        }
        val result=FabricBleNative.poll(r.handle,now)
        if(result<0&&result != -9){
            Log.w("FabricBle", "Native poll failed: result=$result phase=${FabricBleNative.phase(r.handle)} pairing=${r.pairing}")
            finish(r,"TimeoutOrDisconnected");return
        }
        if(r.packet==null)r.packet=FabricBleNative.takePacket(r.handle)
        r.packet?.let{if(r.client?.sendPacket(it)==true)r.packet=null}
        worker.postDelayed({pump(r,generation)},20)
    }
    private fun state(r:Record,state:String,code:Int = -1){
        try{r.callback.onState(r.id,state,code,if(r.active)FabricBleNative.maxPayload(r.handle) else 0,r.peerId)}
        catch(_:RemoteException){finish(r,"Disconnected")}
    }
    private fun finish(r:Record,reason:String){
        if(!records.remove(r.id,r))return
        r.active=false;r.generation++;r.client?.close()
        if(r.handle!=0L)FabricBleNative.close(r.handle);r.handle=0
        r.inbound.clear();r.outbound.clear();r.app?.fill(0);r.app=null;r.packet=null
        r.death?.let{r.callback.asBinder().unlinkToDeath(it,0)}
        try{r.callback.onState(r.id,reason,-1,0,r.peerId)}catch(_:RemoteException){}
    }
    override fun close(){worker.post{records.values.toList().forEach{finish(it,"Closed")};thread.quitSafely()}}
}
