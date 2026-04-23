package com.qwulise.voicechanger.core

import android.net.Uri
import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

enum class VoiceMode(
    val id: String,
    val title: String,
    val summary: String,
    val telegraphId: Int,
) {
    ORIGINAL(
        id = "original",
        title = "Оригинал",
        summary = "Без тембрального эффекта, только усиление микрофона.",
        telegraphId = 0,
    ),
    CHILD(
        id = "child",
        title = "Детский",
        summary = "Высокий яркий голос без металлической окраски.",
        telegraphId = 6,
    ),
    MOUSE(
        id = "mouse",
        title = "Мышь",
        summary = "Резкий мультяшный тон с явным подъемом голоса.",
        telegraphId = 7,
    ),
    MALE(
        id = "male",
        title = "Мужской",
        summary = "Ниже и плотнее, без роботизации.",
        telegraphId = 8,
    ),
    FEMALE(
        id = "female",
        title = "Женский",
        summary = "Более высокий и яркий тембр без ухода в ребенка.",
        telegraphId = 9,
    ),
    MONSTER(
        id = "monster",
        title = "Монстр",
        summary = "Сильно сниженный тембр с плотным низом.",
        telegraphId = 10,
    ),
    ROBOT(
        id = "robot",
        title = "Робот",
        summary = "Металлическая модуляция с грубым цифровым оттенком.",
        telegraphId = 2,
    ),
    ALIEN(
        id = "alien",
        title = "Пришелец",
        summary = "Высокий голос с нестабильной модуляцией.",
        telegraphId = 3,
    ),
    HOARSE(
        id = "hoarse",
        title = "Хриплый",
        summary = "Грязная верхняя атака и шероховатость.",
        telegraphId = 4,
    ),
    ECHO(
        id = "echo",
        title = "Эхо",
        summary = "Короткий повтор поверх голоса.",
        telegraphId = 11,
    ),
    NOISE(
        id = "noise",
        title = "Шум",
        summary = "Шумовая примесь с высокочастотной фильтрацией.",
        telegraphId = 12,
    ),
    HELIUM(
        id = "helium",
        title = "Гелий",
        summary = "Telegraph-стиль: чистый подъем на +12 полутонов.",
        telegraphId = 13,
    ),
    PURR(
        id = "purr",
        title = "Мур",
        summary = "Копия гелия с тем самым вертолетным оттенком.",
        telegraphId = 13,
    ),
    HEXAFLUORIDE(
        id = "hexafluoride",
        title = "Гексафторид",
        summary = "Telegraph-стиль: тяжелое понижение на -5 полутонов.",
        telegraphId = 14,
    ),
    CAVE(
        id = "cave",
        title = "Пещера",
        summary = "Объемная задержка и темный хвост.",
        telegraphId = 15,
    ),
    SPEED(
        id = "speed",
        title = "Ускорение",
        summary = "Небольшой подъем тона и темпа восприятия.",
        telegraphId = 1,
    ),
    CUSTOM(
        id = "custom",
        title = "Своя модуляция",
        summary = "Слайдер эффекта задает тон от -12 до +12 полутонов.",
        telegraphId = 5,
    ),
    ;

    companion object {
        val default: VoiceMode = ORIGINAL

        fun fromId(id: String?): VoiceMode =
            when (id) {
                "bright" -> HELIUM
                "deep" -> MALE
                else -> entries.firstOrNull { it.id == id } ?: default
            }
    }
}

data class VoiceProfile(
    val id: String,
    val name: String,
    val summary: String,
)

object VoiceProfileCatalog {
    val defaultProfiles: List<VoiceProfile> = VoiceMode.entries.map {
        VoiceProfile(it.id, it.title, it.summary)
    }
}

