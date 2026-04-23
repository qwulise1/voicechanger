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
        effectStrength = preferences.getInt(VoiceConfigContract.KEY_EFFECT_STRENGTH, 85),
        micGainPercent = preferences.getInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, 0),
        restrictToTargets = preferences.getBoolean(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, false),
        targetPackages = preferences.getStringSet(VoiceConfigContract.KEY_TARGET_PACKAGES, emptySet()) ?: emptySet(),
        vendorHalEnabled = preferences.getBoolean(VoiceConfigContract.KEY_VENDOR_HAL_ENABLED, false),
        vendorHalParam = preferences.getString(VoiceConfigContract.KEY_VENDOR_HAL_PARAM, null)
            ?: VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
        vendorHalLoopback = preferences.getBoolean(VoiceConfigContract.KEY_VENDOR_HAL_LOOPBACK, false),
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
            .putBoolean(VoiceConfigContract.KEY_VENDOR_HAL_ENABLED, sanitized.vendorHalEnabled)
            .putString(VoiceConfigContract.KEY_VENDOR_HAL_PARAM, sanitized.vendorHalParam)
            .putBoolean(VoiceConfigContract.KEY_VENDOR_HAL_LOOPBACK, sanitized.vendorHalLoopback)
            .apply()
        return sanitized
    }

    fun reset(): VoiceConfig = write(VoiceConfig())

    companion object {
        private const val PREFERENCES_NAME = "qwulivoice_module_config"
    }
}
