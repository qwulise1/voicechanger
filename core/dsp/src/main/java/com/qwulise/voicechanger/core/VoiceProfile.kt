package com.qwulise.voicechanger.core

data class VoiceProfile(
    val id: String,
    val name: String,
    val summary: String,
)

object VoiceProfileCatalog {
    val defaultProfiles: List<VoiceProfile> = listOf(
        VoiceProfile("child", "Child", "Raised pitch with brighter color"),
        VoiceProfile("robot", "Robot", "Hard modulation and metallic tone"),
        VoiceProfile("deep", "Deep", "Lower pitch with denser body"),
        VoiceProfile("cave", "Cave", "Voice with cavern-style reverb tail"),
        VoiceProfile("boost", "Mic Boost", "Independent loudness drive layered over any voice"),
    )
}

