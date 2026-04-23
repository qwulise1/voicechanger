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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qwulise.voicechanger.core.SoundpadLibrary
import com.qwulise.voicechanger.core.SoundpadPlayback
import com.qwulise.voicechanger.core.SoundpadSlot
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var palette: UiPalette
    private lateinit var flipper: ViewFlipper
    private lateinit var statusText: TextView
    private lateinit var homeModeValue: TextView
    private lateinit var homeBoostValue: TextView
    private lateinit var homeSummaryText: TextView
    private lateinit var powerSwitch: PillSwitch
    private lateinit var modeFlow: FlowLayout
    private lateinit var modeSummary: TextView
    private lateinit var effectCard: LinearLayout
    private lateinit var effectLabel: TextView
    private lateinit var effectValue: TextView
    private lateinit var effectSlider: GlassSlider
    private lateinit var boostValue: TextView
    private lateinit var boostSlider: GlassSlider
    private lateinit var soundpadNowText: TextView
    private lateinit var soundpadMixValue: TextView
    private lateinit var soundpadMixSlider: GlassSlider
    private lateinit var soundpadLoopSwitch: PillSwitch
    private lateinit var soundpadPadsColumn: LinearLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private val modeItems = VoiceMode.entries.toList()
    private val navButtons = linkedMapOf<Page, LinearLayout>()
    private var currentPage = Page.HOME
    private var selectedModeIndex = 0
    private var suppressUiCallbacks = false
    private var saveSerial = 0
    private var dirty = false
    private var soundpadDirty = false
    private var soundpadLibrary = SoundpadLibrary()
    private var soundpadPlayback = SoundpadPlayback()
    private var pendingImportSlotId: String? = null

    private val saveRunnable = Runnable { saveCurrentConfig() }
    private val soundpadSaveRunnable = Runnable { saveSoundpadPlayback() }

    private val pagerGesture by lazy(LazyThreadSafetyMode.NONE) {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val deltaX = e2.x - (e1?.x ?: e2.x)
                    val deltaY = e2.y - (e1?.y ?: e2.y)
                    if (abs(deltaX) < abs(deltaY) || abs(deltaX) < dp(72) || abs(velocityX) < 650f) {
                        return false
                    }
                    if (deltaX < 0f) {
                        switchPage((currentPage.ordinal + 1).coerceAtMost(Page.entries.size - 1))
                    } else {
                        switchPage((currentPage.ordinal - 1).coerceAtLeast(0))
                    }
                    return true
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = resolvePalette()

        val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importIntoPendingSlot)
        }
        this.importLauncher = importLauncher

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.backgroundBottom),
            )
            addView(decorBlob(palette.heroAccent.withAlpha(46), 240, 240), FrameLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(32)
                marginEnd = -dp(48)
            })
            addView(decorBlob(palette.accentAlt.withAlpha(40), 180, 180), FrameLayout.LayoutParams(dp(180), dp(180)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(72)
                marginStart = -dp(36)
            })
        }

        val shell = FrameLayout(this).apply {
            setPadding(dp(16), statusBarInset() + dp(12), dp(16), dp(20))
        }

        flipper = ViewFlipper(this).apply {
            addView(homePage())
            addView(voicePage())
            addView(soundpadPage())
        }
        shell.addView(flipper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            bottomMargin = dp(104)
        })
        shell.addView(bottomBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ))

        root.addView(shell)
        setContentView(root)

        loadState()
    }

    override fun onStop() {
        if (dirty) {
            uiHandler.removeCallbacks(saveRunnable)
            saveCurrentConfig()
        }
        if (soundpadDirty) {
            uiHandler.removeCallbacks(soundpadSaveRunnable)
            saveSoundpadPlayback()
        }
        super.onStop()
    }

    private fun loadState() {
        suppressUiCallbacks = true
        val config = runCatching { ModuleConfigClient.load(this) }
            .getOrElse { VoiceConfig() }
            .sanitizeForThisBuild()
        soundpadLibrary = ModuleConfigClient.loadSoundpadLibrary(this).sanitized()
        soundpadPlayback = ModuleConfigClient.loadSoundpadPlayback(this).sanitized()
        selectedModeIndex = modeItems.indexOf(config.mode).coerceAtLeast(0)
        powerSwitch.checked = config.enabled
        effectSlider.progress = config.effectStrength
        boostSlider.progress = config.micGainPercent
        soundpadMixSlider.progress = soundpadPlayback.mixPercent
        soundpadLoopSwitch.checked = soundpadPlayback.looping
        renderModeChips()
        renderAll("Готово. Свайпай между вкладками или жми на нижний бар.")
        suppressUiCallbacks = false
        dirty = true
        uiHandler.postDelayed(saveRunnable, AUTO_SAVE_DELAY_MS)
    }

    private fun VoiceConfig.sanitizeForThisBuild(): VoiceConfig =
        copy(
            vendorHalEnabled = false,
            vendorHalParam = VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = false,
        ).sanitized()

    private fun homePage(): ScrollView =
        pageScroll().apply {
            addView(pageColumn().apply {
                addView(heroCard())
                addView(metricRow())
                addView(powerCard())
                addView(footerCard())
            })
        }

    private fun voicePage(): ScrollView =
        pageScroll().apply {
            addView(pageColumn().apply {
                addView(pageIntro("Голос", "Режимы, тембр и усиление микрофона без стокового уныния."))
                addView(modeCard())
                addView(effectCard())
                addView(boostCard())
                addView(noteCard("Quick note", "Custom semitones появляется только для своей модуляции. Остальное сохраняется автоматически."))
            })
        }

    private fun soundpadPage(): ScrollView =
        pageScroll().apply {
            addView(pageColumn().apply {
                addView(pageIntro("Soundpad", "Импортируй свои звуки или музыку и кидай их прямо в микрофон одним тапом."))
                addView(soundpadControlCard())
                addView(soundpadPadsCard())
                addView(noteCard("Как юзать", "Пустой пад импортирует файл. Готовый пад запускает звук, маленькая кнопка меняет файл, loop держит его по кругу."))
            })
        }

    private fun pageScroll(): ScrollView =
        ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnTouchListener { _, event ->
                pagerGesture.onTouchEvent(event)
                false
            }
        }

    private fun pageColumn(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }

    private fun heroCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.heroBackgroundStart, palette.heroBackgroundEnd, palette.heroStroke, 30)
        setPadding(dp(20), dp(20), dp(20), dp(18))
        layoutParams = panelParams(14)

        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(FrameLayout(this@MainActivity).apply {
                background = rounded(palette.surfaceElevated.withAlpha(150), 26, palette.heroStroke.withAlpha(210))
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                    rightMargin = dp(14)
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(resolveAvatarRes())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                })
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(chip("ROOT • BETA", palette.heroChipBackground, palette.heroChipText))
                addView(title("qwulivoice", 32f).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                addView(body("Голос, кружки, звонки и теперь soundpad в одном жирном интерфейсе.").apply {
                    setTextColor(palette.secondaryText)
                    setPadding(0, dp(6), 0, 0)
                })
            })
        })
        statusText = body("").apply {
            setTextColor(palette.primaryText)
            setPadding(0, dp(16), 0, 0)
        }
        addView(statusText)
    }

    private fun metricRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = panelParams(14)
        val modeMetric = metricCard("Режим")
        homeModeValue = modeMetric.second
        addView(modeMetric.first, metricParams(true))
        val boostMetric = metricCard("Микро")
        homeBoostValue = boostMetric.second
        addView(boostMetric.first, metricParams(false))
    }

    private fun powerCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 26)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)

        powerSwitch = PillSwitch(this@MainActivity).apply {
            setColors(
                onColor = palette.accent,
                offColor = palette.switchOff,
                thumbColor = Color.WHITE,
            )
            onCheckedChange = { onConfigChanged() }
        }

        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(title("Главный тумблер", 18f))
                addView(body("Если включить soundpad на паде, тумблер сам поднимется.").apply {
                    setTextColor(palette.secondaryText)
                    setPadding(0, dp(4), dp(12), 0)
                })
            })
            addView(powerSwitch)
        })
        homeSummaryText = body("").apply {
            setPadding(0, dp(16), 0, 0)
            setTextColor(palette.primaryText)
        }
        addView(homeSummaryText)
    }

    private fun footerCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface.withAlpha(170), 24, palette.cardStroke)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(title("@qwulise", 16f))
        addView(body("Плитку в шторке можно добавить отдельно. Темная и светлая тема подхватываются автоматически.").apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun pageIntro(heading: String, summary: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = panelParams(14)
        setPadding(dp(2), 0, dp(2), 0)
        addView(this@MainActivity.title(heading, 28f))
        addView(body(summary).apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun modeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 26)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(title("Режимы", 20f))
        addView(body("Тап по карточке сразу применяет режим и перерисовывает параметры.").apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, dp(12))
        })
        modeFlow = FlowLayout(this@MainActivity).apply {
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
        }
        addView(modeFlow)
        modeSummary = body("").apply {
            setTextColor(palette.primaryText)
            setPadding(0, dp(14), 0, 0)
        }
        addView(modeSummary)
    }

    private fun effectCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 26)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        effectLabel = title("Интенсивность", 20f)
        addView(effectLabel)
        effectValue = body("").apply {
            setTextColor(palette.primaryText)
            setPadding(0, dp(8), 0, dp(12))
        }
        addView(effectValue)
        effectSlider = GlassSlider(this@MainActivity).apply {
            max = 100
            progress = 85
            setColors(palette.heroAccent, palette.sliderTrack, palette.sliderThumb)
            onProgressChange = { onConfigChanged() }
        }
        addView(effectSlider)
    }.also {
        effectCard = it
    }

    private fun boostCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 26)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(title("Усиление микрофона", 20f))
        boostValue = body("").apply {
            setTextColor(palette.primaryText)
            setPadding(0, dp(8), 0, dp(12))
        }
        addView(boostValue)
        boostSlider = GlassSlider(this@MainActivity).apply {
            max = 101
            progress = 0
            setColors(palette.warning, palette.sliderTrack, palette.sliderThumb)
            onProgressChange = { onConfigChanged() }
        }
        addView(boostSlider)
    }

    private fun soundpadControlCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 26)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)

        soundpadLoopSwitch = PillSwitch(this@MainActivity).apply {
            setColors(
                onColor = palette.accent,
                offColor = palette.switchOff,
                thumbColor = Color.WHITE,
            )
            onCheckedChange = { onSoundpadControlsChanged() }
        }

        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(title("Параметры soundpad", 20f))
                soundpadNowText = body("").apply {
                    setTextColor(palette.secondaryText)
                    setPadding(0, dp(6), dp(12), 0)
                }
                addView(soundpadNowText)
            })
            addView(soundpadLoopSwitch)
        })
        soundpadMixValue = body("").apply {
            setTextColor(palette.primaryText)
            setPadding(0, dp(16), 0, dp(12))
        }
        addView(soundpadMixValue)
        soundpadMixSlider = GlassSlider(this@MainActivity).apply {
            max = 100
            progress = 70
            setColors(palette.accentAlt, palette.sliderTrack, palette.sliderThumb)
            onProgressChange = { onSoundpadControlsChanged() }
        }
        addView(soundpadMixSlider)
    }

    private fun soundpadPadsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 26)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(title("Пады", 20f))
        addView(body("Сделано под быстрый тап: импорт, запуск, стоп, замена.").apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, dp(14))
        })
        soundpadPadsColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(soundpadPadsColumn)
    }

    private fun noteCard(title: String, body: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface.withAlpha(165), 22, palette.cardStroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = panelParams(14)
        addView(this@MainActivity.title(title, 15f))
        addView(this@MainActivity.body(body).apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun bottomBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rounded(palette.navBackground, 30, palette.cardStroke.withAlpha(205))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        elevation = dp(8).toFloat()
        Page.entries.forEach { page ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(Color.TRANSPARENT, 22, Color.TRANSPARENT)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (page != Page.entries.last()) {
                        rightMargin = dp(6)
                    }
                }
                addView(TextView(this@MainActivity).apply {
                    text = page.icon
                    setTextColor(palette.navText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = page.label
                    setTextColor(palette.navText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { switchPage(page.ordinal) }
            }
            navButtons[page] = item
            addView(item)
        }
        renderNavBar()
    }

    private fun metricCard(label: String): Pair<LinearLayout, TextView> {
        val valueView = title("...", 18f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface.withAlpha(175), 22, palette.cardStroke)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(body(label).apply {
                setTextColor(palette.secondaryText)
            })
            addView(valueView.apply {
                setPadding(0, dp(8), 0, 0)
            })
        } to valueView
    }

    private fun metricParams(first: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (first) {
                rightMargin = dp(7)
            } else {
                leftMargin = dp(7)
            }
        }

    private fun renderAll(message: String) {
        renderValueLabels()
        renderHomeCards()
        renderSoundpad()
        renderStatus(message)
        renderNavBar()
    }

    private fun renderModeChips() {
        modeFlow.removeAllViews()
        modeItems.forEachIndexed { index, mode ->
            modeFlow.addView(TextView(this).apply {
                text = mode.title
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, if (index == selectedModeIndex) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (index == selectedModeIndex) palette.chipSelectedText else palette.primaryText)
                background = rounded(
                    if (index == selectedModeIndex) palette.chipSelected else palette.chipBackground,
                    18,
                    if (index == selectedModeIndex) palette.heroAccent.withAlpha(180) else palette.cardStroke,
                )
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    selectedModeIndex = index
                    renderModeChips()
                    onConfigChanged()
                }
            })
        }
    }

    private fun renderValueLabels() {
        val mode = currentMode()
        modeSummary.text = mode.summary
        when (mode) {
            VoiceMode.ORIGINAL -> {
                effectCard.visibility = View.GONE
            }
            VoiceMode.CUSTOM -> {
                effectCard.visibility = View.VISIBLE
                effectLabel.text = "Custom semitones"
                val semitones = ((effectSlider.progress - 50) / 50f) * 12f
                effectValue.text = "Тон: ${if (semitones >= 0f) "+" else ""}${"%.1f".format(semitones)}"
            }
            else -> {
                effectCard.visibility = View.VISIBLE
                effectLabel.text = "Интенсивность режима"
                effectValue.text = "Баланс эффекта: ${effectSlider.progress}%"
            }
        }
        boostValue.text = when (boostSlider.progress) {
            0 -> "Усиление микрофона: 0%"
            101 -> "Усиление микрофона: 101% / hard"
            else -> "Усиление микрофона: ${boostSlider.progress}%"
        }
    }

    private fun renderHomeCards() {
        homeModeValue.text = currentMode().title
        homeBoostValue.text = if (boostSlider.progress == 0) "0%" else "${boostSlider.progress}%"
        val activeSlot = soundpadLibrary.slot(soundpadPlayback.activeSlotId)
            ?.takeIf { soundpadPlayback.playing && it.isReady }
        homeSummaryText.text = buildString {
            append(if (powerSwitch.checked) "Обработка включена." else "Обработка выключена.")
            append("\n")
            append("Режим: ${currentMode().title}")
            if (boostSlider.progress > 0) {
                append(" • Микро ${boostSlider.progress}%")
            }
            append("\n")
            append("Soundpad: ${activeSlot?.title ?: "тишина"}")
        }
    }

    private fun renderSoundpad() {
        soundpadMixValue.text = "Громкость падов: ${soundpadMixSlider.progress}%"
        val activeSlot = soundpadLibrary.slot(soundpadPlayback.activeSlotId)
            ?.takeIf { soundpadPlayback.playing && it.isReady }
        soundpadNowText.text = when {
            activeSlot == null -> "Сейчас: ничего не играет"
            soundpadPlayback.looping -> "Сейчас: ${activeSlot.title} • loop on"
            else -> "Сейчас: ${activeSlot.title}"
        }
        renderSoundpadPads()
        renderHomeCards()
    }

    private fun renderSoundpadPads() {
        soundpadPadsColumn.removeAllViews()
        soundpadLibrary.sanitized().slots.chunked(2).forEachIndexed { rowIndex, rowSlots ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) {
                    setPadding(0, dp(10), 0, 0)
                }
            }
            rowSlots.forEachIndexed { index, slot ->
                row.addView(soundpadPad(slot), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) {
                        rightMargin = dp(6)
                    } else {
                        leftMargin = dp(6)
                    }
                })
            }
            if (rowSlots.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }
            soundpadPadsColumn.addView(row)
        }
    }

    private fun soundpadPad(slot: SoundpadSlot): LinearLayout {
        val accent = palette.slotAccents[slot.accentIndex % palette.slotAccents.size]
        val isPlaying = soundpadPlayback.playing && soundpadPlayback.activeSlotId == slot.id && slot.isReady
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard(
                palette.surfaceElevated.blendWith(accent, 0.18f),
                palette.surfaceAlt.blendWith(accent, 0.09f),
                accent.withAlpha(125),
                24,
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { onPadPrimary(slot) }

            addView(chip(if (slot.isReady) if (isPlaying) "LIVE" else "READY" else "EMPTY", accent.withAlpha(46), accent))
            addView(title(slot.title, 18f).apply {
                setPadding(0, dp(12), 0, 0)
            })
            addView(body(
                when {
                    slot.isReady -> slot.subtitle.ifBlank { "Готов к запуску" }
                    else -> "Импортируй mp3/m4a/wav и он полетит в микрофон"
                },
            ).apply {
                setTextColor(palette.secondaryText)
                setPadding(0, dp(6), 0, dp(16))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton(
                    text = when {
                        !slot.isReady -> "Импорт"
                        isPlaying -> "Стоп"
                        else -> "Play"
                    },
                    backgroundColor = accent,
                    textColor = if (accent.isLight()) palette.backgroundTop else Color.WHITE,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(6)
                    }
                    setOnClickListener { onPadPrimary(slot) }
                })
                addView(actionButton(
                    text = if (slot.isReady) "Сменить" else "Pick",
                    backgroundColor = palette.surface.withAlpha(96),
                    textColor = palette.primaryText,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { launchImporter(slot.id) }
                })
            })
        }
    }

    private fun onPadPrimary(slot: SoundpadSlot) {
        if (!slot.isReady) {
            launchImporter(slot.id)
            return
        }
        if (!powerSwitch.checked) {
            powerSwitch.checked = true
            onConfigChanged()
        }
        soundpadPlayback = if (soundpadPlayback.playing && soundpadPlayback.activeSlotId == slot.id) {
            soundpadPlayback.copy(
                playing = false,
                sessionId = System.currentTimeMillis(),
                mixPercent = soundpadMixSlider.progress,
                looping = soundpadLoopSwitch.checked,
            )
        } else {
            soundpadPlayback.copy(
                activeSlotId = slot.id,
                playing = true,
                sessionId = System.currentTimeMillis(),
                mixPercent = soundpadMixSlider.progress,
                looping = soundpadLoopSwitch.checked,
            )
        }.sanitized()
        renderSoundpad()
        saveSoundpadPlayback(immediate = true)
    }

    private fun launchImporter(slotId: String) {
        pendingImportSlotId = slotId
        importLauncher.launch(arrayOf("audio/*"))
    }

    private fun importIntoPendingSlot(uri: android.net.Uri) {
        val slotId = pendingImportSlotId ?: return
        pendingImportSlotId = null
        renderStatus("Импортирую звук в $slotId...")
        Thread {
            val updated = runCatching {
                val library = soundpadLibrary.sanitized()
                val currentSlot = library.slot(slotId) ?: library.slots.first()
                val imported = SoundpadImporter.importIntoSlot(this, uri, currentSlot)
                val updatedLibrary = library.copy(
                    slots = library.slots.map { if (it.id == slotId) imported else it },
                ).sanitized()
                ModuleConfigClient.saveSoundpadLibrary(this, updatedLibrary)
                updatedLibrary
            }
            uiHandler.post {
                updated.onSuccess { library ->
                    soundpadLibrary = library
                    renderSoundpad()
                    renderStatus("Импортирован пад: ${library.slot(slotId)?.title ?: slotId}")
                }.onFailure {
                    renderStatus("Импорт не удался: ${it.message ?: it::class.java.simpleName}")
                }
            }
        }.start()
    }

    private fun onConfigChanged() {
        if (suppressUiCallbacks) {
            return
        }
        dirty = true
        renderValueLabels()
        renderHomeCards()
        renderStatus("Сохраняю конфиг...")
        uiHandler.removeCallbacks(saveRunnable)
        uiHandler.postDelayed(saveRunnable, AUTO_SAVE_DELAY_MS)
    }

    private fun onSoundpadControlsChanged() {
        if (suppressUiCallbacks) {
            return
        }
        soundpadPlayback = soundpadPlayback.copy(
            looping = soundpadLoopSwitch.checked,
            mixPercent = soundpadMixSlider.progress,
        ).sanitized()
        soundpadDirty = true
        renderSoundpad()
        renderStatus("Обновляю soundpad...")
        uiHandler.removeCallbacks(soundpadSaveRunnable)
        uiHandler.postDelayed(soundpadSaveRunnable, SOUNDPAD_SAVE_DELAY_MS)
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

    private fun saveSoundpadPlayback(immediate: Boolean = false) {
        soundpadDirty = false
        val playback = soundpadPlayback.copy(
            mixPercent = soundpadMixSlider.progress,
            looping = soundpadLoopSwitch.checked,
        ).sanitized()
        if (!immediate) {
            uiHandler.removeCallbacks(soundpadSaveRunnable)
        }
        Thread {
            val result = runCatching { ModuleConfigClient.saveSoundpadPlayback(this, playback) }
            uiHandler.post {
                result.onSuccess {
                    renderStatus("Soundpad обновлен.")
                }.onFailure {
                    soundpadDirty = true
                    renderStatus("Soundpad не сохранился: ${it.message ?: it::class.java.simpleName}")
                }
            }
        }.start()
    }

    private fun readConfigFromUi(): VoiceConfig =
        VoiceConfig(
            enabled = powerSwitch.checked,
            modeId = currentMode().id,
            effectStrength = effectSlider.progress,
            micGainPercent = boostSlider.progress,
            restrictToTargets = false,
            targetPackages = emptySet(),
            vendorHalEnabled = false,
            vendorHalParam = VoiceConfig.DEFAULT_VENDOR_HAL_PARAM,
            vendorHalLoopback = false,
        ).sanitized()

    private fun renderStatus(message: String) {
        val config = readConfigFromUi()
        val activePad = soundpadLibrary.slot(soundpadPlayback.activeSlotId)
            ?.takeIf { soundpadPlayback.playing && it.isReady }
        statusText.text = buildString {
            append(message)
            append("\n")
            append(if (config.enabled) "ON" else "OFF")
            append(" • ${config.mode.title}")
            if (config.micGainPercent > 0) {
                append(" • mic ${config.micGainPercent}%")
            }
            if (activePad != null) {
                append(" • pad ${activePad.title}")
            }
        }
    }

    private fun switchPage(targetIndex: Int) {
        val bounded = targetIndex.coerceIn(0, Page.entries.size - 1)
        val targetPage = Page.entries[bounded]
        if (targetPage == currentPage) {
            return
        }
        val forward = bounded > currentPage.ordinal
        flipper.inAnimation = pageInAnimation(forward)
        flipper.outAnimation = pageOutAnimation(forward)
        flipper.displayedChild = bounded
        currentPage = targetPage
        renderNavBar()
    }

    private fun renderNavBar() {
        navButtons.forEach { (page, button) ->
            val selected = page == currentPage
            button.background = rounded(
                if (selected) palette.navSelected else Color.TRANSPARENT,
                22,
                if (selected) palette.navSelected else Color.TRANSPARENT,
            )
            (0 until button.childCount).forEach { index ->
                val child = button.getChildAt(index) as? TextView ?: return@forEach
                child.setTextColor(if (selected) palette.navSelectedText else palette.navText)
            }
        }
    }

    private fun currentMode(): VoiceMode =
        modeItems.getOrElse(selectedModeIndex) { VoiceMode.default }

    private fun pageInAnimation(forward: Boolean): Animation =
        AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0.72f, 1f))
            addAnimation(
                TranslateAnimation(
                    Animation.RELATIVE_TO_SELF,
                    if (forward) 0.18f else -0.18f,
                    Animation.RELATIVE_TO_SELF,
                    0f,
                    Animation.RELATIVE_TO_SELF,
                    0f,
                    Animation.RELATIVE_TO_SELF,
                    0f,
                ),
            )
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
        }

    private fun pageOutAnimation(forward: Boolean): Animation =
        AnimationSet(true).apply {
            addAnimation(AlphaAnimation(1f, 0.78f))
            addAnimation(
                TranslateAnimation(
                    Animation.RELATIVE_TO_SELF,
                    0f,
                    Animation.RELATIVE_TO_SELF,
                    if (forward) -0.12f else 0.12f,
                    Animation.RELATIVE_TO_SELF,
                    0f,
                    Animation.RELATIVE_TO_SELF,
                    0f,
                ),
            )
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
        }

    private fun title(text: String, sizeSp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.titleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(3).toFloat(), 1f)
        includeFontPadding = false
    }

    private fun chip(text: String, backgroundColor: Int, textColor: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(backgroundColor, 999, Color.TRANSPARENT)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

    private fun actionButton(text: String, backgroundColor: Int, textColor: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(backgroundColor, 16, Color.TRANSPARENT)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }

    private fun gradientCard(startColor: Int, endColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }

    private fun decorBlob(color: Int, widthDp: Int, heightDp: Int): View =
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color, Color.TRANSPARENT),
            ).apply {
                shape = GradientDrawable.OVAL
            }
            alpha = 0.95f
            layoutParams = FrameLayout.LayoutParams(dp(widthDp), dp(heightDp))
        }

    private fun panelParams(bottomDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(bottomDp)
        }

    private fun statusBarInset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun resolveAvatarRes(): Int {
        val resource = resources.getIdentifier("qwulivoice_avatar", "drawable", packageName)
        return if (resource != 0) resource else android.R.drawable.sym_def_app_icon
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun resolvePalette(): UiPalette {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            UiPalette(
                backgroundTop = Color.parseColor("#100D0A"),
                backgroundBottom = Color.parseColor("#1A1611"),
                surface = Color.parseColor("#191510"),
                surfaceAlt = Color.parseColor("#231C15"),
                surfaceElevated = Color.parseColor("#2A2017"),
                heroBackgroundStart = Color.parseColor("#372716"),
                heroBackgroundEnd = Color.parseColor("#1C1512"),
                heroStroke = Color.parseColor("#5A3F1F"),
                heroAccent = Color.parseColor("#F4C237"),
                accent = Color.parseColor("#F0BE36"),
                accentAlt = Color.parseColor("#25D1D8"),
                warning = Color.parseColor("#FF8C42"),
                titleText = Color.parseColor("#FFF5E5"),
                primaryText = Color.parseColor("#F0E0C8"),
                secondaryText = Color.parseColor("#B8A48D"),
                chipBackground = Color.parseColor("#2A221B"),
                chipSelected = Color.parseColor("#F0BE36"),
                chipSelectedText = Color.parseColor("#251906"),
                switchOff = Color.parseColor("#584B3E"),
                sliderTrack = Color.parseColor("#4B3D31"),
                sliderThumb = Color.WHITE,
                cardStroke = Color.parseColor("#3C2F24"),
                heroChipBackground = Color.parseColor("#473012"),
                heroChipText = Color.parseColor("#FFD774"),
                navBackground = Color.parseColor("#15120F"),
                navSelected = Color.parseColor("#362815"),
                navText = Color.parseColor("#CFBDA5"),
                navSelectedText = Color.parseColor("#FFF1D7"),
                slotAccents = intArrayOf(
                    Color.parseColor("#F4C237"),
                    Color.parseColor("#25D1D8"),
                    Color.parseColor("#F66C8B"),
                    Color.parseColor("#9AE65A"),
                    Color.parseColor("#B58CFF"),
                    Color.parseColor("#FFA85E"),
                ),
            )
        } else {
            UiPalette(
                backgroundTop = Color.parseColor("#F5EAD9"),
                backgroundBottom = Color.parseColor("#E8D9C4"),
                surface = Color.parseColor("#FFF8EE"),
                surfaceAlt = Color.parseColor("#F9EEE2"),
                surfaceElevated = Color.parseColor("#FFFDF8"),
                heroBackgroundStart = Color.parseColor("#F1C95C"),
                heroBackgroundEnd = Color.parseColor("#D89A41"),
                heroStroke = Color.parseColor("#D19C33"),
                heroAccent = Color.parseColor("#14BECB"),
                accent = Color.parseColor("#C98D19"),
                accentAlt = Color.parseColor("#0FBEC9"),
                warning = Color.parseColor("#E86B2D"),
                titleText = Color.parseColor("#2D2117"),
                primaryText = Color.parseColor("#493729"),
                secondaryText = Color.parseColor("#7C6756"),
                chipBackground = Color.parseColor("#F2E6D7"),
                chipSelected = Color.parseColor("#D29B22"),
                chipSelectedText = Color.WHITE,
                switchOff = Color.parseColor("#D9C9B5"),
                sliderTrack = Color.parseColor("#E2D2C0"),
                sliderThumb = Color.WHITE,
                cardStroke = Color.parseColor("#DFC5A9"),
                heroChipBackground = Color.parseColor("#FFF3CB"),
                heroChipText = Color.parseColor("#8F5B00"),
                navBackground = Color.parseColor("#1F1813"),
                navSelected = Color.parseColor("#56412A"),
                navText = Color.parseColor("#E2D1BF"),
                navSelectedText = Color.WHITE,
                slotAccents = intArrayOf(
                    Color.parseColor("#D29B22"),
                    Color.parseColor("#0FBEC9"),
                    Color.parseColor("#E86B7A"),
                    Color.parseColor("#63B93B"),
                    Color.parseColor("#8A6CE6"),
                    Color.parseColor("#F18B3A"),
                ),
            )
        }
    }

    private lateinit var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    private data class UiPalette(
        val backgroundTop: Int,
        val backgroundBottom: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val surfaceElevated: Int,
        val heroBackgroundStart: Int,
        val heroBackgroundEnd: Int,
        val heroStroke: Int,
        val heroAccent: Int,
        val accent: Int,
        val accentAlt: Int,
        val warning: Int,
        val titleText: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val chipBackground: Int,
        val chipSelected: Int,
        val chipSelectedText: Int,
        val switchOff: Int,
        val sliderTrack: Int,
        val sliderThumb: Int,
        val cardStroke: Int,
        val heroChipBackground: Int,
        val heroChipText: Int,
        val navBackground: Int,
        val navSelected: Int,
        val navText: Int,
        val navSelectedText: Int,
        val slotAccents: IntArray,
    )

    private enum class Page(val label: String, val icon: String) {
        HOME("Дом", "⌂"),
        VOICE("Голос", "≈"),
        SOUNDPAD("Pad", "♫"),
    }

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
            setMeasuredDimension(dpLocal(58), dpLocal(34))
        }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = if (checked) onColor else offColor
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
            paint.color = thumbColor
            val thumbRadius = height * 0.39f
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
            setMeasuredDimension(width, dpLocal(42))
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
            canvas.drawCircle(thumbX, cy, dpLocal(12).toFloat(), paint)
            paint.color = accent
            canvas.drawCircle(thumbX, cy, dpLocal(6).toFloat(), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(event.action != MotionEvent.ACTION_UP)
                    val start = dpLocal(8).toFloat()
                    val end = width - dpLocal(8).toFloat()
                    val ratio = ((event.x - start) / max(end - start, 1f)).coerceIn(0f, 1f)
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

    class FlowLayout(context: Context) : ViewGroup(context) {
        var horizontalSpacing: Int = 0
        var verticalSpacing: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            var lineWidth = 0
            var lineHeight = 0
            var totalHeight = paddingTop + paddingBottom
            var usedWidth = 0

            repeat(childCount) { index ->
                val child = getChildAt(index)
                if (child.visibility == GONE) {
                    return@repeat
                }
                measureChild(child, childWidthSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (paddingLeft + lineWidth + childWidth + paddingRight > maxWidth && lineWidth > 0) {
                    totalHeight += lineHeight + verticalSpacing
                    usedWidth = max(usedWidth, lineWidth)
                    lineWidth = 0
                    lineHeight = 0
                }
                if (lineWidth > 0) {
                    lineWidth += horizontalSpacing
                }
                lineWidth += childWidth
                lineHeight = max(lineHeight, childHeight)
            }

            totalHeight += lineHeight
            usedWidth = max(usedWidth, lineWidth)
            val resolvedWidth = resolveSize(usedWidth + paddingLeft + paddingRight, widthMeasureSpec)
            val resolvedHeight = resolveSize(totalHeight, heightMeasureSpec)
            setMeasuredDimension(resolvedWidth, resolvedHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = r - l
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0

            repeat(childCount) { index ->
                val child = getChildAt(index)
                if (child.visibility == GONE) {
                    return@repeat
                }
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (x + childWidth + paddingRight > maxWidth && x > paddingLeft) {
                    x = paddingLeft
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }
                child.layout(x, y, x + childWidth, y + childHeight)
                x += childWidth + horizontalSpacing
                lineHeight = max(lineHeight, childHeight)
            }
        }
    }

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))

    private fun Int.isLight(): Boolean =
        ((Color.red(this) * 0.299f) + (Color.green(this) * 0.587f) + (Color.blue(this) * 0.114f)) > 186f

    private fun Int.blendWith(other: Int, ratio: Float): Int {
        val safeRatio = ratio.coerceIn(0f, 1f)
        val inverse = 1f - safeRatio
        return Color.argb(
            (Color.alpha(this) * inverse + Color.alpha(other) * safeRatio).roundToInt(),
            (Color.red(this) * inverse + Color.red(other) * safeRatio).roundToInt(),
            (Color.green(this) * inverse + Color.green(other) * safeRatio).roundToInt(),
            (Color.blue(this) * inverse + Color.blue(other) * safeRatio).roundToInt(),
        )
    }

    companion object {
        private const val AUTO_SAVE_DELAY_MS = 420L
        private const val SOUNDPAD_SAVE_DELAY_MS = 240L
    }
}
