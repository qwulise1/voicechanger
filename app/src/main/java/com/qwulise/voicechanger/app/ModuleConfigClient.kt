package com.qwulise.voicechanger.app

import android.content.Context
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.ModuleInfo
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract

data class ModuleAvailability(
    val releaseInstalled: Boolean,
    val debugInstalled: Boolean,
    val providerVisible: Boolean,
    val providerCallable: Boolean,
) {
    val packageInstalled: Boolean
        get() = releaseInstalled || debugInstalled

    val isAvailable: Boolean
        get() = providerCallable || providerVisible

    fun describe(): String = buildString {
        append("Пакет: ")
        append(
            when {
                releaseInstalled && debugInstalled -> "release + debug"
                releaseInstalled -> "release"
                debugInstalled -> "debug"
                else -> "не найден"
            },
        )
        append(" • provider: ")
        append(if (providerVisible) "виден" else "не виден")
        append(" • handshake: ")
        append(if (providerCallable) "ok" else "нет ответа")
    }
}

object ModuleConfigClient {
    private const val MODULE_RELEASE_PACKAGE = "com.qwulise.voicechanger.module"
    private const val MODULE_DEBUG_PACKAGE = "com.qwulise.voicechanger.module.debug"

    fun inspect(context: Context): ModuleAvailability {
        val packageManager = context.packageManager
        val providerVisible = packageManager.resolveContentProvider(VoiceConfigContract.AUTHORITY, 0) != null
        val providerCallable = runCatching {
            context.contentResolver.call(
                VoiceConfigContract.CONTENT_URI,
                VoiceConfigContract.METHOD_GET_MODULE_INFO,
                null,
                null,
            ) != null
        }.getOrDefault(false)

        return ModuleAvailability(
            releaseInstalled = isPackageInstalled(packageManager, MODULE_RELEASE_PACKAGE),
            debugInstalled = isPackageInstalled(packageManager, MODULE_DEBUG_PACKAGE),
            providerVisible = providerVisible,
            providerCallable = providerCallable,
        )
    }

    fun isModuleAvailable(context: Context): Boolean =
        inspect(context).isAvailable

    fun recommendedScope(): List<String> = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "com.discord",
        "com.whatsapp",
        "org.thoughtcrime.securesms",
        "org.signal.messenger",
        "com.skype.raider",
    )

    fun load(context: Context): VoiceConfig =
        VoiceConfig.fromBundle(
            context.contentResolver.call(
                VoiceConfigContract.CONTENT_URI,
                VoiceConfigContract.METHOD_GET_CONFIG,
                null,
                null,
            ),
        )

    fun save(context: Context, config: VoiceConfig): VoiceConfig =
        VoiceConfig.fromBundle(
            context.contentResolver.call(
                VoiceConfigContract.CONTENT_URI,
                VoiceConfigContract.METHOD_PUT_CONFIG,
                null,
                config.sanitized().toBundle(),
            ),
        )

    fun reset(context: Context): VoiceConfig =
        VoiceConfig.fromBundle(
            context.contentResolver.call(
                VoiceConfigContract.CONTENT_URI,
                VoiceConfigContract.METHOD_RESET_CONFIG,
                null,
                null,
            ),
        )

    fun loadModuleInfo(context: Context): ModuleInfo =
        ModuleInfo.fromBundle(
            context.contentResolver.call(
                VoiceConfigContract.CONTENT_URI,
                VoiceConfigContract.METHOD_GET_MODULE_INFO,
                null,
                null,
            ),
        )

    fun loadLogs(context: Context): List<DiagnosticEvent> =
        context.contentResolver.call(
            VoiceConfigContract.CONTENT_URI,
            VoiceConfigContract.METHOD_GET_LOGS,
            null,
            null,
        )?.getStringArrayList(VoiceConfigContract.KEY_LOG_LINES)
            ?.mapNotNull(DiagnosticEvent::decode)
            ?.sortedByDescending { it.timestampMs }
            ?: emptyList()

    fun clearLogs(context: Context) {
        context.contentResolver.call(
            VoiceConfigContract.CONTENT_URI,
            VoiceConfigContract.METHOD_CLEAR_LOGS,
            null,
            null,
        )
    }

    fun appendLog(context: Context, event: DiagnosticEvent) {
        context.contentResolver.call(
            VoiceConfigContract.CONTENT_URI,
            VoiceConfigContract.METHOD_APPEND_LOG,
            null,
            event.toBundle(),
        )
    }

    private fun isPackageInstalled(packageManager: android.content.pm.PackageManager, packageName: String): Boolean =
        runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
}

private fun DiagnosticEvent.toBundle(): android.os.Bundle = android.os.Bundle().apply {
    putLong(VoiceConfigContract.KEY_LOG_TIMESTAMP_MS, timestampMs)
    putString(VoiceConfigContract.KEY_LOG_PACKAGE_NAME, packageName)
    putString(VoiceConfigContract.KEY_LOG_SOURCE, source)
    putString(VoiceConfigContract.KEY_LOG_DETAIL, detail)
}
