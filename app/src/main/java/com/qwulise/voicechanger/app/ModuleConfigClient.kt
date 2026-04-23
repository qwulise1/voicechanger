package com.qwulise.voicechanger.app

import android.content.Context
import android.net.Uri
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
    private const val MODULE_RELEASE_PACKAGE = "com.qwulise.voicechanger.module.clean"
    private const val MODULE_DEBUG_PACKAGE = "com.qwulise.voicechanger.module.clean.debug"

    fun inspect(context: Context): ModuleAvailability {
        val packageManager = context.packageManager
        val providerVisible = providerUris(context).any { uri ->
            packageManager.resolveContentProvider(uri.authority.orEmpty(), 0) != null
        }
        val providerCallable = runCatching {
            callProvider(
                context,
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
        runCatching {
            VoiceConfig.fromBundle(
                requireNotNull(callProvider(
                    context,
                    VoiceConfigContract.METHOD_GET_CONFIG,
                    null,
                    null,
                )) { "provider returned null config" },
            )
        }.getOrElse {
            RootConfigPublisher.readRootConfig(context.packageName) ?: throw it
        }

    fun save(context: Context, config: VoiceConfig): VoiceConfig {
        val sanitized = config.sanitized()
        val providerResult = runCatching {
            VoiceConfig.fromBundle(
                requireNotNull(callProvider(
                    context,
                    VoiceConfigContract.METHOD_PUT_CONFIG,
                    null,
                    sanitized.toBundle(),
                )) { "provider returned null after save" },
            )
        }
        val rootResult = runCatching {
            RootConfigPublisher.publishConfig(context.packageName, providerResult.getOrNull() ?: sanitized)
        }
        rootResult.getOrThrow()
        return providerResult.getOrElse { sanitized }
    }

    fun reset(context: Context): VoiceConfig {
        val providerResult = runCatching {
            VoiceConfig.fromBundle(
                requireNotNull(callProvider(
                    context,
                    VoiceConfigContract.METHOD_RESET_CONFIG,
                    null,
                    null,
                )) { "provider returned null after reset" },
            )
        }
        val resetConfig = providerResult.getOrNull() ?: VoiceConfig()
        RootConfigPublisher.publishConfig(context.packageName, resetConfig)
        return resetConfig
    }

    fun loadModuleInfo(context: Context): ModuleInfo =
        ModuleInfo.fromBundle(
            requireNotNull(callProvider(
                context,
                VoiceConfigContract.METHOD_GET_MODULE_INFO,
                null,
                null,
            )) { "provider returned null module info" },
        )

    fun loadLogs(context: Context): List<DiagnosticEvent> {
        val providerLogs = runCatching {
            callProvider(
                context,
                VoiceConfigContract.METHOD_GET_LOGS,
                null,
                null,
            )?.getStringArrayList(VoiceConfigContract.KEY_LOG_LINES)
                ?.mapNotNull(DiagnosticEvent::decode)
                ?: emptyList()
        }.getOrDefault(emptyList())
        return (providerLogs + RootConfigPublisher.readRootLogs(context.packageName))
            .distinctBy { "${it.timestampMs}|${it.packageName}|${it.source}|${it.detail}" }
            .sortedByDescending { it.timestampMs }
    }

    fun clearLogs(context: Context) {
        runCatching {
            callProvider(
                context,
                VoiceConfigContract.METHOD_CLEAR_LOGS,
                null,
                null,
            )
        }
        RootConfigPublisher.clearLogs(context.packageName)
    }

    fun appendLog(context: Context, event: DiagnosticEvent) {
        runCatching {
            callProvider(
                context,
                VoiceConfigContract.METHOD_APPEND_LOG,
                null,
                event.toBundle(),
            )
        }
        RootConfigPublisher.appendRootLog(context.packageName, event)
    }

    private fun isPackageInstalled(packageManager: android.content.pm.PackageManager, packageName: String): Boolean =
        runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess

    private fun callProvider(
        context: Context,
        method: String,
        arg: String?,
        extras: android.os.Bundle?,
    ): android.os.Bundle? {
        var lastError: Throwable? = null
        providerUris(context).forEach { uri ->
            runCatching {
                context.contentResolver.call(uri, method, arg, extras)
            }.onSuccess { result ->
                if (result != null) {
                    return result
                }
            }.onFailure {
                lastError = it
            }
        }
        lastError?.let { throw it }
        return null
    }

    private fun providerUris(context: Context): List<Uri> {
        val selfUri = Uri.parse("content://${context.packageName}.config/config")
        return listOf(selfUri, VoiceConfigContract.CONTENT_URI).distinctBy { it.authority }
    }
}

private fun DiagnosticEvent.toBundle(): android.os.Bundle = android.os.Bundle().apply {
    putLong(VoiceConfigContract.KEY_LOG_TIMESTAMP_MS, timestampMs)
    putString(VoiceConfigContract.KEY_LOG_PACKAGE_NAME, packageName)
    putString(VoiceConfigContract.KEY_LOG_SOURCE, source)
    putString(VoiceConfigContract.KEY_LOG_DETAIL, detail)
}
