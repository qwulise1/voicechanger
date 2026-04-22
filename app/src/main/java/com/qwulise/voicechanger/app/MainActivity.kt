package com.qwulise.voicechanger.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.ModuleInfo
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var moduleInfoText: TextView
    private lateinit var scopeText: TextView
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var restrictSwitch: SwitchMaterial
    private lateinit var modeSpinner: Spinner
    private lateinit var effectValue: TextView
    private lateinit var effectSeek: SeekBar
    private lateinit var gainValue: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var packagesInput: EditText
    private lateinit var logsText: TextView

    private val modeItems = VoiceMode.entries.toList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var suppressUiCallbacks = false
    private var currentModuleInfo: ModuleInfo? = null
    private var currentAvailability = ModuleAvailability(
        releaseInstalled = false,
        debugInstalled = false,
        providerVisible = false,
        providerCallable = false,
    )
    private var lastLogs: List<DiagnosticEvent> = emptyList()
    private val logsRefreshRunnable = object : Runnable {
        override fun run() {
            reloadLogs(showToast = false)
            uiHandler.postDelayed(this, LOG_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4EFE6"))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(18)
            setPadding(padding, dp(20), padding, dp(28))
        }

        statusText = body("")
        moduleInfoText = monoBody("")
        scopeText = monoBody("")
        enabledSwitch = SwitchMaterial(this).apply {
            text = "Включить обработку"
            setOnCheckedChangeListener { _, _ ->
                if (!suppressUiCallbacks) {
                    updateStatusPreview()
                }
            }
        }
        restrictSwitch = SwitchMaterial(this).apply {
            text = "Только выбранные пакеты"
            setOnCheckedChangeListener { _, isChecked ->
                packagesInput.isEnabled = isChecked
                packagesInput.alpha = if (isChecked) 1f else 0.55f
                if (!suppressUiCallbacks) {
                    updateStatusPreview()
                }
            }
        }
        modeSpinner = Spinner(this)
        effectValue = body("")
        effectSeek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(simpleSeekListener { updateStatusPreview() })
        }
        gainValue = body("")
        gainSeek = SeekBar(this).apply {
            max = 200
            setOnSeekBarChangeListener(simpleSeekListener { updateStatusPreview() })
        }
        packagesInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            hint = "org.telegram.messenger\ncom.discord\ncom.whatsapp"
            background = fieldBackground()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        logsText = monoBody("Логи еще не загружены.")

        column.addView(headerBlock())

        panel(
            title = "Подключение",
            subtitle = "Companion показывает не только общий статус, а отдельные признаки: найден ли пакет модуля, виден ли provider и отвечает ли реальный handshake.",
        ).also { panel ->
            panel.addView(statusText)
            panel.addView(divider())
            panel.addView(moduleInfoText)
            column.addView(panel)
        }

        panel(
            title = "Обработка",
            subtitle = "Тут настраивается сам голосовой движок: режим, сила эффекта и усиление микрофона. После сохранения настройки уходят прямо в модуль.",
        ).also { panel ->
            panel.addView(enabledSwitch)
            panel.addView(space(10))
            panel.addView(label("Режим"))
            panel.addView(modeSpinner)
            panel.addView(space(12))
            panel.addView(label("Сила эффекта"))
            panel.addView(effectValue)
            panel.addView(effectSeek)
            panel.addView(space(12))
            panel.addView(label("Усиление микрофона"))
            panel.addView(gainValue)
            panel.addView(gainSeek)
            panel.addView(space(12))
            panel.addView(actionRow())
            column.addView(panel)
        }

        panel(
            title = "LSPosed Scope",
            subtitle = "Это список, который стоит включить в scope модуля в LSPosed. Я вывожу его и здесь, и в метаданных модуля для самого менеджера.",
        ).also { panel ->
            panel.addView(scopeText)
            column.addView(panel)
        }

        panel(
            title = "Маршрутизация",
            subtitle = "Если ограничение выключено, модуль обрабатывает все поддерживаемые приложения в пределах LSPosed scope. Если включено, обработка идет только по списку ниже.",
        ).also { panel ->
            panel.addView(restrictSwitch)
            panel.addView(space(10))
            panel.addView(packagesInput)
            panel.addView(space(12))
            panel.addView(routingActionRow())
            column.addView(panel)
        }

        panel(
            title = "Диагностика",
            subtitle = "Лог нужен, чтобы быстро понять, какой слой сработал: AudioRecord, WebRTC или AAudio. Он обновляется сам, без logcat.",
        ).also { panel ->
            panel.addView(logsActionRow())
            panel.addView(space(10))
            panel.addView(logsText)
            column.addView(panel)
        }

        root.addView(column)
        setContentView(root)

        bindModeSpinner()
        renderScopeHint(ModuleConfigClient.recommendedScope())
        reloadFromModule(showToast = false)
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(logsRefreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(logsRefreshRunnable)
        super.onStop()
    }

    private fun bindModeSpinner() {
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modeItems.map { "${it.title} — ${it.summary}" },
        )
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressUiCallbacks) {
                    updateStatusPreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun actionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START

        addView(actionButton("Сохранить") {
            runCatching {
                ModuleConfigClient.save(this@MainActivity, readConfigFromUi())
            }.onSuccess {
                refreshAvailability()
                applyConfigToUi(it)
                reloadLogs(showToast = false)
                toast("Настройки сохранены в модуль.")
            }.onFailure {
                refreshAvailability()
                toast("Не удалось сохранить настройки. ${failureHint()}")
            }
        })

        addView(actionButton("Обновить") {
            reloadFromModule(showToast = true)
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })

        addView(actionButton("Сброс") {
            runCatching {
                ModuleConfigClient.reset(this@MainActivity)
            }.onSuccess {
                refreshAvailability()
                applyConfigToUi(it)
                toast("Конфиг модуля сброшен.")
            }.onFailure {
                refreshAvailability()
                toast("Не удалось сбросить настройки. ${failureHint()}")
            }
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })
    }

    private fun routingActionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START

        addView(actionButton("Реком. scope") {
            val packages = currentModuleInfo?.recommendedScopes.orEmpty()
                .ifEmpty { ModuleConfigClient.recommendedScope() }
            applyPackagePreset(packages)
            toast("Подставлен рекомендуемый LSPosed scope.")
        })

        addView(actionButton("Из логов") {
            val packages = lastLogs
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .filterNot {
                    it == packageName || it == "com.qwulise.voicechanger.module" || it == "com.qwulise.voicechanger.app"
                }
                .distinct()
                .sorted()
            if (packages.isEmpty()) {
                toast("В логах пока нет пакетов для подстановки.")
                return@actionButton
            }
            applyPackagePreset(packages)
            toast("Список пакетов собран из последних логов.")
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })

        addView(actionButton("Весь scope") {
            suppressUiCallbacks = true
            restrictSwitch.isChecked = false
            packagesInput.isEnabled = false
            packagesInput.alpha = 0.55f
            suppressUiCallbacks = false
            updateStatusPreview()
            toast("Ограничение по пакетам отключено.")
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })
    }

    private fun logsActionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START

        addView(actionButton("Обновить логи") {
            reloadLogs(showToast = true)
        })

        addView(actionButton("Очистить логи") {
            runCatching {
                ModuleConfigClient.clearLogs(this@MainActivity)
            }.onSuccess {
                renderLogs(emptyList())
                refreshAvailability()
                toast("Логи очищены.")
            }.onFailure {
                refreshAvailability()
                toast("Не удалось очистить логи. ${failureHint()}")
            }
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setAllCaps(false)
        minHeight = dp(44)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#103B5A"))
        }
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun reloadFromModule(showToast: Boolean) {
        refreshAvailability()

        runCatching {
            ModuleConfigClient.load(this)
        }.onSuccess {
            applyConfigToUi(it)
        }.onFailure {
            applyConfigToUi(VoiceConfig())
        }

        runCatching {
            ModuleConfigClient.loadModuleInfo(this)
        }.onSuccess {
            renderModuleInfo(it)
        }.onFailure {
            renderModuleInfo(null)
        }

        reloadLogs(showToast = false)

        if (showToast) {
            toast(
                when {
                    currentAvailability.providerCallable -> "Настройки загружены из модуля."
                    currentAvailability.packageInstalled -> "Пакет модуля найден, но provider не отвечает."
                    else -> "Модуль не найден. Установи module-release.apk."
                },
            )
        }
    }

    private fun reloadLogs(showToast: Boolean) {
        refreshAvailability()

        runCatching {
            ModuleConfigClient.loadLogs(this)
        }.onSuccess {
            renderLogs(it)
            if (showToast) {
                toast("Логи обновлены.")
            }
        }.onFailure {
            renderLogs(emptyList())
            if (showToast) {
                toast("Не удалось загрузить логи. ${failureHint()}")
            }
        }
    }

    private fun applyConfigToUi(config: VoiceConfig) {
        suppressUiCallbacks = true
        enabledSwitch.isChecked = config.enabled
        restrictSwitch.isChecked = config.restrictToTargets
        packagesInput.isEnabled = config.restrictToTargets
        packagesInput.alpha = if (config.restrictToTargets) 1f else 0.55f
        packagesInput.setText(config.targetPackages.joinToString("\n"))
        modeSpinner.setSelection(modeItems.indexOf(config.mode).coerceAtLeast(0), false)
        effectSeek.progress = config.effectStrength
        gainSeek.progress = config.micGainPercent
        suppressUiCallbacks = false
        renderValueLabels()
        updateStatusPreview()
    }

    private fun readConfigFromUi(): VoiceConfig = VoiceConfig(
        enabled = enabledSwitch.isChecked,
        modeId = modeItems.getOrElse(modeSpinner.selectedItemPosition) { VoiceMode.default }.id,
        effectStrength = effectSeek.progress,
        micGainPercent = gainSeek.progress,
        restrictToTargets = restrictSwitch.isChecked,
        targetPackages = packagesInput.text.toString()
            .split("\n", ",", ";", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
    ).sanitized()

    private fun renderModuleInfo(info: ModuleInfo?) {
        currentModuleInfo = info
        renderScopeHint(info?.recommendedScopes ?: ModuleConfigClient.recommendedScope())
        moduleInfoText.text = if (info == null) {
            buildString {
                append("Модуль пока не ответил.\n")
                append(currentAvailability.describe())
                append("\n\nПоставь именно пару APK из одного run: module-release и companion-release.")
            }
        } else {
            buildString {
                append("Версия: ${info.versionName} (${info.versionCode})\n")
                append("Активных слоев: ${info.activeTargets.size}\n")
                append("План дальше: ${info.plannedTargets.size}\n")
                append("Handshake: ${currentAvailability.describe()}")
            }
        }
        updateStatusPreview()
    }

    private fun renderScopeHint(packages: List<String>) {
        val normalized = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        scopeText.text = buildString {
            append("Включи в LSPosed вот это:\n")
            append(normalized.joinToString("\n") { "• $it" })
        }
    }

    private fun renderValueLabels() {
        effectValue.text = "Сейчас: ${effectSeek.progress}%"
        gainValue.text = "Сейчас: ${gainSeek.progress}%"
    }

    private fun renderLogs(events: List<DiagnosticEvent>) {
        lastLogs = events
        logsText.text = if (events.isEmpty()) {
            "Логов пока нет. Запусти целевое приложение, дай ему доступ к микрофону и проверь голосовую активность."
        } else {
            events.take(25).joinToString("\n\n") { event ->
                val time = DateFormat.format("HH:mm:ss", Date(event.timestampMs))
                "[$time] ${event.packageName}\n${event.source}\n${event.detail}"
            }
        }
    }

    private fun applyPackagePreset(packages: List<String>) {
        val normalized = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        suppressUiCallbacks = true
        restrictSwitch.isChecked = true
        packagesInput.isEnabled = true
        packagesInput.alpha = 1f
        packagesInput.setText(normalized.joinToString("\n"))
        suppressUiCallbacks = false
        updateStatusPreview()
    }

    private fun refreshAvailability() {
        currentAvailability = ModuleConfigClient.inspect(this)
        updateStatusPreview()
    }

    private fun failureHint(): String =
        when {
            currentAvailability.providerCallable -> currentAvailability.describe()
            currentAvailability.packageInstalled -> "Пакет модуля есть, но provider не отвечает. Переустанови оба release APK из одного run. ${currentAvailability.describe()}"
            else -> "Companion не видит пакет модуля. Установи module-release.apk. ${currentAvailability.describe()}"
        }

    private fun updateStatusPreview() {
        val config = readConfigFromUi()
        statusText.text = buildString {
            append(if (currentAvailability.isAvailable) "Модуль на связи. " else "Модуль оффлайн. ")
            append(currentAvailability.describe())
            append("\n\n")
            append(
                if (config.enabled) {
                    "Активен режим «${config.mode.title}», сила ${config.effectStrength}% и усиление ${config.micGainPercent}%."
                } else {
                    "Обработка выключена."
                },
            )
            append("\n")
            append(
                if (config.restrictToTargets) {
                    "Пакетное ограничение включено: ${config.targetPackages.size} шт."
                } else {
                    "Обработка идет по всему выбранному LSPosed scope."
                },
            )
        }
    }

    private fun simpleSeekListener(onChange: () -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderValueLabels()
                if (!suppressUiCallbacks) {
                    onChange()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun headerBlock(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(10))
        addView(TextView(this@MainActivity).apply {
            text = "Voicechanger"
            setTextColor(Color.parseColor("#102A43"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = "Root companion для LSPosed-модуля. Управляет эффектами, маршрутизацией и live-диагностикой по AudioRecord, WebRTC и AAudio."
            setTextColor(Color.parseColor("#486581"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        })
    }

    private fun panel(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#FFFDF8"))
            setStroke(dp(1), Color.parseColor("#D9E2EC"))
        }
        val padding = dp(16)
        setPadding(padding, padding, padding, padding)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(14)
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(Color.parseColor("#102A43"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            setTextColor(Color.parseColor("#52606D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#D9E2EC"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#102A43"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#243B53"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun monoBody(text: String) = body(text).apply {
        setTypeface(Typeface.MONOSPACE)
    }

    private fun fieldBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#FFFFFF"))
        setStroke(dp(1), Color.parseColor("#BCCCDC"))
    }

    private fun space(valueDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(valueDp),
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val LOG_REFRESH_INTERVAL_MS = 5_000L
    }
}
