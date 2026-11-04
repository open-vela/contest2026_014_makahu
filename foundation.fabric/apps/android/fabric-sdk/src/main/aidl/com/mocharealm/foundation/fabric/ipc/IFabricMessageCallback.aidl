package com.mocharealm.foundation.fabric.ipc;

/** Transport/session events and bounded application messages; never identity secrets. */
oneway interface IFabricMessageCallback {
    void onState(long connectionId, String state, int shortCode, int maxMessageBytes, in byte[] peerDeviceId);
    void onMessage(long connectionId, in byte[] payload);
}
