package com.qwulise.voicechanger.module

import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigFileBridge

object ModuleFileBridge {
    private val configPath: String = VoiceConfigFileBridge.configPathFor(BuildConfig.APPLICATION_ID)
    private val logPath: String = VoiceConfigFileBridge.logPathFor(BuildConfig.APPLICATION_ID)

    fun readConfig(): VoiceConfig? =
        VoiceConfigFileBridge.readConfigFile(configPath) ?: VoiceConfigFileBridge.readConfigFile()

    fun appendEvent(event: DiagnosticEvent): Boolean =
        VoiceConfigFileBridge.appendEventFile(event, logPath) ||
            VoiceConfigFileBridge.appendEventFile(event)
}
