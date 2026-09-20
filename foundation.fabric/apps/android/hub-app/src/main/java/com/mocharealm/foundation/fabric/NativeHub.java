package com.mocharealm.foundation.fabric;

public final class NativeHub {
    static {
        System.loadLibrary("fabric_android_jni");
    }

    private NativeHub() {}

    public static native boolean nativeStart(Object context, String stateDirectory, String deviceLabel, int quicPort);
    public static native void nativeStop();
    public static native String nativeStatus();
    public static native String nativeDevices();
    public static native String nativeCandidates();
    public static native boolean nativePair(String address);
    public static native boolean nativePairRemote(String deviceId);
    public static native boolean nativeAccept(String deviceId);
    public static native boolean nativeRemove(String deviceId);
    public static native boolean nativeRegisterAbility(String ability, String packageName, int uid);
    public static native void nativeUnregisterAbility(String ability, String packageName, int uid);
    public static native byte[] nativeInvokeRemote(
            String deviceId, String ability, byte[] payload);
    public static native boolean nativeOpenRemoteStream(
            String deviceId, String ability, String streamName, int readFd, int writeFd);
    public static native byte[] nativeTakeRemoteRequest();
    public static native byte[] nativeTakeRemoteStream();
    public static native void nativeCompleteRemote(long requestId, byte[] payload);

    /** Session + hub clock (fabric-clock PeerClock mapping). */
    public static native String nativeOpenSession(String[] deviceIds, String ability, byte[] configJson);
    public static native void nativeCloseSession(String sessionId);
    public static native boolean nativeEnsureSessionClock(String sessionId);
    public static native long nativeSessionLocalNowNs();
    public static native long nativeSessionGroupToLocalNs(String deviceId, long groupNs);
    public static native long nativeSessionLocalToGroupNs(String deviceId, long localNs);
    public static native long nativeSessionClockUncertaintyNs(String deviceId);
}
