package com.qwulise.voicechanger.module

import android.app.Application
import android.media.AudioRecord
import com.qwulise.voicechanger.core.PcmVoiceProcessor
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class AudioHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (!HookBridge.shouldHookPackage(packageName)) {
            return
        }

        if (!installed.compareAndSet(false, true)) {
            return
        }

        XposedBridge.log("Voicechanger: installing hooks in $packageName")
        DiagnosticsClient.reportEvent(
            packageName = packageName,
            source = "module",
            detail = "Injected hooks installed for process.",
            force = true,
        )
        XposedBridge.hookAllMethods(AudioRecord::class.java, "read", createAudioReadHook(packageName))
        installWebRtcDiagnostics(packageName, lpparam.classLoader)
    }

    private fun createAudioReadHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val readCount = param.result as? Int ?: return
            if (readCount <= 0) {
                return
            }

            val config = ConfigClient.getConfig()
            if (!config.enabled || !HookBridge.isTargetPackageAllowed(config, packageName)) {
                return
            }

            val audioRecord = param.thisObject as? AudioRecord ?: return
            val sampleRate = audioRecord.sampleRate.takeIf { it > 0 } ?: 48_000
            val state = HookBridge.stateFor(audioRecord)

            when (val buffer = param.args.firstOrNull()) {
                is ByteArray -> {
                    val offset = (param.args.getOrNull(1) as? Int) ?: 0
                    PcmVoiceProcessor.processByteArrayPcm16(
                        buffer = buffer,
                        offsetBytes = offset.coerceAtLeast(0),
                        byteCount = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }

                is ShortArray -> {
                    val offset = (param.args.getOrNull(1) as? Int) ?: 0
                    PcmVoiceProcessor.processShortArray(
                        samples = buffer,
                        offset = offset.coerceAtLeast(0),
                        count = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }

                is FloatArray -> {
                    val offset = (param.args.getOrNull(1) as? Int) ?: 0
                    PcmVoiceProcessor.processFloatArray(
                        samples = buffer,
                        offset = offset.coerceAtLeast(0),
                        count = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }

                is ByteBuffer -> {
                    PcmVoiceProcessor.processByteBufferPcm16(
                        buffer = buffer,
                        byteCount = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }
            }

            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.read",
                detail = "rate=${sampleRate}Hz bytes=$readCount mode=${config.mode.id} gain=${config.micGainPercent}%",
                rateKey = "$packageName|AudioRecord.read",
                minIntervalMs = 12_000L,
            )
        }
    }

    private fun installWebRtcDiagnostics(packageName: String, classLoader: ClassLoader) {
        listOf(
            "org.webrtc.audio.WebRtcAudioRecord",
            "org.webrtc.voiceengine.WebRtcAudioRecord",
        ).forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull() ?: return@forEach
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "WebRTC.detect",
                detail = "Detected $className",
                force = true,
                rateKey = "$packageName|WebRTC.detect|$className",
            )

            listOf("initRecording", "startRecording", "nativeDataIsRecorded").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            DiagnosticsClient.reportEvent(
                                packageName = packageName,
                                source = "WebRTC.$methodName",
                                detail = "Observed $methodName in $className",
                                rateKey = "$packageName|$className|$methodName",
                                minIntervalMs = 20_000L,
                            )
                        }
                    })
                }
            }
        }
    }

    private object ConfigClient {
        private const val CACHE_WINDOW_MS = 800L

        @Volatile
        private var lastLoadedAt: Long = 0L

        @Volatile
        private var cachedConfig: VoiceConfig = VoiceConfig()

        fun getConfig(): VoiceConfig {
            val now = System.currentTimeMillis()
            if (now - lastLoadedAt < CACHE_WINDOW_MS) {
                return cachedConfig
            }

            synchronized(this) {
                val refreshedNow = System.currentTimeMillis()
                if (refreshedNow - lastLoadedAt < CACHE_WINDOW_MS) {
                    return cachedConfig
                }

                val context = ContextResolver.resolve() ?: return cachedConfig.also {
                    lastLoadedAt = refreshedNow
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
                    XposedBridge.log("Voicechanger: config fetch failed: $error")
                    cachedConfig
                }

                lastLoadedAt = refreshedNow
                return cachedConfig
            }
        }
    }

    private object DiagnosticsClient {
        private val lastEvents = Collections.synchronizedMap(mutableMapOf<String, Long>())

        fun reportEvent(
            packageName: String,
            source: String,
            detail: String,
            force: Boolean = false,
            rateKey: String = "$packageName|$source",
            minIntervalMs: Long = 10_000L,
        ) {
            val now = System.currentTimeMillis()
            if (!force) {
                val previous = synchronized(lastEvents) { lastEvents[rateKey] }
                if (previous != null && now - previous < minIntervalMs) {
                    return
                }
            }

            val context = ContextResolver.resolve() ?: return
            runCatching {
                context.contentResolver.call(
                    VoiceConfigContract.CONTENT_URI,
                    VoiceConfigContract.METHOD_APPEND_LOG,
                    null,
                    android.os.Bundle().apply {
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
                XposedBridge.log("Voicechanger: diagnostics report failed: $it")
            }
        }
    }

    private object ContextResolver {
        fun resolve(): android.content.Context? {
            val application = try {
                val activityThread = Class.forName("android.app.ActivityThread")
                activityThread.getMethod("currentApplication").invoke(null) as? Application
            } catch (_: Throwable) {
                null
            }
            return application?.applicationContext
        }
    }

    companion object {
        private val installed = AtomicBoolean(false)
    }
}
