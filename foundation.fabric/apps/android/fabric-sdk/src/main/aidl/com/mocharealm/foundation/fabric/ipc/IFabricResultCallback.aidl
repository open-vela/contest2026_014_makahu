package com.mocharealm.foundation.fabric.ipc;

oneway interface IFabricResultCallback {
    void onResult(long requestId, in byte[] payload, String error);
}
