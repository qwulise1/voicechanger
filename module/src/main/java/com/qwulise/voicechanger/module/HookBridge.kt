package com.qwulise.voicechanger.module

import android.media.AudioRecord
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import com.qwulise.voicechanger.core.VoiceProcessingState
import com.qwulise.voicechanger.core.VoiceProfileCatalog
import java.util.Collections
import java.util.WeakHashMap

object HookBridge {
    private val states = Collections.synchronizedMap(WeakHashMap<AudioRecord, VoiceProcessingState>())

    fun activeTargets(): List<String> = listOf(
        "AudioRecord.read(...) Java hook",
        "ContentProvider-backed shared config",
        "Per-stream PCM state cache",
        "Per-app target package routing",
        "WebRTC detection diagnostics",
        "Ring-buffer live logs",
    )

    fun plannedTargets(): List<String> = listOf(
        "AAudio / Oboe native input path",
        "WebRTC voice capture path",
        "Selected app-specific native pipelines",
    )

    fun plannedProfiles(): List<String> = VoiceProfileCatalog.defaultProfiles.map { it.name }

    fun activeProfiles(): List<String> = VoiceMode.entries.map { "${it.title}: ${it.summary}" }

    fun stateFor(audioRecord: AudioRecord): VoiceProcessingState =
        synchronized(states) {
            states.getOrPut(audioRecord) { VoiceProcessingState() }
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
}
