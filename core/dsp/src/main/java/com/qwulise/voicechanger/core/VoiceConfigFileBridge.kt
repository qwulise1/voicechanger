package com.qwulise.voicechanger.core

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

object VoiceConfigFileBridge {
    const val CONFIG_PATH = "/data/local/tmp/qwulivoice-config.properties"
    const val LOG_PATH = "/data/local/tmp/qwulivoice-events.log"

    fun configPathFor(packageName: String): String =
        "/data/local/tmp/qwulivoice-${safeName(packageName)}.properties"

    fun logPathFor(packageName: String): String =
        "/data/local/tmp/qwulivoice-${safeName(packageName)}.events.log"

    fun encodeConfig(config: VoiceConfig): String {
        val sanitized = config.sanitized()
        val properties = Properties().apply {
            setProperty(VoiceConfigContract.KEY_ENABLED, sanitized.enabled.toString())
            setProperty(VoiceConfigContract.KEY_MODE_ID, sanitized.mode.id)
            setProperty(VoiceConfigContract.KEY_EFFECT_STRENGTH, sanitized.effectStrength.toString())
            setProperty(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, sanitized.micGainPercent.toString())
            setProperty(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, sanitized.restrictToTargets.toString())
            setProperty(VoiceConfigContract.KEY_TARGET_PACKAGES, sanitized.targetPackages.joinToString(","))
            setProperty(VoiceConfigContract.KEY_VENDOR_HAL_ENABLED, sanitized.vendorHalEnabled.toString())
            setProperty(VoiceConfigContract.KEY_VENDOR_HAL_PARAM, sanitized.vendorHalParam)
            setProperty(VoiceConfigContract.KEY_VENDOR_HAL_LOOPBACK, sanitized.vendorHalLoopback.toString())
        }
        return StringWriter().also { writer ->
            properties.store(writer, "qwulivoice root bridge config")
        }.toString()
    }

    fun decodeConfig(raw: String): VoiceConfig {
        val properties = Properties().apply {
            load(StringReader(raw))
        }
        return VoiceConfig(
            enabled = properties.boolean(VoiceConfigContract.KEY_ENABLED, false),
            modeId = properties.getProperty(VoiceConfigContract.KEY_MODE_ID) ?: VoiceMode.default.id,
            effectStrength = properties.int(VoiceConfigContract.KEY_EFFECT_STRENGTH, 85),
            micGainPercent = properties.int(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, 0),
            restrictToTargets = properties.boolean(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, false),
            targetPackages = properties.getProperty(VoiceConfigContract.KEY_TARGET_PACKAGES)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet(),
            vendorHalEnabled = properties.boolean(VoiceConfigContract.KEY_VENDOR_HAL_ENABLED, false),
            vendorHalParam = properties.getProperty(VoiceConfigContract.KEY_VENDOR_HAL_PARAM)
                ?: VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = properties.boolean(VoiceConfigContract.KEY_VENDOR_HAL_LOOPBACK, false),
        ).sanitized()
    }

    fun readConfigFile(path: String = CONFIG_PATH): VoiceConfig? =
        runCatching {
            val file = File(path)
            if (!file.isFile) {
                null
            } else {
                decodeConfig(file.readText())
            }
        }.getOrNull()

    fun readEventFile(path: String = LOG_PATH): List<DiagnosticEvent> =
        runCatching {
            val file = File(path)
            if (!file.isFile) {
                emptyList()
            } else {
                file.readLines().mapNotNull(DiagnosticEvent::decode)
            }
        }.getOrDefault(emptyList())

    fun appendEventFile(event: DiagnosticEvent, path: String = LOG_PATH): Boolean =
        runCatching {
            File(path).appendText(event.encode() + "\n")
        }.isSuccess

    private fun safeName(packageName: String): String =
        packageName.ifBlank { "default" }
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }
            .joinToString("")

    private fun Properties.boolean(key: String, default: Boolean): Boolean =
        getProperty(key)?.equals("true", ignoreCase = true) ?: default

    private fun Properties.int(key: String, default: Int): Int =
        getProperty(key)?.toIntOrNull() ?: default
}
