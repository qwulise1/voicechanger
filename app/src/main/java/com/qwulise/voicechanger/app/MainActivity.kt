package com.qwulise.voicechanger.app

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceMode

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var modeSpinner: Spinner
    private lateinit var effectValue: TextView
    private lateinit var effectSeek: SeekBar
    private lateinit var gainValue: TextView
    private lateinit var gainSeek: SeekBar

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
        modeSpinner = Spinner(this)
        effectValue = body("")
        effectSeek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(simpleSeekListener { updateStatusPreview() })
        }
        gainValue = body("")
        gainSeek = SeekBar(this).apply {
            max = 200
            setOnSeekBarChangeListener(simpleSeekListener {
                renderValueLabels()
                updateStatusPreview()
            })
        }

        column.addView(title("Voicechanger Companion"))
        column.addView(body("Настройка root-модуля для перехвата микрофона через LSPosed. Сейчас модуль обрабатывает PCM после AudioRecord.read(...), поэтому эффект работает в scoped-приложениях прямо на входе микрофона."))
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
        column.addView(actionRow())
        column.addView(section("Что сейчас умеет"))
        column.addView(body("• Оригинал + микрофонный буст\n• Робот\n• Яркий тембр\n• Глубокий тембр\n• Общая конфигурация через ContentProvider между модулем и companion APK"))
        column.addView(section("Что дальше"))
        column.addView(body("Следующий этап после этого MVP: WebRTC / native hooks, выбор конкретных пакетов, live-диагностика и более сложные pitch/formant эффекты."))

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
                toast("Модуль не найден. Установи Voicechanger Module и открой экран еще раз.")
                updateStatusPreview()
                return@actionButton
            }
            runCatching {
                ModuleConfigClient.save(this@MainActivity, readConfigFromUi())
            }.onSuccess {
                applyConfigToUi(it)
                toast("Настройки сохранены в модуль.")
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
                toast("Конфиг модуля сброшен.")
            }.onFailure {
                toast("Не удалось сбросить настройки: ${it.message ?: it::class.java.simpleName}")
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
            if (showToast) {
                toast("Модуль пока не установлен или не виден системе.")
            }
            return
        }

        runCatching {
            ModuleConfigClient.load(this)
        }.onSuccess {
            applyConfigToUi(it)
            if (showToast) {
                toast("Настройки загружены из модуля.")
            }
        }.onFailure {
            applyConfigToUi(VoiceConfig())
            toast("Не удалось загрузить конфиг модуля: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun applyConfigToUi(config: VoiceConfig) {
        suppressUiCallbacks = true
        enabledSwitch.isChecked = config.enabled
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
    ).sanitized()

    private fun renderValueLabels() {
        effectValue.text = "Текущая сила: ${effectSeek.progress}%"
        gainValue.text = "Текущий уровень: ${gainSeek.progress}%"
    }

    private fun updateStatusPreview() {
        val available = ModuleConfigClient.isModuleAvailable(this)
        val config = readConfigFromUi()
        statusText.text = buildString {
            append(if (available) "Модуль найден. " else "Модуль не найден. ")
            append(
                if (config.enabled) {
                    "Сейчас выбран режим «${config.mode.title}», сила ${config.effectStrength}% и усиление микрофона ${config.micGainPercent}%."
                } else {
                    "Обработка выключена. После включения можно использовать любой режим и микрофонный буст."
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
