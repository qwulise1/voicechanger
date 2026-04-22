package com.qwulise.voicechanger.app

import android.content.Context
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.ModuleInfo
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract

object ModuleConfigClient {
    fun isModuleAvailable(context: Context): Boolean =
        context.packageManager.resolveContentProvider(VoiceConfigContract.AUTHORITY, 0) != null ||
            runCatching {
                context.contentResolver.call(
                    VoiceConfigContract.CONTENT_URI,
                    VoiceConfigContract.METHOD_GET_MODULE_INFO,
                    null,
                    null,
                ) != null
            }.getOrDefault(false)

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
}
