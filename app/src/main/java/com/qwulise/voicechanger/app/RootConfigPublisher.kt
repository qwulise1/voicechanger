package com.qwulise.voicechanger.app

import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigFileBridge
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
            cat > ${configPath}
            touch ${logPath}
            rm -f ${VoiceConfigFileBridge.CONFIG_PATH}
            chmod 666 ${configPath} ${logPath}
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
