package com.mocharealm.foundation.fabric;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.mocharealm.foundation.fabric.ipc.IFabricAbility;
import com.mocharealm.foundation.fabric.ipc.IFabricHub;
import com.mocharealm.foundation.fabric.ipc.IFabricResultCallback;

import java.util.Arrays;
import java.util.Map;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class FabricHubService extends Service {
    public static final String ACTION_BIND_HUB = "com.mocharealm.foundation.fabric.BIND_HUB";
    public static final String CONNECT_PERMISSION =
            "com.mocharealm.foundation.fabric.permission.CONNECT_HUB";
    private static final int NOTIFICATION_ID = 1001;
    private static final String NOTIFICATION_CHANNEL = "fabric-hub-runtime";
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    private static final String TAG = "FabricHub";
    private static final int QUIC_PORT = 44_330;
    private static final Pattern ABILITY_PATTERN =
            Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)+");
    private static final Pattern STREAM_NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_.-]{1,255}");

    private final Map<String, ProviderRecord> providers = new ConcurrentHashMap<>();
    private final Map<Long, PendingRecord> pending = new ConcurrentHashMap<>();
    private final Map<Long, Integer> remotePending = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong(1L);
    private final AtomicBoolean polling = new AtomicBoolean(false);
    /** Remote invokes only; the poll loops get dedicated threads (they never return). */
    private final ExecutorService remoteExecutor = Executors.newFixedThreadPool(8);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager connectivityManager;
    private Network boundWifiNetwork;
    private Thread requestPollThread;
    private Thread streamPollThread;

    private final IFabricHub.Stub binder = new IFabricHub.Stub() {
        @Override
        public void registerAbility(String ability, String packageName, IFabricAbility callback)
                throws RemoteException {
            int uid = enforceClient(packageName);
            validateAbility(ability);
            if (callback == null) {
                throw new IllegalArgumentException("callback is required");
            }
            IBinder callbackBinder = callback.asBinder();
            IBinder.DeathRecipient death = () -> removeProvider(ability, callbackBinder);
            ProviderRecord record = new ProviderRecord(packageName, uid, callback, death);
            ProviderRecord existing = providers.putIfAbsent(ability, record);
            if (existing != null) {
                if (existing.callback.asBinder() == callbackBinder) {
                    return;
                }
                throw new IllegalStateException("ability already has a provider");
            }
            try {
                callbackBinder.linkToDeath(death, 0);
            } catch (RemoteException error) {
                providers.remove(ability, record);
                throw error;
            }
            if (!NativeHub.nativeRegisterAbility(ability, packageName, uid)) {
                providers.remove(ability, record);
                callbackBinder.unlinkToDeath(death, 0);
                throw new IllegalStateException("native Hub rejected ability registration");
            }
            ServiceState.providerCount = providers.size();
        }

        @Override
        public void unregisterAbility(String ability, String packageName) {
            int uid = enforceClient(packageName);
            ProviderRecord record = providers.get(ability);
            if (record != null && record.uid == uid && record.packageName.equals(packageName)) {
                removeProvider(ability, record.callback.asBinder());
            }
        }

        @Override
        public String[] devices() {
            enforcePermission();
            String remote = NativeHub.nativeDevices();
            String local = "local|trusted|true|" + sanitizeDeviceLabel(localDeviceLabel());
            if (remote == null || remote.isEmpty()) {
                return new String[] {local};
            }
            String[] rows = remote.split("\\n");
            String[] result = new String[rows.length + 1];
            result[0] = local;
            System.arraycopy(rows, 0, result, 1, rows.length);
            return result;
        }

        @Override
        public long invoke(
                String deviceId,
                String ability,
                byte[] payload,
                IFabricResultCallback callback)
                throws RemoteException {
            enforcePermission();
            validateAbility(ability);
            validatePayload(payload);
            if (callback == null) {
                throw new IllegalArgumentException("callback is required");
            }
            long requestId = nextRequestId.getAndUpdate(
                    value -> value == Long.MAX_VALUE ? 1L : value + 1L);
            if (!"local".equals(deviceId)) {
                byte[] requestPayload = Arrays.copyOf(payload, payload.length);
                remoteExecutor.execute(() -> {
                    byte[] result = null;
                    String failure = null;
                    try {
                        result = NativeHub.nativeInvokeRemote(deviceId, ability, requestPayload);
                    } catch (RuntimeException error) {
                        // Category-tagged failure from native (not trusted /
                        // no known address / dial failed / provider missing).
                        failure = error.getMessage();
                    }
                    if (result == null && failure == null) {
                        failure = "remote device or provider is unavailable";
                    }
                    if (failure != null) {
                        Log.w(TAG, "invoke(" + deviceId + ", " + ability + ") failed: " + failure);
                    }
                    try {
                        callback.onResult(requestId, result, result == null ? failure : null);
                    } catch (RemoteException ignored) {
                        // The requester disconnected while the network request was running.
                    }
                });
                return requestId;
            }
            ProviderRecord provider = providers.get(ability);
            if (provider == null) {
                callback.onResult(requestId, null, "ability provider is unavailable");
                return requestId;
            }
            PendingRecord request = new PendingRecord(provider.uid, callback);
            pending.put(requestId, request);
            handler.postDelayed(() -> timeout(requestId, request), REQUEST_TIMEOUT_MS);
            try {
                provider.callback.onInvoke(requestId, Arrays.copyOf(payload, payload.length));
            } catch (RemoteException error) {
                pending.remove(requestId, request);
                removeProvider(ability, provider.callback.asBinder());
                callback.onResult(requestId, null, "provider disconnected");
            }
            return requestId;
        }

        @Override
        public ParcelFileDescriptor[] openStream(String deviceId, String ability, String streamName)
                throws RemoteException {
            enforcePermission();
            validateAbility(ability);
            validateStreamName(streamName);
            if ("local".equals(deviceId)) {
                throw new IllegalArgumentException("local stream routing is not exposed through Android Hub yet");
            }
            ParcelFileDescriptor[] inbound;
            ParcelFileDescriptor[] outbound;
            try {
                inbound = ParcelFileDescriptor.createPipe();
                outbound = ParcelFileDescriptor.createPipe();
            } catch (IOException error) {
                throw new RemoteException(error.toString());
            }
            int nativeReadFd = outbound[0].detachFd();
            int nativeWriteFd = inbound[1].detachFd();
            boolean opened = false;
            String failure = "remote stream is unavailable";
            try {
                opened = NativeHub.nativeOpenRemoteStream(
                        deviceId,
                        ability,
                        streamName,
                        nativeReadFd,
                        nativeWriteFd);
            } catch (RuntimeException error) {
                failure = "remote stream is unavailable: " + error.getMessage();
            }
            if (!opened) {
                closeQuietly(inbound[0]);
                closeQuietly(outbound[1]);
                Log.w(TAG, "openStream(" + deviceId + ", " + ability + "#" + streamName
                        + ") failed: " + failure);
                throw new IllegalStateException(failure);
            }
            return new ParcelFileDescriptor[] {inbound[0], outbound[1]};
        }

        @Override
        public void complete(long requestId, byte[] payload) throws RemoteException {
            enforcePermission();
            validatePayload(payload);
            if (requestId < 0L) {
                Integer providerUid = remotePending.get(requestId);
                if (providerUid == null) {
                    return;
                }
                if (providerUid != Binder.getCallingUid()) {
                    throw new SecurityException("only the selected provider may complete this request");
                }
                if (remotePending.remove(requestId, providerUid)) {
                    NativeHub.nativeCompleteRemote(
                            requestId, Arrays.copyOf(payload, payload.length));
                }
                return;
            }
            PendingRecord request = pending.get(requestId);
            if (request == null) {
                return;
            }
            if (request.providerUid != Binder.getCallingUid()) {
                throw new SecurityException("only the selected provider may complete this request");
            }
            if (pending.remove(requestId, request)) {
                request.callback.onResult(
                        requestId, Arrays.copyOf(payload, payload.length), null);
            }
        }

        @Override
        public String status() {
            enforcePermission();
            return NativeHub.nativeStatus() + " | providers " + providers.size();
        }

        @Override
        public String openSession(String[] deviceIds, String ability, byte[] configJson) {
            enforcePermission();
            validateAbility(ability);
            if (deviceIds == null || deviceIds.length == 0) {
                throw new IllegalArgumentException("deviceIds required");
            }
            byte[] config = configJson == null ? new byte[0] : Arrays.copyOf(configJson, configJson.length);
            String sessionId = NativeHub.nativeOpenSession(deviceIds, ability, config);
            if (sessionId == null) {
                Log.w(TAG, "openSession(" + ability + ") failed: native call returned null");
                return "";
            }
            if (sessionId.startsWith("!")) {
                Log.w(TAG, "openSession(" + ability + ") failed: " + sessionId.substring(1));
                return "";
            }
            return sessionId;
        }

        @Override
        public void closeSession(String sessionId) {
            enforcePermission();
            if (sessionId == null || sessionId.isEmpty()) {
                return;
            }
            NativeHub.nativeCloseSession(sessionId);
        }

        @Override
        public boolean ensureSessionClock(String sessionId) {
            enforcePermission();
            if (sessionId == null || sessionId.isEmpty()) {
                return false;
            }
            return NativeHub.nativeEnsureSessionClock(sessionId);
        }

        @Override
        public long sessionLocalNowNs() {
            enforcePermission();
            return NativeHub.nativeSessionLocalNowNs();
        }

        @Override
        public long sessionGroupToLocalNs(String deviceId, long groupNs) {
            enforcePermission();
            return NativeHub.nativeSessionGroupToLocalNs(deviceId, groupNs);
        }

        @Override
        public long sessionLocalToGroupNs(String deviceId, long localNs) {
            enforcePermission();
            return NativeHub.nativeSessionLocalToGroupNs(deviceId, localNs);
        }

        @Override
        public long sessionClockUncertaintyNs(String deviceId) {
            enforcePermission();
            return NativeHub.nativeSessionClockUncertaintyNs(deviceId);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Hub runtime is active")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        WifiManager wifiManager = getSystemService(WifiManager.class);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("device-fabric-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        bindHubProcessToWifi();
        NativeHub.nativeStart(this, getFilesDir().getAbsolutePath(), localDeviceLabel(), QUIC_PORT);
        polling.set(true);
        // Dedicated threads: these loops never return, so running them on
        // remoteExecutor would permanently consume pool slots that remote
        // invokes need (each invoke can block for COMMAND_TIMEOUT).
        requestPollThread = new Thread(this::pollRemoteRequests, "fabric-poll-requests");
        streamPollThread = new Thread(this::pollRemoteStreams, "fabric-poll-streams");
        requestPollThread.start();
        streamPollThread.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION_BIND_HUB.equals(intent.getAction())) {
            return null;
        }
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        for (Map.Entry<String, ProviderRecord> entry : providers.entrySet()) {
            removeProvider(entry.getKey(), entry.getValue().callback.asBinder());
        }
        pending.clear();
        polling.set(false);
        if (requestPollThread != null) {
            requestPollThread.interrupt();
            requestPollThread = null;
        }
        if (streamPollThread != null) {
            streamPollThread.interrupt();
            streamPollThread = null;
        }
        remoteExecutor.shutdownNow();
        remotePending.clear();
        NativeHub.nativeStop();
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (connectivityManager != null && boundWifiNetwork != null) {
            connectivityManager.bindProcessToNetwork(null);
            boundWifiNetwork = null;
        }
        super.onDestroy();
    }

    public static int providerCount() {
        return ServiceState.providerCount;
    }

    private int enforceClient(String packageName) {
        enforcePermission();
        if (packageName == null || packageName.isEmpty()) {
            throw new SecurityException("package name is required");
        }
        int uid = Binder.getCallingUid();
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains(packageName)) {
            throw new SecurityException("package does not belong to calling UID");
        }
        return uid;
    }

    private void enforcePermission() {
        if (checkCallingPermission(CONNECT_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("missing " + CONNECT_PERMISSION);
        }
    }

    private static void validateAbility(String ability) {
        if (ability == null || ability.length() > 255 || !ABILITY_PATTERN.matcher(ability).matches()) {
            throw new IllegalArgumentException("invalid ability name");
        }
    }

    private static void validatePayload(byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload is missing or too large");
        }
    }

    private static void validateStreamName(String streamName) {
        if (streamName == null || !STREAM_NAME_PATTERN.matcher(streamName).matches()) {
            throw new IllegalArgumentException("invalid stream name");
        }
    }

    private String localDeviceLabel() {
        String deviceName = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            return deviceName.trim();
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            String bluetoothName = adapter == null ? null : adapter.getName();
            if (bluetoothName != null && !bluetoothName.trim().isEmpty()) {
                return bluetoothName.trim();
            }
        } catch (SecurityException ignored) {
            // BLUETOOTH_CONNECT may not be granted to the Hub process yet.
        }
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model.isEmpty() ? "Android device" : model;
        }
        String label = (manufacturer + " " + model).trim();
        return label.isEmpty() ? "Android device" : label;
    }

    private static String sanitizeDeviceLabel(String value) {
        return value.replace('|', ' ').replace('\n', ' ').trim();
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Exception ignored) {
            // The descriptor may already have been detached or closed.
        }
    }

    /**
     * Keep LAN discovery and QUIC on the Wi-Fi network even when Android's
     * process default is a full-tunnel VPN. Without this, mDNS still discovers
     * peers on wlan0 while unicast traffic to the same 192.168.x address is sent
     * into tun0, producing an Offline trusted row and a duplicate Nearby row.
     */
    private void bindHubProcessToWifi() {
        connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null
                    || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                continue;
            }
            if (connectivityManager.bindProcessToNetwork(network)) {
                boundWifiNetwork = network;
                Log.i(TAG, "Bound Hub transport to Wi-Fi network " + network);
            } else {
                Log.w(TAG, "Failed to bind Hub transport to Wi-Fi network " + network);
            }
            return;
        }
        Log.i(TAG, "No Wi-Fi network available; Hub transport uses the system default");
    }

    private void removeProvider(String ability, IBinder callbackBinder) {
        ProviderRecord record = providers.get(ability);
        if (record == null || record.callback.asBinder() != callbackBinder) {
            return;
        }
        if (providers.remove(ability, record)) {
            callbackBinder.unlinkToDeath(record.deathRecipient, 0);
            NativeHub.nativeUnregisterAbility(ability, record.packageName, record.uid);
            ServiceState.providerCount = providers.size();
        }
    }

    private void timeout(long requestId, PendingRecord expected) {
        if (pending.remove(requestId, expected)) {
            try {
                expected.callback.onResult(requestId, null, "provider timed out");
            } catch (RemoteException ignored) {
                // The requester has already disconnected.
            }
        }
    }

    // Idle backoff between non-blocking native polls. The native take methods now
    // return immediately (they must not block while holding the hub's global
    // lock), so pace the empty case here — off any lock — to keep these threads
    // near-idle instead of spinning a CPU core.
    private static final long POLL_IDLE_BACKOFF_MS = 20L;

    /** Sleep between empty polls; returns false if the thread was interrupted. */
    private static boolean idleBackoff() {
        try {
            Thread.sleep(POLL_IDLE_BACKOFF_MS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void pollRemoteRequests() {
        while (polling.get()) {
            byte[] encoded = NativeHub.nativeTakeRemoteRequest();
            if (encoded == null) {
                if (!idleBackoff()) return;
                continue;
            }
            try {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                long requestId = buffer.getLong();
                int abilityLength = Short.toUnsignedInt(buffer.getShort());
                if (abilityLength == 0 || abilityLength > buffer.remaining()) {
                    NativeHub.nativeCompleteRemote(requestId, null);
                    continue;
                }
                byte[] abilityBytes = new byte[abilityLength];
                buffer.get(abilityBytes);
                byte[] payload = new byte[buffer.remaining()];
                buffer.get(payload);
                String ability = new String(abilityBytes, StandardCharsets.UTF_8);
                ProviderRecord provider = providers.get(ability);
                if (provider == null) {
                    Log.w(TAG, "provider-missing: remote invoke for " + ability
                            + " has no registered provider");
                    NativeHub.nativeCompleteRemote(requestId, null);
                    continue;
                }
                remotePending.put(requestId, provider.uid);
                try {
                    provider.callback.onInvoke(requestId, payload);
                } catch (RemoteException error) {
                    remotePending.remove(requestId);
                    removeProvider(ability, provider.callback.asBinder());
                    NativeHub.nativeCompleteRemote(requestId, null);
                }
            } catch (RuntimeException error) {
                // Malformed native messages are dropped; the Rust timeout closes the request.
            }
        }
    }

    private void pollRemoteStreams() {
        while (polling.get()) {
            byte[] encoded = NativeHub.nativeTakeRemoteStream();
            if (encoded == null) {
                if (!idleBackoff()) return;
                continue;
            }
            ParcelFileDescriptor readFromRemote = null;
            ParcelFileDescriptor writeToRemote = null;
            try {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                long requestId = buffer.getLong();
                int readFd = buffer.getInt();
                int writeFd = buffer.getInt();
                int abilityLength = Short.toUnsignedInt(buffer.getShort());
                int streamLength = Short.toUnsignedInt(buffer.getShort());
                if (abilityLength == 0
                        || streamLength == 0
                        || abilityLength + streamLength > buffer.remaining()) {
                    continue;
                }
                byte[] abilityBytes = new byte[abilityLength];
                buffer.get(abilityBytes);
                byte[] streamBytes = new byte[streamLength];
                buffer.get(streamBytes);
                String ability = new String(abilityBytes, StandardCharsets.UTF_8);
                String streamName = new String(streamBytes, StandardCharsets.UTF_8);
                readFromRemote = ParcelFileDescriptor.adoptFd(readFd);
                writeToRemote = ParcelFileDescriptor.adoptFd(writeFd);
                ProviderRecord provider = providers.get(ability);
                if (provider == null) {
                    // Native refuses unregistered abilities before stream_ready,
                    // so this only fires on an unregister race.
                    Log.w(TAG, "stream-rejected: no provider for " + ability + "#" + streamName);
                    closeQuietly(readFromRemote);
                    closeQuietly(writeToRemote);
                    continue;
                }
                try {
                    // onStreamOpen is oneway: binder has already duplicated both
                    // descriptors into the provider process by the time transact
                    // returns, so this process must close its own copies. Keeping
                    // them open leaks two fds per stream and stops the app->remote
                    // pipe from ever reaching EOF.
                    provider.callback.onStreamOpen(
                            requestId, ability, streamName, readFromRemote, writeToRemote);
                } catch (RemoteException error) {
                    removeProvider(ability, provider.callback.asBinder());
                }
            } catch (RuntimeException error) {
                // Malformed native messages are dropped; fd cleanup happens below.
            } finally {
                if (readFromRemote != null) {
                    closeQuietly(readFromRemote);
                }
                if (writeToRemote != null) {
                    closeQuietly(writeToRemote);
                }
            }
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Hub runtime",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Device Fabric available to permitted applications");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static final class ProviderRecord {
        final String packageName;
        final int uid;
        final IFabricAbility callback;
        final IBinder.DeathRecipient deathRecipient;

        ProviderRecord(
                String packageName,
                int uid,
                IFabricAbility callback,
                IBinder.DeathRecipient deathRecipient) {
            this.packageName = packageName;
            this.uid = uid;
            this.callback = callback;
            this.deathRecipient = deathRecipient;
        }
    }

    private static final class PendingRecord {
        final int providerUid;
        final IFabricResultCallback callback;

        PendingRecord(int providerUid, IFabricResultCallback callback) {
            this.providerUid = providerUid;
            this.callback = callback;
        }
    }

    private static final class ServiceState {
        static volatile int providerCount;
    }
}
