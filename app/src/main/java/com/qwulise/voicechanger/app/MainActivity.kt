package com.qwulise.voicechanger.app

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qwulise.voicechanger.core.VoiceProfileCatalog

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
        }

        column.addView(title("Voicechanger Companion"))
        column.addView(body("Этот APK будет управлять root-модулем, профилями, логами и целевыми приложениями."))
        column.addView(section("Что уже есть"))
        column.addView(body("• Базовая архитектура app + module + core\n• GitHub Actions для облачной сборки\n• Shared каталог голосовых профилей"))
        column.addView(section("Профили DSP"))
        column.addView(body(VoiceProfileCatalog.defaultProfiles.joinToString("\n") { "• ${it.name}: ${it.summary}" }))
        column.addView(section("Следующий этап"))
        column.addView(body("Подключение настроек, root-хуков и аудиопайплайна поверх перехвата микрофона."))

        root.addView(column)
        setContentView(root)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
