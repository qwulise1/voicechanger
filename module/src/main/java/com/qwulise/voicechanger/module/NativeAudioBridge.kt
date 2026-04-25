package com.qwulise.voicechanger.module

import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.VoiceConfig
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

data class NativeConfigSnapshot(
    val enabled: Boolean,
    val allowed: Boolean,
    val modeId: String,
    val effectStrength: Int,
    val micGainPercent: Int,
) {
    companion object {
        fun disabled(): NativeConfigSnapshot = NativeConfigSnapshot(
            enabled = false,
            allowed = false,
            modeId = "original",
            effectStrength = 85,
            micGainPercent = 0,
        )
    }
}

data class NativeSoundpadSnapshot(
    val active: Boolean,
    val pcmPath: String,
    val sampleRate: Int,
    val mixPercent: Int,
    val gainPercent: Int,
    val looping: Boolean,
    val sessionId: Long,
) {
    companion object {
        fun disabled(): NativeSoundpadSnapshot = NativeSoundpadSnapshot(
            active = false,
            pcmPath = "",
            sampleRate = 48_000,
            mixPercent = 0,
            gainPercent = 100,
            looping = false,
            sessionId = 0L,
        )
    }
}

object NativeAudioBridge {
    private const val CONFIG_CACHE_WINDOW_MS = 800L
    private const val SOUNDPAD_CACHE_WINDOW_MS = 160L

    private val nativeLoaded = AtomicBoolean(false)
    private val lastEvents = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val reportingFailureLogged = AtomicBoolean(false)

    @Volatile
    private var processPackageName: String = ""

    @Volatile
    private var lastConfigLoadedAt: Long = 0L

    @Volatile
    private var cachedConfig: VoiceConfig = VoiceConfig()

    @Volatile
    private var lastSoundpadLoadedAt: Long = 0L

    @Volatile
    private var cachedSoundpad: NativeSoundpadSnapshot = NativeSoundpadSnapshot.disabled()

    @Volatile
    private var reportingDisabled: Boolean = false

    fun attachToProcess(packageName: String) {
        processPackageName = packageName
        reportingDisabled = false
        reportingFailureLogged.set(false)
        runCatching {
            ensureLoaded()
        }.onFailure {
            XposedBridge.log("qwulivoice: native library load failed: $it")
            return
        }
        runCatching {
            nativeSetProcessPackageName(packageName)
        }.onFailure {
            XposedBridge.log("qwulivoice: native package attach failed: $it")
        }
    }

    @JvmStatic
    fun snapshotForNative(packageName: String): NativeConfigSnapshot {
        val config = getConfig()
        return NativeConfigSnapshot(
            enabled = config.enabled,
            allowed = HookBridge.isTargetPackageAllowed(config, packageName),
            modeId = config.mode.id,
            effectStrength = config.effectStrength,
            micGainPercent = config.micGainPercent,
        )
    }

    @JvmStatic
    fun soundpadSnapshotForNative(): NativeSoundpadSnapshot {
        val now = System.currentTimeMillis()
        if (now - lastSoundpadLoadedAt < SOUNDPAD_CACHE_WINDOW_MS) {
            return cachedSoundpad
        }

        synchronized(this) {
            val refreshedNow = System.currentTimeMillis()
            if (refreshedNow - lastSoundpadLoadedAt < SOUNDPAD_CACHE_WINDOW_MS) {
                return cachedSoundpad
            }

            val snapshot = SoundpadClient.snapshot()
            val activeSlot = snapshot.activeSlot
            cachedSoundpad = if (activeSlot == null) {
                NativeSoundpadSnapshot.disabled()
            } else {
                NativeSoundpadSnapshot(
                    active = true,
                    pcmPath = activeSlot.pcmPath,
                    sampleRate = activeSlot.sampleRate,
                    mixPercent = snapshot.playback.mixPercent,
                    gainPercent = activeSlot.gainPercent,
                    looping = snapshot.playback.looping,
                    sessionId = snapshot.playback.sessionId.takeIf { it > 0L } ?: activeSlot.id.hashCode().toLong(),
                )
            }
            lastSoundpadLoadedAt = refreshedNow
            return cachedSoundpad
        }
    }

    @JvmStatic
    fun reportNativeEvent(
        packageName: String,
        source: String,
        detail: String,
        force: Boolean,
        rateKey: String,
        minIntervalMs: Long,
    ) {
        if (packageName.isBlank() || source.isBlank() || reportingDisabled) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force) {
            val previous = synchronized(lastEvents) { lastEvents[rateKey] }
            if (previous != null && now - previous < minIntervalMs) {
                return
            }
        }

        val event = DiagnosticEvent(
            timestampMs = now,
            packageName = packageName,
            source = source,
            detail = detail,
        )
        runCatching {
            val delivered = ModuleFileBridge.appendEvent(event)
            if (delivered) {
                synchronized(lastEvents) { lastEvents[rateKey] = now }
            } else {
                disableReporting("native diagnostics file unavailable")
            }
        }.onFailure {
            disableReporting("native diagnostics report failed: $it")
        }
    }

    private fun disableReporting(reason: String) {
        reportingDisabled = true
        if (reportingFailureLogged.compareAndSet(false, true)) {
            XposedBridge.log("qwulivoice: disabling native diagnostics in this process: $reason")
        }
    }

    @JvmStatic
    fun currentPackageName(): String = processPackageName

    private fun getConfig(): VoiceConfig {
        val now = System.currentTimeMillis()
        if (now - lastConfigLoadedAt < CONFIG_CACHE_WINDOW_MS) {
            return cachedConfig
        }

        synchronized(this) {
            val refreshedNow = System.currentTimeMillis()
            if (refreshedNow - lastConfigLoadedAt < CONFIG_CACHE_WINDOW_MS) {
                return cachedConfig
            }

            cachedConfig = ModuleFileBridge.readConfig() ?: cachedConfig
            lastConfigLoadedAt = refreshedNow
            return cachedConfig
        }
    }

    private fun ensureLoaded() {
        if (nativeLoaded.compareAndSet(false, true)) {
            System.loadLibrary("voicechanger-native")
        }
    }

    @JvmStatic
    private external fun nativeSetProcessPackageName(packageName: String)
}
