package eu.depau.bosectl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.updateAll
import eu.depau.bosectl.bmap.AudioSettings
import eu.depau.bosectl.bmap.BatteryStatus
import eu.depau.bosectl.bmap.BmapConnection
import eu.depau.bosectl.bmap.BmapException
import eu.depau.bosectl.bmap.BmapTransport
import eu.depau.bosectl.bmap.BoseAdvertisement
import eu.depau.bosectl.bmap.Favorites
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.PairedDevice
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.bmap.bmapPacket
import eu.depau.bosectl.bmap.mergePairedDevices
import eu.depau.bosectl.bmap.parseActiveSource
import eu.depau.bosectl.bmap.parseAudioSettings
import eu.depau.bosectl.bmap.parseBattery
import eu.depau.bosectl.bmap.parseDeviceList
import eu.depau.bosectl.bmap.parseFavorites
import eu.depau.bosectl.bmap.parseModeConfig
import eu.depau.bosectl.widget.BoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

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
     * Distinct from "has an audio link": with multipoint the phone can be
     * connected while a laptop holds the audio (§14).
     */
    val playingHere: Boolean
        get() = activeSourceMac != null && activeSourceMac == localDevice?.mac
}

/**
 * Process-wide owner of the BMAP connection and device state. The UI, the
 * companion service and the widget actions all go through here so they share
 * one socket and one source of truth.
 */
@SuppressLint("StaticFieldLeak")  // holds the application context only
object DeviceRepository {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectMutex = Mutex()
    private var connection: BmapConnection? = null
    private var unsolicitedJob: Job? = null
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null
    private var keepaliveJob: Job? = null

    @Volatile
    private var lastAutoConnectAt = 0L

    @Volatile
    private var lastConnectFailedAt = 0L
    private var visibleScreens = 0
    private var notificationsActive = false

    private val _state = MutableStateFlow(BoseState())
    val state: StateFlow<BoseState> = _state.asStateFlow()

    fun init(appContext: Context) {
        if (!::context.isInitialized) context = appContext.applicationContext
    }

    suspend fun savedDeviceMac(): String? =
        context.dataStore.data.first()[Prefs.DEVICE_MAC]

    private fun deviceCacheKey(mac: String) =
        stringPreferencesKey("dev_cache_${mac.uppercase()}")

    private fun clearActiveWidgetCache(prefs: MutablePreferences) {
        prefs.remove(Prefs.DEVICE_BLE_PRODUCT_ID)
        prefs.remove(Prefs.LAST_SEEN_AT)
        prefs.remove(Prefs.LAST_SEEN_AVAILABLE)
        prefs.remove(Prefs.CACHE_DEVICE_NAME)
        prefs.remove(Prefs.CACHE_CONNECTED)
        prefs.remove(Prefs.CACHE_CURRENT_MODE)
        prefs.remove(Prefs.CACHE_SPATIAL)
        prefs.remove(Prefs.CACHE_BAT_LEFT)
        prefs.remove(Prefs.CACHE_BAT_RIGHT)
        prefs.remove(Prefs.CACHE_BAT_CASE)
        prefs.remove(Prefs.CACHE_BAT_OVERALL)
        prefs.remove(Prefs.CACHE_STARRED)
        prefs.remove(Prefs.CACHE_ANC)
        prefs.remove(Prefs.CACHE_CNC)
        prefs.remove(Prefs.CACHE_TOUCH)
        prefs.remove(Prefs.CACHE_LINK)
        prefs.remove(Prefs.CACHE_PLAYING_HERE)
    }

    private fun restoreDeviceCache(
        prefs: MutablePreferences,
        mac: String,
        fallbackName: String?,
    ) {
        prefs[Prefs.CACHE_CONNECTED] = false
        val raw = prefs[deviceCacheKey(mac)]
        if (raw == null) {
            if (fallbackName != null) prefs[Prefs.CACHE_DEVICE_NAME] = fallbackName
            return
        }
        runCatching {
            val o = JSONObject(raw)
            prefs[Prefs.CACHE_DEVICE_NAME] =
                o.optString("name").takeIf { it.isNotEmpty() } ?: (fallbackName ?: "Bose Control")
            if (o.has("mode")) prefs[Prefs.CACHE_CURRENT_MODE] = o.getInt("mode")
            if (o.has("spatial")) prefs[Prefs.CACHE_SPATIAL] = o.getInt("spatial")
            if (o.has("anc")) prefs[Prefs.CACHE_ANC] = o.getBoolean("anc")
            if (o.has("cnc")) prefs[Prefs.CACHE_CNC] = o.getInt("cnc")
            if (o.has("touch")) prefs[Prefs.CACHE_TOUCH] = o.getBoolean("touch")
            if (o.has("bat_l")) prefs[Prefs.CACHE_BAT_LEFT] = o.getInt("bat_l")
            if (o.has("bat_r")) prefs[Prefs.CACHE_BAT_RIGHT] = o.getInt("bat_r")
            if (o.has("bat_c")) prefs[Prefs.CACHE_BAT_CASE] = o.getInt("bat_c")
            if (o.has("bat_o")) prefs[Prefs.CACHE_BAT_OVERALL] = o.getInt("bat_o")
            o.optString("starred").takeIf { it.isNotEmpty() }?.let {
                prefs[Prefs.CACHE_STARRED] = it
            }
        }.onFailure {
            if (fallbackName != null) prefs[Prefs.CACHE_DEVICE_NAME] = fallbackName
        }
    }

