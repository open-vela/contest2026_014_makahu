package com.mocharealm.foundation.fabric.buffer;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLinux;

/** Robolectric 4.17 fstat(fd) delegates to stat(null), losing size and mode.
 * Read the actual host descriptor, so production permission/size checks run. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
public class DescriptorLinuxShadow extends ShadowLinux {
    private final Map<FileDescriptor, java.io.RandomAccessFile> files = new java.util.IdentityHashMap<>();

    @Implementation
    @Override
    protected synchronized FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        try {
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(Path.of(path)))
                throw new ErrnoException("open", OsConstants.ELOOP);
            java.io.RandomAccessFile file = new java.io.RandomAccessFile(path,
                (flags & (OsConstants.O_WRONLY | OsConstants.O_RDWR)) != 0 ? "rw" : "r");
            FileDescriptor descriptor = file.getFD();
            files.put(descriptor, file);
            return descriptor;
        } catch (java.io.FileNotFoundException error) {
            int code = Files.notExists(Path.of(path)) ? OsConstants.ENOENT : OsConstants.EACCES;
            throw new ErrnoException("open", code, error);
        } catch (java.io.IOException error) { throw new ErrnoException("open", OsConstants.EIO, error); }
    }

    @Implementation
    protected synchronized int read(FileDescriptor descriptor, byte[] bytes, int offset, int count) throws ErrnoException {
        try { return Math.max(0, files.get(descriptor).read(bytes, offset, count)); }
        catch (java.io.IOException error) { throw new ErrnoException("read", OsConstants.EIO, error); }
    }

    @Implementation
    protected synchronized void close(FileDescriptor descriptor) throws ErrnoException {
        try { java.io.RandomAccessFile file = files.remove(descriptor); if (file != null) file.close(); }
        catch (java.io.IOException error) { throw new ErrnoException("close", OsConstants.EIO, error); }
    }

    @Implementation
    protected void fsync(FileDescriptor descriptor) throws ErrnoException {
        try { descriptor.sync(); }
        catch (java.io.IOException error) { throw new ErrnoException("fsync", OsConstants.EIO, error); }
    }

    @Implementation
    protected void chmod(String path, int mode) throws ErrnoException {
        try { Files.setAttribute(Path.of(path), "unix:mode", mode); }
        catch (Exception error) { throw new ErrnoException("chmod", OsConstants.EIO, error); }
    }

    @Implementation
    public StructStat stat(String path) throws ErrnoException {
        return pathStat(path, false);
    }

    @Implementation
    public StructStat lstat(String path) throws ErrnoException {
        return pathStat(path, true);
    }

    private StructStat pathStat(String path, boolean noFollow) throws ErrnoException {
        try {
            Map<String, Object> attrs = Files.readAttributes(Path.of(path), "unix:mode,size",
                noFollow ? new java.nio.file.LinkOption[]{java.nio.file.LinkOption.NOFOLLOW_LINKS}
                         : new java.nio.file.LinkOption[0]);
            return new StructStat(0, 0, (Integer) attrs.get("mode"), 1, 0, 0, 0,
                (Long) attrs.get("size"), 0, 0, 0, 0, 0);
        } catch (java.nio.file.NoSuchFileException error) {
            throw new ErrnoException("stat", OsConstants.ENOENT, error);
        } catch (java.io.IOException error) {
            throw new ErrnoException("stat", OsConstants.EIO, error);
        }
    }

    @Implementation
    @Override
    protected StructStat fstat(FileDescriptor descriptor) throws ErrnoException {
        try {
            Field field = FileDescriptor.class.getDeclaredField("fd");
            field.setAccessible(true);
            Map<String, Object> attrs = Files.readAttributes(
                Path.of("/proc/self/fd/" + field.getInt(descriptor)), "unix:mode,size");
            return new StructStat(0, 0, (Integer) attrs.get("mode"), 1, 0, 0, 0,
                (Long) attrs.get("size"), 0, 0, 0, 0, 0);
        } catch (Exception error) {
            throw new ErrnoException("fstat", OsConstants.EIO, error);
        }
    }
}
