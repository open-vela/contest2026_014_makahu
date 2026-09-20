package com.mocharealm.foundation.fabric.buffer.data.llm
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Convert existing Android AAC recordings without changing the retained original. */
internal object BufferAsrAudio {
    private const val maxPcmBytes = 4 * 1024 * 1024
    fun wav(blob: File, audioFormat: String?): ByteArray {
        require(blob.isFile && blob.length() in 1..BufferConstants.maxAudioBytes.toLong()) { "录音文件无效" }
        val mime = audioFormat.orEmpty().substringBefore(';')
        if (mime in setOf("audio/wav", "audio/x-wav", "pcm16/wav")) {
            val bytes = blob.readBytes()
            require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "WAV 文件无效" }
            return bytes
        }
        require(mime == "audio/mp4") { "不支持此录音格式" }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(blob.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("录音没有音轨")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0); codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputEnded = false
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArrayOutputStream()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (true) {
                check(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "录音转换超时" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!; input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "解码器未输出 PCM16" }
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(pcm.size() + info.size <= maxPcmBytes) { "解码音频过大" }
                                val output = codec.getOutputBuffer(index)!!
                                output.position(info.offset); output.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); output.get(bytes); pcm.write(bytes)
                            }
                        } finally { codec.releaseOutputBuffer(index, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return wrapPcm(pcm.toByteArray(), rate, channels)
        } finally { decoder?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }

    /** Reject digital silence, not quiet speech or particular words. Originals remain queued. */
    internal fun requireAudioSignal(wav: ByteArray) {
        require(wav.size >= 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(wav, 8, 4, Charsets.US_ASCII) == "WAVE") { "WAV 文件无效" }
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var pcm16 = false
        var dataFound = false
        var signal = false
        while (offset <= wav.size - 8) {
            val size = bytes.getInt(offset + 4)
            require(size >= 0 && size <= wav.size - offset - 8) { "WAV 数据不完整" }
            val tag = String(wav, offset, 4, Charsets.US_ASCII)
            val start = offset + 8
            if (tag == "fmt ") {
                require(size >= 16) { "WAV 格式无效" }
                pcm16 = bytes.getShort(start).toInt() == 1 && bytes.getShort(start + 14).toInt() == 16
            }
            if (tag == "data") {
                dataFound = true
                require(size % 2 == 0) { "WAV 样本不完整" }
                for (index in start until start + size step 2) {
                    if (kotlin.math.abs(bytes.getShort(index).toInt()) > 16) signal = true
                }
            }
            offset = start + size + (size % 2)
        }
        require(pcm16 && dataFound) { "需要 PCM16 WAV 录音" }
        require(signal) { "未收到有效录音信号，请检查模拟器麦克风后重新录音" }
    }

    internal fun wrapPcm(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        require(rate in 8000..48000 && channels in 1..2 && pcm.isNotEmpty() &&
            pcm.size <= maxPcmBytes && pcm.size % (channels * 2) == 0) { "PCM 音频无效" }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
