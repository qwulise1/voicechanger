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
import java.lang.reflect.Field
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
        XposedBridge.hookAllConstructors(AudioRecord::class.java, createAudioRecordConstructorHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "startRecording", createAudioRecordStartHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "stop", createAudioRecordStopHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "release", createAudioRecordReleaseHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "read", createAudioReadHook(packageName))
        installWebRtcHooks(packageName, lpparam.classLoader)
    }

    private fun createAudioRecordConstructorHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val audioRecord = param.thisObject as? AudioRecord ?: return
            val session = HookBridge.registerAudioRecord(audioRecord, packageName)
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.new",
                detail = session.describe(),
                rateKey = "$packageName|AudioRecord.new|${session.audioSource}|${session.sampleRate}|${session.channelCount}|${session.encoding}",
                minIntervalMs = 8_000L,
            )
        }
    }

    private fun createAudioRecordStartHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val audioRecord = param.thisObject as? AudioRecord ?: return
            val session = HookBridge.registerAudioRecord(audioRecord, packageName)
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.start",
                detail = session.describe(),
                rateKey = "$packageName|AudioRecord.start|${session.audioSource}|${session.sampleRate}",
                minIntervalMs = 8_000L,
            )
        }
    }

    private fun createAudioRecordStopHook(packageName: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val audioRecord = param.thisObject as? AudioRecord ?: return
            val session = HookBridge.sessionFor(audioRecord) ?: HookBridge.registerAudioRecord(audioRecord, packageName)
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.stop",
                detail = session.describe(),
                rateKey = "$packageName|AudioRecord.stop|${session.audioSource}|${session.sampleRate}",
                minIntervalMs = 8_000L,
            )
        }
    }

    private fun createAudioRecordReleaseHook(packageName: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val audioRecord = param.thisObject as? AudioRecord ?: return
            val session = HookBridge.sessionFor(audioRecord) ?: HookBridge.registerAudioRecord(audioRecord, packageName)
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.release",
                detail = session.describe(),
                rateKey = "$packageName|AudioRecord.release|${session.audioSource}|${session.sampleRate}",
                minIntervalMs = 8_000L,
            )
            HookBridge.releaseAudioRecord(audioRecord)
        }
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
            val session = HookBridge.registerAudioRecord(audioRecord, packageName)
            val sampleRate = audioRecord.sampleRate.takeIf { it > 0 } ?: session.sampleRate ?: 48_000
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
                    HookBridge.markBufferProcessed(buffer, readCount, "AudioRecord.read")
                }
            }

            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.read",
                detail = "rate=${sampleRate}Hz bytes=$readCount mode=${config.mode.id} gain=${config.micGainPercent}% ${session.describe()}",
                rateKey = "$packageName|AudioRecord.read",
                minIntervalMs = 12_000L,
            )
        }
    }

    private fun installWebRtcHooks(packageName: String, classLoader: ClassLoader) {
        listOf(
            "org.webrtc.audio.WebRtcAudioRecord",
            "org.webrtc.voiceengine.WebRtcAudioRecord",
        ).forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull() ?: return@forEach
            val binding = WebRtcBinding.inspect(className, clazz)
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "WebRTC.detect",
                detail = "Detected $className ${binding.describe()}",
                force = true,
                rateKey = "$packageName|WebRTC.detect|$className",
            )

            listOf("initRecording", "startRecording").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val audioRecord = binding.audioRecord(param.thisObject)
                            val session = audioRecord?.let { HookBridge.registerAudioRecord(it, packageName) }
                            DiagnosticsClient.reportEvent(
                                packageName = packageName,
                                source = "WebRTC.$methodName",
                                detail = buildString {
                                    append("Observed $methodName in $className")
                                    session?.let { append(" ${it.describe()}") }
                                },
                                rateKey = "$packageName|$className|$methodName",
                                minIntervalMs = 20_000L,
                            )
                        }
                    })
                }
            }

            runCatching {
                XposedBridge.hookAllMethods(clazz, "nativeDataIsRecorded", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val byteCount = (param.args.firstOrNull() as? Int)?.coerceAtLeast(0) ?: 0
                        val audioRecord = binding.audioRecord(param.thisObject)
                        val byteBuffer = binding.byteBuffer(param.thisObject)
                        val session = audioRecord?.let { HookBridge.registerAudioRecord(it, packageName) }
                        val sampleRate = audioRecord?.sampleRate?.takeIf { it > 0 }
                            ?: binding.sampleRate(param.thisObject)
                            ?: session?.sampleRate
                            ?: 48_000

                        DiagnosticsClient.reportEvent(
                            packageName = packageName,
                            source = "WebRTC.nativeDataIsRecorded",
                            detail = buildString {
                                append("Observed nativeDataIsRecorded bytes=$byteCount")
                                session?.let { append(" ${it.describe()}") }
                            },
                            rateKey = "$packageName|$className|nativeDataIsRecorded",
                            minIntervalMs = 20_000L,
                        )

                        val config = ConfigClient.getConfig()
                        if (!config.enabled || !HookBridge.isTargetPackageAllowed(config, packageName)) {
                            return
                        }
                        if (byteCount <= 0 || byteBuffer == null || audioRecord == null) {
                            return
                        }
                        if (!HookBridge.shouldProcessWebRtcBuffer(byteBuffer, byteCount)) {
                            return
                        }

                        runCatching {
                            PcmVoiceProcessor.processByteBufferPcm16(
                                buffer = byteBuffer,
                                byteCount = byteCount,
                                sampleRate = sampleRate,
                                config = config,
                                state = HookBridge.stateFor(audioRecord),
                            )
                            HookBridge.markBufferProcessed(byteBuffer, byteCount, "WebRTC.nativeDataIsRecorded")
                            DiagnosticsClient.reportEvent(
                                packageName = packageName,
                                source = "WebRTC.bridge",
                                detail = "Applied WebRTC fallback bytes=$byteCount rate=${sampleRate}Hz ${session?.describe().orEmpty()}",
                                rateKey = "$packageName|$className|WebRTC.bridge.apply",
                                minIntervalMs = 12_000L,
                            )
                        }.onFailure {
                            DiagnosticsClient.reportEvent(
                                packageName = packageName,
                                source = "WebRTC.bridge",
                                detail = "Fallback failed: ${it::class.java.simpleName}",
                                rateKey = "$packageName|$className|WebRTC.bridge.error",
                                minIntervalMs = 20_000L,
                            )
                        }
                    }
                })
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

    private data class WebRtcBinding(
        val className: String,
        val audioRecordField: Field?,
        val byteBufferField: Field?,
        val sampleRateField: Field?,
    ) {
        fun audioRecord(instance: Any): AudioRecord? = runCatching {
            audioRecordField?.get(instance) as? AudioRecord
        }.getOrNull()

        fun byteBuffer(instance: Any): ByteBuffer? = runCatching {
            byteBufferField?.get(instance) as? ByteBuffer
        }.getOrNull()

        fun sampleRate(instance: Any): Int? = runCatching {
            sampleRateField?.get(instance) as? Int
        }.getOrNull()

        fun describe(): String = buildString {
            append("audioRecordField=${audioRecordField?.name ?: "-"}")
            append(" byteBufferField=${byteBufferField?.name ?: "-"}")
            append(" sampleRateField=${sampleRateField?.name ?: "-"}")
        }

        companion object {
            fun inspect(className: String, clazz: Class<*>): WebRtcBinding {
                val fields = clazz.declaredFields.onEach { field ->
                    runCatching { field.isAccessible = true }
                }
                return WebRtcBinding(
                    className = className,
                    audioRecordField = fields.firstOrNull { it.name.equals("audioRecord", ignoreCase = true) }
                        ?: fields.firstOrNull { AudioRecord::class.java.isAssignableFrom(it.type) },
                    byteBufferField = fields.firstOrNull { it.name.equals("byteBuffer", ignoreCase = true) }
                        ?: fields.firstOrNull { ByteBuffer::class.java.isAssignableFrom(it.type) },
                    sampleRateField = fields.firstOrNull {
                        (it.type == Int::class.javaPrimitiveType || it.type == Int::class.javaObjectType) &&
                            it.name.contains("sampleRate", ignoreCase = true)
                    },
                )
            }
        }
    }

    companion object {
        private val installed = AtomicBoolean(false)
    }
}
