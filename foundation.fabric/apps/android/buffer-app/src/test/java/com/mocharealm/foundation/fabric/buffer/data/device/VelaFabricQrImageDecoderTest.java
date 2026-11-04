package com.mocharealm.foundation.fabric.buffer.data.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.app.Application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class VelaFabricQrImageDecoderTest {
    @Test public void decodesRealZxingEncodedVelaLocator() throws Exception {
        byte[] publicKey = new byte[32];
        for (int i = 0; i < publicKey.length; i++) publicKey[i] = (byte)(i + 1);
        String payload = VelaFabricQrLocator.encode(publicKey);

        Bitmap bitmap = bitmapForQr(payload);
        try {
            VelaFabricQrLocator decoded = VelaFabricQrImageDecoder.decodeBitmap(bitmap);
            assertEquals(payload, VelaFabricQrLocator.encode(decoded.copyPublicKey()));
            assertEquals(VelaFabricQrLocator.parse(payload).getDeviceId(), decoded.getDeviceId());
        } finally {
            bitmap.recycle();
        }
    }

    @Test public void rejectsImageWithoutQr() {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565);
        bitmap.eraseColor(0xffeeeeee);
        try {
            VelaFabricQrImageDecoder.decodeBitmap(bitmap);
            fail("expected QR_NOT_FOUND");
        } catch (VelaFabricQrImageDecoder.DecodeException expected) {
            assertEquals(VelaFabricQrImageDecoder.Failure.QR_NOT_FOUND, expected.getFailure());
        } finally {
            bitmap.recycle();
        }
    }

    @Test public void rejectsQrThatDoesNotContainVelaLocator() throws Exception {
        Bitmap bitmap = bitmapForQr("https://example.invalid/not-a-vela-locator");
        try {
            VelaFabricQrImageDecoder.decodeBitmap(bitmap);
            fail("expected INVALID_LOCATOR");
        } catch (VelaFabricQrImageDecoder.DecodeException expected) {
            assertEquals(VelaFabricQrImageDecoder.Failure.INVALID_LOCATOR, expected.getFailure());
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap bitmapForQr(String text) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE,
                512, 512, hints);
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
