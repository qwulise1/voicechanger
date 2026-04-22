package com.qwulise.voicechanger.module

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract

class VoiceConfigProvider : ContentProvider() {
    private val store: VoiceConfigStore by lazy {
        VoiceConfigStore(requireNotNull(context))
    }
    private val logStore: DiagnosticLogStore by lazy {
        DiagnosticLogStore(requireNotNull(context))
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        when (method) {
            VoiceConfigContract.METHOD_GET_CONFIG -> store.read().toBundle()
            VoiceConfigContract.METHOD_PUT_CONFIG -> store.write(VoiceConfig.fromBundle(extras)).toBundle()
            VoiceConfigContract.METHOD_RESET_CONFIG -> store.reset().toBundle()
            VoiceConfigContract.METHOD_GET_LOGS -> Bundle().apply {
                putStringArrayList(
                    VoiceConfigContract.KEY_LOG_LINES,
                    ArrayList(logStore.read().map { it.encode() }),
                )
            }
            VoiceConfigContract.METHOD_CLEAR_LOGS -> Bundle().apply {
                logStore.clear()
            }
            VoiceConfigContract.METHOD_APPEND_LOG -> Bundle().apply {
                val event = DiagnosticEvent(
                    timestampMs = extras?.getLong(VoiceConfigContract.KEY_LOG_TIMESTAMP_MS)
                        ?: System.currentTimeMillis(),
                    packageName = extras?.getString(VoiceConfigContract.KEY_LOG_PACKAGE_NAME).orEmpty(),
                    source = extras?.getString(VoiceConfigContract.KEY_LOG_SOURCE).orEmpty(),
                    detail = extras?.getString(VoiceConfigContract.KEY_LOG_DETAIL).orEmpty(),
                )
                if (event.packageName.isNotBlank() && event.source.isNotBlank()) {
                    logStore.append(event)
                }
            }
            else -> super.call(method, arg, extras) ?: Bundle.EMPTY
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
                VoiceConfigContract.KEY_RESTRICT_TO_TARGETS,
                VoiceConfigContract.KEY_TARGET_PACKAGES,
            ),
        ).apply {
            addRow(
                arrayOf(
                    if (config.enabled) 1 else 0,
                    config.modeId,
                    config.effectStrength,
                    config.micGainPercent,
                    if (config.restrictToTargets) 1 else 0,
                    config.targetPackages.joinToString(","),
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
