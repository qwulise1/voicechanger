package com.qwulise.voicechanger.module

import android.app.Application
import android.os.Bundle
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
            effectStrength = 55,
            micGainPercent = 100,
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
            XposedBridge.log("Voicechanger: native library load failed: $it")
            return
        }
        runCatching {
            nativeSetProcessPackageName(packageName)
        }.onFailure {
            XposedBridge.log("Voicechanger: native package attach failed: $it")
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

        val context = resolveContext() ?: return
        runCatching {
            context.contentResolver.call(
                VoiceConfigContract.CONTENT_URI,
                VoiceConfigContract.METHOD_APPEND_LOG,
                null,
                Bundle().apply {
                    putString(VoiceConfigContract.KEY_LOG_PACKAGE_NAME, packageName)
                    putString(VoiceConfigContract.KEY_LOG_SOURCE, source)
                    putString(VoiceConfigContract.KEY_LOG_DETAIL, detail)
                    putLong(VoiceConfigContract.KEY_LOG_TIMESTAMP_MS, now)
                },
            )
            synchronized(lastEvents) {
                lastEvents[rateKey] = now
            }
        }.onFailure {
            XposedBridge.log("Voicechanger: native diagnostics report failed: $it")
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

            val context = resolveContext() ?: return cachedConfig.also {
                lastConfigLoadedAt = refreshedNow
            }

            cachedConfig = try {
                VoiceConfig.fromBundle(
                    context.contentResolver.call(
                        VoiceConfigContract.CONTENT_URI,
                        VoiceConfigContract.METHOD_GET_CONFIG,
                        null,
                        null,
                    ),
                )
            } catch (error: Throwable) {
                XposedBridge.log("Voicechanger: native config fetch failed: $error")
                cachedConfig
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
        val application = try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
        return application?.applicationContext
    }

    @JvmStatic
    private external fun nativeSetProcessPackageName(packageName: String)
}
