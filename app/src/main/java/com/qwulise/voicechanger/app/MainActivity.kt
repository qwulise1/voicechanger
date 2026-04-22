package com.qwulise.voicechanger.app

import android.graphics.Typeface
import android.os.Bundle
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
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
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
    private var suppressUiCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
        }

        statusText = body("")
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
                packagesInput.alpha = if (isChecked) 1f else 0.6f
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
        }
        logsText = body("Логи еще не загружены.").apply {
            setTypeface(Typeface.MONOSPACE)
        }

        column.addView(title("Voicechanger Companion"))
        column.addView(body("Companion APK для LSPosed-модуля. Здесь можно включать обработку, ограничивать ее по пакетам и смотреть живую диагностику того, в каком приложении сработали хуки."))
        column.addView(section("Статус"))
        column.addView(statusText)
        column.addView(section("Обработка"))
        column.addView(enabledSwitch)
        column.addView(section("Режим"))
        column.addView(modeSpinner)
        column.addView(section("Сила эффекта"))
        column.addView(effectValue)
        column.addView(effectSeek)
        column.addView(section("Усиление микрофона"))
        column.addView(gainValue)
        column.addView(gainSeek)
        column.addView(section("Маршрутизация"))
        column.addView(restrictSwitch)
        column.addView(body("Если переключатель выключен, модуль обрабатывает все поддерживаемые приложения. Если включен, обработка идет только в пакетах из списка ниже."))
        column.addView(packagesInput)
        column.addView(actionRow())
        column.addView(section("Диагностика"))
        column.addView(body("Лог показывает последние срабатывания хуков, найденные WebRTC-стэки и активные пакеты. Это помогает понять, цепляется ли приложение без логката."))
        column.addView(logsActionRow())
        column.addView(logsText)

        root.addView(column)
        setContentView(root)

        bindModeSpinner()
        reloadFromModule(showToast = false)
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
        setPadding(0, dp(18), 0, 0)

        addView(actionButton("Сохранить") {
            if (!ModuleConfigClient.isModuleAvailable(this@MainActivity)) {
                toast("Модуль не найден. Установи Voicechanger Module и открой экран снова.")
                updateStatusPreview()
                return@actionButton
            }
            runCatching {
                ModuleConfigClient.save(this@MainActivity, readConfigFromUi())
            }.onSuccess {
                applyConfigToUi(it)
                reloadLogs(showToast = false)
                toast("Настройки сохранены.")
            }.onFailure {
                toast("Не удалось сохранить настройки: ${it.message ?: it::class.java.simpleName}")
            }
        })

        addView(actionButton("Обновить") {
            reloadFromModule(showToast = true)
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })

        addView(actionButton("Сброс") {
            if (!ModuleConfigClient.isModuleAvailable(this@MainActivity)) {
                toast("Модуль не найден.")
                updateStatusPreview()
                return@actionButton
            }
            runCatching {
                ModuleConfigClient.reset(this@MainActivity)
            }.onSuccess {
                applyConfigToUi(it)
                toast("Конфиг сброшен.")
            }.onFailure {
                toast("Не удалось сбросить настройки: ${it.message ?: it::class.java.simpleName}")
            }
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })
    }

    private fun logsActionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        setPadding(0, dp(12), 0, dp(6))

        addView(actionButton("Обновить логи") {
            reloadLogs(showToast = true)
        })

        addView(actionButton("Очистить логи") {
            if (!ModuleConfigClient.isModuleAvailable(this@MainActivity)) {
                toast("Модуль не найден.")
                return@actionButton
            }
            runCatching {
                ModuleConfigClient.clearLogs(this@MainActivity)
            }.onSuccess {
                renderLogs(emptyList())
                toast("Логи очищены.")
            }.onFailure {
                toast("Не удалось очистить логи: ${it.message ?: it::class.java.simpleName}")
            }
        }.apply {
            setPadding(dp(10), paddingTop, paddingRight, paddingBottom)
        })
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun reloadFromModule(showToast: Boolean) {
        if (!ModuleConfigClient.isModuleAvailable(this)) {
            applyConfigToUi(VoiceConfig())
            renderLogs(emptyList())
            if (showToast) {
                toast("Модуль пока не установлен или не виден системе.")
            }
            return
        }

        runCatching {
            ModuleConfigClient.load(this)
        }.onSuccess {
            applyConfigToUi(it)
            reloadLogs(showToast = false)
            if (showToast) {
                toast("Настройки загружены из модуля.")
            }
        }.onFailure {
            applyConfigToUi(VoiceConfig())
            renderLogs(emptyList())
            toast("Не удалось загрузить конфиг модуля: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun reloadLogs(showToast: Boolean) {
        if (!ModuleConfigClient.isModuleAvailable(this)) {
            renderLogs(emptyList())
            return
        }

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
                toast("Не удалось загрузить логи: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun applyConfigToUi(config: VoiceConfig) {
        suppressUiCallbacks = true
        enabledSwitch.isChecked = config.enabled
        restrictSwitch.isChecked = config.restrictToTargets
        packagesInput.isEnabled = config.restrictToTargets
        packagesInput.alpha = if (config.restrictToTargets) 1f else 0.6f
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

    private fun renderValueLabels() {
        effectValue.text = "Текущая сила: ${effectSeek.progress}%"
        gainValue.text = "Текущий уровень: ${gainSeek.progress}%"
    }

    private fun renderLogs(events: List<DiagnosticEvent>) {
        logsText.text = if (events.isEmpty()) {
            "Логов пока нет. Запусти целевое приложение, дай ему доступ к микрофону и попробуй голосовую активность."
        } else {
            events.take(25).joinToString("\n\n") { event ->
                val time = DateFormat.format("HH:mm:ss", Date(event.timestampMs))
                "[$time] ${event.packageName}\n${event.source}\n${event.detail}"
            }
        }
    }

    private fun updateStatusPreview() {
        val available = ModuleConfigClient.isModuleAvailable(this)
        val config = readConfigFromUi()
        statusText.text = buildString {
            append(if (available) "Модуль найден. " else "Модуль не найден. ")
            append(
                if (config.enabled) {
                    "Режим «${config.mode.title}», сила ${config.effectStrength}% и усиление ${config.micGainPercent}%. "
                } else {
                    "Обработка выключена. "
                },
            )
            append(
                if (config.restrictToTargets) {
                    "Ограничение по пакетам включено: ${config.targetPackages.size} шт."
                } else {
                    "Обработка будет идти во всех поддерживаемых пакетах."
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
