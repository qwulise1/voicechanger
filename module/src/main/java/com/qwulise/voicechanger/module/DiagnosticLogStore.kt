package com.qwulise.voicechanger.module

import android.content.Context
import com.qwulise.voicechanger.core.DiagnosticEvent

class DiagnosticLogStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<DiagnosticEvent> =
        preferences.getString(KEY_LOG_DATA, null)
            .orEmpty()
            .split(RECORD_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull(DiagnosticEvent::decode)
            .sortedByDescending { it.timestampMs }

    fun append(event: DiagnosticEvent) {
        val next = (listOf(event) + read())
            .distinctBy { "${it.timestampMs}:${it.packageName}:${it.source}:${it.detail}" }
            .sortedByDescending { it.timestampMs }
            .take(MAX_RECORDS)
            .joinToString(RECORD_SEPARATOR) { it.encode() }

        preferences.edit()
            .putString(KEY_LOG_DATA, next)
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_LOG_DATA)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "voicechanger_module_logs"
        private const val KEY_LOG_DATA = "log_data"
        private const val RECORD_SEPARATOR = "\u001e"
        private const val MAX_RECORDS = 80
    }
}
