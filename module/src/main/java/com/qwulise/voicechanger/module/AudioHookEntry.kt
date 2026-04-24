package com.qwulise.voicechanger.module

import android.media.AudioRecord
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.PcmVoiceProcessor
import com.qwulise.voicechanger.core.VoiceConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.Collections

class AudioHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (!HookBridge.shouldHookPackage(packageName)) {
            return
        }

        val installKey = "${lpparam.processName ?: packageName}|$packageName"
        synchronized(installedPackages) {
            if (!installedPackages.add(installKey)) {
                return
            }
        }

        runCatching {
            NativeAudioBridge.attachToProcess(packageName)
        }.onFailure {
            XposedBridge.log("qwulivoice: native attach failed in $packageName: $it")
        }

        XposedBridge.log("qwulivoice: installing safe AudioRecord.read hook in $packageName")
        XposedBridge.hookAllConstructors(AudioRecord::class.java, createAudioRecordConstructorHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "startRecording", createStartRecordingHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "read", createAudioReadHook(packageName))
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
    }
}
