package com.qwulise.voicechanger.core

import android.net.Uri
import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

enum class VoiceMode(
    val id: String,
    val title: String,
    val summary: String,
) {
    ORIGINAL(
        id = "original",
        title = "Оригинал",
        summary = "Без тембрального эффекта, только общее усиление микрофона.",
    ),
    ROBOT(
        id = "robot",
        title = "Робот",
        summary = "Металлическая модуляция с грубым цифровым оттенком.",
    ),
    BRIGHT(
        id = "bright",
        title = "Яркий",
        summary = "Более светлый, резкий и высокий по восприятию тембр.",
    ),
    DEEP(
        id = "deep",
        title = "Глубокий",
        summary = "Более плотный и низкий по восприятию тембр.",
    ),
    ;

    companion object {
        val default: VoiceMode = ORIGINAL

        fun fromId(id: String?): VoiceMode =
            entries.firstOrNull { it.id == id } ?: default
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
    val effectStrength: Int = 55,
    val micGainPercent: Int = 100,
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
        micGainPercent = micGainPercent.coerceIn(0, 200),
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
        putInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, micGainPercent.coerceIn(0, 200))
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
                effectStrength = bundle?.getInt(VoiceConfigContract.KEY_EFFECT_STRENGTH, 55) ?: 55,
                micGainPercent = bundle?.getInt(VoiceConfigContract.KEY_MIC_GAIN_PERCENT, 100) ?: 100,
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
    const val AUTHORITY = "com.qwulise.voicechanger.module.config"
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
    var lowPass: Float = 0f
    var previousInput: Float = 0f
    var wasPositive: Boolean = false
    var subPolarity: Float = 1f
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
        val strength = config.effectStrength.coerceIn(0, 100) / 100f
        val rate = sampleRate.coerceAtLeast(8_000)
        val effected = when (config.mode) {
            VoiceMode.ORIGINAL -> input
            VoiceMode.ROBOT -> applyRobot(input, rate, strength, state)
            VoiceMode.BRIGHT -> applyBright(input, strength, state)
            VoiceMode.DEEP -> applyDeep(input, strength, state)
        }
        val gained = effected * (config.micGainPercent.coerceIn(0, 200) / 100f)
        return softClip(gained)
    }

    private fun applyRobot(
        input: Float,
        sampleRate: Int,
        strength: Float,
        state: VoiceProcessingState,
    ): Float {
        val carrierHz = 55f + strength * 125f
        val carrier = if (sin(state.phase) >= 0.0) 1f else -1f
        state.phase += (2.0 * PI * carrierHz / sampleRate)
        if (state.phase >= 2.0 * PI) {
            state.phase -= 2.0 * PI
        }

        val crushResolution = 14f + strength * 50f
        val crushed = (input * crushResolution).roundToInt() / crushResolution
        return softClip(input * 0.18f + crushed * carrier * (0.82f + strength * 0.1f))
    }

    private fun applyBright(
        input: Float,
        strength: Float,
        state: VoiceProcessingState,
    ): Float {
        val emphasis = input - (state.previousInput * (0.72f + strength * 0.18f))
        state.previousInput = input
        val sharpened = input * (0.65f - strength * 0.10f) + emphasis * (1.15f + strength * 0.95f)
        return softClip(sharpened * (1.02f + strength * 0.25f))
    }

    private fun applyDeep(
        input: Float,
        strength: Float,
        state: VoiceProcessingState,
    ): Float {
        val alpha = 0.10f - strength * 0.05f
        state.lowPass += (input - state.lowPass) * alpha.coerceIn(0.03f, 0.12f)

        val positive = input >= 0f
        if (positive && !state.wasPositive) {
            state.subPolarity *= -1f
        }
        state.wasPositive = positive

        val sub = state.lowPass * state.subPolarity * (0.18f + strength * 0.35f)
        return softClip(state.lowPass * (1.04f + strength * 0.26f) + sub)
    }

    private fun softClip(value: Float): Float {
        val limited = value / (1f + abs(value) * 0.85f)
        return limited.coerceIn(-1f, 1f)
    }

    private fun ensureScratch(requiredSize: Int) {
        if (requiredSize <= floatBuffer.size) {
            return
        }
        floatBuffer = FloatArray(requiredSize)
    }
}
