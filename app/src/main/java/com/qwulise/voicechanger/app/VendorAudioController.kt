package com.qwulise.voicechanger.app

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager

data class VendorAudioStatus(
    val hasOplusMagicVoiceFeature: Boolean,
    val hasOplusGamespaceFeature: Boolean,
    val vendorVoiceChangeSupport: String,
    val vendorVoiceChangeVersion: String,
    val vendorYoumeSupport: String,
    val metaAudioSupport: String,
) {
    val likelySupported: Boolean
        get() = hasOplusMagicVoiceFeature ||
            hasOplusGamespaceFeature ||
            vendorVoiceChangeSupport.equals("true", ignoreCase = true)

    fun describe(): String = buildString {
        append("OPlus HAL: ")
        append(if (likelySupported) "похож на доступный" else "явных флагов нет")
        append("\nfeature magicvoice: $hasOplusMagicVoiceFeature")
        append("\nfeature gamespace voicechange: $hasOplusGamespaceFeature")
        append("\nro.vendor.audio.voice.change.support: $vendorVoiceChangeSupport")
        append("\nro.vendor.audio.voice.change.version: $vendorVoiceChangeVersion")
        append("\nro.vendor.audio.voice.change.youme.support: $vendorYoumeSupport")
        append("\nro.oplus.audio.support.meta_audio: $metaAudioSupport")
    }
}

object VendorAudioController {
    const val DEFAULT_TARGET_PACKAGE = "org.telegram.messenger.beta"
    const val DEFAULT_OPLUS_ELECTRIC_PARAM =
        "HTz5CcMNnLwx0cokMdR3tGT0F7Eh4=c0xwLnNMcC5zCGxKR8UEvAhLwx0cuA"

    private const val KEY_CURRENT_GAME_PACKAGE = "currentGamePackageName="
    private const val KEY_OPLUS_MAGIC_VOICE = "oplusmagicvoiceinfo="
    private const val KEY_CLEAR_MAGIC_VOICE = "clearMagicVoiceInfo=true"
    private const val KEY_LOOPBACK_PACKAGE = "magicvoiceloopbackpackage="
    private const val KEY_LOOPBACK_ENABLE = "magicvoiceloopbackenable="
    private const val KEY_TRACK_VOLUME = "OPLUS_AUDIO_SET_TRACKVOLUME:"

    fun inspect(context: Context): VendorAudioStatus {
        val packageManager = context.packageManager
        return VendorAudioStatus(
            hasOplusMagicVoiceFeature = OPLUS_MAGICVOICE_FEATURES.any(packageManager::hasSystemFeature),
            hasOplusGamespaceFeature = OPLUS_GAMESPACE_FEATURES.any(packageManager::hasSystemFeature),
            vendorVoiceChangeSupport = systemProperty("ro.vendor.audio.voice.change.support"),
            vendorVoiceChangeVersion = systemProperty("ro.vendor.audio.voice.change.version"),
            vendorYoumeSupport = systemProperty("ro.vendor.audio.voice.change.youme.support"),
            metaAudioSupport = systemProperty("ro.oplus.audio.support.meta_audio"),
        )
    }

    fun applyOplusMagicVoice(
        context: Context,
        targetPackage: String,
        voiceParam: String,
        enableLoopback: Boolean,
    ): List<String> {
        val packageName = targetPackage.trim()
        val param = voiceParam.trim()
        require(packageName.isNotEmpty()) { "package пустой" }
        require(param.isNotEmpty()) { "voice-param пустой" }

        val audioManager = context.audioManager()
        val commands = mutableListOf<String>()
        fun send(command: String) {
            audioManager.setParameters(command)
            commands += command
        }

        send(KEY_CURRENT_GAME_PACKAGE + packageName)
        send(KEY_OPLUS_MAGIC_VOICE + param + "|" + packageName + "|true")
        if (enableLoopback) {
            send(KEY_LOOPBACK_PACKAGE)
            send(KEY_LOOPBACK_ENABLE + "0")
            send(KEY_LOOPBACK_PACKAGE + packageName)
            send(KEY_LOOPBACK_ENABLE + "1")
        }
        return commands
    }

    fun clearOplusMagicVoice(context: Context): List<String> {
        val audioManager = context.audioManager()
        val commands = listOf(
            KEY_CLEAR_MAGIC_VOICE,
            KEY_CURRENT_GAME_PACKAGE + "null",
            KEY_LOOPBACK_PACKAGE,
            KEY_LOOPBACK_ENABLE + "0",
        )
        commands.forEach(audioManager::setParameters)
        return commands
    }

    fun setOplusTrackVolume(context: Context, targetPackage: String, gain: Float): String {
        val packageName = targetPackage.trim()
        require(packageName.isNotEmpty()) { "package пустой" }
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        val command = KEY_TRACK_VOLUME + gain.coerceIn(0f, 2f) + ":" + uid
        context.audioManager().setParameters(command)
        return command
    }

    private fun Context.audioManager(): AudioManager =
        requireNotNull(getSystemService(Context.AUDIO_SERVICE) as? AudioManager) {
            "AudioManager недоступен"
        }

    private fun systemProperty(key: String): String =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "unset") as String
        }.getOrDefault("unreadable")

    private val OPLUS_MAGICVOICE_FEATURES = listOf(
        "oplus.software.audio.magicvoice_v2_basic_support",
        "oplus.software.audio.magicvoice_v2.1_basic_support",
        "oplus.software.audio.magicvoice_support",
        "oplus.software.audio.magicvoice_loopback_support",
    )

    private val OPLUS_GAMESPACE_FEATURES = listOf(
        "oplus.gamespace.voicechange.support",
        "oppo.gamespace.voicechange.support",
    )
}
