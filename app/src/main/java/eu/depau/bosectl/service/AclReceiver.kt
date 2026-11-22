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
    private fun isBoseDevice(device: BluetoothDevice): Boolean =
        runCatching {
            device.name?.contains("Bose", ignoreCase = true) == true
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun isMacConnected(context: Context, mac: String): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        return runCatching {
            val device = adapter.getRemoteDevice(mac)
            isDeviceConnected(device)
        }.getOrDefault(false)
    }

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
                        isBoseDevice(candidate) &&
                        isDeviceConnected(candidate)
                }
                ?.sortedBy { it.address }
                ?.firstOrNull()
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return
        DeviceRepository.init(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val savedMac = DeviceRepository.savedDeviceMac() ?: return@launch
                val isSavedDevice = savedMac.equals(device.address, ignoreCase = true)

                when (intent.action) {
                    // Bluetooth broadcasts guarded by BLUETOOTH_CONNECT are exempt
                    // from the background foreground-service start restrictions.
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (isSavedDevice) {
                            BoseConnectionService.start(context)
                        } else if (
                            isBoseDevice(device) &&
                            !DeviceRepository.state.value.connected &&
                            !isMacConnected(context, savedMac)
                        ) {
                            val name = runCatching { device.name }.getOrNull()
                            Log.i(TAG, "Saved device offline; auto-switching to ${device.address}")
                            DeviceRepository.setDevice(
                                mac = device.address,
                                displayName = name ?: "Switching...",
                            )
                            BoseConnectionService.start(context)
                        }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (!isSavedDevice) return@launch
                        val fallback = findOtherConnectedBoseDevice(context, device.address)
                        if (fallback != null) {
                            val name = runCatching { fallback.name }.getOrNull()
                            Log.i(TAG, "Active device disconnected; falling back to ${fallback.address}")
                            DeviceRepository.setDevice(
                                mac = fallback.address,
                                displayName = name ?: "Switching...",
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
