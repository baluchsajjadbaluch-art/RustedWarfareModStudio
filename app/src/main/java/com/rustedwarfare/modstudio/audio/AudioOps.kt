package com.rustedwarfare.modstudio.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real audio utilities: technical probing via MediaExtractor and a genuine
 * decoder (MediaCodec) that converts any platform-supported audio (wav/ogg/mp3)
 * into 16-bit PCM WAV bytes. Format conversion is only offered because this
 * actually decodes — nothing is faked.
 */
object AudioOps {

    data class AudioInfo(
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitrateKbps: Int,
        val mime: String
    )

    fun probe(format: MediaFormat, durationUs: Long): AudioInfo = AudioInfo(
        durationMs = durationUs / 1000,
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        bitrateKbps = if (format.containsKey(MediaFormat.KEY_BIT_RATE))
            format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else 0,
        mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
    )

    /**
     * Decode the audio file into 16-bit PCM WAV.
     * @param inputFile a real file path (MediaExtractor needs a data source)
     * @param onProgress fraction 0..1
     */
    fun decodeToWav(
        inputFile: File,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Pair<ByteArray, AudioInfo> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val fmt = format ?: throw IllegalArgumentException("No audio track found")
            extractor.selectTrack(trackIndex)
            val inputMime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = fmt.getLong(MediaFormat.KEY_DURATION)

            val codec = MediaCodec.createDecoderByType(inputMime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val startTime = System.currentTimeMillis()

            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val inBuf = codec.dequeueInputBuffer(10_000)
                    if (inBuf >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(inBuf)!!
                        val chunk = extractor.readSampleData(buf, 0)
                        if (chunk < 0) {
                            codec.queueInputBuffer(inBuf, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inBuf, 0, chunk, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        outSampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val outBuf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val elapsed = System.currentTimeMillis() - startTime
                        val progress = if (durationUs > 0) {
                            (elapsed.coerceAtMost(60_000L) / 60_000.0).toFloat()
                        } else 0.5f
                        onProgress(progress.coerceIn(0f, 0.95f))
                    }
                }
            }
            codec.stop()
            codec.release()
            onProgress(1f)

            val info2 = AudioInfo(
                durationMs = pcmOut.size().toLong() * 1000 / (outSampleRate * outChannels * 2),
                sampleRate = outSampleRate,
                channels = outChannels,
                bitrateKbps = 0,
                mime = "audio/raw (PCM16)"
            )
            return wavFromPcm(pcmOut.toByteArray(), outSampleRate, outChannels) to info2
        } finally {
            extractor.release()
        }
    }

    /** Wrap raw 16-bit LE PCM into a canonical RIFF/WAVE container. */
    fun wavFromPcm(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val total = pcm.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + total)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(total)
        val out = ByteArray(44 + total)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, total)
        return out
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
