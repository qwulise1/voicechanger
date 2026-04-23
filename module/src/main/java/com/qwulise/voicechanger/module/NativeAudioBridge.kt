package com.qwulise.voicechanger.module

import android.os.Bundle
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract
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

object NativeAudioBridge {
    private const val CONFIG_CACHE_WINDOW_MS = 800L

    private val nativeLoaded = AtomicBoolean(false)
    private val lastEvents = Collections.synchronizedMap(mutableMapOf<String, Long>())

    @Volatile
    private var processPackageName: String = ""

    @Volatile
    private var lastConfigLoadedAt: Long = 0L

    @Volatile
    private var cachedConfig: VoiceConfig = VoiceConfig()

    fun attachToProcess(packageName: String) {
        processPackageName = packageName
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
    fun reportNativeEvent(
        packageName: String,
        source: String,
        detail: String,
        force: Boolean,
        rateKey: String,
        minIntervalMs: Long,
    ) {
        if (packageName.isBlank() || source.isBlank()) {
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
        val context = resolveContext()
        runCatching {
            val delivered = if (context == null) {
                ModuleFileBridge.appendEvent(event)
            } else {
                runCatching {
                    requireNotNull(context.contentResolver.call(
                        VoiceConfigContract.CONTENT_URI,
                        VoiceConfigContract.METHOD_APPEND_LOG,
                        null,
                        Bundle().apply {
                            putString(VoiceConfigContract.KEY_LOG_PACKAGE_NAME, packageName)
                            putString(VoiceConfigContract.KEY_LOG_SOURCE, source)
                            putString(VoiceConfigContract.KEY_LOG_DETAIL, detail)
                            putLong(VoiceConfigContract.KEY_LOG_TIMESTAMP_MS, now)
                        },
                    )) { "provider returned null append result" }
                }.isSuccess || ModuleFileBridge.appendEvent(event)
            }
            if (delivered) {
                synchronized(lastEvents) { lastEvents[rateKey] = now }
            } else {
                XposedBridge.log("qwulivoice: native diagnostics file/provider unavailable")
            }
        }.onFailure {
            XposedBridge.log("qwulivoice: native diagnostics report failed: $it")
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

            val rootConfig = ModuleFileBridge.readConfig()
            val context = resolveContext()
            if (context == null) {
                cachedConfig = rootConfig ?: cachedConfig
                lastConfigLoadedAt = refreshedNow
                return cachedConfig
            }

            cachedConfig = try {
                VoiceConfig.fromBundle(
                    requireNotNull(context.contentResolver.call(
                        VoiceConfigContract.CONTENT_URI,
                        VoiceConfigContract.METHOD_GET_CONFIG,
                        null,
                        null,
                    )) { "provider returned null config" },
                )
            } catch (error: Throwable) {
                XposedBridge.log("qwulivoice: native config fetch failed: $error")
                rootConfig ?: cachedConfig
            }

            lastConfigLoadedAt = refreshedNow
            return cachedConfig
        }
    }

    private fun ensureLoaded() {
        if (nativeLoaded.compareAndSet(false, true)) {
            System.loadLibrary("voicechanger-native")
        }
    }

    private fun resolveContext(): android.content.Context? {
        return ProcessContextResolver.resolve()
    }

    @JvmStatic
    private external fun nativeSetProcessPackageName(packageName: String)
}
