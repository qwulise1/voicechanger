package com.qwulise.voicechanger.module

import com.qwulise.voicechanger.core.VoiceProfileCatalog

object HookBridge {
    fun plannedTargets(): List<String> = listOf(
        "AudioRecord.read(...)",
        "AAudio / Oboe native input path",
        "WebRTC voice capture path",
        "Selected app-specific native pipelines",
    )

    fun plannedProfiles(): List<String> = VoiceProfileCatalog.defaultProfiles.map { it.name }
}

