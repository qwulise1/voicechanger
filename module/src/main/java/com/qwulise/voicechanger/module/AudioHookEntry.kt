package com.qwulise.voicechanger.module

import android.app.Application
import android.media.AudioRecord
import com.qwulise.voicechanger.core.PcmVoiceProcessor
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract
import com.qwulise.voicechanger.core.VoiceProcessingState
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!HookBridge.shouldHookPackage(lpparam.packageName)) {
            return
        }

        if (!installed.compareAndSet(false, true)) {
            return
        }

        XposedBridge.log("Voicechanger: installing AudioRecord hooks in ${lpparam.packageName}")
        XposedBridge.hookAllMethods(AudioRecord::class.java, "read", audioReadHook)
    }

    private val audioReadHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val readCount = param.result as? Int ?: return
            if (readCount <= 0) {
                return
            }

            val config = ConfigClient.getConfig()
            if (!config.enabled) {
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

                val context = resolveContext()
                if (context == null) {
                    lastLoadedAt = refreshedNow
                    return cachedConfig
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

        private fun resolveContext(): android.content.Context? {
            val viaReflection = try {
                val activityThread = Class.forName("android.app.ActivityThread")
                activityThread.getMethod("currentApplication").invoke(null) as? Application
            } catch (_: Throwable) {
                null
            }
            return viaReflection?.applicationContext
        }
    }

    companion object {
        private val installed = AtomicBoolean(false)
    }
}
