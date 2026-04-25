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
import java.lang.reflect.Method

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

        if (shouldDeferTelegramStartupHooks(packageName)) {
            installDeferredTelegramStartupHooks(packageName, lpparam.classLoader)
            return
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

        if (shouldInstallTelegramPluginSafety(packageName)) {
            installTelegramPluginSafetyHooks(packageName, lpparam.classLoader)
        }

        if (usesNativeOnlyHooks(packageName)) {
            XposedBridge.log("qwulivoice: native-only hook mode in $packageName")
            return
        }

        if (usesTelegramScopedHooks(packageName)) {
            val scopedInstalled = installTelegramScopedHooks(packageName, lpparam.classLoader)
            XposedBridge.log("qwulivoice: installing telegram-style AudioRecord.read hook in $packageName")
            XposedBridge.hookAllMethods(
                AudioRecord::class.java,
                "read",
                createAudioReadHook(
                    packageName = packageName,
                    trackedOnly = true,
                    byteBufferOnly = true,
                    allowUntrackedFallback = !scopedInstalled,
                ),
            )
        } else {
            XposedBridge.log("qwulivoice: installing safe AudioRecord.read hook in $packageName")
            XposedBridge.hookAllConstructors(AudioRecord::class.java, createAudioRecordConstructorHook(packageName))
            XposedBridge.hookAllMethods(AudioRecord::class.java, "startRecording", createStartRecordingHook(packageName))
            XposedBridge.hookAllMethods(AudioRecord::class.java, "read", createAudioReadHook(packageName))
            if (shouldInstallWebRtcJavaHooks(packageName)) {
                val installedAny = installWebRtcHooks(packageName, lpparam.classLoader)
                if (shouldInstallDeferredWebRtcHooks(packageName)) {
                    installDeferredWebRtcHooks(packageName, lpparam.classLoader)
                } else if (!installedAny) {
                    XposedBridge.log("qwulivoice: WebRTC classes not ready in $packageName")
                }
            } else {
                XposedBridge.log("qwulivoice: skipping Java WebRTC hook in $packageName for startup safety")
            }
        }
    }

    private fun installDeferredTelegramStartupHooks(packageName: String, classLoader: ClassLoader?) {
        installTelegramStartupSafetyHooks(packageName, classLoader)

        val applicationLoaderClass = runCatching {
            XposedHelpers.findClass("org.telegram.messenger.ApplicationLoader", classLoader)
        }.getOrNull()
        if (applicationLoaderClass != null) {
            installTelegramRuntimeAfterApplicationCreate(packageName, applicationLoaderClass, classLoader)
            return
        }

        if (classLoader == null) {
            return
        }
        val watcherKey = "$packageName|ApplicationLoader"
        if (!installedTelegramApplicationWatchers.add(watcherKey)) {
            return
        }

        val hookRef = arrayOfNulls<Set<XC_MethodHook.Unhook>>(1)
        hookRef[0] = XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val requestedName = param.args.firstOrNull() as? String ?: return
                if (requestedName != "org.telegram.messenger.ApplicationLoader") {
                    return
                }
                val loadedClass = param.result as? Class<*> ?: return
                installTelegramRuntimeAfterApplicationCreate(
                    packageName = packageName,
                    applicationLoaderClass = loadedClass,
                    fallbackClassLoader = loadedClass.classLoader ?: classLoader,
                )
                hookRef[0]?.forEach { it.unhook() }
                installedTelegramApplicationWatchers.remove(watcherKey)
            }
        })
    }

    private fun installTelegramRuntimeAfterApplicationCreate(
        packageName: String,
        applicationLoaderClass: Class<*>,
        fallbackClassLoader: ClassLoader?,
    ) {
        val installKey = "$packageName|${applicationLoaderClass.name}|onCreate"
        if (!installedTelegramApplicationHooks.add(installKey)) {
            return
        }

        val hookRef = arrayOfNulls<Set<XC_MethodHook.Unhook>>(1)
        hookRef[0] = XposedBridge.hookAllMethods(applicationLoaderClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                installTelegramRuntimeHooks(
                    packageName = packageName,
                    classLoader = param.thisObject?.javaClass?.classLoader ?: fallbackClassLoader,
                )
                DiagnosticsClient.reportEvent(
                    packageName = packageName,
                    source = "Telegram.startup",
                    detail = "Installed delayed runtime hooks after ApplicationLoader.onCreate.",
                    rateKey = "$packageName|Telegram.startup|installed",
                    minIntervalMs = 20_000L,
                )
                hookRef[0]?.forEach { it.unhook() }
            }
        })
    }

    private fun installTelegramRuntimeHooks(packageName: String, classLoader: ClassLoader?) {
        if (!installedTelegramRuntimePackages.add(packageName)) {
            return
        }
        val scopedInstalled = installTelegramScopedHooks(packageName, classLoader)
        XposedBridge.log("qwulivoice: installing delayed telegram-style AudioRecord.read hook in $packageName")
        XposedBridge.hookAllMethods(
            AudioRecord::class.java,
            "read",
            createAudioReadHook(
                packageName = packageName,
                trackedOnly = true,
                byteBufferOnly = true,
                allowUntrackedFallback = !scopedInstalled,
            ),
        )
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

    private fun createAudioReadHook(
        packageName: String,
        trackedOnly: Boolean = false,
        byteBufferOnly: Boolean = false,
        allowUntrackedFallback: Boolean = true,
    ) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                processReadResult(
                    packageName = packageName,
                    param = param,
                    trackedOnly = trackedOnly,
                    byteBufferOnly = byteBufferOnly,
                    allowUntrackedFallback = allowUntrackedFallback,
                )
            }.onFailure {
                XposedBridge.log("qwulivoice: AudioRecord.read hook failed in $packageName: $it")
            }
        }
    }

    private fun processReadResult(
        packageName: String,
        param: XC_MethodHook.MethodHookParam,
        trackedOnly: Boolean = false,
        byteBufferOnly: Boolean = false,
        allowUntrackedFallback: Boolean = true,
    ) {
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
        val trackedKind = HookBridge.classifyTrackedRecorder(audioRecord)
        if (trackedOnly) {
            if (trackedKind == null && !allowUntrackedFallback) {
                return
            }
            if (byteBufferOnly && param.args.firstOrNull() !is ByteBuffer) {
                return
            }
        }
        if (!trackedOnly && trackedKind == RecorderKind.CALL && param.args.firstOrNull() is ByteBuffer) {
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "AudioRecord.read",
                detail = "Skipping ByteBuffer call-recorder path; WebRTC hook will handle it.",
                rateKey = "$packageName|AudioRecord.read|webrtc-skip",
                minIntervalMs = 12_000L,
            )
            return
        }
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
                if (!HookBridge.shouldProcessBuffer(buffer, readCount, "AudioRecord.read")) {
                    return
                }
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

    private fun installTelegramScopedHooks(packageName: String, classLoader: ClassLoader?): Boolean {
        var installedAny = false
        val mediaControllerClass = runCatching {
            XposedHelpers.findClass("org.telegram.messenger.MediaController", classLoader)
        }.getOrNull()
        if (mediaControllerClass != null) {
            mediaControllerClass.declaredMethods
                .filter { method ->
                    method.name.startsWith("lambda\$startRecording$") ||
                        method.name.startsWith("lambda\$toggleRecordingPause$") ||
                        method.name == "startRecording"
                }
                .forEach { method ->
                    installedAny = true
                    XposedBridge.hookMethod(method, createTelegramNoteRecorderHook(packageName))
                }
            mediaControllerClass.declaredMethods
                .filter { it.name == "stopRecording" }
                .forEach { method ->
                    installedAny = true
                    XposedBridge.hookMethod(method, createTelegramNoteStopHook(packageName))
                }
        }

        WEB_RTC_RECORD_CLASSES.forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull() ?: return@forEach
            clazz.declaredMethods
                .filter { it.name == "initRecording" }
                .forEach { method ->
                    installedAny = true
                    XposedBridge.hookMethod(method, createWebRtcInitHook(packageName, className))
                }
            clazz.declaredMethods
                .filter { it.name == "stopRecording" || it.name == "onDestroy" }
                .forEach { method ->
                    installedAny = true
                    XposedBridge.hookMethod(method, createWebRtcStopHook(packageName, className))
                }
        }
        return installedAny
    }

    private fun createTelegramNoteRecorderHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val owner = param.thisObject ?: return
            val audioRecord = runCatching {
                XposedHelpers.getObjectField(owner, "audioRecorder") as? AudioRecord
            }.getOrNull()
            HookBridge.registerNoteRecorder(audioRecord)
            if (audioRecord != null) {
                DiagnosticsClient.reportEvent(
                    packageName = packageName,
                    source = "Telegram.noteRecorder",
                    detail = "Registered note recorder=${audioRecord.hashCode()}",
                    rateKey = "$packageName|Telegram.noteRecorder",
                    minIntervalMs = 15_000L,
                )
            }
        }
    }

    private fun createTelegramNoteStopHook(packageName: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            HookBridge.clearNoteRecorders()
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "Telegram.noteRecorder",
                detail = "Cleared note recorder set.",
                rateKey = "$packageName|Telegram.noteRecorder|clear",
                minIntervalMs = 15_000L,
            )
        }
    }

    private fun createWebRtcInitHook(packageName: String, className: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result
            val success = (result as? Int)?.let { it >= 0 } ?: true
            if (!success) {
                return
            }
            val audioRecord = runCatching {
                XposedHelpers.getObjectField(param.thisObject, "audioRecord") as? AudioRecord
            }.getOrNull()
            HookBridge.registerCallRecorder(audioRecord)
            if (audioRecord != null) {
                DiagnosticsClient.reportEvent(
                    packageName = packageName,
                    source = "WebRtc.initRecording",
                    detail = "Registered call recorder=${audioRecord.hashCode()} class=${className.substringAfterLast('.')}",
                    rateKey = "$packageName|$className|initRecording",
                    minIntervalMs = 10_000L,
                )
            }
        }
    }

    private fun createWebRtcStopHook(packageName: String, className: String) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            HookBridge.clearCallRecorders()
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "WebRtc.stopRecording",
                detail = "Cleared call recorder set for ${className.substringAfterLast('.')}",
                rateKey = "$packageName|$className|stopRecording",
                minIntervalMs = 10_000L,
            )
        }
    }

    private fun installWebRtcHooks(packageName: String, classLoader: ClassLoader?): Boolean {
        var installedAny = false
        WEB_RTC_RECORD_CLASSES.forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull() ?: return@forEach
            installedAny = installWebRtcHooksOnClass(packageName, className, clazz) || installedAny
        }
        return installedAny
    }

    private fun installWebRtcHooksOnClass(
        packageName: String,
        className: String,
        clazz: Class<*>,
    ): Boolean {
        val installKey = "$packageName|$className"
        if (!installedWebRtcClasses.add(installKey)) {
            return false
        }
        XposedBridge.log("qwulivoice: installing WebRTC nativeDataIsRecorded hook in $packageName for $className")
        XposedBridge.hookAllMethods(clazz, "nativeDataIsRecorded", createWebRtcRecordHook(packageName, className))
        clazz.declaredMethods
            .filter { it.name == "initRecording" }
            .forEach { method ->
                XposedBridge.hookMethod(method, createWebRtcInitHook(packageName, className))
            }
        clazz.declaredMethods
            .filter { it.name == "stopRecording" || it.name == "onDestroy" }
            .forEach { method ->
                XposedBridge.hookMethod(method, createWebRtcStopHook(packageName, className))
            }
        return true
    }

    private fun installDeferredWebRtcHooks(packageName: String, classLoader: ClassLoader?) {
        if (classLoader == null) {
            return
        }
        val watcherKey = "$packageName|webrtc"
        if (!installedWebRtcWatchers.add(watcherKey)) {
            return
        }

        val hookRef = arrayOfNulls<Set<XC_MethodHook.Unhook>>(1)
        hookRef[0] = XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val requestedName = param.args.firstOrNull() as? String ?: return
                if (requestedName !in WEB_RTC_RECORD_CLASSES) {
                    return
                }
                val loadedClass = param.result as? Class<*> ?: return
                installWebRtcHooksOnClass(packageName, requestedName, loadedClass)
                val allInstalled = WEB_RTC_RECORD_CLASSES.all { className ->
                    installedWebRtcClasses.contains("$packageName|$className")
                }
                if (allInstalled) {
                    hookRef[0]?.forEach { it.unhook() }
                    installedWebRtcWatchers.remove(watcherKey)
                }
            }
        })
    }

    private fun installTelegramPluginSafetyHooks(packageName: String, classLoader: ClassLoader?) {
        TELEGRAM_PLUGIN_SAFETY_CLASSES.keys.forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
            if (clazz != null) {
                installTelegramPluginSafetyHooksOnClass(packageName, className, clazz)
            }
        }
        if (classLoader == null) {
            return
        }
        val watcherKey = "$packageName|plugin-safety"
        if (!installedPluginSafetyWatchers.add(watcherKey)) {
            return
        }
        val hookRef = arrayOfNulls<Set<XC_MethodHook.Unhook>>(1)
        hookRef[0] = XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val requestedName = param.args.firstOrNull() as? String ?: return
                if (requestedName !in TELEGRAM_PLUGIN_SAFETY_CLASSES.keys) {
                    return
                }
                val loadedClass = param.result as? Class<*> ?: return
                installTelegramPluginSafetyHooksOnClass(packageName, requestedName, loadedClass)
                val installedAll = TELEGRAM_PLUGIN_SAFETY_CLASSES.keys.all { className ->
                    installedPluginSafetyClasses.contains("$packageName|$className")
                }
                if (installedAll) {
                    hookRef[0]?.forEach { it.unhook() }
                    installedPluginSafetyWatchers.remove(watcherKey)
                }
            }
        })
    }

    private fun installTelegramStartupSafetyHooks(packageName: String, classLoader: ClassLoader?) {
        installOneShotShortCircuitWatcher(
            packageName = packageName,
            classLoader = classLoader,
            className = "com.exteragram.messenger.plugins.PluginsController",
            methodName = "applyBlacklist",
        )
    }

    private fun installOneShotShortCircuitWatcher(
        packageName: String,
        classLoader: ClassLoader?,
        className: String,
        methodName: String,
    ) {
        if (classLoader == null) {
            return
        }
        val watcherKey = "$packageName|$className|$methodName|startup"
        if (!installedStartupSafetyWatchers.add(watcherKey)) {
            return
        }

        val hookRef = arrayOfNulls<Set<XC_MethodHook.Unhook>>(1)
        hookRef[0] = XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val requestedName = param.args.firstOrNull() as? String ?: return
                if (requestedName != className) {
                    return
                }
                val loadedClass = param.result as? Class<*> ?: return
                installOneShotShortCircuitHookOnClass(
                    packageName = packageName,
                    className = className,
                    clazz = loadedClass,
                    methodName = methodName,
                )
                hookRef[0]?.forEach { it.unhook() }
                installedStartupSafetyWatchers.remove(watcherKey)
            }
        })
    }

    private fun installOneShotShortCircuitHookOnClass(
        packageName: String,
        className: String,
        clazz: Class<*>,
        methodName: String,
    ) {
        val installKey = "$packageName|$className|$methodName|one-shot"
        if (!installedStartupSafetyClasses.add(installKey)) {
            return
        }

        val hookRef = arrayOfNulls<Set<XC_MethodHook.Unhook>>(1)
        hookRef[0] = XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                DiagnosticsClient.reportEvent(
                    packageName = packageName,
                    source = "PluginSafety.startup",
                    detail = "Short-circuited ${className.substringAfterLast('.')}.${methodName} during startup.",
                    rateKey = "$packageName|$className|$methodName|startup",
                    minIntervalMs = 20_000L,
                )
                val returnType = (param.method as? Method)?.returnType
                param.result = defaultReturnValue(returnType)
                hookRef[0]?.forEach { it.unhook() }
            }
        })
    }

    private fun installTelegramPluginSafetyHooksOnClass(
        packageName: String,
        className: String,
        clazz: Class<*>,
    ): Boolean {
        val installKey = "$packageName|$className"
        if (!installedPluginSafetyClasses.add(installKey)) {
            return false
        }
        val targetMethods = TELEGRAM_PLUGIN_SAFETY_CLASSES[className].orEmpty()
        var installedAny = false
        targetMethods.forEach { methodName ->
            val unhooks = XposedBridge.hookAllMethods(
                clazz,
                methodName,
                createPluginSafetyShortCircuitHook(packageName, className, methodName),
            )
            if (unhooks.isNotEmpty()) {
                installedAny = true
            }
        }
        if (!installedAny) {
            installedPluginSafetyClasses.remove(installKey)
            return false
        }
        XposedBridge.log("qwulivoice: installed plugin runtime safety in $packageName for $className")
        return true
    }

    private fun createPluginSafetyShortCircuitHook(
        packageName: String,
        className: String,
        methodName: String,
    ) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            DiagnosticsClient.reportEvent(
                packageName = packageName,
                source = "PluginSafety",
                detail = "Short-circuited ${className.substringAfterLast('.')}.${methodName}",
                rateKey = "$packageName|$className|$methodName|plugin-safety",
                minIntervalMs = 8_000L,
            )
            val returnType = (param.method as? Method)?.returnType
            param.result = defaultReturnValue(returnType)
        }
    }

    private fun defaultReturnValue(returnType: Class<*>?): Any? =
        when (returnType) {
            null,
            Void.TYPE -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
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
        if (byteCount <= 0 || !HookBridge.shouldProcessBuffer(buffer, byteCount, "WebRtc.nativeDataIsRecorded")) {
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

    private fun isTelegramFamilyPackage(packageName: String): Boolean =
        packageName.startsWith("org.telegram.messenger") ||
            packageName.startsWith("com.exteragram")

    private fun shouldDeferTelegramStartupHooks(packageName: String): Boolean =
        packageName == "org.telegram.messenger"

    private fun usesTelegramScopedHooks(packageName: String): Boolean =
        isTelegramFamilyPackage(packageName) && !usesNativeOnlyHooks(packageName)

    private fun usesNativeOnlyHooks(packageName: String): Boolean =
        packageName.startsWith("com.exteragram") ||
            packageName.startsWith("com.discord")

    private fun shouldAttachNative(packageName: String): Boolean =
        usesNativeOnlyHooks(packageName) || !isTelegramFamilyPackage(packageName)

    private fun shouldRestrictToMainProcess(packageName: String): Boolean =
        isTelegramFamilyPackage(packageName)

    private fun shouldInstallWebRtcJavaHooks(packageName: String): Boolean =
        isTelegramFamilyPackage(packageName) ||
            packageName == "com.google.android.dialer" ||
            packageName.startsWith("com.discord")

    private fun shouldInstallTelegramPluginSafety(packageName: String): Boolean =
        packageName.startsWith("com.exteragram")

    private fun shouldInstallDeferredWebRtcHooks(packageName: String): Boolean =
        packageName == "com.google.android.dialer" ||
            packageName.startsWith("com.discord")

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
        @Volatile
        private var reportingDisabled = false
        @Volatile
        private var failureLogged = false

        fun reportEvent(
            packageName: String,
            source: String,
            detail: String,
            rateKey: String = "$packageName|$source",
            minIntervalMs: Long = 10_000L,
        ) {
            if (reportingDisabled) {
                return
            }
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
                    disableReporting("file unavailable for $packageName/$source")
                }
            }.onFailure {
                disableReporting(it.toString())
            }
        }

        private fun disableReporting(reason: String) {
            reportingDisabled = true
            if (!failureLogged) {
                failureLogged = true
                XposedBridge.log("qwulivoice: disabling target diagnostics in this process: $reason")
            }
        }
    }

    companion object {
        private val installedPackages = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedTelegramRuntimePackages = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedTelegramApplicationHooks = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedTelegramApplicationWatchers = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedWebRtcClasses = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedWebRtcWatchers = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedPluginSafetyClasses = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedPluginSafetyWatchers = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedStartupSafetyClasses = Collections.synchronizedSet(mutableSetOf<String>())
        private val installedStartupSafetyWatchers = Collections.synchronizedSet(mutableSetOf<String>())
        private val WEB_RTC_RECORD_CLASSES = listOf(
            "org.webrtc.audio.WebRtcAudioRecord",
            "org.webrtc.voiceengine.WebRtcAudioRecord",
        )
        private val TELEGRAM_PLUGIN_SAFETY_CLASSES = linkedMapOf(
            "com.exteragram.messenger.plugins.PluginsController" to listOf(
                "applyBlacklist",
            ),
        )
    }
}
