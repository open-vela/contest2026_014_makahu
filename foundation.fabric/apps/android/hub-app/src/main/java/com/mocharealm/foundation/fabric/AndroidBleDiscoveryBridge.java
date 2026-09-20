package com.mocharealm.foundation.fabric;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AndroidBleDiscoveryBridge {
    private static final int COMPANY_ID = 0xfffe;

    private static BluetoothLeAdvertiser advertiser;
    private static AdvertiseCallback callback;

    private AndroidBleDiscoveryBridge() {}

    public static void startAdvertising(Context context, byte[] manufacturerPayload) {
        stop();
        if (!hasBluetoothPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)) {
            return;
        }
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null || !adapter.isEnabled() || !adapter.isMultipleAdvertisementSupported()) {
            return;
        }
        BluetoothLeAdvertiser platformAdvertiser = adapter.getBluetoothLeAdvertiser();
        if (platformAdvertiser == null) {
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(false)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addManufacturerData(COMPANY_ID, manufacturerPayload)
                .build();
        callback = new AdvertiseCallback() {
            @Override
            public void onStartFailure(int errorCode) {
                advertiser = null;
                callback = null;
            }
        };
        try {
            platformAdvertiser.startAdvertising(settings, data, callback);
            advertiser = platformAdvertiser;
        } catch (RuntimeException ignored) {
            callback = null;
        }
    }

    public static byte[] scan(Context context, int millis) {
        if (!hasBluetoothPermission(context, Manifest.permission.BLUETOOTH_SCAN)) {
            return new byte[0];
        }
        BluetoothAdapter adapter = adapter(context);
        BluetoothLeScanner scanner = adapter == null || !adapter.isEnabled()
                ? null
                : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return new byte[0];
        }
        List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                byte[] payload = result.getScanRecord() == null
                        ? null
                        : result.getScanRecord().getManufacturerSpecificData(COMPANY_ID);
                if (payload != null) {
                    payloads.add(payload);
                }
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                latch.countDown();
            }
        };
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid((ParcelUuid) null)
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            latch.await(Math.max(100, millis), TimeUnit.MILLISECONDS);
            scanner.stopScan(scanCallback);
        } catch (RuntimeException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return framePayloads(payloads);
    }

    public static void stop() {
        if (advertiser == null || callback == null) {
            advertiser = null;
            callback = null;
            return;
        }
        try {
            advertiser.stopAdvertising(callback);
        } catch (RuntimeException ignored) {
            // Adapter may already be disabled while the Hub is stopping.
        }
        advertiser = null;
        callback = null;
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private static boolean hasBluetoothPermission(Context context, String permission) {
        return Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static byte[] framePayloads(List<byte[]> payloads) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        synchronized (payloads) {
            for (byte[] payload : payloads) {
                if (payload.length > 0xffff) {
                    continue;
                }
                output.write((payload.length >>> 8) & 0xff);
                output.write(payload.length & 0xff);
                output.write(payload, 0, payload.length);
            }
        }
        return output.toByteArray();
    }
}
