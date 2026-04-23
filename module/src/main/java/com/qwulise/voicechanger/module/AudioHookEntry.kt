package com.qwulise.voicechanger.module

import android.app.Application
import android.content.Context
import android.media.AudioRecord
import com.qwulise.voicechanger.core.DiagnosticEvent
import com.qwulise.voicechanger.core.PcmVoiceProcessor
import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigContract
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

        XposedBridge.log("qwulivoice: installing safe AudioRecord.read hook in $packageName")
        XposedBridge.hookAllMethods(Application::class.java, "attach", createApplicationAttachHook(packageName))
        XposedBridge.hookAllConstructors(AudioRecord::class.java, createAudioRecordConstructorHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "startRecording", createStartRecordingHook(packageName))
        XposedBridge.hookAllMethods(AudioRecord::class.java, "read", createAudioReadHook(packageName))
    }

    private fun createApplicationAttachHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val context = param.args.firstOrNull() as? Context
            ProcessContextResolver.attach(context)
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "Application.attach",
                detail = "LSPosed module injected. process=${android.os.Process.myPid()} rootConfig=${ModuleFileBridge.readConfig() != null}",
                rateKey = "$packageName|Application.attach|injected",
                minIntervalMs = 15_000L,
            )
        }
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
        if (!config.enabled) {
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
            detail = "Processed read=$readCount rate=${sampleRate}Hz mode=${config.mode.id} boost=${config.micGainPercent} ${session.describe()}",
            rateKey = "$packageName|AudioRecord.read|processed",
            minIntervalMs = 12_000L,
        )
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

                val rootConfig = ModuleFileBridge.readConfig()
                val context = ProcessContextResolver.resolve()
                if (context == null) {
                    cachedConfig = rootConfig ?: cachedConfig
                    lastLoadedAt = refreshedNow
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
                    XposedBridge.log("qwulivoice: config fetch failed: $error")
                    rootConfig ?: cachedConfig
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
                val context = ProcessContextResolver.resolve()
                val delivered = if (context == null) {
                    ModuleFileBridge.appendEvent(event)
                } else {
                    runCatching {
                        requireNotNull(context.contentResolver.call(
                            VoiceConfigContract.CONTENT_URI,
                            VoiceConfigContract.METHOD_APPEND_LOG,
                            null,
                            android.os.Bundle().apply {
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
