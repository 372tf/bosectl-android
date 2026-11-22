package eu.depau.bosectl.widget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.data.DeviceRepository

private const val TAG = "WidgetActions"

/** Widget taps work even with the app process cold: connect on demand, act, refresh cache. */
private inline fun run(context: Context, what: String, crossinline block: suspend () -> Unit) {
    DeviceRepository.init(context)
    DeviceRepository.runAsync {
        runCatching { block() }.onFailure { Log.w(TAG, "$what from widget failed", it) }
    }
}

class SetModeAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("mode_idx")
        fun params(idx: Int) = actionParametersOf(KEY to idx)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val idx = parameters[KEY] ?: return
        run(context, "setMode") { DeviceRepository.setMode(idx) }
    }
}

class SetSpatialAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("spatial")
        fun params(value: Int) = actionParametersOf(KEY to value)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val value = parameters[KEY] ?: return
        run(context, "setSpatial") { DeviceRepository.setSpatial(Spatial.fromValue(value)) }
    }
}

class ToggleAncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val enabled = parameters[ToggleableStateKey] ?: return
        run(context, "setAnc") { DeviceRepository.setAnc(enabled) }
    }
}

class ToggleTouchControlsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val enabled = parameters[ToggleableStateKey] ?: return
        run(context, "setTouchControls") { DeviceRepository.setTouchControls(enabled) }
    }
}

/** Glance has no slider (RemoteViews has no SeekBar), so the level steps. */
class AdjustCncAction : ActionCallback {
    companion object {
        private val KEY = ActionParameters.Key<Int>("delta")
        fun params(delta: Int) = actionParametersOf(KEY to delta)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[KEY] ?: return
        run(context, "adjustCnc") { DeviceRepository.adjustCnc(delta) }
    }
}

/** 接続中のBoseデバイスへ即座に切り替えるアクション */
class SwitchDeviceAction : ActionCallback {
    private fun isDeviceConnected(device: BluetoothDevice): Boolean =
        runCatching {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        run(context, "switchDevice") {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@run
            val currentMac = DeviceRepository.savedDeviceMac()
            val connectedBoseDevices = adapter.bondedDevices
                ?.filter { device ->
                    device.name?.contains("Bose", ignoreCase = true) == true &&
                        isDeviceConnected(device)
                }
                ?.sortedBy { it.address }
                .orEmpty()

            if (connectedBoseDevices.isEmpty()) {
                Log.w(TAG, "No connected Bose device found")
                return@run
            }

            val currentIdx = connectedBoseDevices.indexOfFirst {
                it.address.equals(currentMac, ignoreCase = true)
            }

            // すでに選択中の1台だけが接続中なら、切り替えずに状態だけ最新化
            if (currentIdx != -1 && connectedBoseDevices.size == 1) {
                DeviceRepository.refresh()
                return@run
            }

            // 今の機器が未接続なら接続中の1台目へ、2台以上接続中なら次の機器へ切り替え
            val nextDevice = if (currentIdx == -1) {
                connectedBoseDevices.first()
            } else {
                connectedBoseDevices[(currentIdx + 1) % connectedBoseDevices.size]
            }

            // 設定を残したままキャッシュをクリアし、切り替え先をセットしてウィジェット更新
            DeviceRepository.setDevice(
                mac = nextDevice.address,
                displayName = nextDevice.name ?: "Switching...",
            )

            DeviceRepository.refresh()
        }
    }
}
