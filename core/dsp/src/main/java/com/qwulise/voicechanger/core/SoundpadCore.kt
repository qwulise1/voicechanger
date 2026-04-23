package com.qwulise.voicechanger.core

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

data class SoundpadSlot(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val pcmPath: String = "",
    val sampleRate: Int = 0,
    val frameCount: Int = 0,
    val gainPercent: Int = 100,
    val accentIndex: Int = 0,
) {
    val isReady: Boolean
        get() = pcmPath.isNotBlank() && sampleRate > 0 && frameCount > 0

    fun sanitized(): SoundpadSlot = copy(
        id = safeId(id),
        title = title.trim().ifBlank { "Пад" },
        subtitle = subtitle.trim(),
        pcmPath = pcmPath.trim(),
        sampleRate = sampleRate.coerceAtLeast(0),
        frameCount = frameCount.coerceAtLeast(0),
        gainPercent = gainPercent.coerceIn(0, 160),
        accentIndex = accentIndex.coerceAtLeast(0),
    )

    companion object {
        fun empty(index: Int): SoundpadSlot = SoundpadSlot(
            id = "slot_${index + 1}",
            title = "Пад ${index + 1}",
            subtitle = "Импортируй свой звук",
            accentIndex = index,
        )

        internal fun safeId(value: String): String =
            value.ifBlank { "slot" }
                .map { char ->
                    when {
                        char.isLetterOrDigit() || char == '_' || char == '-' -> char
                        else -> '_'
                    }
                }
                .joinToString("")
    }
}

data class SoundpadLibrary(
    val slots: List<SoundpadSlot> = defaultSlots(),
) {
    fun sanitized(): SoundpadLibrary {
        val normalized = slots
            .take(MAX_SLOTS)
            .mapIndexed { index, slot ->
                slot.copy(
                    id = slot.id.ifBlank { "slot_${index + 1}" },
                    accentIndex = if (slot.accentIndex < 0) index else slot.accentIndex,
                ).sanitized()
            }
        return if (normalized.size >= MAX_SLOTS) {
            copy(slots = normalized)
        } else {
            copy(slots = normalized + defaultSlots().drop(normalized.size))
        }
    }

    fun slot(slotId: String): SoundpadSlot? =
        sanitized().slots.firstOrNull { it.id == slotId }

    companion object {
        const val MAX_SLOTS = 6

        fun defaultSlots(): List<SoundpadSlot> =
            List(MAX_SLOTS, SoundpadSlot::empty)
    }
}

data class SoundpadPlayback(
    val activeSlotId: String = "",
    val playing: Boolean = false,
    val looping: Boolean = false,
    val mixPercent: Int = 70,
    val sessionId: Long = 0L,
) {
    fun sanitized(): SoundpadPlayback = copy(
        activeSlotId = SoundpadSlot.safeId(activeSlotId),
        mixPercent = mixPercent.coerceIn(0, 100),
        sessionId = sessionId.coerceAtLeast(0L),
    )
}

object SoundpadFileBridge {
    fun libraryPathFor(packageName: String): String =
        "/data/local/tmp/qwulivoice-${safeName(packageName)}.soundpad.properties"

    fun playbackPathFor(packageName: String): String =
        "/data/local/tmp/qwulivoice-${safeName(packageName)}.soundpad.state.properties"

    fun pcmDirectoryFor(packageName: String): String =
        "/data/local/tmp/qwulivoice-${safeName(packageName)}-soundpad"

    fun pcmPathFor(packageName: String, slotId: String): String =
        "${pcmDirectoryFor(packageName)}/${safeName(SoundpadSlot.safeId(slotId))}.pcm"

    fun encodeLibrary(library: SoundpadLibrary): String {
        val sanitized = library.sanitized()
        val properties = Properties().apply {
            setProperty("slot_count", sanitized.slots.size.toString())
            sanitized.slots.forEachIndexed { index, slot ->
                setProperty("slot.$index.id", slot.id)
                setProperty("slot.$index.title", slot.title)
                setProperty("slot.$index.subtitle", slot.subtitle)
                setProperty("slot.$index.pcm_path", slot.pcmPath)
                setProperty("slot.$index.sample_rate", slot.sampleRate.toString())
                setProperty("slot.$index.frame_count", slot.frameCount.toString())
                setProperty("slot.$index.gain_percent", slot.gainPercent.toString())
                setProperty("slot.$index.accent_index", slot.accentIndex.toString())
            }
        }
        return StringWriter().also { writer ->
            properties.store(writer, "qwulivoice soundpad library")
        }.toString()
    }

    fun decodeLibrary(raw: String): SoundpadLibrary {
        val properties = Properties().apply {
            load(StringReader(raw))
        }
        val count = properties.getProperty("slot_count")?.toIntOrNull()
            ?.coerceIn(0, SoundpadLibrary.MAX_SLOTS)
            ?: SoundpadLibrary.MAX_SLOTS
        val slots = List(count) { index ->
            SoundpadSlot(
                id = properties.getProperty("slot.$index.id") ?: "slot_${index + 1}",
                title = properties.getProperty("slot.$index.title") ?: "Пад ${index + 1}",
                subtitle = properties.getProperty("slot.$index.subtitle").orEmpty(),
                pcmPath = properties.getProperty("slot.$index.pcm_path").orEmpty(),
                sampleRate = properties.getProperty("slot.$index.sample_rate")?.toIntOrNull() ?: 0,
                frameCount = properties.getProperty("slot.$index.frame_count")?.toIntOrNull() ?: 0,
                gainPercent = properties.getProperty("slot.$index.gain_percent")?.toIntOrNull() ?: 100,
                accentIndex = properties.getProperty("slot.$index.accent_index")?.toIntOrNull() ?: index,
            )
        }
        return SoundpadLibrary(slots).sanitized()
    }

    fun encodePlayback(playback: SoundpadPlayback): String {
        val sanitized = playback.sanitized()
        val properties = Properties().apply {
            setProperty("active_slot_id", sanitized.activeSlotId)
            setProperty("playing", sanitized.playing.toString())
            setProperty("looping", sanitized.looping.toString())
            setProperty("mix_percent", sanitized.mixPercent.toString())
            setProperty("session_id", sanitized.sessionId.toString())
        }
        return StringWriter().also { writer ->
            properties.store(writer, "qwulivoice soundpad playback")
        }.toString()
    }

    fun decodePlayback(raw: String): SoundpadPlayback {
        val properties = Properties().apply {
            load(StringReader(raw))
        }
        return SoundpadPlayback(
            activeSlotId = properties.getProperty("active_slot_id").orEmpty(),
            playing = properties.getProperty("playing")?.equals("true", ignoreCase = true) == true,
            looping = properties.getProperty("looping")?.equals("true", ignoreCase = true) == true,
            mixPercent = properties.getProperty("mix_percent")?.toIntOrNull() ?: 70,
            sessionId = properties.getProperty("session_id")?.toLongOrNull() ?: 0L,
        ).sanitized()
    }

    fun readLibraryFile(path: String): SoundpadLibrary? =
        runCatching {
            val file = File(path)
            if (!file.isFile) {
                null
            } else {
                decodeLibrary(file.readText())
            }
        }.getOrNull()

    fun readPlaybackFile(path: String): SoundpadPlayback? =
        runCatching {
            val file = File(path)
            if (!file.isFile) {
                null
            } else {
                decodePlayback(file.readText())
            }
        }.getOrNull()

    private fun safeName(packageName: String): String =
        packageName.ifBlank { "default" }
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
}
