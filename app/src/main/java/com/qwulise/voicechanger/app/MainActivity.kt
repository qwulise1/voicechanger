package com.qwulise.voicechanger.app

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.provider.MediaStore
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
    private lateinit var palette: UiPalette

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
        palette = resolvePalette()

        val root = ScrollView(this).apply {
            setBackgroundColor(palette.background)
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
            subtitle = "Лог нужен, чтобы быстро понять, какой слой сработал: AudioRecord или WebRTC. Он обновляется сам, без logcat.",
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
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START

        addView(actionButton("Сохранить настройки") {
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

        addView(actionButton("Обновить из модуля") {
            reloadFromModule(showToast = true)
        })

        addView(actionButton("Сбросить настройки") {
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
        })
    }

    private fun routingActionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
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
        })

        addView(actionButton("Весь scope") {
            suppressUiCallbacks = true
            restrictSwitch.isChecked = false
            packagesInput.isEnabled = false
            packagesInput.alpha = 0.55f
            suppressUiCallbacks = false
            updateStatusPreview()
            toast("Ограничение по пакетам отключено.")
        })
    }

    private fun logsActionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START

        addView(actionButton("Обновить логи") {
            reloadLogs(showToast = true)
        })

        addView(actionButton("Экспорт логов в Downloads") {
            runCatching {
                exportLogsToDownloads()
            }.onSuccess { uri ->
                toast("Логи экспортированы: ${uri.lastPathSegment ?: uri.toString()}")
            }.onFailure {
                toast("Не удалось экспортировать логи: ${it.message ?: it::class.java.simpleName}")
            }
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
        })
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setAllCaps(false)
        isSingleLine = false
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(8)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(palette.buttonBackground)
        }
        setTextColor(palette.buttonText)
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

    private fun exportLogsToDownloads(): Uri {
        val events = lastLogs.ifEmpty { ModuleConfigClient.loadLogs(this) }
        val logText = if (events.isEmpty()) {
            "Логов пока нет."
        } else {
            events.joinToString("\n\n") { event ->
                val time = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(event.timestampMs))
                "[$time] ${event.packageName}\n${event.source}\n${event.detail}"
            }
        }
        val name = "voicechanger-logs-${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Не удалось создать файл в Downloads"
        }
        contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
            requireNotNull(writer) { "Не удалось открыть файл для записи" }
            writer.write(logText)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
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
            setTextColor(palette.titleText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = "Root companion для LSPosed-модуля. Управляет эффектами, маршрутизацией и live-диагностикой по стабильным слоям AudioRecord и WebRTC."
            setTextColor(palette.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        })
    }

    private fun panel(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
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
            bottomMargin = dp(14)
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(palette.titleText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            setTextColor(palette.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.panelStroke)
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
        setTextColor(palette.titleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(palette.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(3).toFloat(), 1.0f)
    }

    private fun monoBody(text: String) = body(text).apply {
        setTypeface(Typeface.MONOSPACE)
    }

    private fun fieldBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(palette.fieldBackground)
        setStroke(dp(1), palette.fieldStroke)
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

    private data class UiPalette(
        val background: Int,
        val panelBackground: Int,
        val panelStroke: Int,
        val titleText: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val fieldBackground: Int,
        val fieldStroke: Int,
        val buttonBackground: Int,
        val buttonText: Int,
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
                secondaryText = Color.parseColor("#93A4B5"),
                fieldBackground = Color.parseColor("#0F1720"),
                fieldStroke = Color.parseColor("#2A3A49"),
                buttonBackground = Color.parseColor("#2D7FF9"),
                buttonText = Color.WHITE,
            )
        } else {
            UiPalette(
                background = Color.parseColor("#F4EFE6"),
                panelBackground = Color.parseColor("#FFFDF8"),
                panelStroke = Color.parseColor("#D9E2EC"),
                titleText = Color.parseColor("#102A43"),
                primaryText = Color.parseColor("#243B53"),
                secondaryText = Color.parseColor("#52606D"),
                fieldBackground = Color.parseColor("#FFFFFF"),
                fieldStroke = Color.parseColor("#BCCCDC"),
                buttonBackground = Color.parseColor("#103B5A"),
                buttonText = Color.WHITE,
            )
        }
    }
}