    suspend fun setDevice(mac: String, fallbackName: String? = null) {
        _state.value = BoseState()
        context.dataStore.edit { prefs ->
            clearActiveWidgetCache(prefs)
            prefs[Prefs.DEVICE_MAC] = mac
            restoreDeviceCache(prefs, mac, fallbackName)
        }
        disconnect()
    }

    suspend fun clearDevice() {
        disconnect()
        context.dataStore.edit { it.clear() }
        _state.value = BoseState()
    }

    suspend fun linkPreference(): LinkPreference =
        LinkPreference.fromId(context.dataStore.data.first()[Prefs.LINK_LAYER])

    suspend fun setLinkPreference(preference: LinkPreference) {
        context.dataStore.edit { it[Prefs.LINK_LAYER] = preference.id }
        disconnect()
    }

    /**
     * Open the first transport that works. Both carry the same protocol, so the
     * only visible difference is [BoseState.link] — and that LE keeps working
     * when the earbuds are playing to another device.
     */
    private suspend fun openFirstWorkingTransport(
        device: BluetoothDevice,
        automatic: Boolean,
    ): Pair<BmapTransport, LinkLayer> {
        val order =
            linkOrder(linkPreference(), classicLinkUp = classicUsable(automatic), automatic)
        var lastError: Exception? = null
        for (link in order) {
            try {
                val transport = openTransport(link, context, device)
                Log.i(TAG, "Connected to ${device.address} over$link")
                return transport to link
            } catch (e: Exception) {
                Log.w(TAG, "$link transport failed:${e.message}")
                lastError = e
            }
        }
        throw lastError ?: BmapException("No usable link to ${device.address}")
    }

