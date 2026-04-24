package com.qwulise.voicechanger.app

import android.content.Context

enum class UiThemeMode(val id: String, val title: String) {
    SYSTEM("system", "Системная"),
    LIGHT("light", "Светлая"),
    DARK("dark", "Темная"),
    ;

    companion object {
        fun fromId(id: String?): UiThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class UiAccentPreset(
    val id: String,
    val title: String,
    val accentLight: Int,
    val accentDark: Int,
    val altLight: Int,
    val altDark: Int,
) {
    GOLD(
        id = "gold",
        title = "Gold",
        accentLight = 0xFFD29B22.toInt(),
        accentDark = 0xFFF0BE36.toInt(),
        altLight = 0xFF0FBEC9.toInt(),
        altDark = 0xFF25D1D8.toInt(),
    ),
    GREEN(
        id = "green",
        title = "Green",
        accentLight = 0xFF57A94B.toInt(),
        accentDark = 0xFF8FD266.toInt(),
        altLight = 0xFF0EBAA5.toInt(),
        altDark = 0xFF35D0B8.toInt(),
    ),
    CYAN(
        id = "cyan",
        title = "Cyan",
        accentLight = 0xFF1595C7.toInt(),
        accentDark = 0xFF36B4E8.toInt(),
        altLight = 0xFF15C9D1.toInt(),
        altDark = 0xFF48E1E8.toInt(),
    ),
    ROSE(
        id = "rose",
        title = "Rose",
        accentLight = 0xFFD46A86.toInt(),
        accentDark = 0xFFF37A9A.toInt(),
        altLight = 0xFFE08B43.toInt(),
        altDark = 0xFFFFAE5E.toInt(),
    ),
    ;

    companion object {
        fun fromId(id: String?): UiAccentPreset =
            entries.firstOrNull { it.id == id } ?: GOLD
    }
}

data class UiSettings(
    val themeMode: UiThemeMode = UiThemeMode.SYSTEM,
    val accentPresetId: String = UiAccentPreset.GOLD.id,
    val useMonet: Boolean = false,
) {
    val accentPreset: UiAccentPreset
        get() = UiAccentPreset.fromId(accentPresetId)
}

object UiSettingsStore {
    private const val PREFS_NAME = "qwulivoice_ui_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ACCENT_PRESET = "accent_preset"
    private const val KEY_USE_MONET = "use_monet"

    fun read(context: Context): UiSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UiSettings(
            themeMode = UiThemeMode.fromId(prefs.getString(KEY_THEME_MODE, UiThemeMode.SYSTEM.id)),
            accentPresetId = prefs.getString(KEY_ACCENT_PRESET, UiAccentPreset.GOLD.id).orEmpty(),
            useMonet = prefs.getBoolean(KEY_USE_MONET, false),
        )
    }

    fun write(context: Context, settings: UiSettings): UiSettings {
        val sanitized = settings.copy(
            themeMode = UiThemeMode.fromId(settings.themeMode.id),
            accentPresetId = settings.accentPreset.id,
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, sanitized.themeMode.id)
            .putString(KEY_ACCENT_PRESET, sanitized.accentPreset.id)
            .putBoolean(KEY_USE_MONET, sanitized.useMonet)
            .apply()
        return sanitized
    }
}
