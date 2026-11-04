import com.mocharealm.foundation.fabric.ble.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;

/** Actual JNI against production Kotlin classes; no Android radio is mocked. */
public final class PortableJniSmoke {
  static long a,b,now;
  static byte[] entropy(){byte[] e=new byte[32];new SecureRandom().nextBytes(e);return e;}
  static void check(boolean v){if(!v)throw new AssertionError("JNI fixture invariant failed");}
  static void pump(){now+=10;for(long h:new long[]{a,b}){int r=FabricBleNative.poll(h,now);check(r==0||r==-9);}
    byte[] p=FabricBleNative.takePacket(a);if(p!=null){int r=FabricBleNative.feed(b,p,now);check(r==0||r==-9);}
    p=FabricBleNative.takePacket(b);if(p!=null){int r=FabricBleNative.feed(a,p,now);check(r==0||r==-9);}}
  public static void main(String[] args)throws Exception{
    String root=Files.createTempDirectory("fabric-jni-").toString(),ad=root+"/a",bd=root+"/b";
    byte[] ai=FabricBleNative.identityInfo(ad),bi=FabricBleNative.identityInfo(bd);check(ai!=null&&bi!=null);
    byte[] ap=Arrays.copyOfRange(ai,0,32),bp=Arrays.copyOfRange(bi,0,32);
    check(!FabricBleNative.isTrusted(ad,bp));
    a=FabricBleNative.requestPairingHost(ad,entropy(),bp,20,0);b=FabricBleNative.requestPairingHost(bd,entropy(),null,20,0);check(a>0&&b>0);
    for(int i=0;i<1000&&(FabricBleNative.pairingHostCode(a)<0||FabricBleNative.pairingHostCode(b)<0);i++)pump();
    int code=FabricBleNative.pairingHostCode(a);check(code>=0&&code==FabricBleNative.pairingHostCode(b));
    check(FabricBleNative.pairingHostConfirm(a,code,true,now)==0);for(int i=0;i<50;i++)pump();check(!FabricBleNative.isTrusted(ad,bp));
    check(FabricBleNative.pairingHostConfirm(b,code,true,now)==0);
    for(int i=0;i<1000&&(FabricBleNative.phase(a)!=4||FabricBleNative.phase(b)!=4||!FabricBleNative.pairingHostFlushed(a)||!FabricBleNative.pairingHostFlushed(b));i++)pump();
    check(FabricBleNative.phase(a)==4&&FabricBleNative.phase(b)==4);check(FabricBleNative.isTrusted(ad,bp)&&FabricBleNative.isTrusted(bd,ap));
    FabricBleNative.close(a);FabricBleNative.close(b);check(Arrays.equals(ai,FabricBleNative.identityInfo(ad)));
    check(Arrays.equals(bp,FabricBleNative.trustedPeer(ad,Arrays.copyOfRange(bi,32,64))));
    byte[] raw=new byte[140];Arrays.fill(raw,0,16,(byte)1);ByteBuffer.wrap(raw,16,8).putLong(1);
    Arrays.fill(raw,24,40,(byte)2);Arrays.fill(raw,40,56,(byte)3);Arrays.fill(raw,56,72,(byte)4);Arrays.fill(raw,72,88,(byte)5);Arrays.fill(raw,88,104,(byte)6);ByteBuffer.wrap(raw,136,4).putInt(12288);
    byte[] context=FabricBleNative.finalizeContext(raw);check(context!=null);boolean[] allowed={true};FabricBlePolicy policy=(c,action)->allowed[0]&&action>=0&&action<=2&&Arrays.equals(c,context);
    a=FabricBleNative.createFromIdentity(ad,bp,entropy(),policy,20);b=FabricBleNative.createFromIdentity(bd,ap,entropy(),policy,20);check(a>0&&b>0);check(FabricBleNative.propose(a,context)==0);
    for(int i=0;i<1000&&(FabricBleNative.phase(a)!=6||FabricBleNative.phase(b)!=6);i++)pump();check(FabricBleNative.phase(a)==6&&FabricBleNative.phase(b)==6);check(FabricBleNative.maxPayload(a)==12288);
    byte[] payload=new byte[12288];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*17);check(FabricBleNative.send(a,payload,now)==0);
    byte[] message=null;for(int i=0;i<3000&&message==null;i++){pump();message=FabricBleNative.takeMessage(b);}check(Arrays.equals(message,payload));
    check(FabricBleNative.send(a,new byte[12289],now)==-4);for(int i=0;i<20;i++)pump();allowed[0]=false;check(FabricBleNative.send(a,new byte[]{1},now)==-2);
    FabricBleNative.close(a);FabricBleNative.close(b);check(FabricBleNative.phase(a)==-1);
    System.out.println("Production Kotlin/JNI first pairing + durable trust/reopen + encrypted Session + 12KiB + revocation passed (host JVM, no radio)");
  }
}