data class VoiceConfig(
    val enabled: Boolean = false,
    val modeId: String = VoiceMode.default.id,
    val effectStrength: Int = 100,
    val micGainPercent: Int = 0,
    val restrictToTargets: Boolean = false,
    val targetPackages: Set<String> = emptySet(),
    val vendorHalEnabled: Boolean = false,
    val vendorHalParam: String = DEFAULT_VENDOR_HAL_PARAM,
    val vendorHalLoopback: Boolean = false,
) {
    val mode: VoiceMode
        get() = VoiceMode.fromId(modeId)

    fun sanitized(): VoiceConfig = copy(
        effectStrength = effectStrength.coerceIn(0, 100),
        micGainPercent = micGainPercent.coerceIn(0, 101),
        targetPackages = targetPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet(),
        modeId = mode.id,
        vendorHalParam = vendorHalParam.trim(),
    )

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(VoiceConfigContract.KEY_ENABLED, enabled)
        putString(VoiceConfigContract.KEY_MODE_ID, mode.id)
        putInt(VoiceConfigContract.KEY_EFFECT_STRENGTH, effectStrength.coerceIn(0, 100))
        putInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, micGainPercent.coerceIn(0, 101))
        putBoolean(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, restrictToTargets)
        putStringArrayList(
            VoiceConfigContract.KEY_TARGET_PACKAGES,
            ArrayList(targetPackages.map { it.trim() }.filter { it.isNotEmpty() }.sorted()),
        )
        putBoolean(VoiceConfigContract.KEY_VENDOR_HAL_ENABLED, vendorHalEnabled)
        putString(VoiceConfigContract.KEY_VENDOR_HAL_PARAM, vendorHalParam.trim())
        putBoolean(VoiceConfigContract.KEY_VENDOR_HAL_LOOPBACK, vendorHalLoopback)
    }

    companion object {
        const val DEFAULT_VENDOR_HAL_PARAM =
            "HTz5CcMNnLwx0cokMdR3tGT0F7Eh4=c0xwLnNMcC5zCGxKR8UEvAhLwx0cuA"

        fun fromBundle(bundle: Bundle?): VoiceConfig =
            VoiceConfig(
                enabled = bundle?.getBoolean(VoiceConfigContract.KEY_ENABLED, false) ?: false,
                modeId = bundle?.getString(VoiceConfigContract.KEY_MODE_ID) ?: VoiceMode.default.id,
                effectStrength = bundle?.getInt(VoiceConfigContract.KEY_EFFECT_STRENGTH, 100) ?: 100,
                micGainPercent = bundle?.getInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, 0) ?: 0,
                restrictToTargets = bundle?.getBoolean(VoiceConfigContract.KEY_RESTRICT_TO_TARGETS, false) ?: false,
                targetPackages = bundle?.getStringArrayList(VoiceConfigContract.KEY_TARGET_PACKAGES)?.toSet() ?: emptySet(),
                vendorHalEnabled = bundle?.getBoolean(VoiceConfigContract.KEY_VENDOR_HAL_ENABLED, false) ?: false,
                vendorHalParam = bundle?.getString(VoiceConfigContract.KEY_VENDOR_HAL_PARAM) ?: DEFAULT_VENDOR_HAL_PARAM,
                vendorHalLoopback = bundle?.getBoolean(VoiceConfigContract.KEY_VENDOR_HAL_LOOPBACK, false) ?: false,
            ).sanitized()
    }
}

data class DiagnosticEvent(
    val timestampMs: Long,
    val packageName: String,
    val source: String,
    val detail: String,
) {
    fun encode(): String = listOf(
        timestampMs.toString(),
        escapeLogField(packageName),
        escapeLogField(source),
        escapeLogField(detail),
    ).joinToString(LOG_FIELD_SEPARATOR.toString())

    companion object {
        private const val LOG_FIELD_SEPARATOR = '\u001f'

        fun decode(line: String): DiagnosticEvent? {
            val parts = line.split(LOG_FIELD_SEPARATOR)
            if (parts.size != 4) {
                return null
            }
            val timestamp = parts[0].toLongOrNull() ?: return null
            return DiagnosticEvent(
                timestampMs = timestamp,
                packageName = unescapeLogField(parts[1]),
                source = unescapeLogField(parts[2]),
                detail = unescapeLogField(parts[3]),
            )
        }

        private fun escapeLogField(value: String): String =
            value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace(LOG_FIELD_SEPARATOR.toString(), "\\u001f")

        private fun unescapeLogField(value: String): String =
            value.replace("\\u001f", LOG_FIELD_SEPARATOR.toString())
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
    }
}

