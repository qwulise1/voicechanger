package com.qwulise.voicechanger.app

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.view.ViewOutlineProvider
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qwulise.voicechanger.core.DiagnosticEvent
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
    private lateinit var uiSettings: UiSettings
    private lateinit var palette: UiPalette
    private lateinit var flipper: ViewFlipper
    private lateinit var navBar: FrameLayout
    private lateinit var navIndicator: View
    private lateinit var navRow: LinearLayout

    private lateinit var statusText: TextView
    private lateinit var homeModeValue: TextView
    private lateinit var homeBoostValue: TextView
    private lateinit var homePadValue: TextView
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
    private lateinit var soundpadOverlayText: TextView
    private lateinit var soundpadMixValue: TextView
    private lateinit var soundpadMixSlider: GlassSlider
    private lateinit var soundpadLoopSwitch: PillSwitch
    private lateinit var soundpadPadsColumn: LinearLayout

    private lateinit var themeModeFlow: FlowLayout
    private lateinit var accentFlow: FlowLayout
    private lateinit var monetSwitch: PillSwitch
    private lateinit var logsStatusText: TextView
    private lateinit var logsColumn: LinearLayout

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
    private var logsEvents: List<DiagnosticEvent> = emptyList()

    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

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
                    if (abs(deltaX) < abs(deltaY) || abs(deltaX) < dp(68) || abs(velocityX) < 650f) {
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
        uiSettings = UiSettingsStore.read(this)
        palette = resolvePalette()
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importIntoPendingSlot)
        }

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.backgroundTop, palette.backgroundBottom),
            )
            addView(decorBlob(palette.heroAccent.withAlpha(42), 260, 260), FrameLayout.LayoutParams(dp(260), dp(260)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(24)
                marginEnd = -dp(56)
            })
            addView(decorBlob(palette.accentAlt.withAlpha(34), 220, 220), FrameLayout.LayoutParams(dp(220), dp(220)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(54)
                marginStart = -dp(58)
            })
        }

        val shell = FrameLayout(this).apply {
            setPadding(dp(16), statusBarInset() + dp(12), dp(16), 0)
        }

        flipper = ViewFlipper(this).apply {
            addView(homePage())
            addView(voicePage())
            addView(soundpadPage())
            addView(settingsPage())
        }
        shell.addView(
            flipper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        shell.addView(
            bottomBar(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
                bottomMargin = dp(20)
            },
        )

        root.addView(shell)
        setContentView(root)

        loadState()
        applyStartPageFromIntent(intent, animated = false)
    }

    override fun onResume() {
        super.onResume()
        syncOverlayBubble(userInitiated = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyStartPageFromIntent(intent, animated = true)
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
        if (!config.enabled && soundpadPlayback.playing) {
            soundpadPlayback = soundpadPlayback.copy(
                playing = false,
                sessionId = System.currentTimeMillis(),
            ).sanitized()
            runCatching { ModuleConfigClient.saveSoundpadPlayback(this, soundpadPlayback) }
        }
        selectedModeIndex = modeItems.indexOf(config.mode).coerceAtLeast(0)
        powerSwitch.checked = config.enabled
        effectSlider.progress = config.effectStrength
        boostSlider.progress = config.micGainPercent
        soundpadMixSlider.progress = soundpadPlayback.mixPercent
        soundpadLoopSwitch.checked = soundpadPlayback.looping
        monetSwitch.checked = uiSettings.useMonet
        renderModeChips()
        renderThemeSelectors()
        renderAll("Готово. Все основные штуки под рукой.")
        suppressUiCallbacks = false
        dirty = false
        soundpadDirty = false
        refreshLogs()
        syncOverlayBubble(userInitiated = false)
    }

    private fun applyStartPageFromIntent(intent: Intent?, animated: Boolean) {
        val target = intent?.getIntExtra(EXTRA_START_PAGE, -1) ?: -1
        if (target in 0 until Page.entries.size) {
            switchPage(target, animated)
        }
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
                addView(metricGrid())
                addView(powerCard())
                addView(footerCard())
            })
        }

    private fun voicePage(): ScrollView =
        pageScroll().apply {
            addView(pageColumn().apply {
                addView(pageIntro("Голос", "Режимы, тембр и усиление микрофона. Без лишнего мусора и с мгновенным сохранением."))
                addView(modeCard())
                addView(effectCard())
                addView(boostCard())
            })
        }

    private fun soundpadPage(): ScrollView =
        pageScroll().apply {
            addView(pageColumn().apply {
                addView(pageIntro("Soundpad", "Импортируй свои звуки или музыку и кидай их прямо в микрофон одним тапом."))
                addView(soundpadControlCard())
                addView(soundpadPadsCard())
            })
        }

    private fun settingsPage(): ScrollView =
        pageScroll().apply {
            addView(pageColumn().apply {
                addView(pageIntro("Настройки", "Светлая, темная, monet, цвета и живые логи модуля в одном месте."))
                addView(themeSettingsCard())
                addView(logsCard())
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
            layoutTransition = LayoutTransition().apply {
                setDuration(220L)
            }
            setPadding(0, 0, 0, dp(104))
        }

    private fun heroCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.heroBackgroundStart, palette.heroBackgroundEnd, palette.heroStroke, 32)
        setPadding(dp(20), dp(20), dp(20), dp(18))
        layoutParams = panelParams(14)

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@MainActivity).apply {
                background = rounded(palette.surfaceElevated.withAlpha(150), 28, palette.heroStroke.withAlpha(210))
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                    rightMargin = dp(14)
                }
                addView(ImageView(context).apply {
                    setImageResource(resolveAvatarRes())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(chip("ROOT • BETA", palette.heroChipBackground, palette.heroChipText))
                    addView(
                        chip("@qwulise", palette.surface.withAlpha(82), palette.primaryText).apply {
                            setPadding(dp(10), dp(6), dp(10), dp(6))
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            leftMargin = dp(8)
                        },
                    )
                })
                addView(title("qwulivoice", 32f).apply {
                    setPadding(0, dp(12), 0, 0)
                })
                addView(body("Голос, кружки, звонки и soundpad. Все в одном приложении.").apply {
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

    private fun metricGrid(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = panelParams(14)
        val modeMetric = metricCard("Режим")
        homeModeValue = modeMetric.second
        addView(modeMetric.first, metricParams(0))
        val boostMetric = metricCard("Микро")
        homeBoostValue = boostMetric.second
        addView(boostMetric.first, metricParams(1))
        val padMetric = metricCard("Pad")
        homePadValue = padMetric.second
        addView(padMetric.first, metricParams(2))
    }

    private fun powerCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)

        powerSwitch = PillSwitch(this@MainActivity).apply {
            setColors(
                onColor = palette.accent,
                offColor = palette.switchOff,
                thumbColor = Color.WHITE,
            )
            onCheckedChange = { onMasterPowerChanged() }
        }

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(title("Главный тумблер", 18f))
                addView(body("Один свитч управляет и голосом, и активным soundpad.").apply {
                    setTextColor(palette.secondaryText)
                    setPadding(0, dp(5), dp(12), 0)
                })
            })
            addView(powerSwitch)
        })

        homeSummaryText = body("").apply {
            setTextColor(palette.primaryText)
            setPadding(0, dp(16), 0, 0)
        }
        addView(homeSummaryText)
    }

    private fun footerCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface.withAlpha(164), 24, palette.cardStroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = panelParams(14)
        addView(title("Автор", 14f))
        addView(body("@qwulise").apply {
            setPadding(0, dp(4), 0, 0)
            setTextColor(palette.secondaryText)
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
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(title("Режимы", 20f))
        addView(body("Тап по карточке сразу применяет режим и обновляет все связанные параметры.").apply {
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
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        effectLabel = title("Интенсивность режима", 20f)
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
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
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
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(title("Параметры soundpad", 20f))
                soundpadNowText = body("").apply {
                    setTextColor(palette.primaryText)
                    setPadding(0, dp(6), 0, 0)
                }
                addView(soundpadNowText)
                soundpadOverlayText = body("").apply {
                    setTextColor(palette.secondaryText)
                    setPadding(0, dp(6), dp(12), 0)
                }
                addView(soundpadOverlayText)
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
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("Пады", 20f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(actionButton("+ Добавить", palette.accent, if (palette.accent.isLight()) palette.backgroundTop else Color.WHITE).apply {
                setOnClickListener { onAddPadRequested() }
            })
        })
        addView(body("Добавляй сколько нужно. Пустых заготовок больше нет.").apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, dp(14))
        })
        soundpadPadsColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                setDuration(220L)
            }
        }
        addView(soundpadPadsColumn)
    }

    private fun themeSettingsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(title("Оформление", 22f))
        addView(body("Отдельная вкладка под тему, акцент и системные цвета.").apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, dp(18))
        })
        addView(settingBlock("Тема", "Как выглядит приложение целиком").apply {
            themeModeFlow = FlowLayout(this@MainActivity).apply {
                horizontalSpacing = dp(8)
                verticalSpacing = dp(8)
            }
            addView(themeModeFlow)
        })
        addView(settingBlock("Цвет", "Базовый акцент для карточек и контролов").apply {
            accentFlow = FlowLayout(this@MainActivity).apply {
                horizontalSpacing = dp(8)
                verticalSpacing = dp(8)
            }
            addView(accentFlow)
        })
        addView(settingSwitchBlock("Monet", "Использовать системные dynamic-цвета, если Android умеет.") { switch ->
            monetSwitch = switch.apply {
                setColors(
                    onColor = palette.accent,
                    offColor = palette.switchOff,
                    thumbColor = Color.WHITE,
                )
                onCheckedChange = {
                    if (!suppressUiCallbacks) {
                        updateUiSettings(uiSettings.copy(useMonet = checked))
                    }
                }
            }
        })
    }

    private fun logsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = gradientCard(palette.surface, palette.surfaceAlt, palette.cardStroke, 28)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = panelParams(14)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("Логи", 22f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ghostButton("Обновить").apply {
                setOnClickListener { refreshLogs() }
            })
            addView(
                ghostButton("Очистить").apply {
                    setOnClickListener { clearLogs() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = dp(8)
                },
            )
        })
        logsStatusText = body("Подтягиваю последние события хука и provider...").apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(8), 0, dp(14))
        }
        addView(logsStatusText)
        logsColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(logsColumn)
    }

    private fun bottomBar(): FrameLayout = FrameLayout(this).apply {
        navBar = this
        background = rounded(palette.navBackground, 30, palette.navStroke)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        elevation = dp(12).toFloat()

        navIndicator = View(this@MainActivity).apply {
            background = gradientCard(
                palette.navBubbleStart,
                palette.navBubbleEnd,
                palette.navBubbleStroke,
                24,
            )
        }
        addView(navIndicator, FrameLayout.LayoutParams(0, dp(58)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        navRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addView(navRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        Page.entries.forEach { page ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(58)
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { switchPage(page.ordinal) }
            }
            navButtons[page] = item
            navRow.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        post { renderNavBar(animated = false) }
    }

    private fun metricCard(label: String): Pair<LinearLayout, TextView> {
        val valueView = title("...", 17f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface.withAlpha(170), 22, palette.cardStroke)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(body(label).apply {
                setTextColor(palette.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            addView(valueView.apply {
                setPadding(0, dp(8), 0, 0)
            })
        } to valueView
    }

    private fun metricParams(index: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) {
                leftMargin = dp(8)
            }
        }

    private fun settingBlock(title: String, summary: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface.withAlpha(120), 22, palette.cardStroke)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = panelParams(12)
        addView(this@MainActivity.title(title, 16f))
        addView(body(summary).apply {
            setTextColor(palette.secondaryText)
            setPadding(0, dp(4), 0, dp(10))
        })
    }

    private fun settingSwitchBlock(
        title: String,
        summary: String,
        configure: (PillSwitch) -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface.withAlpha(120), 22, palette.cardStroke)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = panelParams(12)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(this@MainActivity.title(title, 16f))
                addView(body(summary).apply {
                    setTextColor(palette.secondaryText)
                    setPadding(0, dp(4), dp(12), 0)
                })
            })
            addView(PillSwitch(this@MainActivity).also(configure))
        })
    }

    private fun renderAll(message: String) {
        renderValueLabels()
        renderHomeCards()
        renderSoundpad()
        renderThemeSelectors()
        renderStatus(message)
        renderNavBar(animated = false)
    }

    private fun renderModeChips() {
        modeFlow.removeAllViews()
        modeItems.forEachIndexed { index, mode ->
            modeFlow.addView(selectorChip(mode.title, index == selectedModeIndex).apply {
                setOnClickListener {
                    selectedModeIndex = index
                    renderModeChips()
                    onConfigChanged()
                }
            })
        }
    }

    private fun renderThemeSelectors() {
        themeModeFlow.removeAllViews()
        UiThemeMode.entries.forEach { mode ->
            themeModeFlow.addView(selectorChip(mode.title, uiSettings.themeMode == mode).apply {
                setOnClickListener {
                    updateUiSettings(uiSettings.copy(themeMode = mode))
                }
            })
        }
        accentFlow.removeAllViews()
        UiAccentPreset.entries.forEach { preset ->
            accentFlow.addView(selectorChip(preset.title, uiSettings.accentPreset == preset).apply {
                setOnClickListener {
                    updateUiSettings(uiSettings.copy(accentPresetId = preset.id))
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
        val activePad = currentActivePad()
        homeModeValue.text = currentMode().title
        homeBoostValue.text = if (boostSlider.progress == 0) "0%" else "${boostSlider.progress}%"
        homePadValue.text = activePad?.title ?: "Off"
        homeSummaryText.text = buildString {
            append(if (powerSwitch.checked) "Обработка включена." else "Обработка выключена.")
            append("\n")
            append("Режим: ${currentMode().title}")
            if (boostSlider.progress > 0) {
                append(" • Микро ${boostSlider.progress}%")
            }
            append("\n")
            append("Soundpad: ${activePad?.title ?: "ничего не играет"}")
            append(" • Overlay: ")
            append(
                when {
                    !powerSwitch.checked -> "off"
                    activePad == null -> "ожидает запуск"
                    SoundpadOverlayBubbleService.canDraw(this@MainActivity) -> "активен"
                    else -> "нужен доступ"
                },
            )
        }
    }

    private fun renderSoundpad() {
        val activePad = currentActivePad()
        soundpadNowText.text = when {
            activePad == null -> "Сейчас: ничего не играет"
            soundpadPlayback.looping -> "Сейчас: ${activePad.title} • loop on"
            else -> "Сейчас: ${activePad.title}"
        }
        soundpadOverlayText.text = when {
            !powerSwitch.checked -> "Главный тумблер выключен, поэтому soundpad тоже стоит."
            activePad == null -> "Оверлей-кнопка появится, когда запустишь какой-нибудь пад."
            SoundpadOverlayBubbleService.canDraw(this) -> "Плавающая кнопка с авой уже должна висеть поверх приложений."
            else -> "Для плавающей кнопки выдай доступ к показу поверх других приложений."
        }
        soundpadMixValue.text = "Громкость падов: ${soundpadMixSlider.progress}%"
        renderSoundpadPads()
        renderHomeCards()
    }

    private fun renderSoundpadPads() {
        soundpadPadsColumn.removeAllViews()
        val slots = soundpadLibrary.sanitized().slots
        if (slots.isEmpty()) {
            soundpadPadsColumn.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rounded(palette.surface.withAlpha(110), 24, palette.cardStroke)
                    setPadding(dp(18), dp(22), dp(18), dp(22))
                    addView(title("Пока пусто", 18f))
                    addView(body("Добавь первый пад и импортируй звук в один тап.").apply {
                        setTextColor(palette.secondaryText)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, dp(14))
                    })
                    addView(actionButton("+ Добавить первый пад", palette.accent, if (palette.accent.isLight()) palette.backgroundTop else Color.WHITE).apply {
                        setOnClickListener { onAddPadRequested() }
                    })
                },
            )
            return
        }

        slots.chunked(2).forEachIndexed { rowIndex, rowSlots ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) {
                    setPadding(0, dp(10), 0, 0)
                }
            }
            rowSlots.forEachIndexed { index, slot ->
                row.addView(
                    soundpadPad(slot),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) {
                            leftMargin = dp(8)
                        }
                    },
                )
            }
            if (rowSlots.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply {
                    leftMargin = dp(8)
                })
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
                palette.surfaceAlt.blendWith(accent, 0.10f),
                accent.withAlpha(124),
                24,
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chip(
                    when {
                        slot.isReady && isPlaying -> "LIVE"
                        slot.isReady -> "READY"
                        else -> "EMPTY"
                    },
                    accent.withAlpha(44),
                    accent,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(ghostButton("✕").apply {
                    setOnClickListener { removePad(slot) }
                })
            })
            addView(title(slot.title, 18f).apply {
                setPadding(0, dp(12), 0, 0)
            })
            addView(body(
                when {
                    slot.isReady -> slot.subtitle.ifBlank { "Готов к запуску" }
                    else -> "Нажми импорт и загрузи свой звук"
                },
            ).apply {
                setTextColor(palette.secondaryText)
                setPadding(0, dp(6), 0, dp(16))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton(
                    when {
                        !slot.isReady -> "Импорт"
                        isPlaying -> "Стоп"
                        else -> "Play"
                    },
                    accent,
                    if (accent.isLight()) palette.backgroundTop else Color.WHITE,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(6)
                    }
                    setOnClickListener { onPadPrimary(slot) }
                })
                addView(ghostButton(if (slot.isReady) "Сменить" else "Pick").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { launchImporter(slot.id) }
                })
            })
        }
    }

    private fun renderLogs() {
        logsColumn.removeAllViews()
        if (logsEvents.isEmpty()) {
            logsColumn.addView(body("Логи пока пустые. Сначала попробуй включить обработку или записать что-нибудь.").apply {
                setTextColor(palette.secondaryText)
            })
            return
        }
        logsEvents.take(MAX_LOG_LINES).forEachIndexed { index, event ->
            logsColumn.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(palette.surface.withAlpha(122), 18, palette.cardStroke)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    if (index > 0) {
                        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "${DateFormat.format("HH:mm:ss", Date(event.timestampMs))} • ${event.source}"
                        setTextColor(palette.titleText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = event.detail
                        setTextColor(palette.secondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(Typeface.MONOSPACE)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(0, dp(6), 0, 0)
                    })
                }.also { card ->
                    card.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index > 0) {
                            topMargin = dp(8)
                        }
                    }
                },
            )
        }
    }

    private fun onMasterPowerChanged() {
        if (suppressUiCallbacks) {
            return
        }
        if (!powerSwitch.checked && soundpadPlayback.playing) {
            soundpadPlayback = soundpadPlayback.copy(
                playing = false,
                sessionId = System.currentTimeMillis(),
            ).sanitized()
            soundpadDirty = true
            renderSoundpad()
            syncOverlayBubble(userInitiated = false)
            uiHandler.removeCallbacks(soundpadSaveRunnable)
            uiHandler.post(soundpadSaveRunnable)
        }
        onConfigChanged()
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

    private fun onPadPrimary(slot: SoundpadSlot) {
        if (!slot.isReady) {
            launchImporter(slot.id)
            return
        }
        val wasDisabled = !powerSwitch.checked
        if (wasDisabled) {
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
        syncOverlayBubble(userInitiated = true)
        saveSoundpadPlayback(immediate = true)
    }

    private fun onAddPadRequested() {
        soundpadLibrary = soundpadLibrary.appendSlot()
        renderSoundpad()
        persistSoundpadLibrary("Пад добавлен.")
    }

    private fun removePad(slot: SoundpadSlot) {
        soundpadLibrary = soundpadLibrary.copy(
            slots = soundpadLibrary.sanitized().slots.filterNot { it.id == slot.id },
        ).sanitized()
        if (soundpadPlayback.activeSlotId == slot.id) {
            soundpadPlayback = soundpadPlayback.copy(
                activeSlotId = "",
                playing = false,
                sessionId = System.currentTimeMillis(),
            ).sanitized()
            saveSoundpadPlayback(immediate = true, silent = true)
            syncOverlayBubble(userInitiated = false)
        }
        renderSoundpad()
        persistSoundpadLibrary("Пад удален.")
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
                val currentSlot = library.slot(slotId) ?: SoundpadSlot.empty(library.slots.size)
                val imported = SoundpadImporter.importIntoSlot(this, uri, currentSlot)
                val updatedSlots = if (library.slot(slotId) == null) {
                    library.slots + imported
                } else {
                    library.slots.map { if (it.id == slotId) imported else it }
                }
                val updatedLibrary = library.copy(slots = updatedSlots).sanitized()
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

    private fun persistSoundpadLibrary(message: String) {
        val library = soundpadLibrary.sanitized()
        Thread {
            val result = runCatching { ModuleConfigClient.saveSoundpadLibrary(this, library) }
            uiHandler.post {
                result.onSuccess {
                    renderStatus(message)
                }.onFailure {
                    renderStatus("Библиотека soundpad не сохранилась: ${it.message ?: it::class.java.simpleName}")
                }
            }
        }.start()
    }

    private fun refreshLogs() {
        logsStatusText.text = "Обновляю логи..."
        Thread {
            val result = runCatching { ModuleConfigClient.loadLogs(this) }
            uiHandler.post {
                result.onSuccess { events ->
                    logsEvents = events
                    logsStatusText.text = if (events.isEmpty()) {
                        "Пока пусто."
                    } else {
                        "Показаны последние ${minOf(events.size, MAX_LOG_LINES)} событий."
                    }
                    renderLogs()
                }.onFailure {
                    logsStatusText.text = "Не удалось загрузить логи: ${it.message ?: it::class.java.simpleName}"
                    logsEvents = emptyList()
                    renderLogs()
                }
            }
        }.start()
    }

    private fun clearLogs() {
        logsStatusText.text = "Чищу логи..."
        Thread {
            val result = runCatching { ModuleConfigClient.clearLogs(this) }
            uiHandler.post {
                result.onSuccess {
                    logsEvents = emptyList()
                    logsStatusText.text = "Логи очищены."
                    renderLogs()
                }.onFailure {
                    logsStatusText.text = "Не удалось очистить: ${it.message ?: it::class.java.simpleName}"
                }
            }
        }.start()
    }

    private fun updateUiSettings(newSettings: UiSettings) {
        if (newSettings == uiSettings) {
            return
        }
        uiSettings = UiSettingsStore.write(this, newSettings)
        recreate()
    }

    private fun syncOverlayBubble(userInitiated: Boolean) {
        SoundpadOverlayBubbleService.sync(
            context = this,
            show = powerSwitch.checked && soundpadPlayback.playing,
            userInitiated = userInitiated,
        )
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

    private fun saveSoundpadPlayback(immediate: Boolean = false, silent: Boolean = false) {
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
                    syncOverlayBubble(userInitiated = false)
                    if (!silent) {
                        renderStatus("Soundpad обновлен.")
                    }
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
        val activePad = currentActivePad()
        statusText.text = buildString {
            append(message)
            append("\n")
            append(if (powerSwitch.checked) "ON" else "OFF")
            append(" • ${currentMode().title}")
            if (boostSlider.progress > 0) {
                append(" • mic ${boostSlider.progress}%")
            }
            if (activePad != null) {
                append(" • pad ${activePad.title}")
            }
        }
    }

    private fun switchPage(targetIndex: Int, animated: Boolean = true) {
        val bounded = targetIndex.coerceIn(0, Page.entries.size - 1)
        val targetPage = Page.entries[bounded]
        if (targetPage == currentPage && flipper.displayedChild == bounded) {
            renderNavBar(animated = true)
            return
        }
        val forward = bounded > currentPage.ordinal
        if (animated) {
            flipper.inAnimation = pageInAnimation(forward)
            flipper.outAnimation = pageOutAnimation(forward)
        } else {
            flipper.inAnimation = null
            flipper.outAnimation = null
        }
        flipper.displayedChild = bounded
        currentPage = targetPage
        renderNavBar(animated = true)
        if (currentPage == Page.SETTINGS && logsEvents.isEmpty()) {
            refreshLogs()
        }
    }

    private fun renderNavBar(animated: Boolean) {
        navButtons.forEach { (page, button) ->
            val selected = page == currentPage
            (0 until button.childCount).forEach { index ->
                val child = button.getChildAt(index) as? TextView ?: return@forEach
                child.setTextColor(if (selected) palette.navSelectedText else palette.navText)
            }
        }
        navBar.post { updateNavIndicator(animated) }
    }

    private fun updateNavIndicator(animated: Boolean) {
        val button = navButtons[currentPage] ?: return
        if (button.width == 0) {
            navBar.post { updateNavIndicator(animated) }
            return
        }
        val params = navIndicator.layoutParams as FrameLayout.LayoutParams
        if (params.width != button.width) {
            params.width = button.width
            navIndicator.layoutParams = params
        }
        val targetX = (navRow.left + button.left).toFloat()
        if (animated) {
            navIndicator.animate()
                .x(targetX)
                .setDuration(240L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            navIndicator.x = targetX
        }
    }

    private fun currentMode(): VoiceMode =
        modeItems.getOrElse(selectedModeIndex) { VoiceMode.default }

    private fun currentActivePad(): SoundpadSlot? =
        soundpadLibrary.slot(soundpadPlayback.activeSlotId)
            ?.takeIf { powerSwitch.checked && soundpadPlayback.playing && it.isReady }

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
                    if (forward) -0.10f else 0.10f,
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

    private fun selectorChip(text: String, selected: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (selected) palette.chipSelectedText else palette.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = rounded(
                if (selected) palette.chipSelected else palette.chipBackground,
                18,
                if (selected) palette.heroAccent.withAlpha(160) else palette.cardStroke,
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
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

    private fun ghostButton(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(palette.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(palette.surface.withAlpha(120), 14, palette.cardStroke)
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
        val dark = when (uiSettings.themeMode) {
            UiThemeMode.SYSTEM -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }

            UiThemeMode.LIGHT -> false
            UiThemeMode.DARK -> true
        }
        val accentBundle = resolveAccentBundle(dark)
        return if (dark) {
            UiPalette(
                backgroundTop = Color.parseColor("#120F0C"),
                backgroundBottom = Color.parseColor("#1D1712"),
                surface = Color.parseColor("#231C16"),
                surfaceAlt = Color.parseColor("#2B221A"),
                surfaceElevated = Color.parseColor("#35281E"),
                heroBackgroundStart = Color.parseColor("#3B2A18").blendWith(accentBundle.accent, 0.20f),
                heroBackgroundEnd = Color.parseColor("#201812").blendWith(accentBundle.alt, 0.10f),
                heroStroke = Color.parseColor("#5A4020").blendWith(accentBundle.accent, 0.32f),
                heroAccent = accentBundle.hero,
                accent = accentBundle.accent,
                accentAlt = accentBundle.alt,
                warning = accentBundle.warning,
                titleText = Color.parseColor("#FFF6E8"),
                primaryText = Color.parseColor("#F0E2D1"),
                secondaryText = Color.parseColor("#B8A28C"),
                chipBackground = Color.parseColor("#31271F"),
                chipSelected = accentBundle.accent,
                chipSelectedText = if (accentBundle.accent.isLight()) Color.parseColor("#1C1209") else Color.WHITE,
                switchOff = Color.parseColor("#5A4D41"),
                sliderTrack = Color.parseColor("#564638"),
                sliderThumb = Color.WHITE,
                cardStroke = Color.parseColor("#423328"),
                heroChipBackground = Color.parseColor("#4A3214"),
                heroChipText = Color.parseColor("#FFE39B"),
                navBackground = Color.parseColor("#18130F").withAlpha(236),
                navStroke = Color.parseColor("#584331").withAlpha(160),
                navBubbleStart = Color.parseColor("#3C2A1A").blendWith(accentBundle.accent, 0.30f),
                navBubbleEnd = Color.parseColor("#20160F").blendWith(accentBundle.alt, 0.14f),
                navBubbleStroke = Color.parseColor("#6A4B24").blendWith(accentBundle.accent, 0.42f),
                navText = Color.parseColor("#D2BEA6"),
                navSelectedText = Color.parseColor("#FFF7EC"),
                slotAccents = intArrayOf(
                    accentBundle.accent,
                    accentBundle.alt,
                    Color.parseColor("#F16E8D"),
                    Color.parseColor("#98E05B"),
                    Color.parseColor("#AA88FF"),
                    Color.parseColor("#FF9A52"),
                ),
            )
        } else {
            UiPalette(
                backgroundTop = Color.parseColor("#F5EAD9"),
                backgroundBottom = Color.parseColor("#E9DCC9"),
                surface = Color.parseColor("#FFF8EE"),
                surfaceAlt = Color.parseColor("#F8EDE0"),
                surfaceElevated = Color.parseColor("#FFFDF8"),
                heroBackgroundStart = Color.parseColor("#F3C95F").blendWith(accentBundle.accent, 0.18f),
                heroBackgroundEnd = Color.parseColor("#DFA14A").blendWith(accentBundle.alt, 0.10f),
                heroStroke = Color.parseColor("#D39C33").blendWith(accentBundle.accent, 0.26f),
                heroAccent = accentBundle.hero,
                accent = accentBundle.accent,
                accentAlt = accentBundle.alt,
                warning = accentBundle.warning,
                titleText = Color.parseColor("#2D2117"),
                primaryText = Color.parseColor("#48372A"),
                secondaryText = Color.parseColor("#7B6757"),
                chipBackground = Color.parseColor("#F3E7D9"),
                chipSelected = accentBundle.accent,
                chipSelectedText = if (accentBundle.accent.isLight()) Color.parseColor("#1E1307") else Color.WHITE,
                switchOff = Color.parseColor("#D7C7B2"),
                sliderTrack = Color.parseColor("#E4D4C2"),
                sliderThumb = Color.WHITE,
                cardStroke = Color.parseColor("#DFC6AA"),
                heroChipBackground = Color.parseColor("#FFF1C8"),
                heroChipText = Color.parseColor("#8D5B00"),
                navBackground = Color.parseColor("#1F1813").withAlpha(236),
                navStroke = Color.parseColor("#5B4330").withAlpha(138),
                navBubbleStart = Color.parseColor("#5C452D").blendWith(accentBundle.accent, 0.34f),
                navBubbleEnd = Color.parseColor("#2D2117").blendWith(accentBundle.alt, 0.12f),
                navBubbleStroke = Color.parseColor("#7D5E3F").blendWith(accentBundle.accent, 0.34f),
                navText = Color.parseColor("#E5D3BF"),
                navSelectedText = Color.WHITE,
                slotAccents = intArrayOf(
                    accentBundle.accent,
                    accentBundle.alt,
                    Color.parseColor("#E86B7A"),
                    Color.parseColor("#63B93B"),
                    Color.parseColor("#8A6CE6"),
                    Color.parseColor("#F18B3A"),
                ),
            )
        }
    }

    private fun resolveAccentBundle(dark: Boolean): AccentBundle {
        val preset = uiSettings.accentPreset
        val fallbackAccent = if (dark) preset.accentDark else preset.accentLight
        val fallbackAlt = if (dark) preset.altDark else preset.altLight
        if (uiSettings.useMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return AccentBundle(
                accent = resources.getColor(
                    if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600,
                    theme,
                ),
                alt = resources.getColor(
                    if (dark) android.R.color.system_accent2_300 else android.R.color.system_accent2_600,
                    theme,
                ),
                hero = resources.getColor(
                    if (dark) android.R.color.system_accent3_300 else android.R.color.system_accent3_500,
                    theme,
                ),
                warning = resources.getColor(
                    if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_700,
                    theme,
                ),
            )
        }
        return AccentBundle(
            accent = fallbackAccent,
            alt = fallbackAlt,
            hero = fallbackAccent.blendWith(fallbackAlt, 0.36f),
            warning = if (dark) Color.parseColor("#FF944F") else Color.parseColor("#E56E2F"),
        )
    }

    private data class AccentBundle(
        val accent: Int,
        val alt: Int,
        val hero: Int,
        val warning: Int,
    )

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
        val navStroke: Int,
        val navBubbleStart: Int,
        val navBubbleEnd: Int,
        val navBubbleStroke: Int,
        val navText: Int,
        val navSelectedText: Int,
        val slotAccents: IntArray,
    )

    private enum class Page(val label: String, val icon: String) {
        HOME("Дом", "⌂"),
        VOICE("Голос", "≈"),
        SOUNDPAD("Pad", "♫"),
        SETTINGS("Тема", "⚙"),
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
            setMeasuredDimension(
                resolveSize(usedWidth + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec),
            )
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
        const val EXTRA_START_PAGE = "start_page"
        const val START_PAGE_SOUNDPAD = 2

        private const val AUTO_SAVE_DELAY_MS = 420L
        private const val SOUNDPAD_SAVE_DELAY_MS = 240L
        private const val MAX_LOG_LINES = 24
    }
}
