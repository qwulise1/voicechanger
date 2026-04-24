package com.qwulise.voicechanger.module

import android.media.AudioRecord
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.PcmVoiceProcessor
import com.qwulise.voicechanger.core.VoiceConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.Collections

class AudioHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val processName = lpparam.processName ?: packageName
        if (!HookBridge.shouldHookPackage(packageName)) {
            return
        }
        if (processName != packageName && shouldRestrictToMainProcess(packageName)) {
            return
        }

        val installKey = "$processName|$packageName"
        synchronized(installedPackages) {
            if (!installedPackages.add(installKey)) {
                return
            }
        }

        if (shouldAttachNative(packageName)) {
            runCatching {
                NativeAudioBridge.attachToProcess(packageName)
            }.onFailure {
                XposedBridge.log("qwulivoice: native attach failed in $packageName: $it")
            }
        } else {
            XposedBridge.log("qwulivoice: native hook skipped for $packageName")
        }

        XposedBridge.log("qwulivoice: installing safe AudioRecord.read hook in $packageName")
        XposedBridge.hookAllConstructors(AudioRecord::class.java, createAudioRecordConstructorHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "startRecording", createStartRecordingHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "read", createAudioReadHook(packageName))
        installWebRtcHooks(packageName, lpparam.classLoader)
    }

    private fun createAudioRecordConstructorHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            HookBridge.registerAudioRecord(param.thisObject as? AudioRecord ?: return, packageName)
        }
    }

    private fun createStartRecordingHook(packageName: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.startRecording",
                detail = "Recording started.",
                rateKey = "$packageName|AudioRecord.startRecording",
                minIntervalMs = 15_000L,
            )
        }
    }

    private fun createAudioReadHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                processReadResult(packageName, param)
            }.onFailure {
                XposedBridge.log("qwulivoice: AudioRecord.read hook failed in $packageName: $it")
            }
        }
    }

    private fun processReadResult(packageName: String, param: XC_MethodHook.MethodHookParam) {
        val readCount = param.result as? Int ?: return
        if (readCount <= 0) {
            return
        }

        val config = ConfigClient.getConfig()
        val soundpadSnapshot = SoundpadClient.snapshot()
        if (!config.enabled && !soundpadSnapshot.isActive) {
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.read",
                detail = "Hook observed read=$readCount but processing is disabled.",
                rateKey = "$packageName|AudioRecord.read|disabled",
                minIntervalMs = 15_000L,
            )
            return
        }

        val audioRecord = param.thisObject as? AudioRecord ?: return
        val session = HookBridge.registerAudioRecord(audioRecord, packageName)
        val sampleRate = audioRecord.sampleRate.takeIf { it > 0 } ?: session.sampleRate ?: 48_000
        val channelCount = resolveChannelCount(audioRecord, session)
        val state = HookBridge.stateFor(audioRecord)

        when (val buffer = param.args.firstOrNull()) {
            is ByteArray -> {
                val offset = (param.args.getOrNull(1) as? Int) ?: 0
                if (config.enabled) {
                    PcmVoiceProcessor.processByteArrayPcm16(
                        buffer = buffer,
                        offsetBytes = offset.coerceAtLeast(0),
                        byteCount = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }
                if (soundpadSnapshot.isActive) {
                    SoundpadMixer.mixIntoByteArrayPcm16(
                        buffer = buffer,
                        offsetBytes = offset.coerceAtLeast(0),
                        byteCount = readCount,
                        outputSampleRate = sampleRate,
                        channelCount = channelCount,
                        state = state,
                        snapshot = soundpadSnapshot,
                    )
                }
            }

            is ShortArray -> {
                val offset = (param.args.getOrNull(1) as? Int) ?: 0
                if (config.enabled) {
                    PcmVoiceProcessor.processShortArray(
                        samples = buffer,
                        offset = offset.coerceAtLeast(0),
                        count = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }
                if (soundpadSnapshot.isActive) {
                    SoundpadMixer.mixIntoShortArray(
                        samples = buffer,
                        offset = offset.coerceAtLeast(0),
                        count = readCount,
                        outputSampleRate = sampleRate,
                        channelCount = channelCount,
                        state = state,
                        snapshot = soundpadSnapshot,
                    )
                }
            }

            is FloatArray -> {
                val offset = (param.args.getOrNull(1) as? Int) ?: 0
                if (config.enabled) {
                    PcmVoiceProcessor.processFloatArray(
                        samples = buffer,
                        offset = offset.coerceAtLeast(0),
                        count = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }
                if (soundpadSnapshot.isActive) {
                    SoundpadMixer.mixIntoFloatArray(
                        samples = buffer,
                        offset = offset.coerceAtLeast(0),
                        count = readCount,
                        outputSampleRate = sampleRate,
                        channelCount = channelCount,
                        state = state,
                        snapshot = soundpadSnapshot,
                    )
                }
            }

            is ByteBuffer -> {
                if (config.enabled) {
                    PcmVoiceProcessor.processByteBufferPcm16(
                        buffer = buffer,
                        byteCount = readCount,
                        sampleRate = sampleRate,
                        config = config,
                        state = state,
                    )
                }
                if (soundpadSnapshot.isActive) {
                    SoundpadMixer.mixIntoByteBufferPcm16(
                        buffer = buffer,
                        byteCount = readCount,
                        outputSampleRate = sampleRate,
                        channelCount = channelCount,
                        state = state,
                        snapshot = soundpadSnapshot,
                    )
                }
                HookBridge.markBufferProcessed(buffer, readCount, "AudioRecord.read")
            }

            else -> {
                DiagnosticsClient.reportEvent(
                    packageName = packageName,
                    source = "AudioRecord.read",
                    detail = "Unsupported buffer type=${param.args.firstOrNull()?.javaClass?.name ?: "null"} read=$readCount",
                    rateKey = "$packageName|AudioRecord.read|unsupported",
                    minIntervalMs = 15_000L,
                )
                return
            }
        }

        DiagnosticsClient.reportEvent(
            packageName = packageName,
            source = "AudioRecord.read",
            detail = "Processed read=$readCount rate=${sampleRate}Hz effectiveChannels=$channelCount mode=${config.mode.id} boost=${config.micGainPercent} soundpad=${soundpadSnapshot.activeSlot?.id ?: "off"} ${session.describe()}",
            rateKey = "$packageName|AudioRecord.read|processed",
            minIntervalMs = 12_000L,
        )
    }

    private fun resolveChannelCount(audioRecord: AudioRecord, session: AudioRecordSession): Int {
        audioRecord.channelCount.takeIf { it > 0 }?.let { return it }
        session.channelCount?.takeIf { it > 0 }?.let { return it }

        val channelMask = session.channelMask?.takeIf { it > 0 }
            ?: runCatching { audioRecord.channelConfiguration }.getOrNull()?.takeIf { it > 0 }
        if (channelMask != null) {
            val bits = Integer.bitCount(channelMask)
            if (bits in 1..8) {
                return bits
            }
        }

        return 1
    }

    private fun installWebRtcHooks(packageName: String, classLoader: ClassLoader?) {
        WEB_RTC_RECORD_CLASSES.forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull() ?: return@forEach
            XposedBridge.log("qwulivoice: installing WebRTC nativeDataIsRecorded hook in $packageName for $className")
            XposedBridge.hookAllMethods(clazz, "nativeDataIsRecorded", createWebRtcRecordHook(packageName, className))
        }
    }

    private fun createWebRtcRecordHook(packageName: String, className: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                processWebRtcBuffer(packageName, className, param)
            }.onFailure {
                XposedBridge.log("qwulivoice: WebRTC hook failed in $packageName for $className: $it")
            }
        }
    }

    private fun processWebRtcBuffer(
        packageName: String,
        className: String,
        param: XC_MethodHook.MethodHookParam,
    ) {
        val holder = param.thisObject ?: return
        val buffer = runCatching { XposedHelpers.getObjectField(holder, "byteBuffer") as? ByteBuffer }
            .getOrNull()
            ?: return
        val byteCount = param.args
            .mapNotNull { it as? Int }
            .firstOrNull { it > 0 }
            ?: buffer.capacity()
        if (byteCount <= 0 || !HookBridge.shouldProcessWebRtcBuffer(buffer, byteCount)) {
            return
        }

        val config = ConfigClient.getConfig()
        val soundpadSnapshot = SoundpadClient.snapshot()
        if (!config.enabled && !soundpadSnapshot.isActive) {
            return
        }

        val audioRecord = runCatching { XposedHelpers.getObjectField(holder, "audioRecord") as? AudioRecord }
            .getOrNull()
        val audioSession = audioRecord?.let { HookBridge.registerAudioRecord(it, packageName) }
        val sampleRate = audioRecord?.sampleRate?.takeIf { it > 0 }
            ?: guessIntField(holder, "sampleRate", "sampleRateHz", "sampleRateInHz")
            ?: audioSession?.sampleRate
            ?: 48_000
        val channelCount = when {
            audioRecord != null && audioSession != null -> resolveChannelCount(audioRecord, audioSession)
            else -> guessIntField(holder, "channels", "channelCount", "inputChannels") ?: 1
        }.coerceAtLeast(1)
        val state = HookBridge.stateForWebRtc(holder)
        HookBridge.registerWebRtcInstance(
            instance = holder,
            packageName = packageName,
            className = className,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioRecordFieldName = "audioRecord",
            byteBufferFieldName = "byteBuffer",
        )

        if (config.enabled) {
            PcmVoiceProcessor.processByteBufferPcm16(
                buffer = buffer,
                byteCount = byteCount,
                sampleRate = sampleRate,
                config = config,
                state = state,
            )
        }
        if (soundpadSnapshot.isActive) {
            SoundpadMixer.mixIntoByteBufferPcm16(
                buffer = buffer,
                byteCount = byteCount,
                outputSampleRate = sampleRate,
                channelCount = channelCount,
                state = state,
                snapshot = soundpadSnapshot,
            )
        }
        HookBridge.markBufferProcessed(buffer, byteCount, "WebRtc.nativeDataIsRecorded")
        DiagnosticsClient.reportEvent(
            packageName = packageName,
            source = "WebRtc.nativeDataIsRecorded",
            detail = "Processed bytes=$byteCount rate=${sampleRate}Hz channels=$channelCount class=${className.substringAfterLast('.')} mode=${config.mode.id} boost=${config.micGainPercent} soundpad=${soundpadSnapshot.activeSlot?.id ?: "off"}",
            rateKey = "$packageName|$className|nativeDataIsRecorded",
            minIntervalMs = 12_000L,
        )
    }

    private fun guessIntField(target: Any, vararg names: String): Int? =
        names.firstNotNullOfOrNull { name ->
            runCatching {
                (XposedHelpers.getObjectField(target, name) as? Number)?.toInt()
            }.recoverCatching {
                XposedHelpers.getIntField(target, name)
            }.getOrNull()?.takeIf { it > 0 }
        }

    private fun shouldAttachNative(packageName: String): Boolean =
        !packageName.startsWith("org.telegram.messenger") &&
            !packageName.startsWith("com.exteragram")

    private fun shouldRestrictToMainProcess(packageName: String): Boolean =
        packageName.startsWith("org.telegram.messenger") ||
            packageName.startsWith("com.exteragram")

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

                cachedConfig = ModuleFileBridge.readConfig() ?: cachedConfig

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
            rateKey: String = "$packageName|$source",
            minIntervalMs: Long = 10_000L,
        ) {
            val now = System.currentTimeMillis()
            val previous = synchronized(lastEvents) { lastEvents[rateKey] }
            if (previous != null && now - previous < minIntervalMs) {
                return
            }

            runCatching {
                val event = DiagnosticEvent(
                    timestampMs = now,
                    packageName = packageName,
                    source = source,
                    detail = detail,
                )
                val delivered = ModuleFileBridge.appendEvent(event)
                if (delivered) {
                    synchronized(lastEvents) { lastEvents[rateKey] = now }
                } else {
                    XposedBridge.log("qwulivoice: diagnostics report failed for $source in $packageName")
                }
            }.onFailure {
                XposedBridge.log("qwulivoice: diagnostics report failed: $it")
            }
        }
    }

    companion object {
        private val installedPackages = Collections.synchronizedSet(mutableSetOf<String>())
        private val WEB_RTC_RECORD_CLASSES = listOf(
            "org.webrtc.audio.WebRtcAudioRecord",
            "org.webrtc.voiceengine.WebRtcAudioRecord",
        )
    }
}
