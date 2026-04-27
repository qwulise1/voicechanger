package com.qwulise.voicechanger.app

import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigFileBridge
import com.qwulise.voicechanger.core.SoundpadFileBridge
import com.qwulise.voicechanger.core.SoundpadLibrary
import com.qwulise.voicechanger.core.SoundpadPlayback
import java.io.File
import java.util.concurrent.TimeUnit

object RootConfigPublisher {
    private const val SU_TIMEOUT_SECONDS = 6L

    fun publishConfig(packageName: String, config: VoiceConfig) {
        val rawConfig = VoiceConfigFileBridge.encodeConfig(config)
        val configPath = VoiceConfigFileBridge.configPathFor(packageName)
        val logPath = VoiceConfigFileBridge.logPathFor(packageName)
        val script = """
            umask 000
            mkdir -p /data/local/tmp
            mkdir -p /data/adb/qwulivoice
            tee ${configPath} /data/adb/qwulivoice/config.properties >/dev/null
            touch ${logPath}
            rm -f ${VoiceConfigFileBridge.CONFIG_PATH}
            chmod 666 ${configPath} ${logPath} /data/adb/qwulivoice/config.properties
        """.trimIndent()
        runSu(script, rawConfig)
    }

    fun clearLogs(packageName: String) {
        val logPath = VoiceConfigFileBridge.logPathFor(packageName)
        val script = """
            umask 000
            mkdir -p /data/local/tmp
            : > ${logPath}
            chmod 666 ${logPath}
        """.trimIndent()
        runSu(script, "")
    }

    fun readRootLogs(packageName: String) =
        VoiceConfigFileBridge.readEventFile(VoiceConfigFileBridge.logPathFor(packageName))

    fun readRootConfig(packageName: String): VoiceConfig? =
        VoiceConfigFileBridge.readConfigFile(VoiceConfigFileBridge.configPathFor(packageName))

    fun publishSoundpadLibrary(packageName: String, library: SoundpadLibrary) {
        val rawLibrary = SoundpadFileBridge.encodeLibrary(library)
        val libraryPath = SoundpadFileBridge.libraryPathFor(packageName)
        val playbackPath = SoundpadFileBridge.playbackPathFor(packageName)
        val script = """
            umask 000
            mkdir -p /data/local/tmp
            mkdir -p /data/adb/qwulivoice
            mkdir -p ${SoundpadFileBridge.pcmDirectoryFor(packageName)}
            tee ${libraryPath} /data/adb/qwulivoice/soundpad.properties >/dev/null
            touch ${playbackPath}
            touch /data/adb/qwulivoice/soundpad.state.properties
            chmod 666 ${libraryPath} ${playbackPath} /data/adb/qwulivoice/soundpad.properties /data/adb/qwulivoice/soundpad.state.properties
            chmod 777 ${SoundpadFileBridge.pcmDirectoryFor(packageName)}
        """.trimIndent()
        runSu(script, rawLibrary)
    }

    fun publishSoundpadPlayback(packageName: String, playback: SoundpadPlayback) {
        val rawPlayback = SoundpadFileBridge.encodePlayback(playback)
        val playbackPath = SoundpadFileBridge.playbackPathFor(packageName)
        val libraryPath = SoundpadFileBridge.libraryPathFor(packageName)
        val script = """
            umask 000
            mkdir -p /data/local/tmp
            mkdir -p /data/adb/qwulivoice
            mkdir -p ${SoundpadFileBridge.pcmDirectoryFor(packageName)}
            touch ${libraryPath}
            touch /data/adb/qwulivoice/soundpad.properties
            tee ${playbackPath} /data/adb/qwulivoice/soundpad.state.properties >/dev/null
            chmod 666 ${libraryPath} ${playbackPath} /data/adb/qwulivoice/soundpad.properties /data/adb/qwulivoice/soundpad.state.properties
            chmod 777 ${SoundpadFileBridge.pcmDirectoryFor(packageName)}
        """.trimIndent()
        runSu(script, rawPlayback)
    }

    fun publishSoundpadClip(packageName: String, slotId: String, sourceFile: File) {
        require(sourceFile.isFile) { "PCM source file not found: ${sourceFile.absolutePath}" }
        val targetPath = SoundpadFileBridge.pcmPathFor(packageName, slotId)
        val targetFileName = File(targetPath).name
        val script = """
            umask 000
            mkdir -p ${SoundpadFileBridge.pcmDirectoryFor(packageName)}
            mkdir -p /data/adb/qwulivoice/soundpad
            cp '${sourceFile.absolutePath}' '${targetPath}'
            chmod 666 '${targetPath}'
            cp '${targetPath}' /data/adb/qwulivoice/soundpad/
            chmod 666 '/data/adb/qwulivoice/soundpad/${targetFileName}'
            chmod 777 ${SoundpadFileBridge.pcmDirectoryFor(packageName)}
            chmod 777 /data/adb/qwulivoice/soundpad
        """.trimIndent()
        runSu(script, "")
    }

    fun readRootSoundpadLibrary(packageName: String): SoundpadLibrary? =
        SoundpadFileBridge.readLibraryFile(SoundpadFileBridge.libraryPathFor(packageName))

    fun readRootSoundpadPlayback(packageName: String): SoundpadPlayback? =
        SoundpadFileBridge.readPlaybackFile(SoundpadFileBridge.playbackPathFor(packageName))

    private fun runSu(script: String, stdin: String) {
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(stdin)
        }
        if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("su timeout")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            error(output.ifBlank { "su exit ${process.exitValue()}" })
        }
    }

    fun appendRootLog(packageName: String, event: com.qwulise.voicechanger.core.DiagnosticEvent): Boolean =
        VoiceConfigFileBridge.appendEventFile(event, VoiceConfigFileBridge.logPathFor(packageName))
}
