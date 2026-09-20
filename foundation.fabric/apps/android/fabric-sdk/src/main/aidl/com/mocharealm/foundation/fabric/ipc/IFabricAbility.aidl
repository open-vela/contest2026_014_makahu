package com.mocharealm.foundation.fabric.ipc;

import android.os.ParcelFileDescriptor;

oneway interface IFabricAbility {
    void onInvoke(long requestId, in byte[] payload);
    void onStreamOpen(
            long requestId,
            String ability,
            String streamName,
            in ParcelFileDescriptor readFromRemote,
            in ParcelFileDescriptor writeToRemote);
}
