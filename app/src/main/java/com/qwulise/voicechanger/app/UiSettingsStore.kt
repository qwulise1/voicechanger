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
        title = "Золото",
        accentLight = 0xFFD29B22.toInt(),
        accentDark = 0xFFF0BE36.toInt(),
        altLight = 0xFF0FBEC9.toInt(),
        altDark = 0xFF25D1D8.toInt(),
    ),
    GREEN(
        id = "green",
        title = "Лайм",
        accentLight = 0xFF57A94B.toInt(),
        accentDark = 0xFF8FD266.toInt(),
        altLight = 0xFF0EBAA5.toInt(),
        altDark = 0xFF35D0B8.toInt(),
    ),
    CYAN(
        id = "cyan",
        title = "Nord",
        accentLight = 0xFF5E81AC.toInt(),
        accentDark = 0xFF81A1C1.toInt(),
        altLight = 0xFF88C0D0.toInt(),
        altDark = 0xFF8FBCBB.toInt(),
    ),
    ROSE(
        id = "rose",
        title = "Роза",
        accentLight = 0xFFD46A86.toInt(),
        accentDark = 0xFFF37A9A.toInt(),
        altLight = 0xFFE08B43.toInt(),
        altDark = 0xFFFFAE5E.toInt(),
    ),
    MINT(
        id = "mint",
        title = "Мята",
        accentLight = 0xFF27AF8B.toInt(),
        accentDark = 0xFF56D1AB.toInt(),
        altLight = 0xFF1793B0.toInt(),
        altDark = 0xFF42B7D4.toInt(),
    ),
    OCEAN(
        id = "ocean",
        title = "Океан",
        accentLight = 0xFF2369C9.toInt(),
        accentDark = 0xFF4F93F2.toInt(),
        altLight = 0xFF12B8C8.toInt(),
        altDark = 0xFF4CE0E8.toInt(),
    ),
    SUNSET(
        id = "sunset",
        title = "Закат",
        accentLight = 0xFFE06A2F.toInt(),
        accentDark = 0xFFFF8A4F.toInt(),
        altLight = 0xFFDA4167.toInt(),
        altDark = 0xFFFF7196.toInt(),
    ),
    VIOLET(
        id = "violet",
        title = "Catppuccin",
        accentLight = 0xFF8839EF.toInt(),
        accentDark = 0xFFCBA6F7.toInt(),
        altLight = 0xFF209FB5.toInt(),
        altDark = 0xFF74C7EC.toInt(),
    ),
    RUBY(
        id = "ruby",
        title = "Dracula",
        accentLight = 0xFF644AC9.toInt(),
        accentDark = 0xFFBD93F9.toInt(),
        altLight = 0xFF036A96.toInt(),
        altDark = 0xFF8BE9FD.toInt(),
    ),
    ICEBERG(
        id = "iceberg",
        title = "Tokyo",
        accentLight = 0xFF2959AA.toInt(),
        accentDark = 0xFF7AA2F7.toInt(),
        altLight = 0xFF5A3E8E.toInt(),
        altDark = 0xFFBB9AF7.toInt(),
    ),
    GRAPHITE(
        id = "graphite",
        title = "Gruvbox",
        accentLight = 0xFF458588.toInt(),
        accentDark = 0xFF83A598.toInt(),
        altLight = 0xFFB16286.toInt(),
        altDark = 0xFFD3869B.toInt(),
    ),
    STEEL(
        id = "steel",
        title = "Сталь",
        accentLight = 0xFF66768C.toInt(),
        accentDark = 0xFF95A7BF.toInt(),
        altLight = 0xFF3BA69B.toInt(),
        altDark = 0xFF63CDC0.toInt(),
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
    val overlayOpacityPercent: Int = 82,
    val overlaySizePercent: Int = 100,
) {
    val accentPreset: UiAccentPreset
        get() = UiAccentPreset.fromId(accentPresetId)
}

object UiSettingsStore {
    const val OVERLAY_OPACITY_MIN = 1
    const val OVERLAY_OPACITY_MAX = 100
    const val OVERLAY_SIZE_MIN = 10
    const val OVERLAY_SIZE_MAX = 160

    private const val PREFS_NAME = "qwulivoice_ui_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ACCENT_PRESET = "accent_preset"
    private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
    private const val KEY_OVERLAY_SIZE = "overlay_size"

    fun read(context: Context): UiSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UiSettings(
            themeMode = UiThemeMode.fromId(prefs.getString(KEY_THEME_MODE, UiThemeMode.SYSTEM.id)),
            accentPresetId = prefs.getString(KEY_ACCENT_PRESET, UiAccentPreset.GOLD.id).orEmpty(),
            overlayOpacityPercent = prefs.getInt(KEY_OVERLAY_OPACITY, 82).coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX),
            overlaySizePercent = prefs.getInt(KEY_OVERLAY_SIZE, 100).coerceIn(OVERLAY_SIZE_MIN, OVERLAY_SIZE_MAX),
        )
    }

    fun write(context: Context, settings: UiSettings): UiSettings {
        val sanitized = settings.copy(
            themeMode = UiThemeMode.fromId(settings.themeMode.id),
            accentPresetId = settings.accentPreset.id,
            overlayOpacityPercent = settings.overlayOpacityPercent.coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX),
            overlaySizePercent = settings.overlaySizePercent.coerceIn(OVERLAY_SIZE_MIN, OVERLAY_SIZE_MAX),
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, sanitized.themeMode.id)
            .putString(KEY_ACCENT_PRESET, sanitized.accentPreset.id)
            .putInt(KEY_OVERLAY_OPACITY, sanitized.overlayOpacityPercent)
            .putInt(KEY_OVERLAY_SIZE, sanitized.overlaySizePercent)
            .apply()
        return sanitized
    }
}
