package com.qwulise.voicechanger.app

import android.content.Context
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract

object ModuleConfigClient {
    fun isModuleAvailable(context: Context): Boolean =
        context.packageManager.resolveContentProvider(VoiceConfigContract.AUTHORITY, 0) != null

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
}
