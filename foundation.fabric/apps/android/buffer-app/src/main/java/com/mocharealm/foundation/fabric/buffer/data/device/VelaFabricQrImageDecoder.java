package com.mocharealm.foundation.fabric.buffer.data.device;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.EnumMap;
import java.util.Map;

/** Bounded, on-demand decoding for a user-selected Photo Picker URI. */
public final class VelaFabricQrImageDecoder {
    public static final long MAX_COMPRESSED_BYTES = 8L * 1024L * 1024L;
    public static final int MAX_SOURCE_DIMENSION = 20_000;
    public static final long MAX_SOURCE_PIXELS = 80_000_000L;
    public static final int MAX_DECODED_DIMENSION = 2_048;
    public static final int MAX_DECODED_PIXELS = 2_097_152;
    /** Bitmap + RGB pixels + ZXing luminance buffer stay below this budget. */
    public static final long MAX_WORKING_MEMORY_BYTES = 16L * 1024L * 1024L;

    public enum Failure {
        FILE_TOO_LARGE,
        IMAGE_TOO_LARGE,
        INVALID_IMAGE,
        QR_NOT_FOUND,
        INVALID_LOCATOR
    }

    public static final class DecodeException extends Exception {
        private final Failure failure;

        DecodeException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure getFailure() {
            return failure;
        }
    }

    @FunctionalInterface
    public interface CancellationCheck {
        void check() throws IOException;
    }

    private VelaFabricQrImageDecoder() { }

    /**
     * Reads only the selected content URI into a bounded app-cache file, then
     * samples it before allocating a bitmap. No broad media/storage permission
     * is needed. The caller should run this method on a worker dispatcher.
     */
    public static VelaFabricQrLocator decode(
            ContentResolver resolver,
            File cacheDirectory,
            Uri uri,
            CancellationCheck cancellationCheck) throws IOException, DecodeException {
        if (resolver == null || cacheDirectory == null || uri == null ||
                !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("A Photo Picker content URI is required");
        }
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Cannot create the private image cache");
        }

        File boundedImage = File.createTempFile("vela-qr-", ".image", cacheDirectory);
        try {
            copyBounded(resolver, uri, boundedImage, cancellationCheck);
            return decodeFile(boundedImage, cancellationCheck);
        } finally {
            if (!boundedImage.delete()) boundedImage.deleteOnExit();
        }
    }

    private static void copyBounded(ContentResolver resolver, Uri uri, File target,
                                    CancellationCheck cancellationCheck)
            throws IOException, DecodeException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Selected image could not be opened");
            try (BufferedInputStream source = new BufferedInputStream(input);
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buffer = new byte[8 * 1024];
                long copied = 0;
                int read;
                while ((read = source.read(buffer)) != -1) {
                    checkCancellation(cancellationCheck);
                    copied += read;
                    if (copied > MAX_COMPRESSED_BYTES) {
                        throw new DecodeException(Failure.FILE_TOO_LARGE);
                    }
                    output.write(buffer, 0, read);
                }
                if (copied == 0) throw new DecodeException(Failure.INVALID_IMAGE);
            }
        }
    }

    private static VelaFabricQrLocator decodeFile(File file,
                                                   CancellationCheck cancellationCheck)
            throws IOException, DecodeException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        checkCancellation(cancellationCheck);
        int sourceWidth = bounds.outWidth;
        int sourceHeight = bounds.outHeight;
        long sourcePixels = (long) sourceWidth * sourceHeight;
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new DecodeException(Failure.INVALID_IMAGE);
        }
        if (sourceWidth > MAX_SOURCE_DIMENSION || sourceHeight > MAX_SOURCE_DIMENSION ||
                sourcePixels > MAX_SOURCE_PIXELS) {
            throw new DecodeException(Failure.IMAGE_TOO_LARGE);
        }

        int sampleSize = 1;
        while (ceilDiv(sourceWidth, sampleSize) > MAX_DECODED_DIMENSION ||
                ceilDiv(sourceHeight, sampleSize) > MAX_DECODED_DIMENSION ||
                (long) ceilDiv(sourceWidth, sampleSize) * ceilDiv(sourceHeight, sampleSize) >
                        MAX_DECODED_PIXELS) {
            sampleSize <<= 1;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        checkCancellation(cancellationCheck);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new DecodeException(Failure.INVALID_IMAGE);
        try {
            long decodedPixels = (long) bitmap.getWidth() * bitmap.getHeight();
            if (bitmap.getWidth() > MAX_DECODED_DIMENSION ||
                    bitmap.getHeight() > MAX_DECODED_DIMENSION ||
                    decodedPixels > MAX_DECODED_PIXELS ||
                    bitmap.getAllocationByteCount() > MAX_WORKING_MEMORY_BYTES / 4) {
                throw new DecodeException(Failure.IMAGE_TOO_LARGE);
            }
            checkCancellation(cancellationCheck);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource luminance = new RGBLuminanceSource(width, height, pixels);
            pixels = null;
            checkCancellation(cancellationCheck);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            QRCodeReader reader = new QRCodeReader();
            final Result result;
            try {
                result = reader.decode(new BinaryBitmap(new HybridBinarizer(luminance)), hints);
            } catch (NotFoundException notFound) {
                throw new DecodeException(Failure.QR_NOT_FOUND);
            } catch (com.google.zxing.ChecksumException | com.google.zxing.FormatException invalidQr) {
                throw new DecodeException(Failure.QR_NOT_FOUND);
            } finally {
                reader.reset();
            }
            checkCancellation(cancellationCheck);
            VelaFabricQrLocator locator = VelaFabricQrLocator.parse(result.getText());
            if (locator == null) throw new DecodeException(Failure.INVALID_LOCATOR);
            return locator;
        } finally {
            bitmap.recycle();
        }
    }

    /** Decode an already bounded bitmap; exposed for JVM/Robolectric coverage. */
    public static VelaFabricQrLocator decodeBitmap(Bitmap bitmap) throws DecodeException {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new DecodeException(Failure.INVALID_IMAGE);
        }
        long pixelsCount = (long) bitmap.getWidth() * bitmap.getHeight();
        if (bitmap.getWidth() > MAX_DECODED_DIMENSION ||
                bitmap.getHeight() > MAX_DECODED_DIMENSION || pixelsCount > MAX_DECODED_PIXELS ||
                bitmap.getAllocationByteCount() > MAX_WORKING_MEMORY_BYTES / 4) {
            throw new DecodeException(Failure.IMAGE_TOO_LARGE);
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        RGBLuminanceSource luminance = new RGBLuminanceSource(width, height, pixels);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        QRCodeReader reader = new QRCodeReader();
        try {
            Result result = reader.decode(new BinaryBitmap(new HybridBinarizer(luminance)), hints);
            VelaFabricQrLocator locator = VelaFabricQrLocator.parse(result.getText());
            if (locator == null) throw new DecodeException(Failure.INVALID_LOCATOR);
            return locator;
        } catch (NotFoundException | com.google.zxing.ChecksumException |
                 com.google.zxing.FormatException noQr) {
            throw new DecodeException(Failure.QR_NOT_FOUND);
        } finally {
            reader.reset();
        }
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static void checkCancellation(CancellationCheck cancellationCheck) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("QR image decode cancelled");
        if (cancellationCheck != null) cancellationCheck.check();
    }
}
