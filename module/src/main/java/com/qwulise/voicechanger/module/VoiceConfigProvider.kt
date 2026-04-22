package com.qwulise.voicechanger.module

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract

class VoiceConfigProvider : ContentProvider() {
    private val store: VoiceConfigStore by lazy {
        VoiceConfigStore(requireNotNull(context))
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle =
        when (method) {
            VoiceConfigContract.METHOD_GET_CONFIG -> store.read().toBundle()
            VoiceConfigContract.METHOD_PUT_CONFIG -> store.write(VoiceConfig.fromBundle(extras)).toBundle()
            VoiceConfigContract.METHOD_RESET_CONFIG -> store.reset().toBundle()
            else -> super.call(method, arg, extras) ?: android.os.Bundle.EMPTY
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val config = store.read()
        return MatrixCursor(
            arrayOf(
                VoiceConfigContract.KEY_ENABLED,
                VoiceConfigContract.KEY_MODE_ID,
                VoiceConfigContract.KEY_EFFECT_STRENGTH,
                VoiceConfigContract.KEY_MIC_GAIN_PERCENT,
            ),
        ).apply {
            addRow(
                arrayOf(
                    if (config.enabled) 1 else 0,
                    config.modeId,
                    config.effectStrength,
                    config.micGainPercent,
                ),
            )
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.${VoiceConfigContract.AUTHORITY}.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
