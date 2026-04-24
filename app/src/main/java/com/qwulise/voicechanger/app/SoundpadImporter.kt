package com.qwulise.voicechanger.app

import android.content.Context
import android.database.Cursor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.qwulise.voicechanger.core.SoundpadFileBridge
import com.qwulise.voicechanger.core.SoundpadSlot
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

object SoundpadImporter {
    fun importIntoSlot(context: Context, uri: Uri, slot: SoundpadSlot): SoundpadSlot {
        val tempFile = File.createTempFile("soundpad-${slot.id}-", ".pcm", context.cacheDir)
        return try {
            val decoded = normalizePcmToTargetRate(
                outputFile = tempFile,
                decoded = decodeToMonoPcm(context, uri, tempFile),
            )
            RootConfigPublisher.publishSoundpadClip(context.packageName, slot.id, tempFile)
            val rawTitle = queryDisplayName(context, uri)
                ?.substringBeforeLast('.')
                ?.trim()
                .orEmpty()
                .ifBlank { slot.title }
                .take(22)
            slot.copy(
                title = rawTitle,
                subtitle = formatDuration(decoded.durationMs),
                pcmPath = SoundpadFileBridge.pcmPathFor(context.packageName, slot.id),
                sampleRate = decoded.sampleRate,
                frameCount = decoded.frameCount,
                gainPercent = 100,
            ).sanitized()
        } finally {
            tempFile.delete()
        }
    }

