package com.qwulise.voicechanger.module

import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.SoundpadFileBridge
import com.qwulise.voicechanger.core.SoundpadLibrary
import com.qwulise.voicechanger.core.SoundpadPlayback
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigFileBridge

object ModuleFileBridge {
    private val configPath: String = VoiceConfigFileBridge.configPathFor(BuildConfig.APPLICATION_ID)
    private val logPath: String = VoiceConfigFileBridge.logPathFor(BuildConfig.APPLICATION_ID)
    private val soundpadLibraryPath: String = SoundpadFileBridge.libraryPathFor(BuildConfig.APPLICATION_ID)
    private val soundpadPlaybackPath: String = SoundpadFileBridge.playbackPathFor(BuildConfig.APPLICATION_ID)

    fun readConfig(): VoiceConfig? =
        VoiceConfigFileBridge.readConfigFile(configPath)

    fun readSoundpadLibrary(): SoundpadLibrary? =
        SoundpadFileBridge.readLibraryFile(soundpadLibraryPath)

    fun readSoundpadPlayback(): SoundpadPlayback? =
        SoundpadFileBridge.readPlaybackFile(soundpadPlaybackPath)

    fun appendEvent(event: DiagnosticEvent): Boolean =
        VoiceConfigFileBridge.appendEventFile(event, logPath)
}
