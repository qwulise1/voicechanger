package com.qwulise.voicechanger.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var powerSwitch: PillSwitch
    private lateinit var statusText: TextView
    private lateinit var modeChipRow: LinearLayout
    private lateinit var modeSummary: TextView
    private lateinit var effectValue: TextView
    private lateinit var boostValue: TextView
    private lateinit var effectSlider: GlassSlider
    private lateinit var boostSlider: GlassSlider
    private lateinit var palette: UiPalette

    private val uiHandler = Handler(Looper.getMainLooper())
    private val modeItems = VoiceMode.entries.toList()
    private var selectedModeIndex = 0
    private var suppressUiCallbacks = false
    private var saveSerial = 0
    private var dirty = false
    private val saveRunnable = Runnable { saveCurrentConfig() }

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
            setPadding(dp(18), statusBarInset() + dp(24), dp(18), dp(28))
        }

        statusText = body("")
        powerSwitch = PillSwitch(this).apply {
            setColors(
                onColor = palette.accent,
                offColor = palette.switchOff,
                thumbColor = Color.WHITE,
            )
            onCheckedChange = { onConfigChanged() }
        }
        modeChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modeSummary = body("")
        effectValue = body("")
        boostValue = body("")
        effectSlider = GlassSlider(this).apply {
            max = 100
            progress = 85
            setColors(palette.accent, palette.sliderTrack, palette.sliderThumb)
            onProgressChange = { onConfigChanged() }
        }
        boostSlider = GlassSlider(this).apply {
            max = 101
            progress = 0
            setColors(palette.warning, palette.sliderTrack, palette.sliderThumb)
            onProgressChange = { onConfigChanged() }
        }

        column.addView(hero())
        column.addView(controlPanel())
        column.addView(footer())
        root.addView(column)
        setContentView(root)

        loadConfig()
    }

    override fun onStop() {
        if (dirty) {
            uiHandler.removeCallbacks(saveRunnable)
            saveCurrentConfig()
        }
        super.onStop()
    }

    private fun loadConfig() {
        suppressUiCallbacks = true
        val config = runCatching { ModuleConfigClient.load(this) }
            .getOrElse { VoiceConfig() }
            .sanitizeForThisBuild()
        selectedModeIndex = modeItems.indexOf(config.mode).coerceAtLeast(0)
        powerSwitch.checked = config.enabled
        effectSlider.progress = config.effectStrength
        boostSlider.progress = config.micGainPercent
        renderModeChips()
        renderValueLabels()
        suppressUiCallbacks = false
        dirty = true
        renderStatus("Готово. Приложения выбирай в LSPosed.")
        uiHandler.postDelayed(saveRunnable, AUTO_SAVE_DELAY_MS)
    }

    private fun readConfigFromUi(): VoiceConfig =
        VoiceConfig(
            enabled = powerSwitch.checked,
            modeId = modeItems.getOrElse(selectedModeIndex) { VoiceMode.default }.id,
            effectStrength = effectSlider.progress,
            micGainPercent = boostSlider.progress,
            restrictToTargets = false,
            targetPackages = emptySet(),
            vendorHalEnabled = false,
            vendorHalParam = VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = false,
        ).sanitized()

    private fun VoiceConfig.sanitizeForThisBuild(): VoiceConfig =
        copy(
            vendorHalEnabled = false,
            vendorHalParam = VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = false,
        ).sanitized()

    private fun onConfigChanged() {
        if (suppressUiCallbacks) {
            return
        }
        dirty = true
        renderValueLabels()
        renderStatus("Сохраняю...")
        uiHandler.removeCallbacks(saveRunnable)
        uiHandler.postDelayed(saveRunnable, AUTO_SAVE_DELAY_MS)
    }

    private fun saveCurrentConfig() {
        val config = readConfigFromUi()
        val serial = ++saveSerial
        dirty = false
        Thread {
            val result = runCatching { ModuleConfigClient.save(this, config) }
            uiHandler.post {
                if (serial != saveSerial) {
                    return@post
                }
                result.onSuccess {
                    val time = DateFormat.format("HH:mm:ss", Date())
                    renderStatus("Сохранено $time.")
                    VoiceQuickTileService.requestTileRefresh(this)
                }.onFailure {
                    dirty = true
                    renderStatus("Не сохранилось: ${it.message ?: it::class.java.simpleName}")
                }
            }
        }.start()
    }

    private fun renderModeChips() {
        modeChipRow.removeAllViews()
        modeItems.forEachIndexed { index, mode ->
            modeChipRow.addView(TextView(this).apply {
                text = mode.title
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, if (index == selectedModeIndex) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (index == selectedModeIndex) palette.chipSelectedText else palette.primaryText)
                background = rounded(
                    if (index == selectedModeIndex) palette.chipSelected else palette.chipBackground,
                    999,
                    if (index == selectedModeIndex) palette.accent else palette.panelStroke,
                )
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener {
                    selectedModeIndex = index
                    renderModeChips()
                    onConfigChanged()
                }
            })
        }
    }

    private fun renderValueLabels() {
        val mode = modeItems.getOrElse(selectedModeIndex) { VoiceMode.default }
        modeSummary.text = mode.summary
        effectValue.text = if (mode == VoiceMode.CUSTOM) {
            val semitones = ((effectSlider.progress - 50) / 50f) * 12f
            "Тон: ${if (semitones >= 0f) "+" else ""}${"%.1f".format(semitones)} полутонов"
        } else {
            "Баланс эффекта: ${effectSlider.progress}%"
        }
        boostValue.text = when (boostSlider.progress) {
            0 -> "Усиление микрофона: выключено"
            101 -> "Усиление микрофона: 101% / hard"
            else -> "Усиление микрофона: ${boostSlider.progress}%"
        }
    }

    private fun renderStatus(message: String) {
        val config = readConfigFromUi()
        statusText.text = buildString {
            append(message)
            append("\n")
            append(if (config.enabled) "Включен" else "Выключен")
            append(" • ${config.mode.title}")
            if (config.micGainPercent > 0) {
                append(" • микрофон ${config.micGainPercent}")
            }
        }
    }

    private fun hero(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.heroBackground, 28, palette.heroStroke)
        setPadding(dp(20), dp(20), dp(20), dp(18))
        layoutParams = panelParams(14)

        addView(TextView(this@MainActivity).apply {
            text = "qwulivoice"
            setTextColor(palette.titleText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = "Глобальная обработка микрофона через LSPosed для голосовых, кружков и звонков."
            setTextColor(palette.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(8), 0, dp(12))
        })
        addView(statusText)
    }

    private fun controlPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.panelBackground, 24, palette.panelStroke)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)

        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(label("Включение").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(powerSwitch)
        })
        addView(space(20))
        addView(label("Режим"))
        addView(HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(modeChipRow)
        })
        addView(modeSummary.apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(8), 0, 0)
        })
        addView(space(20))
        addView(label("Сила эффекта"))
        addView(effectValue)
        addView(effectSlider)
        addView(space(20))
        addView(label("Усиление микрофона"))
        addView(boostValue)
        addView(boostSlider)
    }

    private fun footer(): TextView = body("Автор: @qwulise\nБыстрое включение можно добавить в шторку: плитка qwulivoice.").apply {
        gravity = Gravity.CENTER
        setTextColor(palette.secondaryText)
        setPadding(dp(8), dp(8), dp(8), 0)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.titleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(valueDp))
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

    private fun statusBarInset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
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
                accent = Color.parseColor("#40D39A"),
                warning = Color.parseColor("#F0B35B"),
                switchOff = Color.parseColor("#334247"),
                sliderTrack = Color.parseColor("#2D3A3F"),
                sliderThumb = Color.parseColor("#FFFFFF"),
                chipBackground = Color.parseColor("#17252A"),
                chipSelected = Color.parseColor("#40D39A"),
                chipSelectedText = Color.parseColor("#062018"),
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
                accent = Color.parseColor("#1BAE72"),
                warning = Color.parseColor("#C87825"),
                switchOff = Color.parseColor("#CBD8D1"),
                sliderTrack = Color.parseColor("#D8E3DD"),
                sliderThumb = Color.WHITE,
                chipBackground = Color.parseColor("#F3F7F4"),
                chipSelected = Color.parseColor("#1BAE72"),
                chipSelectedText = Color.WHITE,
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
        val accent: Int,
        val warning: Int,
        val switchOff: Int,
        val sliderTrack: Int,
        val sliderThumb: Int,
        val chipBackground: Int,
        val chipSelected: Int,
        val chipSelectedText: Int,
    )

    class PillSwitch(context: Context) : View(context) {
        var checked: Boolean = false
            set(value) {
                field = value
                invalidate()
            }
        var onCheckedChange: (() -> Unit)? = null
        private var onColor = Color.GREEN
        private var offColor = Color.GRAY
        private var thumbColor = Color.WHITE
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setColors(onColor: Int, offColor: Int, thumbColor: Int) {
            this.onColor = onColor
            this.offColor = offColor
            this.thumbColor = thumbColor
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dpLocal(62), dpLocal(34))
        }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = if (checked) onColor else offColor
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
            paint.color = thumbColor
            val thumbRadius = height * 0.40f
            val cx = if (checked) width - radius else radius
            canvas.drawCircle(cx, height / 2f, thumbRadius, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                checked = !checked
                onCheckedChange?.invoke()
            }
            return true
        }

        private fun dpLocal(value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }

    class GlassSlider(context: Context) : View(context) {
        var max: Int = 100
        var progress: Int = 0
            set(value) {
                field = value.coerceIn(0, max)
                invalidate()
            }
        var onProgressChange: (() -> Unit)? = null
        private var accent = Color.GREEN
        private var track = Color.GRAY
        private var thumb = Color.WHITE
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun setColors(accent: Int, track: Int, thumb: Int) {
            this.accent = accent
            this.track = track
            this.thumb = thumb
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, dpLocal(44))
        }

        override fun onDraw(canvas: Canvas) {
            val cy = height / 2f
            val start = dpLocal(8).toFloat()
            val end = width - dpLocal(8).toFloat()
            val radius = dpLocal(7).toFloat()
            rect.set(start, cy - radius, end, cy + radius)
            paint.color = track
            canvas.drawRoundRect(rect, radius, radius, paint)
            val ratio = if (max == 0) 0f else progress / max.toFloat()
            val thumbX = start + ((end - start) * ratio)
            rect.set(start, cy - radius, thumbX, cy + radius)
            paint.color = accent
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = thumb
            canvas.drawCircle(thumbX, cy, dpLocal(13).toFloat(), paint)
            paint.color = accent
            canvas.drawCircle(thumbX, cy, dpLocal(7).toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(event.action != MotionEvent.ACTION_UP)
                    val start = dpLocal(8).toFloat()
                    val end = width - dpLocal(8).toFloat()
                    val ratio = ((event.x - start) / (end - start)).coerceIn(0f, 1f)
                    val newProgress = (ratio * max).roundToInt().coerceIn(0, max)
                    if (newProgress != progress) {
                        progress = newProgress
                        onProgressChange?.invoke()
                    }
                    return true
                }
            }
            return true
        }

        private fun dpLocal(value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 450L
    }
}