    private fun normalizePcmToTargetRate(outputFile: File, decoded: DecodedAudio): DecodedAudio {
        if (decoded.sampleRate == TARGET_SAMPLE_RATE || decoded.frameCount <= 0 || !outputFile.isFile) {
            return decoded
        }
        val sourceSamples = runCatching {
            val shortBuffer = ByteBuffer.wrap(outputFile.readBytes())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            ShortArray(shortBuffer.remaining()).also(shortBuffer::get)
        }.getOrDefault(ShortArray(0))
        if (sourceSamples.isEmpty()) {
            return decoded
        }

        val targetFrameCount = ((sourceSamples.size.toDouble() * TARGET_SAMPLE_RATE) / decoded.sampleRate.coerceAtLeast(8_000).toDouble())
            .roundToInt()
            .coerceAtLeast(1)
        val resampled = ShortArray(targetFrameCount)
        if (sourceSamples.size == 1) {
            java.util.Arrays.fill(resampled, sourceSamples[0])
        } else {
            val step = (sourceSamples.size - 1).toDouble() / (targetFrameCount - 1).coerceAtLeast(1).toDouble()
            repeat(targetFrameCount) { index ->
                val sourceIndex = index * step
                val base = sourceIndex.toInt().coerceIn(0, sourceSamples.lastIndex)
                val next = (base + 1).coerceAtMost(sourceSamples.lastIndex)
                val fraction = (sourceIndex - base).toFloat().coerceIn(0f, 1f)
                val first = sourceSamples[base].toInt()
                val second = sourceSamples[next].toInt()
                resampled[index] = (first + ((second - first) * fraction))
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }

        val bytes = ByteBuffer.allocate(resampled.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        resampled.forEach(bytes::putShort)
        outputFile.writeBytes(bytes.array())
        return DecodedAudio(
            sampleRate = TARGET_SAMPLE_RATE,
            frameCount = resampled.size,
        )
    }

    private fun decodeToMonoPcm(context: Context, uri: Uri, outputFile: File): DecodedAudio {
        val extractor = MediaExtractor()
        val descriptor = requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")) {
            "Не удалось открыть аудио"
        }
        return descriptor.use { asset ->
            extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Аудиодорожка не найдена")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный audio mime")
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 48_000
            var channels = inputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var frameCount = 0
            val declaredDurationUs = inputFormat.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L
            var lastOutputPresentationUs = 0L
            var lastOutputFrameDurationUs = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            try {
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    while (!outputDone) {
                        if (!inputDone) {
                            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        extractor.sampleTime.coerceAtLeast(0L),
                                        0,
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                codec.outputFormat?.let { format ->
                                    sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                                    channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                                    pcmEncoding = format.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                                        ?: AudioFormat.ENCODING_PCM_16BIT
                                }
                            }
                            else -> {
                                if (outputIndex >= 0) {
                                    if (info.size > 0) {
                                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                                        outputBuffer.position(info.offset)
                                        outputBuffer.limit(info.offset + info.size)
                                        val writtenFrames = writeMonoPcm(
                                            output = output,
                                            input = outputBuffer.slice(),
                                            channels = channels.coerceAtLeast(1),
                                            pcmEncoding = pcmEncoding,
                                        )
                                        frameCount += writtenFrames
                                        lastOutputPresentationUs = maxOf(lastOutputPresentationUs, info.presentationTimeUs.coerceAtLeast(0L))
                                        if (sampleRate > 0 && writtenFrames > 0) {
                                            lastOutputFrameDurationUs = maxOf(
                                                lastOutputFrameDurationUs,
                                                (writtenFrames * 1_000_000L) / sampleRate.coerceAtLeast(8_000),
                                            )
                                        }
                                    }
                                    codec.releaseOutputBuffer(outputIndex, false)
                                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outputDone = true
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                runCatching { extractor.release() }
            }

            val effectiveDurationUs = when {
                declaredDurationUs > 0L -> declaredDurationUs
                lastOutputPresentationUs > 0L -> lastOutputPresentationUs + lastOutputFrameDurationUs
                else -> 0L
            }
            val derivedSampleRate = if (effectiveDurationUs > 0L && frameCount > 0) {
                ((frameCount * 1_000_000L) / effectiveDurationUs).toInt()
            } else {
                sampleRate
            }
            val effectiveSampleRate = when {
                derivedSampleRate !in 8_000..96_000 -> sampleRate
                sampleRate <= 0 -> derivedSampleRate
                abs(derivedSampleRate - sampleRate) >= (sampleRate / 5) -> derivedSampleRate
                else -> sampleRate
            }

            DecodedAudio(
                sampleRate = effectiveSampleRate.coerceAtLeast(8_000),
                frameCount = frameCount.coerceAtLeast(0),
            )
        }
    }

    private fun writeMonoPcm(
        output: BufferedOutputStream,
        input: ByteBuffer,
        channels: Int,
        pcmEncoding: Int,
    ): Int {
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuffer = input.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val sampleCount = floatBuffer.remaining()
                val frames = sampleCount / channels
                val bytes = ByteArray(frames * 2)
                var sampleIndex = 0
                repeat(frames) { frame ->
                    var mixed = 0f
                    repeat(channels) {
                        mixed += if (sampleIndex < sampleCount) floatBuffer.get(sampleIndex++) else 0f
                    }
                    val mono = (mixed / channels.toFloat()).coerceIn(-1f, 1f)
                    val pcm = (mono * Short.MAX_VALUE).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    val byteIndex = frame * 2
                    bytes[byteIndex] = (pcm and 0xFF).toByte()
                    bytes[byteIndex + 1] = ((pcm shr 8) and 0xFF).toByte()
                }
                output.write(bytes)
                frames
            }

            else -> {
                val shortBuffer = input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val sampleCount = shortBuffer.remaining()
                val frames = sampleCount / channels
                val bytes = ByteArray(frames * 2)
                var sampleIndex = 0
                repeat(frames) { frame ->
                    var mixed = 0
                    repeat(channels) {
                        mixed += if (sampleIndex < sampleCount) shortBuffer.get(sampleIndex++).toInt() else 0
                    }
                    val mono = (mixed / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    val byteIndex = frame * 2
                    bytes[byteIndex] = (mono and 0xFF).toByte()
                    bytes[byteIndex + 1] = ((mono shr 8) and 0xFF).toByte()
                }
                output.write(bytes)
                frames
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.useCursor { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) cursor.getString(columnIndex) else null
            }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private inline fun <T> Cursor.useCursor(block: (Cursor) -> T): T =
        use(block)

    private data class DecodedAudio(
        val sampleRate: Int,
        val frameCount: Int,
    ) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else (frameCount * 1000L) / sampleRate
    }

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val TARGET_SAMPLE_RATE = 48_000
}
