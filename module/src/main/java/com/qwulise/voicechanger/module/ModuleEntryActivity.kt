package com.qwulise.voicechanger.module

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ModuleEntryActivity : AppCompatActivity() {
    private lateinit var palette: UiPalette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = resolvePalette()

        val root = ScrollView(this).apply {
            setBackgroundColor(palette.background)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(18)
            setPadding(padding, padding, padding, padding)
        }

        column.addView(title("Voicechanger"))
        column.addView(body("Это root/LSPosed-часть Voicechanger. Настройки находятся в обычном экране приложения, а список приложений выбирается только в LSPosed. Нативный слой сейчас удержан в experimental-режиме ради стабильного старта приложений."))
        column.addView(panel("Включи в LSPosed").apply {
            addView(body("Рекомендуемый scope, который модуль также отдает самому LSPosed:"))
            addView(mono(resources.getStringArray(R.array.recommended_scopes).joinToString("\n") { "• $it" }))
        })
        column.addView(panel("Активные слои").apply {
            addView(mono(HookBridge.activeTargets().joinToString("\n") { "• $it" }))
        })
        column.addView(panel("Режимы").apply {
            addView(mono(HookBridge.activeProfiles().joinToString("\n") { "• $it" }))
        })
        column.addView(panel("Следующий этап").apply {
            addView(mono(HookBridge.plannedTargets().joinToString("\n") { "• $it" }))
        })

        root.addView(column)
        setContentView(root)
    }

    private fun panel(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(palette.panelBackground)
            setStroke(dp(1), palette.panelStroke)
        }
        val padding = dp(16)
        setPadding(padding, padding, padding, padding)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(14)
        }
        addView(section(title))
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(palette.titleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(palette.titleText)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(palette.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun mono(text: String) = body(text).apply {
        setTypeface(Typeface.MONOSPACE)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private data class UiPalette(
        val background: Int,
        val panelBackground: Int,
        val panelStroke: Int,
        val titleText: Int,
        val primaryText: Int,
    )

    private fun resolvePalette(): UiPalette {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            UiPalette(
                background = Color.parseColor("#0B1117"),
                panelBackground = Color.parseColor("#111C24"),
                panelStroke = Color.parseColor("#22303D"),
                titleText = Color.parseColor("#E6EEF5"),
                primaryText = Color.parseColor("#D5DEE7"),
            )
        } else {
            UiPalette(
                background = Color.parseColor("#F4EFE6"),
                panelBackground = Color.parseColor("#FFFDF8"),
                panelStroke = Color.parseColor("#D9E2EC"),
                titleText = Color.parseColor("#102A43"),
                primaryText = Color.parseColor("#243B53"),
            )
        }
    }
}
