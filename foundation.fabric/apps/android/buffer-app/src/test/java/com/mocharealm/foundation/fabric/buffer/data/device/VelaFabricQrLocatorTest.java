package com.mocharealm.foundation.fabric.buffer.data.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.security.MessageDigest;
import org.junit.Test;

public class VelaFabricQrLocatorTest {
    @Test public void roundTripsPublicKeyAndDerivesFabricDeviceId() throws Exception {
        byte[] publicKey = new byte[32];
        for (int i = 0; i < publicKey.length; i++) publicKey[i] = (byte)i;

        String encoded = VelaFabricQrLocator.encode(publicKey);
        VelaFabricQrLocator locator = VelaFabricQrLocator.parse(encoded);
        assertEquals("vela-fabric://pair/v1/" + hex(publicKey), encoded);
        assertArrayEquals(publicKey, locator.copyPublicKey());
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(publicKey)),
                locator.getDeviceId());
    }

    @Test public void rejectsMalformedOrSecretBearingPayloads() {
        String key = "0a".repeat(32);
        assertNull(VelaFabricQrLocator.parse(null));
        assertNull(VelaFabricQrLocator.parse("https://pair/v1/" + key));
        assertNull(VelaFabricQrLocator.parse("vela-fabric://pair/v2/" + key));
        assertNull(VelaFabricQrLocator.parse("vela-fabric://pair/v1/" + key + "?token=secret"));
        assertNull(VelaFabricQrLocator.parse("vela-fabric://pair/v1/" + key.toUpperCase()));
        assertNull(VelaFabricQrLocator.parse("vela-fabric://pair/v1/" + "00".repeat(32)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }
}
