package com.qwulise.voicechanger.module

import android.content.Context
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract

class VoiceConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): VoiceConfig = VoiceConfig(
        enabled = preferences.getBoolean(VoiceConfigContract.KEY_ENABLED, false),
        modeId = preferences.getString(VoiceConfigContract.KEY_MODE_ID, null)
            ?: VoiceConfig().modeId,
        effectStrength = preferences.getInt(VoiceConfigContract.KEY_EFFECT_STRENGTH, 55),
        micGainPercent = preferences.getInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, 100),
        restrictToTargets = preferences.getBoolean(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, false),
        targetPackages = preferences.getStringSet(VoiceConfigContract.KEY_TARGET_PACKAGES, emptySet()) ?: emptySet(),
    ).sanitized()

    fun write(config: VoiceConfig): VoiceConfig {
        val sanitized = config.sanitized()
        preferences.edit()
            .putBoolean(VoiceConfigContract.KEY_ENABLED, sanitized.enabled)
            .putString(VoiceConfigContract.KEY_MODE_ID, sanitized.modeId)
            .putInt(VoiceConfigContract.KEY_EFFECT_STRENGTH, sanitized.effectStrength)
            .putInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, sanitized.micGainPercent)
            .putBoolean(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, sanitized.restrictToTargets)
            .putStringSet(VoiceConfigContract.KEY_TARGET_PACKAGES, sanitized.targetPackages)
            .apply()
        return sanitized
    }

    fun reset(): VoiceConfig = write(VoiceConfig())

    companion object {
        private const val PREFERENCES_NAME = "voicechanger_module_config"
    }
}
