package com.mocharealm.foundation.fabric.sdk;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mocharealm.foundation.fabric.ipc.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Cross-UID admission against the real installed Hub, without pretending to
 * provide a remote GATT peer. Grant CONNECT_HUB to this test APK before running. */
public class BleBinderAdmissionTest {
    private static final String HUB = "com.mocharealm.foundation.fabric";
    private final byte[] id = new byte[16];
    private final IFabricMessageCallback messages = new IFabricMessageCallback.Stub() {
        public void onState(long id, String state, int code, int maximum, byte[] peer) {}
        public void onMessage(long id, byte[] payload) {}
    };
    private final IFabricAbility ability = new IFabricAbility.Stub() {
        public void onInvoke(long request, byte[] payload) {}
        public void onStreamOpen(long request, String ability, String name,
            ParcelFileDescriptor read, ParcelFileDescriptor write) {}
    };
    private interface Action { void run() throws Exception; }
    private void rejected(Action action) throws Exception {
        try { action.run(); fail("unprivileged or malformed request accepted"); }
        catch (SecurityException | IllegalArgumentException | IllegalStateException expected) {}
    }
    // Run this method separately before granting the custom runtime permission.
    @Test public void missingConnectPermissionCannotBind() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(HUB + ".permission.CONNECT_HUB"));
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {}
            public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent(HUB + ".BIND_HUB")
            .setComponent(new ComponentName(HUB, HUB + ".FabricHubService"));
        try {
            boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (bound) context.unbindService(connection);
            assertFalse("missing CONNECT_HUB accepted", bound);
        } catch (SecurityException expected) { /* manifest/Binder service gate */ }
    }

    @Test public void callerAndLiveAbilityGatePortableSessions() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotEquals(android.os.Process.myUid(), context.getPackageManager().getApplicationInfo(HUB, 0).uid);
        ArrayBlockingQueue<IFabricHub> connected = new ArrayBlockingQueue<>(1);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                connected.offer(IFabricHub.Stub.asInterface(binder));
            }
            public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent(HUB + ".BIND_HUB")
            .setComponent(new ComponentName(HUB, HUB + ".FabricHubService"));
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        IFabricHub hub = connected.poll(60, TimeUnit.SECONDS);
        assertNotNull("installed Hub did not bind", hub);
        byte[] localDeviceId = hub.localDeviceId();
        assertNotNull(localDeviceId);
        assertEquals("Fabric DeviceId must be the raw 32-byte identity", 32, localDeviceId.length);
        assertArrayEquals(localDeviceId, hub.localDeviceId());
        String name = "test.ble.client" + android.os.Process.myPid();
        String address = "02:00:00:00:00:01";
        try {
            rejected(() -> hub.registerAbility(name, HUB, ability));
            rejected(() -> hub.openBleMessages(address, null, name, id, id, id, 12288, messages));
            try { hub.registerAbility(name, context.getPackageName(), ability); }
            catch (IllegalStateException e) { throw new AssertionError("registration failed; Hub=" + hub.status(), e); }
            rejected(() -> hub.openBleMessages(address, null, name, id, id, id, 12289, messages));
            rejected(() -> hub.openBleMessages(address, null, name, new byte[15], id, id, 12288, messages));
            rejected(() -> hub.openBleMessages(address, new byte[31], name, id, id, id, 12288, messages));
            rejected(() -> hub.sendBleMessage(Long.MAX_VALUE, new byte[]{1}));
            rejected(() -> hub.confirmBlePairing(Long.MAX_VALUE, 123456, true));
            long port = hub.openBleMessages(address, null, name, id, id, id, 12288, messages);
            assertTrue("authorized request did not reach Hub GATT actor", port > 0);
            hub.unregisterAbility(name, context.getPackageName());
            rejected(() -> hub.openBleMessages(address, null, name, id, id, id, 12288, messages));
            rejected(() -> hub.sendBleMessage(port, new byte[]{1}));
        } finally {
            hub.unregisterAbility(name, context.getPackageName());
            context.unbindService(connection);
        }
    }
}
