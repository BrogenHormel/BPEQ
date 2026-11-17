package app.bpeq.widget

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.bpeq.BpeqApp
import app.bpeq.data.Applier
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows the profile on the dongle; each tap applies the next favorite profile
 * (all profiles if there are no favorites).
 */
class ProfileTileService : TileService() {
    override fun onStartListening() = refresh()

    private fun cycle(): List<app.bpeq.core.model.Profile> {
        val lib = BpeqApp.instance.library.profiles.value
        return lib.filter { it.favorite }.ifEmpty { lib }
    }

    private fun refresh() {
        val app = BpeqApp.instance
        app.devices.refreshStatus()
        val tile = qsTile ?: return
        val on = app.library.onDevice.value?.first?.let(app.library::get)
        val connected = app.devices.status.value.canTalk
        tile.label = "BPEQ"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            !connected -> "No dongle"
            on != null -> on.name
            else -> "Tap to apply"
        }
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        val app = BpeqApp.instance
        app.log.info("Quick Settings tile tapped")
        app.devices.refreshStatus()
        if (app.devices.status.value is DeviceStatus.Busy) return
        val list = cycle()
        if (list.isEmpty()) return
        val currentId = app.library.onDevice.value?.first
        val next = list[(list.indexOfFirst { it.id == currentId } + 1).mod(list.size)]
        qsTile?.let { t -> if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = "Applying ${next.name}…"; t.updateTile() }
        Applier.scope.launch { Applier.apply(next.id) { Applier.toast(it) }; refresh() }
    }
}

