package com.mocharealm.foundation.fabric;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AndroidWifiAwareDiscoveryBridge {
    private static final String SERVICE_NAME = "device-fabric";

    private static WifiAwareSession publishSession;
    private static PublishDiscoverySession publishDiscoverySession;

    private AndroidWifiAwareDiscoveryBridge() {}

    public static synchronized void publish(Context context, byte[] payload) {
        stop();
        if (!isAvailable(context)) {
            return;
        }
        WifiAwareManager manager = context.getSystemService(WifiAwareManager.class);
        if (manager == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            manager.attach(new AttachCallback() {
                @Override
                public void onAttached(WifiAwareSession session) {
                    publishSession = session;
                    try {
                        PublishConfig config = new PublishConfig.Builder()
                                .setServiceName(SERVICE_NAME)
                                .setServiceSpecificInfo(payload)
                                .build();
                        session.publish(config, new DiscoverySessionCallback() {
                            @Override
                            public void onPublishStarted(PublishDiscoverySession session) {
                                publishDiscoverySession = session;
                                latch.countDown();
                            }

                            @Override
                            public void onSessionConfigFailed() {
                                latch.countDown();
                            }
                        }, null);
                    } catch (RuntimeException ignored) {
                        closeQuietly(session);
                        publishSession = null;
                        latch.countDown();
                    }
                }

                @Override
                public void onAttachFailed() {
                    latch.countDown();
                }
            }, null);
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            latch.countDown();
        }
    }

    public static byte[] scan(Context context, int millis) {
        if (!isAvailable(context)) {
            return new byte[0];
        }
        WifiAwareManager manager = context.getSystemService(WifiAwareManager.class);
        if (manager == null) {
            return new byte[0];
        }
        List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<WifiAwareSession> awareSession = new AtomicReference<>();
        AtomicReference<SubscribeDiscoverySession> subscribeSession = new AtomicReference<>();
        try {
            manager.attach(new AttachCallback() {
                @Override
                public void onAttached(WifiAwareSession session) {
                    awareSession.set(session);
                    try {
                        SubscribeConfig config = new SubscribeConfig.Builder()
                                .setServiceName(SERVICE_NAME)
                                .build();
                        session.subscribe(config, new DiscoverySessionCallback() {
                            @Override
                            public void onSubscribeStarted(SubscribeDiscoverySession session) {
                                subscribeSession.set(session);
                                attached.countDown();
                            }

                            @Override
                            public void onServiceDiscovered(
                                    PeerHandle peerHandle,
                                    byte[] serviceSpecificInfo,
                                    List<byte[]> matchFilter
                            ) {
                                if (serviceSpecificInfo != null) {
                                    payloads.add(serviceSpecificInfo);
                                }
                            }

                            @Override
                            public void onSessionConfigFailed() {
                                attached.countDown();
                                done.countDown();
                            }
                        }, null);
                    } catch (RuntimeException ignored) {
                        closeQuietly(session);
                        attached.countDown();
                        done.countDown();
                    }
                }

                @Override
                public void onAttachFailed() {
                    attached.countDown();
                    done.countDown();
                }
            }, null);
            attached.await(1, TimeUnit.SECONDS);
            done.await(Math.max(100, millis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            attached.countDown();
            done.countDown();
        } finally {
            closeQuietly(subscribeSession.get());
            closeQuietly(awareSession.get());
        }
        return framePayloads(payloads);
    }

    public static synchronized void stop() {
        closeQuietly(publishDiscoverySession);
        closeQuietly(publishSession);
        publishDiscoverySession = null;
        publishSession = null;
    }

    private static boolean isAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            return false;
        }
        WifiAwareManager manager = context.getSystemService(WifiAwareManager.class);
        return manager != null && manager.isAvailable();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Session may already be closed by the framework.
        }
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
