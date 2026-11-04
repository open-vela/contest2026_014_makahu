package com.mocharealm.foundation.fabric.buffer.domain
import java.io.File

/** Immutable recovery files: retries reuse matching bytes, never replace originals. */
internal object RecoveryFiles {
    @Synchronized
    fun save(destination: File, bytes: ByteArray) {
        if (destination.exists()) {
            check(destination.length() == bytes.size.toLong() && destination.readBytes().contentEquals(bytes)) {
                "recovery file already has different content; original retained"
            }
            return
        }
        val temporary = File.createTempFile("buffer-recovery-", ".part", destination.parentFile)
        try {
            android.system.Os.chmod(temporary.absolutePath, 384) // 0600 before writing private bytes.
            temporary.outputStream().use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            check(temporary.renameTo(destination)) { "unable to commit recovery file" }
        } finally {
            temporary.delete()
        }
    }
}
