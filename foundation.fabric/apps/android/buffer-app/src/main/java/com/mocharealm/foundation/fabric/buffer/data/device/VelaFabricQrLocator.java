package com.mocharealm.foundation.fabric.buffer.data.device;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Public, non-authorizing locator printed by an unpaired Vela device. */
public final class VelaFabricQrLocator {
    private static final String PREFIX = "vela-fabric://pair/v1/";
    private final byte[] publicKey;
    private final String deviceId;

    private VelaFabricQrLocator(byte[] publicKey) {
        this.publicKey = publicKey.clone();
        this.deviceId = toHex(sha256(publicKey));
    }

    public static String encode(byte[] publicKey) {
        if (publicKey == null || publicKey.length != 32 || isZero(publicKey)) {
            throw new IllegalArgumentException("Fabric public key must be 32 non-zero bytes");
        }
        return PREFIX + toHex(publicKey);
    }

    /** Accept only the canonical syntax. The payload has no pairing secret or
     * authorization and is passed to Fabric as the expected peer public key. */
    public static VelaFabricQrLocator parse(String payload) {
        if (payload == null || payload.length() != PREFIX.length() + 64 ||
                !payload.startsWith(PREFIX)) return null;
        byte[] key = fromLowerHex(payload.substring(PREFIX.length()));
        if (key == null || isZero(key)) return null;
        return new VelaFabricQrLocator(key);
    }

    public byte[] copyPublicKey() {
        return publicKey.clone();
    }

    public String getDeviceId() {
        return deviceId;
    }

    private static byte[] fromLowerHex(String encoded) {
        if (encoded.length() != 64) return null;
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            int high = hex(encoded.charAt(i * 2));
            int low = hex(encoded.charAt(i * 2 + 1));
            if (high < 0 || low < 0) return null;
            bytes[i] = (byte)((high << 4) | low);
        }
        return bytes;
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        return -1;
    }

    private static boolean isZero(byte[] bytes) {
        int aggregate = 0;
        for (byte value : bytes) aggregate |= value;
        return aggregate == 0;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] encoded = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            encoded[i * 2] = alphabet[value >>> 4];
            encoded[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(encoded);
    }
}
