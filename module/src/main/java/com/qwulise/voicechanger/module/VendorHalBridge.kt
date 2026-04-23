package com.qwulise.voicechanger.module

import android.content.Context
import android.media.AudioManager
import com.qwulise.voicechanger.core.VoiceConfig
import de.robv.android.xposed.XposedBridge
import java.util.Collections

object VendorHalBridge {
    private const val KEY_CURRENT_GAME_PACKAGE = "currentGamePackageName="
    private const val KEY_OPLUS_MAGIC_VOICE = "oplusmagicvoiceinfo="
    private const val KEY_CLEAR_MAGIC_VOICE = "clearMagicVoiceInfo=true"
    private const val KEY_LOOPBACK_PACKAGE = "magicvoiceloopbackpackage="
    private const val KEY_LOOPBACK_ENABLE = "magicvoiceloopbackenable="
    private const val MIN_APPLY_INTERVAL_MS = 2_500L

    private val lastAppliedAt = Collections.synchronizedMap(mutableMapOf<String, Long>())

    fun applyIfConfigured(
        packageName: String,
        config: VoiceConfig,
        reason: String,
        force: Boolean = false,
    ) {
        if (BuildConfig.APPLICATION_ID.contains(".clean")) {
            return
        }
        if (!config.vendorHalEnabled) {
            return
        }
        val param = config.vendorHalParam.trim()
        if (param.isEmpty()) {
            report(packageName, "oplus-hal", "Skipped $reason: empty vendorHalParam", minIntervalMs = 15_000L)
            return
        }

        val now = System.currentTimeMillis()
        val key = "$packageName|$param|${config.vendorHalLoopback}"
        val previous = synchronized(lastAppliedAt) { lastAppliedAt[key] }
        if (!force && previous != null && now - previous < MIN_APPLY_INTERVAL_MS) {
            return
        }

        val context = ProcessContextResolver.resolve()
        if (context == null) {
            report(packageName, "oplus-hal", "Skipped $reason: no process context", minIntervalMs = 15_000L)
            return
        }

        runCatching {
            val audioManager = requireNotNull(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) {
                "AudioManager unavailable"
            }
            val commands = buildCommands(packageName, param, config.vendorHalLoopback)
            commands.forEach(audioManager::setParameters)
            synchronized(lastAppliedAt) {
                lastAppliedAt[key] = now
            }
            report(
                packageName = packageName,
                source = "oplus-hal",
                detail = "Applied from target process reason=$reason commands=${commands.joinToString(";")}",
                minIntervalMs = 8_000L,
            )
        }.onFailure {
            XposedBridge.log("Voicechanger: OPlus HAL apply failed in $packageName: $it")
            report(
                packageName = packageName,
                source = "oplus-hal-error",
                detail = "Failed $reason: ${it.message ?: it::class.java.simpleName}",
                minIntervalMs = 8_000L,
            )
        }
    }

    fun clear(packageName: String, reason: String) {
        val context = ProcessContextResolver.resolve() ?: return
        runCatching {
            val audioManager = requireNotNull(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            val commands = listOf(
                KEY_CLEAR_MAGIC_VOICE,
                KEY_CURRENT_GAME_PACKAGE + "null",
                KEY_LOOPBACK_PACKAGE,
                KEY_LOOPBACK_ENABLE + "0",
            )
            commands.forEach(audioManager::setParameters)
            report(
                packageName = packageName,
                source = "oplus-hal",
                detail = "Cleared from target process reason=$reason commands=${commands.joinToString(";")}",
                minIntervalMs = 8_000L,
            )
        }
    }

    private fun buildCommands(packageName: String, param: String, enableLoopback: Boolean): List<String> =
        buildList {
            add(KEY_CURRENT_GAME_PACKAGE + packageName)
            add(KEY_OPLUS_MAGIC_VOICE + param + "|" + packageName + "|true")
            if (enableLoopback) {
                add(KEY_LOOPBACK_PACKAGE)
                add(KEY_LOOPBACK_ENABLE + "0")
                add(KEY_LOOPBACK_PACKAGE + packageName)
                add(KEY_LOOPBACK_ENABLE + "1")
            }
        }

    private fun report(
        packageName: String,
        source: String,
        detail: String,
        minIntervalMs: Long,
    ) {
        NativeAudioBridge.reportNativeEvent(
            packageName = packageName,
            source = source,
            detail = detail,
            force = false,
            rateKey = "$packageName|$source|$detail",
            minIntervalMs = minIntervalMs,
        )
    }
}
