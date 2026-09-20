package com.mocharealm.foundation.fabric.ipc;

import android.os.ParcelFileDescriptor;
import com.mocharealm.foundation.fabric.ipc.IFabricAbility;
import com.mocharealm.foundation.fabric.ipc.IFabricResultCallback;

interface IFabricHub {
    void registerAbility(String ability, String packageName, IFabricAbility callback);
    void unregisterAbility(String ability, String packageName);
    String[] devices();
    long invoke(String deviceId, String ability, in byte[] payload, IFabricResultCallback callback);
    ParcelFileDescriptor[] openStream(String deviceId, String ability, String streamName);
    void complete(long requestId, in byte[] payload);
    String status();

    /**
     * Open an app-level fabric session over trusted devices for [ability].
     * Returns session id, or empty on failure. Hub owns peer clock domain setup.
     */
    String openSession(in String[] deviceIds, String ability, in byte[] configJson);

    void closeSession(String sessionId);

    /** Run fabric-clock probes for all session peers (hub-side, not ability payload). */
    boolean ensureSessionClock(String sessionId);

    long sessionLocalNowNs();

    /** Map group instant → device local monotonic ns using hub PeerClock mapping. */
    long sessionGroupToLocalNs(String deviceId, long groupNs);

    long sessionLocalToGroupNs(String deviceId, long localNs);

    long sessionClockUncertaintyNs(String deviceId);
}
