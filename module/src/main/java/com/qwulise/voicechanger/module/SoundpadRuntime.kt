package com.qwulise.voicechanger.module

import com.qwulise.voicechanger.core.SoundpadLibrary
import com.qwulise.voicechanger.core.SoundpadPlayback
import com.qwulise.voicechanger.core.SoundpadSlot
import com.qwulise.voicechanger.core.VoiceProcessingState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class SoundpadSnapshot(
    val library: SoundpadLibrary = SoundpadLibrary(),
    val playback: SoundpadPlayback = SoundpadPlayback(),
) {
    val activeSlot: SoundpadSlot?
        get() = library.slot(playback.activeSlotId)?.takeIf { playback.playing && playback.mixPercent > 0 && it.isReady }

    val isActive: Boolean
        get() = activeSlot != null
}

object SoundpadClient {
    private const val CACHE_WINDOW_MS = 160L

    @Volatile
    private var lastLoadedAt: Long = 0L

    @Volatile
    private var cachedSnapshot: SoundpadSnapshot = SoundpadSnapshot()

    fun snapshot(): SoundpadSnapshot {
        val now = System.currentTimeMillis()
        if (now - lastLoadedAt < CACHE_WINDOW_MS) {
            return cachedSnapshot
        }

        synchronized(this) {
            val refreshedNow = System.currentTimeMillis()
            if (refreshedNow - lastLoadedAt < CACHE_WINDOW_MS) {
                return cachedSnapshot
            }
            val library = ModuleFileBridge.readSoundpadLibrary() ?: SoundpadLibrary()
            val playback = ModuleFileBridge.readSoundpadPlayback() ?: SoundpadPlayback()
            cachedSnapshot = SoundpadSnapshot(library.sanitized(), playback.sanitized())
            lastLoadedAt = refreshedNow
            return cachedSnapshot
        }
    }
}

object SoundpadMixer {
    private val runtimes = Collections.synchronizedMap(WeakHashMap<VoiceProcessingState, RuntimeState>())

    fun mixIntoShortArray(
        samples: ShortArray,
        offset: Int,
        count: Int,
        outputSampleRate: Int,
        state: VoiceProcessingState,
        snapshot: SoundpadSnapshot,
    ) {
        val runtime = prepareRuntime(state, snapshot)
        if (!runtime.active || count <= 0 || offset !in samples.indices) {
            return
        }
        val safeCount = count.coerceAtMost(samples.size - offset)
        repeat(safeCount) { index ->
            val mixed = (samples[offset + index] / 32768f) + nextSample(outputSampleRate, runtime)
            samples[offset + index] = packToShort(mixed)
        }
    }

    fun mixIntoFloatArray(
        samples: FloatArray,
        offset: Int,
        count: Int,
        outputSampleRate: Int,
        state: VoiceProcessingState,
        snapshot: SoundpadSnapshot,
    ) {
        val runtime = prepareRuntime(state, snapshot)
        if (!runtime.active || count <= 0 || offset !in samples.indices) {
            return
        }
        val safeCount = count.coerceAtMost(samples.size - offset)
        repeat(safeCount) { index ->
            samples[offset + index] = (samples[offset + index] + nextSample(outputSampleRate, runtime))
                .coerceIn(-1f, 1f)
        }
    }

    fun mixIntoByteArrayPcm16(
        buffer: ByteArray,
        offsetBytes: Int,
        byteCount: Int,
        outputSampleRate: Int,
        state: VoiceProcessingState,
        snapshot: SoundpadSnapshot,
    ) {
        val runtime = prepareRuntime(state, snapshot)
        if (!runtime.active || byteCount < 2 || offsetBytes !in buffer.indices) {
            return
        }
        val safeByteCount = byteCount
            .coerceAtMost(buffer.size - offsetBytes)
            .let { it - (it % 2) }
        var cursor = offsetBytes
        val end = offsetBytes + safeByteCount
        while (cursor < end) {
            val raw = (buffer[cursor].toInt() and 0xFF) or (buffer[cursor + 1].toInt() shl 8)
            val mixed = (raw.toShort().toInt() / 32768f) + nextSample(outputSampleRate, runtime)
            val packed = packToShort(mixed).toInt()
            buffer[cursor] = (packed and 0xFF).toByte()
            buffer[cursor + 1] = ((packed shr 8) and 0xFF).toByte()
            cursor += 2
        }
    }