data class ModuleInfo(
    val versionName: String = "unknown",
    val versionCode: Long = 0,
    val activeTargets: List<String> = emptyList(),
    val plannedTargets: List<String> = emptyList(),
    val recommendedScopes: List<String> = emptyList(),
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(VoiceConfigContract.KEY_MODULE_VERSION_NAME, versionName)
        putLong(VoiceConfigContract.KEY_MODULE_VERSION_CODE, versionCode)
        putStringArrayList(VoiceConfigContract.KEY_ACTIVE_TARGETS, ArrayList(activeTargets))
        putStringArrayList(VoiceConfigContract.KEY_PLANNED_TARGETS, ArrayList(plannedTargets))
        putStringArrayList(VoiceConfigContract.KEY_RECOMMENDED_SCOPES, ArrayList(recommendedScopes))
    }

    companion object {
        fun fromBundle(bundle: Bundle?): ModuleInfo =
            ModuleInfo(
                versionName = bundle?.getString(VoiceConfigContract.KEY_MODULE_VERSION_NAME).orEmpty()
                    .ifBlank { "unknown" },
                versionCode = bundle?.getLong(VoiceConfigContract.KEY_MODULE_VERSION_CODE, 0) ?: 0,
                activeTargets = bundle?.getStringArrayList(VoiceConfigContract.KEY_ACTIVE_TARGETS)
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                plannedTargets = bundle?.getStringArrayList(VoiceConfigContract.KEY_PLANNED_TARGETS)
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                recommendedScopes = bundle?.getStringArrayList(VoiceConfigContract.KEY_RECOMMENDED_SCOPES)
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
            )
    }
}

object VoiceConfigContract {
    const val AUTHORITY = "com.qwulivoice.beta.config"
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")

    const val METHOD_GET_CONFIG = "get_config"
    const val METHOD_PUT_CONFIG = "put_config"
    const val METHOD_RESET_CONFIG = "reset_config"
    const val METHOD_GET_MODULE_INFO = "get_module_info"
    const val METHOD_GET_LOGS = "get_logs"
    const val METHOD_CLEAR_LOGS = "clear_logs"
    const val METHOD_APPEND_LOG = "append_log"

    const val KEY_ENABLED = "enabled"
    const val KEY_MODE_ID = "mode_id"
    const val KEY_EFFECT_STRENGTH = "effect_strength"
    const val KEY_MIC_GAIN_PERCENT = "mic_gain_percent"
    const val KEY_RESTRICT_TO_TARGETS = "restrict_to_targets"
    const val KEY_TARGET_PACKAGES = "target_packages"
    const val KEY_VENDOR_HAL_ENABLED = "vendor_hal_enabled"
    const val KEY_VENDOR_HAL_PARAM = "vendor_hal_param"
    const val KEY_VENDOR_HAL_LOOPBACK = "vendor_hal_loopback"
    const val KEY_LOG_LINES = "log_lines"
    const val KEY_LOG_PACKAGE_NAME = "log_package_name"
    const val KEY_LOG_SOURCE = "log_source"
    const val KEY_LOG_DETAIL = "log_detail"
    const val KEY_LOG_TIMESTAMP_MS = "log_timestamp_ms"
    const val KEY_MODULE_VERSION_NAME = "module_version_name"
    const val KEY_MODULE_VERSION_CODE = "module_version_code"
    const val KEY_ACTIVE_TARGETS = "active_targets"
    const val KEY_PLANNED_TARGETS = "planned_targets"
    const val KEY_RECOMMENDED_SCOPES = "recommended_scopes"
}

class VoiceProcessingState {
    var phase: Double = 0.0
    var phase2: Double = 0.0
    var lowPass: Float = 0f
    var lowPass2: Float = 0f
    var previousInput: Float = 0f
    var hpPrevInput: Float = 0f
    var hpPrevOutput: Float = 0f
    var wasPositive: Boolean = false
    var subPolarity: Float = 1f
    var sampleHoldCounter: Int = 0
    var sampleHoldValue: Float = 0f
    var delayPos: Int = 0
    var readInitialized: Boolean = false
    var readPos: Float = 0f
    var xfadeOldPos: Float = 0f
    var xfadeRemaining: Int = 0
    var pitchPhase: Float = 0f
    var written: Long = 0L
    var sampleRate: Int = 0
    var lastModeId: String = ""
    var noiseState: Int = 324508639
    var ring: FloatArray = FloatArray(DEFAULT_RING_SIZE)
    var delay: FloatArray = FloatArray(DEFAULT_DELAY_SIZE)

