package eu.depau.bosectl.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Presence detection: ACL (dis)connect broadcasts (implicit-broadcast exempt,
 * so they wake the app) trigger connect/teardown for the selected device.
 */
class AclReceiver : BroadcastReceiver() {

    private fun isDeviceConnected(device: BluetoothDevice): Boolean =
        runCatching {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun findOtherConnectedBoseDevice(
        context: Context,
        excludedMac: String,
    ): BluetoothDevice? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return null
        return runCatching {
            adapter.bondedDevices
                ?.filter { candidate ->
                    !candidate.address.equals(excludedMac, ignoreCase = true) &&
                        candidate.name?.contains("Bose", ignoreCase = true) == true &&
                        isDeviceConnected(candidate)
                }
                ?.sortedBy { it.address }
                ?.firstOrNull()
        }.getOrNull()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return
        DeviceRepository.init(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val savedMac = DeviceRepository.savedDeviceMac() ?: return@launch
                if (!savedMac.equals(device.address, ignoreCase = true)) return@launch

                when (intent.action) {
                    // Bluetooth broadcasts guarded by BLUETOOTH_CONNECT are exempt
                    // from the background foreground-service start restrictions.
                    BluetoothDevice.ACTION_ACL_CONNECTED -> BoseConnectionService.start(context)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val fallback = findOtherConnectedBoseDevice(context, device.address)
                        if (fallback != null) {
                            Log.i(TAG, "Active device disconnected; falling back to ${fallback.address}")
                            DeviceRepository.setDevice(
                                mac = fallback.address,
                                displayName = fallback.name ?: "Switching...",
                            )
                            runCatching { DeviceRepository.refresh() }
                                .onFailure { Log.w(TAG, "Fallback refresh failed", it) }
                        } else {
                            DeviceRepository.onDeviceDisappeared()
                            BoseConnectionService.stop(context)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AclReceiver"
    }
}
