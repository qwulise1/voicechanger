package com.qwulise.voicechanger.app

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qwulise.voicechanger.core.VoiceConfig

class VoiceQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val current = runCatching { ModuleConfigClient.load(this) }.getOrDefault(VoiceConfig())
        val next = current.copy(enabled = !current.enabled).sanitized()
        runCatching { ModuleConfigClient.save(this, next) }
        updateTile(next)
    }

    private fun updateTile(config: VoiceConfig? = null) {
        val current = config ?: runCatching { ModuleConfigClient.load(this) }.getOrDefault(VoiceConfig())
        qsTile?.apply {
            label = "Voicechanger"
            subtitle = if (current.enabled) current.mode.title else "Выключен"
            state = if (current.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, VoiceQuickTileService::class.java),
                )
            }
        }
    }
}