    fun prepare(rate: Int, mode: VoiceMode) {
        val safeRate = rate.coerceAtLeast(8_000)
        if (sampleRate != safeRate) {
            sampleRate = safeRate
            ring = FloatArray(nextPowerOfTwo(max(DEFAULT_RING_SIZE, safeRate * 2)))
            delay = FloatArray(max(DEFAULT_DELAY_SIZE, safeRate))
            resetModeState(mode)
            written = 0L
            return
        }
        if (lastModeId != mode.id) {
            resetModeState(mode)
        }
    }

    fun writeRing(sample: Float) {
        ring[written.toInt() and (ring.size - 1)] = sample
        written++
    }

    private fun resetModeState(mode: VoiceMode) {
        phase = 0.0
        phase2 = 0.0
        lowPass = 0f
        lowPass2 = 0f
        previousInput = 0f
        hpPrevInput = 0f
        hpPrevOutput = 0f
        wasPositive = false
        subPolarity = 1f
        sampleHoldCounter = 0
        sampleHoldValue = 0f
        delayPos = 0
        readInitialized = false
        readPos = 0f
        xfadeOldPos = 0f
        xfadeRemaining = 0
        pitchPhase = 0f
        delay.fill(0f)
        lastModeId = mode.id
    }

    companion object {
        private const val DEFAULT_RING_SIZE = 65_536
        private const val DEFAULT_DELAY_SIZE = 48_000

        private fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) {
                result = result shl 1
            }
            return result
        }
    }
}

object PcmVoiceProcessor {
    private var floatBuffer = FloatArray(2048)

