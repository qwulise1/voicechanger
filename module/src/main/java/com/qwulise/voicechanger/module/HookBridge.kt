package com.qwulise.voicechanger.module

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import com.qwulise.voicechanger.core.VoiceProcessingState
import com.qwulise.voicechanger.core.VoiceProfileCatalog
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap

object HookBridge {
    private val states = Collections.synchronizedMap(WeakHashMap<AudioRecord, VoiceProcessingState>())
    private val sessions = Collections.synchronizedMap(WeakHashMap<AudioRecord, AudioRecordSession>())
    private val recentBuffers = Collections.synchronizedMap(WeakHashMap<ByteBuffer, ProcessedBufferStamp>())

    fun activeTargets(): List<String> = listOf(
        "AudioRecord.read(...) Java hook",
        "AudioRecord lifecycle metadata hooks",
        "ContentProvider-backed shared config",
        "Per-stream PCM state cache",
        "Per-app target package routing",
        "WebRTC native buffer fallback bridge",
        "Ring-buffer live logs",
    )

    fun plannedTargets(): List<String> = listOf(
        "AAudio / Oboe native input path",
        "Vendor-specific native capture paths",
        "Selected app-specific native pipelines",
    )

    fun plannedProfiles(): List<String> = VoiceProfileCatalog.defaultProfiles.map { it.name }

    fun activeProfiles(): List<String> = VoiceMode.entries.map { "${it.title}: ${it.summary}" }

    fun stateFor(audioRecord: AudioRecord): VoiceProcessingState =
        synchronized(states) {
            states.getOrPut(audioRecord) { VoiceProcessingState() }
        }

    fun registerAudioRecord(audioRecord: AudioRecord, packageName: String): AudioRecordSession =
        synchronized(sessions) {
            sessions.getOrPut(audioRecord) {
                AudioRecordSession(
                    packageName = packageName,
                    audioSource = invokeInt(audioRecord, "getAudioSource"),
                    sampleRate = invokeInt(audioRecord, "getSampleRate"),
                    channelCount = invokeInt(audioRecord, "getChannelCount"),
                    encoding = invokeInt(audioRecord, "getFormat"),
                    bufferSizeInFrames = invokeInt(audioRecord, "getBufferSizeInFrames"),
                )
            }
        }

    fun sessionFor(audioRecord: AudioRecord): AudioRecordSession? =
        synchronized(sessions) { sessions[audioRecord] }

    fun releaseAudioRecord(audioRecord: AudioRecord) {
        synchronized(states) {
            states.remove(audioRecord)
        }
        synchronized(sessions) {
            sessions.remove(audioRecord)
        }
    }

    fun markBufferProcessed(buffer: ByteBuffer, byteCount: Int, source: String) {
        synchronized(recentBuffers) {
            recentBuffers[buffer] = ProcessedBufferStamp(
                byteCount = byteCount,
                source = source,
                timestampMs = System.currentTimeMillis(),
            )
        }
    }

    fun shouldProcessWebRtcBuffer(buffer: ByteBuffer, byteCount: Int, freshnessWindowMs: Long = 250L): Boolean {
        val now = System.currentTimeMillis()
        val previous = synchronized(recentBuffers) { recentBuffers[buffer] } ?: return true
        return previous.byteCount != byteCount || now - previous.timestampMs > freshnessWindowMs
    }

    fun isTargetPackageAllowed(config: VoiceConfig, packageName: String): Boolean {
        if (!config.restrictToTargets) {
            return true
        }
        return packageName in config.targetPackages
    }

    fun shouldHookPackage(packageName: String): Boolean = packageName !in setOf(
        "android",
        "com.android.systemui",
        "com.qwulise.voicechanger.app",
        "com.qwulise.voicechanger.module",
    )

    private fun invokeInt(target: Any, methodName: String): Int? =
        runCatching { target.javaClass.getMethod(methodName).invoke(target) as? Int }.getOrNull()
}

data class AudioRecordSession(
    val packageName: String,
    val audioSource: Int?,
    val sampleRate: Int?,
    val channelCount: Int?,
    val encoding: Int?,
    val bufferSizeInFrames: Int?,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun describe(): String = buildString {
        append("source=${audioSourceLabel(audioSource)}")
        sampleRate?.let { append(" rate=${it}Hz") }
        channelCount?.let { append(" channels=$it") }
        encoding?.let { append(" encoding=${encodingLabel(it)}") }
        bufferSizeInFrames?.let { append(" frames=$it") }
    }

    private fun audioSourceLabel(value: Int?): String =
        when (value) {
            null -> "unknown"
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            9 -> "UNPROCESSED"
            10 -> "VOICE_PERFORMANCE"
            else -> value.toString()
        }

    private fun encodingLabel(value: Int): String =
        when (value) {
            AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24BIT"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM_32BIT"
            else -> value.toString()
        }
}

private data class ProcessedBufferStamp(
    val byteCount: Int,
    val source: String,
    val timestampMs: Long,
)
