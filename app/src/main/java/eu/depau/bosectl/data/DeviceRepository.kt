package eu.depau.bosectl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import eu.depau.bosectl.bmap.BmapTransport
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import eu.depau.bosectl.bmap.AudioSettings
import eu.depau.bosectl.bmap.BatteryStatus
import eu.depau.bosectl.bmap.BoseAdvertisement
import eu.depau.bosectl.bmap.BmapConnection
import eu.depau.bosectl.bmap.BmapException
import eu.depau.bosectl.bmap.Favorites
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.PairedDevice
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.bmap.bmapPacket
import eu.depau.bosectl.bmap.parseActiveSource
import eu.depau.bosectl.bmap.parseAudioSettings
import eu.depau.bosectl.bmap.parseBattery
import eu.depau.bosectl.bmap.mergePairedDevices
import eu.depau.bosectl.bmap.parseDeviceList
import eu.depau.bosectl.bmap.parseFavorites
import eu.depau.bosectl.bmap.parseModeConfig
import eu.depau.bosectl.widget.BoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DeviceRepository"
private const val POLL_FOREGROUND_MS = 2000L
private const val POLL_BACKGROUND_MS = 10000L

/** Give up spinning on a connect/disconnect that never reports back. */
private const val DEVICE_ACTION_TIMEOUT_MS = 20_000L

/** Advertisements arrive every few seconds; don't retry a failed connect that often. */
private const val AUTO_CONNECT_RETRY_MS = 60_000L

/** A broadcast receiver is held open across this, so keep it short. */
private const val AUTO_CONNECT_BUDGET_MS = 8_000L

/**
 * After a failed connect, automatic triggers (ACL up, sightings, screens) must
 * not pile up retries: with a broken bond each secure RFCOMM attempt forced a
 * classic auth failure that tore the ACL down, and retrying on every resulting
 * ACL_CONNECTED broadcast became a chime storm (see CLAUDE.md).
 */
private const val CONNECT_FAILURE_COOLDOWN_MS = 60_000L

/** How often to notice that an LE link died. Costs no radio traffic. */
private const val LINK_WATCHDOG_MS = 15_000L

/** Comfortably inside the ~40s idle timeout the earbuds enforce on an LE link. */
private const val LE_KEEPALIVE_MS = 20_000L

data class BoseState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val deviceName: String? = null,
    val firmware: String? = null,
    val battery: BatteryStatus? = null,
    val modes: List<ModeConfig> = emptyList(),
    val currentModeIdx: Int? = null,
    val favorites: Favorites? = null,
    val audioSettings: AudioSettings? = null,
    val touchControls: Boolean? = null,
    /** Devices the earbuds know about, in the order [4.4] last reported them. */
    val pairedDevices: List<PairedDevice> = emptyList(),
    /** MAC of the device currently holding audio, from [5.1]. */
    val activeSourceMac: String? = null,
    /** Which transport the live connection is using, null when disconnected. */
    val link: LinkLayer? = null,
    /** When a BLE advertisement was last seen, epoch millis; null if never. */
    val lastSeenAt: Long? = null,
    /** The advertisement's "available to connect" bit at [lastSeenAt]. */
    val lastSeenAvailable: Boolean = false,
    /**
     * Devices with a connect or disconnect in flight, mapped to the state we
     * asked for. [4.1] never sends a RESULT and [4.2]'s link takes a moment to
     * drop, so "done" means the device reporting the target state in [4.4].
     */
    val pendingDevices: Map<String, Boolean> = emptyMap(),
    val lastError: String? = null,
) {
    val starredModes: List<ModeConfig>
        get() = modes.filter { favorites?.starred?.contains(it.modeIdx) == true && !it.isFreeSlot }
    val currentMode: ModeConfig?
        get() = modes.firstOrNull { it.modeIdx == currentModeIdx }
    /**
     * Connected devices, this one first, then whichever is playing.
     * [4.4]'s own order is not stable between reads, so imposing one here stops
     * rows from swapping around underneath the user.
     */
    val connectedDevices: List<PairedDevice>
        get() = pairedDevices.filter { it.connected }.sortedWith(
            compareByDescending<PairedDevice> { it.isLocalDevice }
                .thenByDescending { it.mac == activeSourceMac }
        )

    /** This phone's entry in the earbuds' device list — `isLocalDevice` in [4.5]. */
    private val localDevice: PairedDevice?
        get() = pairedDevices.firstOrNull { it.isLocalDevice }

    /**
     * Is this phone the device the earbuds are actually playing, per [5.1]?
     *
     
