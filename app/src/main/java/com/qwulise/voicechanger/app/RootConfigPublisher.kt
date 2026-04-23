package com.qwulise.voicechanger.app

import com.qwulise.voicechanger.core.VoiceConfig
import com.qwulise.voicechanger.core.VoiceConfigFileBridge
import java.io.File
import java.util.concurrent.TimeUnit

object RootConfigPublisher {
    private const val SU_TIMEOUT_SECONDS = 6L

    fun publishConfig(config: VoiceConfig) {
        val rawConfig = VoiceConfigFileBridge.encodeConfig(config)
        val script = """
            umask 000
            mkdir -p /data/local/tmp
            cat > ${VoiceConfigFileBridge.CONFIG_PATH}
            touch ${VoiceConfigFileBridge.LOG_PATH}
            chmod 666 ${VoiceConfigFileBridge.CONFIG_PATH} ${VoiceConfigFileBridge.LOG_PATH}
        """.trimIndent()
        runSu(script, rawConfig)
    }

    fun clearLogs() {
        val script = """
            umask 000
            mkdir -p /data/local/tmp
            : > ${VoiceConfigFileBridge.LOG_PATH}
            chmod 666 ${VoiceConfigFileBridge.LOG_PATH}
        """.trimIndent()
        runSu(script, "")
    }

    fun readRootLogs() = VoiceConfigFileBridge.readEventFile()

    fun readRootConfig(): VoiceConfig? = VoiceConfigFileBridge.readConfigFile()

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

    fun describePaths(): String = buildString {
        append(VoiceConfigFileBridge.CONFIG_PATH)
        append(if (File(VoiceConfigFileBridge.CONFIG_PATH).isFile) " exists" else " missing")
        append("\n")
        append(VoiceConfigFileBridge.LOG_PATH)
        append(if (File(VoiceConfigFileBridge.LOG_PATH).isFile) " exists" else " missing")
    }
}