    fun mixIntoByteBufferPcm16(
        buffer: ByteBuffer,
        byteCount: Int,
        outputSampleRate: Int,
        state: VoiceProcessingState,
        snapshot: SoundpadSnapshot,
    ) {
        val runtime = prepareRuntime(state, snapshot)
        if (!runtime.active || byteCount < 2) {
            return
        }
        if (buffer.hasArray()) {
            val offset = buffer.arrayOffset()
            mixIntoByteArrayPcm16(
                buffer = buffer.array(),
                offsetBytes = offset,
                byteCount = byteCount,
                outputSampleRate = outputSampleRate,
                state = state,
                snapshot = snapshot,
            )
            return
        }
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = (byteCount - (byteCount % 2)) / 2
        duplicate.position(0)
        repeat(sampleCount) {
            val mixed = (duplicate.short / 32768f) + nextSample(outputSampleRate, runtime)
            duplicate.position(duplicate.position() - 2)
            duplicate.putShort(packToShort(mixed))
        }
    }

    private fun prepareRuntime(
        state: VoiceProcessingState,
        snapshot: SoundpadSnapshot,
    ): RuntimeState {
        val runtime = synchronized(runtimes) {
            runtimes.getOrPut(state) { RuntimeState() }
        }
        val activeSlot = snapshot.activeSlot
        if (activeSlot == null) {
            runtime.active = false
            runtime.looping = false
            runtime.mix = 0f
            return runtime
        }

        val sessionId = snapshot.playback.sessionId.takeIf { it > 0L } ?: activeSlot.id.hashCode().toLong()
        if (runtime.completedSessionId == sessionId && runtime.slotId == activeSlot.id && !snapshot.playback.looping) {
            runtime.active = false
            runtime.mix = 0f
            return runtime
        }

        val needsReload = sessionId != runtime.sessionId ||
            activeSlot.id != runtime.slotId ||
            activeSlot.pcmPath != runtime.pcmPath
        if (needsReload) {
            runtime.samples = loadPcm(activeSlot.pcmPath)
            runtime.sourceSampleRate = activeSlot.sampleRate.coerceAtLeast(8_000)
            runtime.position = 0.0
            runtime.sessionId = sessionId
            runtime.slotId = activeSlot.id
            runtime.pcmPath = activeSlot.pcmPath
            runtime.completedSessionId = 0L
            runtime.active = runtime.samples.isNotEmpty()
        }
        runtime.looping = snapshot.playback.looping
        runtime.mix = computeMix(snapshot.playback.mixPercent, activeSlot.gainPercent)
        if (runtime.samples.isEmpty()) {
            runtime.active = false
        }
        return runtime
    }

    private fun nextSample(outputSampleRate: Int, runtime: RuntimeState): Float {
        if (!runtime.active || runtime.samples.isEmpty()) {
            return 0f
        }

        val frameCount = runtime.samples.size
        if (frameCount <= 0) {
            runtime.active = false
            return 0f
        }
        if (!runtime.looping && runtime.position >= frameCount) {
            runtime.active = false
            runtime.completedSessionId = runtime.sessionId
            return 0f
        }
        if (runtime.looping && runtime.position >= frameCount) {
            runtime.position %= frameCount.toDouble()
        }

        val base = runtime.position.toInt().coerceIn(0, frameCount - 1)
        val nextIndex = when {
            base + 1 < frameCount -> base + 1
            runtime.looping -> 0
            else -> base
        }
        val fraction = (runtime.position - base).toFloat().coerceIn(0f, 1f)
        val first = runtime.samples[base] / 32768f
        val second = runtime.samples[nextIndex] / 32768f
        val sample = first + ((second - first) * fraction)
        val step = runtime.sourceSampleRate.toDouble() / outputSampleRate.coerceAtLeast(8_000).toDouble()
        runtime.position += step.coerceAtLeast(0.1)
        return (sample * runtime.mix).coerceIn(-1f, 1f)
    }

    private fun loadPcm(path: String): ShortArray =
        runCatching {
            val file = File(path)
            if (!file.isFile || file.length() < 2L) {
                return@runCatching ShortArray(0)
            }
            val bytes = file.readBytes()
            val shortBuffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            ShortArray(shortBuffer.remaining()).also(shortBuffer::get)
        }.getOrDefault(ShortArray(0))

    private fun computeMix(mixPercent: Int, gainPercent: Int): Float {
        val mix = (mixPercent.coerceIn(0, 100) / 100f).toDouble().pow(1.18).toFloat()
        val slotGain = (gainPercent.coerceIn(0, 160) / 100f).toDouble().pow(0.92).toFloat()
        return (mix * slotGain * 0.92f).coerceIn(0f, 1.35f)
    }

    private fun packToShort(value: Float): Short {
        val clipped = value.coerceIn(-1f, 1f)
        val shaped = (if (clipped >= 0f) 1f else -1f) *
            abs(clipped).toDouble().pow(0.98).toFloat()
        return (shaped * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private class RuntimeState {
        var sessionId: Long = 0L
        var completedSessionId: Long = 0L
        var slotId: String = ""
        var pcmPath: String = ""
        var samples: ShortArray = ShortArray(0)
        var sourceSampleRate: Int = 48_000
        var position: Double = 0.0
        var looping: Boolean = false
        var active: Boolean = false
        var mix: Float = 0f
    }
}