    fun processShortArray(
        samples: ShortArray,
        offset: Int,
        count: Int,
        sampleRate: Int,
        config: VoiceConfig,
        state: VoiceProcessingState,
    ) {
        if (!config.enabled || count <= 0 || offset !in samples.indices) {
            return
        }

        val safeCount = count.coerceAtMost(samples.size - offset)
        repeat(safeCount) { index ->
            val input = samples[offset + index] / 32768f
            val output = processSample(input, sampleRate, config, state)
            samples[offset + index] = (output * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    fun processFloatArray(
        samples: FloatArray,
        offset: Int,
        count: Int,
        sampleRate: Int,
        config: VoiceConfig,
        state: VoiceProcessingState,
    ) {
        if (!config.enabled || count <= 0 || offset !in samples.indices) {
            return
        }

        val safeCount = count.coerceAtMost(samples.size - offset)
        repeat(safeCount) { index ->
            samples[offset + index] = processSample(samples[offset + index], sampleRate, config, state)
        }
    }

    fun processByteArrayPcm16(
        buffer: ByteArray,
        offsetBytes: Int,
        byteCount: Int,
        sampleRate: Int,
        config: VoiceConfig,
        state: VoiceProcessingState,
    ) {
        if (!config.enabled || byteCount < 2 || offsetBytes !in buffer.indices) {
            return
        }

        val safeByteCount = byteCount
            .coerceAtMost(buffer.size - offsetBytes)
            .let { it - (it % 2) }

        var cursor = offsetBytes
        val end = offsetBytes + safeByteCount
        while (cursor < end) {
            val raw = (buffer[cursor].toInt() and 0xFF) or (buffer[cursor + 1].toInt() shl 8)
            val sample = raw.toShort().toInt() / 32768f
            val output = processSample(sample, sampleRate, config, state)
            val packed = (output * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
                .toInt()
            buffer[cursor] = (packed and 0xFF).toByte()
            buffer[cursor + 1] = ((packed shr 8) and 0xFF).toByte()
            cursor += 2
        }
    }

    fun processByteBufferPcm16(
        buffer: ByteBuffer,
        byteCount: Int,
        sampleRate: Int,
        config: VoiceConfig,
        state: VoiceProcessingState,
    ) {
        if (!config.enabled || byteCount < 2 || !buffer.isDirect && !buffer.hasArray()) {
            return
        }

        val safeByteCount = byteCount - (byteCount % 2)
        if (safeByteCount <= 0) {
            return
        }

        if (buffer.hasArray()) {
            val offset = buffer.arrayOffset()
            processByteArrayPcm16(buffer.array(), offset, safeByteCount, sampleRate, config, state)
            return
        }

        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = safeByteCount / 2
        ensureScratch(sampleCount)
        duplicate.position(0)
        repeat(sampleCount) { index ->
            floatBuffer[index] = duplicate.short / 32768f
        }
        repeat(sampleCount) { index ->
            floatBuffer[index] = processSample(floatBuffer[index], sampleRate, config, state)
        }
        duplicate.position(0)
        repeat(sampleCount) { index ->
            val packed = (floatBuffer[index] * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            duplicate.putShort(packed)
        }
    }

    private fun processSample(
        input: Float,
        sampleRate: Int,
        config: VoiceConfig,
        state: VoiceProcessingState,
    ): Float {
        val rate = sampleRate.coerceAtLeast(8_000)
        val mode = config.mode
        val strength = config.effectStrength.coerceIn(0, 100) / 100f
        state.prepare(rate, mode)
        state.writeRing(input)

        val wet = if (mode == VoiceMode.CUSTOM) 1f else strength.coerceIn(0f, 1f)
        val effected = if (mode == VoiceMode.ORIGINAL) {
            input
        } else {
            processTelegraphMode(input, mode, config.effectStrength, state)
        }
        val mixed = (input * (1f - wet)) + (effected * wet)
        return applyMicBoost(mixed, config.micGainPercent)
    }

    private fun processTelegraphMode(
        input: Float,
        mode: VoiceMode,
        effectStrength: Int,
        state: VoiceProcessingState,
    ): Float = when (mode) {
        VoiceMode.ORIGINAL -> input
        VoiceMode.SPEED -> brighten(transposeSemitones(2f, state), 0.10f, state)
        VoiceMode.ROBOT -> robot(input, state)
        VoiceMode.ALIEN -> alien(state)
        VoiceMode.HOARSE -> hoarse(input, state)
        VoiceMode.CUSTOM -> customPitch(effectStrength, state)
        VoiceMode.CHILD -> brighten(transposeSemitones(9f, state), 0.16f, state)
        VoiceMode.MOUSE -> brighten(transposeSemitones(11f, state), 0.22f, state)
        VoiceMode.MALE -> darken(transposeSemitones(-3f, state), 0.09f, 1.18f, state)
        VoiceMode.FEMALE -> brighten(transposeSemitones(3f, state), 0.12f, state)
        VoiceMode.MONSTER -> darken(transposeSemitones(-8f, state), 0.06f, 1.42f, state)
        VoiceMode.ECHO -> echo(input, 0.18f, 0.45f, 0.52f, state)
        VoiceMode.NOISE -> noise(input, state)
        VoiceMode.HELIUM -> brighten(transposeSemitones(12f, state), 0.24f, state)
        VoiceMode.PURR -> brighten(legacyPitched(1.42f, state), 0.36f, state)
        VoiceMode.HEXAFLUORIDE -> darken(transposeSemitones(-5f, state), 0.06f, 1.36f, state)
        VoiceMode.CAVE -> cave(input, state)
    }

    private fun customPitch(effectStrength: Int, state: VoiceProcessingState): Float {
        val semitones = ((effectStrength.coerceIn(0, 100) - 50) / 50f) * 12f
        val ratio = 2.0.pow((semitones / 12f).toDouble()).toFloat()
        val shifted = granularPitched(ratio, state)
        return if (ratio >= 1f) {
            brighten(shifted, 0.22f, state)
        } else {
            darken(shifted, 0.08f, 1.18f, state)
        }
    }

    private fun robot(input: Float, state: VoiceProcessingState): Float =
        softClip(quantize(input * ((osc1(78f, state) * 0.65f) + 0.35f), 24f) * 1.35f)

    private fun alien(state: VoiceProcessingState): Float =
        softClip(
            sampleHold(
                legacyPitched(1.22f, state) *
                    ((((osc1(33f, state) * 0.6f) + (osc2(91f, state) * 0.4f)) * 0.55f) + 0.45f),
                3,
                state,
            ) * 1.25f,
        )

    private fun hoarse(input: Float, state: VoiceProcessingState): Float =
        softClip(highPass(input + (nextNoise(state) * 0.1f * abs(0.08f + input)), 0.986f, state) * 1.7f)

    private fun noise(input: Float, state: VoiceProcessingState): Float =
        softClip(highPass(input + (nextNoise(state) * 0.18f), 0.972f, state) * 1.16f)

    private fun cave(input: Float, state: VoiceProcessingState): Float {
        val delay1 = readDelay((state.sampleRate * 0.11f).toInt(), state)
        val delay2 = readDelay((state.sampleRate * 0.23f).toInt(), state)
        val delay3 = readDelay((state.sampleRate * 0.41f).toInt(), state)
        val mid = delay2 * 0.22f
        writeDelay(softClip(input + (delay1 * 0.42f) + mid), state)
        return softClip((input * 0.7f) + (delay1 * 0.38f) + mid + (delay3 * 0.14f))
    }

    private fun echo(input: Float, seconds: Float, feedback: Float, mix: Float, state: VoiceProcessingState): Float {
        val delayed = readDelay((state.sampleRate * seconds).toInt(), state)
        writeDelay(softClip(input + (delayed * feedback)), state)
        return softClip(input + (delayed * mix))
    }

    private fun transposeSemitones(semitones: Float, state: VoiceProcessingState): Float =
        granularPitched(2.0.pow((semitones / 12f).toDouble()).toFloat(), state)

    private fun granularPitched(ratio: Float, state: VoiceProcessingState): Float {
        if (ratio <= 0f) {
            return 0f
        }
        if (abs(ratio - 1f) < 0.015f) {
            return sampleAt((state.written - 1).toFloat(), state)
        }

        val grain = (state.sampleRate * 0.045f).coerceIn(360f, state.ring.size / 4f)
        val minDelay = (state.sampleRate * 0.018f).coerceAtLeast(96f)
        val maxDelay = minDelay + grain
        if (state.written.toFloat() < maxDelay + 4f) {
            return sampleAt((state.written - 1).toFloat(), state)
        }

        val phaseStep = (abs(ratio - 1f) / grain).coerceAtLeast(0.00001f)
        state.pitchPhase = (state.pitchPhase + phaseStep) % 1f

        fun readGrain(phase: Float): Float {
            val delay = if (ratio >= 1f) {
                maxDelay - (phase * grain)
            } else {
                minDelay + (phase * grain)
            }
            return sampleAt(state.written.toFloat() - delay, state)
        }

        val phaseA = state.pitchPhase
        val phaseB = (phaseA + 0.5f) % 1f
        val weightA = raisedSine(phaseA)
        val weightB = raisedSine(phaseB)
        val total = (weightA + weightB).coerceAtLeast(0.0001f)
        return ((readGrain(phaseA) * weightA) + (readGrain(phaseB) * weightB)) / total
    }

    private fun raisedSine(phase: Float): Float =
        (0.5 - (0.5 * cos(phase * 2.0 * PI))).toFloat().coerceIn(0f, 1f)

    private fun legacyPitched(ratio: Float, state: VoiceProcessingState): Float {
        if (ratio <= 0f || state.written <= 2L) {
            return 0f
        }
        if (!state.readInitialized) {
            state.readPos = state.written.toFloat() - (state.sampleRate * 0.06f)
            state.readInitialized = true
        }
        val minReadable = state.written.toFloat() - state.ring.size + 32f
        val maxReadable = max(state.written.toFloat() - 16f, minReadable)
        if (state.readPos < minReadable) {
            state.readPos = minReadable
        }

        var sample = sampleAt(state.readPos, state)
        if (state.xfadeRemaining > 0) {
            val oldSample = sampleAt(state.xfadeOldPos, state)
            val blend = (XFADE_SAMPLES - state.xfadeRemaining) / XFADE_SAMPLES.toFloat()
            sample = (sample * blend) + (oldSample * (1f - blend))
            state.xfadeOldPos += ratio
            state.xfadeRemaining--
        }

        state.readPos += ratio
        if (state.readPos > maxReadable) {
            startCrossfade(maxReadable - (state.sampleRate * 0.035f), state)
        } else if (state.readPos < minReadable) {
            startCrossfade(minReadable + (state.sampleRate * 0.02f), state)
        }
        return sample
    }

    private fun startCrossfade(newReadPos: Float, state: VoiceProcessingState) {
        state.xfadeOldPos = state.readPos
        state.readPos = newReadPos
        state.xfadeRemaining = XFADE_SAMPLES
    }

    private fun sampleAt(position: Float, state: VoiceProcessingState): Float {
        val base = position.toInt()
        val fraction = position - base
        val mask = state.ring.size - 1
        val first = state.ring[base and mask]
        val second = state.ring[(base + 1) and mask]
        return first + ((second - first) * fraction)
    }

    private fun brighten(input: Float, amount: Float, state: VoiceProcessingState): Float =
        softClip((input * (1f - amount)) + (highPass(input, 0.975f, state) * amount * 1.8f))

    private fun darken(input: Float, alpha: Float, gain: Float, state: VoiceProcessingState): Float =
        softClip(lowPass(input, alpha, state) * gain)

    private fun lowPass(input: Float, alpha: Float, state: VoiceProcessingState): Float {
        state.lowPass += alpha * (input - state.lowPass)
        return state.lowPass
    }

    private fun highPass(input: Float, coefficient: Float, state: VoiceProcessingState): Float {
        val output = coefficient * ((state.hpPrevOutput + input) - state.hpPrevInput)
        state.hpPrevOutput = output
        state.hpPrevInput = input
        return output
    }

    private fun sampleHold(input: Float, samples: Int, state: VoiceProcessingState): Float {
        if (state.sampleHoldCounter <= 0) {
            state.sampleHoldCounter = samples
            state.sampleHoldValue = input
        } else {
            state.sampleHoldCounter--
        }
        return state.sampleHoldValue
    }

    private fun quantize(input: Float, steps: Float): Float =
        (input * steps).roundToInt() / steps

    private fun osc1(hz: Float, state: VoiceProcessingState): Float {
        state.phase += (hz * 2.0 * PI) / state.sampleRate
        if (state.phase > 2.0 * PI) {
            state.phase -= 2.0 * PI
        }
        return sin(state.phase).toFloat()
    }

    private fun osc2(hz: Float, state: VoiceProcessingState): Float {
        state.phase2 += (hz * 2.0 * PI) / state.sampleRate
        if (state.phase2 > 2.0 * PI) {
            state.phase2 -= 2.0 * PI
        }
        return sin(state.phase2).toFloat()
    }

    private fun nextNoise(state: VoiceProcessingState): Float {
        val step1 = state.noiseState xor (state.noiseState shl 13)
        val step2 = step1 xor (step1 ushr 17)
        state.noiseState = step2 xor (step2 shl 5)
        return ((state.noiseState and Int.MAX_VALUE) / 1_073_741_800f) - 1f
    }

    private fun readDelay(samples: Int, state: VoiceProcessingState): Float {
        val delaySamples = samples.coerceIn(1, state.delay.size - 1)
        var index = state.delayPos - delaySamples
        while (index < 0) {
            index += state.delay.size
        }
        return state.delay[index % state.delay.size]
    }

    private fun writeDelay(input: Float, state: VoiceProcessingState) {
        state.delay[state.delayPos] = input
        state.delayPos = (state.delayPos + 1) % state.delay.size
    }

    private fun applyMicBoost(input: Float, boost: Int): Float {
        val amount = boost.coerceIn(0, 101).toFloat()
        if (amount <= 0f) {
            return input.coerceIn(-1f, 1f)
        }

        if (amount >= 101f) {
            val clipped = (input * 76f).coerceIn(-1f, 1f)
            return (if (clipped >= 0f) 1f else -1f) *
                abs(clipped).toDouble().pow(0.55).toFloat()
        }

        val normalized = amount / 100f
        val gain = 1f + (3.0f * normalized.toDouble().pow(1.65).toFloat())
        val saturationMix = 0.16f * normalized * normalized * normalized
        val clipped = (input * gain).coerceIn(-1f, 1f)
        val saturated = (if (clipped >= 0f) 1f else -1f) *
            abs(clipped).toDouble().pow(0.72).toFloat()
        return ((clipped * (1f - saturationMix)) + (saturated * saturationMix)).coerceIn(-1f, 1f)
    }

    private fun softClip(value: Float): Float =
        tanh(value.toDouble()).toFloat().coerceIn(-1f, 1f)

    private const val XFADE_SAMPLES = 128

    private fun ensureScratch(requiredSize: Int) {
        if (requiredSize <= floatBuffer.size) {
            return
        }
        floatBuffer = FloatArray(requiredSize)
    }
}