    /**
     * Connect if not already connected. Throws BmapException on failure.
     *
     * [automatic] must be true for anything the user did not explicitly ask for
     * — it forbids initiating a classic link. See [linkOrder].
     */
    suspend fun ensureConnected(automatic: Boolean = false): BmapConnection =
        connectMutex.withLock {
            connection?.takeIf { it.isConnected }?.let { return it }
            // Automatic triggers arrive in bursts (every ACL flap, every
            // advertisement); once a connect has failed, let the dust settle
            // instead of feeding the flap that caused the failure.
            if (automatic &&
                System.currentTimeMillis() - lastConnectFailedAt < CONNECT_FAILURE_COOLDOWN_MS
            ) {
                throw BmapException("Connect failed recently; backing off")
            }
            connection?.close()
            connection = null

            val mac = savedDeviceMac()
                ?: throw BmapException("No device selected")
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                ?: throw BmapException("Bluetooth unavailable")
            val device = adapter.getRemoteDevice(mac)

            _state.value = _state.value.copy(busy = true, lastError = null)
            try {
                val (transport, link) = openFirstWorkingTransport(device, automatic)
                val conn = BmapConnection(transport)
                connection = conn
                unsolicitedJob?.cancel()
                unsolicitedJob = scope.launch {
                    conn.unsolicited.collect { onUnsolicited(it) }
                }
                // Ask the device to push state changes; without this it stays silent
                // and we have to poll. Format comes from the official app's
                // NotificationByFblock [9.2].
                notificationsActive = runCatching { conn.enableNotifications() }
                    .onSuccess { Log.i(TAG, "Notifications enabled for blocks $it") }
                    .onFailure { Log.w(TAG, "Notification subscribe failed", it) }
                    .getOrDefault(emptyList())
                    .isNotEmpty()
                _state.value = _state.value.copy(connected = true, busy = false, link = link)
                // Push is the mechanism; polling only covers firmware that refuses
                // the subscription.
                if (!notificationsActive) startPolling()
                startLinkWatchdog()
                startKeepalive(link)
                lastConnectFailedAt = 0L
                conn
            } catch (e: Exception) {
                lastConnectFailedAt = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    connected = false, busy = false, lastError = e.message
                )
                persistWidgetCache()
                throw e
            }
        }

    fun disconnect() {
        stopPolling()
        stopLinkWatchdog()
        stopKeepalive()
        unsolicitedJob?.cancel()
        unsolicitedJob = null
        connection?.close()
        connection = null
        _state.value = _state.value.copy(connected = false, busy = false, link = null)
        scope.launch { persistWidgetCache() }
    }

    /**
     * Run device work on the repository scope so screen navigation can't
     * cancel it mid-request. Errors land in state.lastError.
     */
    fun runAsync(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "Device action failed", it) }
        }
    }

    /**
     * Connect because the device showed up or the user asked. [automatic] must
     * be true for the ACL-receiver path — nobody tapped anything there, so it
     * gets neither classic initiation nor a bypass of the failure cooldown.
     */
    fun onDeviceAppeared(automatic: Boolean = false) = runAsync {
        ensureConnected(automatic)
        refresh()
    }

    fun onDeviceDisappeared() = disconnect()

    /**
     * Connect + refresh only if the earbuds are actually reachable — app launch
     * must not spin on "connecting" when they're away.
     *
     * A classic link means RFCOMM is available. Failing that, a *recent BLE
     * sighting* means the LE transport is worth a try even with no classic link,
     * which is how the app can show state while the earbuds play to another
     * device. With neither, don't touch the radio.
     */
    fun autoConnectIfAvailable() = runAsync {
        if (state.value.connected) {
            refresh()
        } else if (isAclUp() || isNearby()) {
            ensureConnected(automatic = true)
            refresh()
        }
    }

    /**
     * A nearby sighting is only useful if something acts on it: connect over LE
     * so state and the widget go live while the earbuds play to another device.
     *
     * Rate-limited because advertisements arrive every couple of seconds, and
     * strictly [automatic] so it can never take the audio (see [linkOrder]).
     */
    private suspend fun autoConnectOnSighting() {
        if (state.value.connected || state.value.busy) return
        if (connection?.isConnected == true) return
        if (savedDeviceMac() == null) return
        // Nothing to try: the only permitted transport needs a link we don't have.
        if (linkOrder(linkPreference(), classicUsable(automatic = true), automatic = true)
                .isEmpty()
        ) return
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAt < AUTO_CONNECT_RETRY_MS) return
        lastAutoConnectAt = now
        // Bounded: this runs while a broadcast receiver is held open.
        withTimeoutOrNull(AUTO_CONNECT_BUDGET_MS) {
            runCatching {
                ensureConnected(automatic = true)
                refresh()
            }.onFailure { Log.d(TAG, "Auto-connect on sighting failed: ${it.message}") }
        }
    }

    /**
     * Nothing tells us when an LE link dies — there is no ACL broadcast for it,
     * and with pushes working there are no requests to fail either. Without this
     * the UI and the widget would keep claiming "connected" forever.
     */
    private fun startLinkWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (true) {
                delay(LINK_WATCHDOG_MS)
                if (connection?.isConnected == false) {
                    Log.i(TAG, "Link went away; clearing state")
                    disconnect()
                    return@launch
                }
            }
        }
    }

    private fun stopLinkWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * The earbuds hang up an idle LE link after ~40 s — `newState=0` with
     * `status=19`, `GATT_CONN_TERMINATE_PEER_USER`. Once `[9.2]` is subscribed we
     * have nothing to send, so a quiet link gets reaped and the widget goes stale.
     *
     * So poke it with the most inert GET there is: `[0.1]` BMAP version is a
     * constant string, reads nothing, and changes nothing.
     *
     * Classic needs none of this — that link is held up by the audio connection.
     */
    private fun startKeepalive(link: LinkLayer) {
        stopKeepalive()
        if (link != LinkLayer.LE) return
        keepaliveJob = scope.launch {
            while (true) {
                delay(LE_KEEPALIVE_MS)
                val conn = connection?.takeIf { it.isConnected } ?: return@launch
                runCatching { conn.raw(bmapPacket(0, 1, Op.GET)) }
                    .onFailure { Log.d(TAG, "Keepalive failed: ${it.message}") }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /** Was a matching advertisement seen recently enough to bother connecting? */
    private suspend fun isNearby(): Boolean {
        if (linkPreference() == LinkPreference.CLASSIC_ONLY) return false
        val seen = context.dataStore.data.first()[Prefs.LAST_SEEN_AT] ?: return false
        return System.currentTimeMillis() - seen < PRESENCE_FRESH_MS
    }

    /**
     * A batch of Bose advertisements from the system scan (see [PresenceScanner]).
     * Runs from a broadcast receiver, so [onDone] releases its wake lock.
     */
    fun onAdvertisementsSeen(
        sightings: List<Pair<String, BoseAdvertisement>>,
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            try {
                val saved = savedDeviceMac() ?: return@launch
                val prefs = context.dataStore.data.first()
                val knownProduct = prefs[Prefs.DEVICE_BLE_PRODUCT_ID]

                // An address match is proof. Otherwise fall back to "same model
                // as ours", which is all the advertisement offers: the rotating
                // address is Bose's own resolvable scheme, not a standard RPA we
                // could resolve with an IRK.
                // ponytail: another set of the same model in range would count as
                // ours. Harmless for a presence hint — it only ever causes an LE
                // connect attempt that then fails.
                val exact = sightings.firstOrNull { it.first.equals(saved, ignoreCase = true) }
                val match = exact
                    ?: sightings.firstOrNull { it.second.bleProductId == knownProduct }
                    ?: return@launch

                context.dataStore.edit {
                    it[Prefs.LAST_SEEN_AT] = System.currentTimeMillis()
                    it[Prefs.LAST_SEEN_AVAILABLE] = match.second
