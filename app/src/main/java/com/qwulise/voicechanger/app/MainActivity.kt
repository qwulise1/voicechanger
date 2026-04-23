package com.qwulise.voicechanger.app

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var modeSpinner: Spinner
    private lateinit var effectValue: TextView
    private lateinit var effectSeek: SeekBar
    private lateinit var boostValue: TextView
    private lateinit var boostSeek: SeekBar
    private lateinit var statusText: TextView
    private lateinit var palette: UiPalette

    private val uiHandler = Handler(Looper.getMainLooper())
    private val modeItems = VoiceMode.entries.toList()
    private var suppressUiCallbacks = false
    private var saveSerial = 0
    private var dirty = false
    private val saveRunnable = Runnable {
        saveCurrentConfig()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = resolvePalette()

        val root = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.backgroundBottom),
            )
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }

        statusText = body("")
        enabledSwitch = SwitchMaterial(this).apply {
            text = "Включить voicechanger"
            textSize = 17f
            setTextColor(palette.primaryText)
            setOnCheckedChangeListener { _, _ -> onConfigChanged() }
        }
        modeSpinner = Spinner(this)
        effectValue = body("")
        effectSeek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(simpleSeekListener())
        }
        boostValue = body("")
        boostSeek = SeekBar(this).apply {
            max = 101
            setOnSeekBarChangeListener(simpleSeekListener())
        }

        column.addView(hero())
        column.addView(controlPanel())
        column.addView(footer())
        root.addView(column)
        setContentView(root)

        suppressUiCallbacks = true
        bindModeSpinner()
        loadConfig()
    }

    override fun onStop() {
        if (dirty) {
            uiHandler.removeCallbacks(saveRunnable)
            saveCurrentConfig()
        }
        super.onStop()
    }

    private fun bindModeSpinner() {
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modeItems.map { it.title },
        )
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderValueLabels()
                onConfigChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadConfig() {
        suppressUiCallbacks = true
        val config = runCatching { ModuleConfigClient.load(this) }
            .getOrElse { VoiceConfig() }
            .sanitizeForThisBuild()
        applyConfigToUi(config)
        suppressUiCallbacks = false
        dirty = true
        renderStatus("Готово. Выбор приложений делается только через LSPosed scope.")
        uiHandler.removeCallbacks(saveRunnable)
        uiHandler.postDelayed(saveRunnable, AUTO_SAVE_DELAY_MS)
    }

    private fun applyConfigToUi(config: VoiceConfig) {
        enabledSwitch.isChecked = config.enabled
        modeSpinner.setSelection(modeItems.indexOf(config.mode).coerceAtLeast(0), false)
        effectSeek.progress = config.effectStrength
        boostSeek.progress = config.micGainPercent
        renderValueLabels()
    }

    private fun readConfigFromUi(): VoiceConfig =
        VoiceConfig(
            enabled = enabledSwitch.isChecked,
            modeId = modeItems.getOrElse(modeSpinner.selectedItemPosition) { VoiceMode.default }.id,
            effectStrength = effectSeek.progress,
            micGainPercent = boostSeek.progress,
            restrictToTargets = false,
            targetPackages = emptySet(),
            vendorHalEnabled = hiddenVendorHalEnabled(),
            vendorHalParam = VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = false,
        ).sanitized()

    private fun VoiceConfig.sanitizeForThisBuild(): VoiceConfig =
        copy(
            vendorHalEnabled = hiddenVendorHalEnabled(),
            vendorHalParam = VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = false,
        ).sanitized()

    private fun hiddenVendorHalEnabled(): Boolean =
        packageName.startsWith("com.qwulise.voicechanger.module") &&
            !packageName.contains(".clean")

    private fun onConfigChanged() {
        if (suppressUiCallbacks) {
            return
        }
        dirty = true
        renderValueLabels()
        renderStatus("Сохраняю автоматически...")
        uiHandler.removeCallbacks(saveRunnable)
        uiHandler.postDelayed(saveRunnable, AUTO_SAVE_DELAY_MS)
    }

    private fun saveCurrentConfig() {
        val config = readConfigFromUi()
        val serial = ++saveSerial
        dirty = false
        Thread {
            val result = runCatching {
                ModuleConfigClient.save(this, config)
            }
            uiHandler.post {
                if (serial != saveSerial) {
                    return@post
                }
                result.onSuccess {
                    val time = DateFormat.format("HH:mm:ss", Date())
                    renderStatus("Сохранено $time. Перезапусти приложение после изменения LSPosed scope.")
                }.onFailure {
                    dirty = true
                    renderStatus("Не сохранилось: ${it.message ?: it::class.java.simpleName}")
                }
            }
        }.start()
    }

    private fun renderValueLabels() {
        val mode = modeItems.getOrElse(modeSpinner.selectedItemPosition) { VoiceMode.default }
        effectValue.text = if (mode == VoiceMode.CUSTOM) {
            val semitones = customSemitones(effectSeek.progress)
            "Тон: ${if (semitones >= 0f) "+" else ""}${"%.1f".format(semitones)} полутонов"
        } else {
            "Эффект / оригинал: ${effectSeek.progress}%"
        }
        boostValue.text = if (boostSeek.progress == 0) {
            "Усиление микрофона: выключено"
        } else {
            "Усиление микрофона: ${boostSeek.progress} / 101"
        }
    }

    private fun renderStatus(message: String) {
        val config = readConfigFromUi()
        statusText.text = buildString {
            append(message)
            append("\n")
            append(if (config.enabled) "Активен " else "Выключен ")
            append("режим «${config.mode.title}»")
            if (config.micGainPercent > 0) {
                append(" + буст ${config.micGainPercent}")
            }
        }
    }

    private fun customSemitones(progress: Int): Float =
        ((progress.coerceIn(0, 100) - 50) / 50f) * 12f

    private fun simpleSeekListener(): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderValueLabels()
                if (fromUser) {
                    onConfigChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!suppressUiCallbacks) {
                    onConfigChanged()
                }
            }
        }

    private fun hero(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.heroBackground, 26, palette.heroStroke)
        setPadding(dp(20), dp(20), dp(20), dp(18))
        layoutParams = panelParams(bottom = 14)

        addView(TextView(this@MainActivity).apply {
            text = "Voicechanger"
            setTextColor(palette.titleText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = "Глобальная обработка микрофона через LSPosed. Голосовые, кружки, звонки и любые выбранные приложения."
            setTextColor(palette.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(8), 0, dp(10))
        })
        addView(statusText)
    }

    private fun controlPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.panelBackground, 22, palette.panelStroke)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(bottom = 14)

        addView(enabledSwitch)
        addView(space(16))
        addView(label("Режим"))
        addView(modeSpinner)
        addView(space(16))
        addView(label("Сила эффекта"))
        addView(effectValue)
        addView(effectSeek)
        addView(space(16))
        addView(label("Усиление микрофона"))
        addView(boostValue)
        addView(boostSeek)
    }

    private fun footer(): TextView = body("Автор: @qwulise\nScope выбирай в LSPosed. Внутри приложения список пакетов специально убран.").apply {
        gravity = Gravity.CENTER
        setTextColor(palette.secondaryText)
        setPadding(dp(8), dp(6), dp(8), 0)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.titleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun space(valueDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(valueDp),
        )
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            setStroke(dp(1), strokeColor)
        }

    private fun panelParams(bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun resolvePalette(): UiPalette {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            UiPalette(
                backgroundTop = Color.parseColor("#071015"),
                backgroundBottom = Color.parseColor("#0E1D22"),
                heroBackground = Color.parseColor("#13262C"),
                panelBackground = Color.parseColor("#101A1E"),
                heroStroke = Color.parseColor("#2D4C54"),
                panelStroke = Color.parseColor("#24353A"),
                titleText = Color.parseColor("#EAF7F3"),
                primaryText = Color.parseColor("#D7E5E1"),
                secondaryText = Color.parseColor("#91A9A4"),
            )
        } else {
            UiPalette(
                backgroundTop = Color.parseColor("#F8F1E4"),
                backgroundBottom = Color.parseColor("#E8F0EA"),
                heroBackground = Color.parseColor("#FFF9EE"),
                panelBackground = Color.parseColor("#FFFFFF"),
                heroStroke = Color.parseColor("#E0CFAF"),
                panelStroke = Color.parseColor("#D6E0D9"),
                titleText = Color.parseColor("#15322B"),
                primaryText = Color.parseColor("#233D37"),
                secondaryText = Color.parseColor("#60746E"),
            )
        }
    }

    private data class UiPalette(
        val backgroundTop: Int,
        val backgroundBottom: Int,
        val heroBackground: Int,
        val panelBackground: Int,
        val heroStroke: Int,
        val panelStroke: Int,
        val titleText: Int,
        val primaryText: Int,
        val secondaryText: Int,
    )

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 450L
    }
}
