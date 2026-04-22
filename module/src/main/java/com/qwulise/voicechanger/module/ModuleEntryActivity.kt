package com.qwulise.voicechanger.module

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ModuleEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
        }

        column.addView(title("Voicechanger Module"))
        column.addView(body("LSPosed-модуль уже перехватывает Java-путь AudioRecord и применяет живую обработку PCM по настройкам из companion APK."))
        column.addView(section("Активно сейчас"))
        column.addView(body(HookBridge.activeTargets().joinToString("\n") { "• $it" }))
        column.addView(section("Доступные режимы"))
        column.addView(body(HookBridge.activeProfiles().joinToString("\n") { "• $it" }))
        column.addView(section("Следующий этап"))
        column.addView(body(HookBridge.plannedTargets().joinToString("\n") { "• $it" }))

        root.addView(column)
        setContentView(root)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(typeface, Typeface.BOLD)
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
