package eu.depau.bosectl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import eu.depau.bosectl.bmap.BmapTransport
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    // デバイスごとの個別キャッシュキー生成ヘルパー
    private fun devStrKey(mac: String, suffix: String) =
        stringPreferencesKey("dev_${mac.uppercase()}_$suffix")
    private fun devIntKey(mac: String, suffix: String) =
        intPreferencesKey("dev_${mac.uppercase()}_$suffix")
    private fun devBoolKey(mac: String, suffix: String) =
        booleanPreferencesKey("dev_${mac.uppercase()}_$suffix")

    suspend fun savedDeviceMac(): String? =
        context.dataStore.data.first()[Prefs.DEVICE_MAC]

    suspend fun setDevice(mac: String, fallbackName: String? = null) {
        _state.value = BoseState()
        context.dataStore.edit { prefs ->
            prefs[Prefs.DEVICE_MAC] = mac
            prefs[Prefs.CACHE_CONNECTED] = false
            prefs[Prefs.CACHE_PLAYING_HERE] = false
            prefs.remove(Prefs.CACHE_LINK)
            prefs.remove(Prefs.LAST_SEEN_AT)
            prefs.remove(Prefs.LAST_SEEN_AVAILABLE)

            // 保存済みのデバイス別キャッシュがあれば復元する
            val savedName = prefs[devStrKey(mac, "name")] ?: fallbackName
            if (savedName != null) prefs[Prefs.CACHE_DEVICE_NAME] = savedName
            else prefs.remove(Prefs.CACHE_DEVICE_NAME)

            val savedStarred = prefs[devStrKey(mac, "starred")]
            if (savedStarred != null) prefs[Prefs.CACHE_STARRED] = savedStarred
            else prefs.remove(Prefs.CACHE_STARRED)

            val savedMode = prefs[devIntKey(mac, "mode")]
            if (savedMode != null) prefs[Prefs.CACHE_CURRENT_MODE] = savedMode
            else prefs.remove(Prefs.CACHE_CURRENT_MODE)

            val savedSpatial = prefs[devIntKey(mac, "spatial")]
            if (savedSpatial != null) prefs[Prefs.CACHE_SPATIAL] = savedSpatial
            else prefs.remove(Prefs.CACHE_SPATIAL)

            val savedAnc = prefs[devBoolKey(mac, "anc")]
            if (savedAnc != null) prefs[Prefs.CACHE_ANC] = savedAnc
            else prefs.remove(Prefs.CACHE_ANC)

            val savedCnc = prefs[devIntKey(mac, "cnc")]
            if (savedCnc != null) prefs[Prefs.CACHE_CNC] = savedCnc
            else prefs.remove(Prefs.CACHE_CNC)

            val savedTouch = prefs[devBoolKey(mac, "touch")]
            if (savedTouch != null) prefs[Prefs.CACHE_TOUCH] = savedTouch
            else prefs.remove(Prefs.CACHE_TOUCH)

            val savedBleId = prefs[devIntKey(mac, "ble_id")]
            if (savedBleId != null) prefs[Prefs.DEVICE_BLE_PRODUCT_ID] = savedBleId
            else prefs.remove(Prefs.DEVICE_BLE_PRODUCT_ID)

            // バッテリー残量は古いデバイスの値が残らないよう一旦クリア（接続後に最新値が取得される）
            prefs.remove(Prefs.CACHE_BAT_LEFT)
            prefs.remove(Prefs.CACHE_BAT_RIGHT)
            prefs.remove(Prefs.CACHE_BAT_CASE)
            prefs.remove(Prefs.CACHE_BAT_OVERALL)
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
                Log.i(TAG, "Connected to ${device.address} over $link")
                return transport to link
            } catch (e: Exception) {
                Log.w(TAG, "$link transport failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: BmapException("No usable link to ${device.address}")
    }

    suspend fun ensureConnected(automatic: Boolean = false): BmapConnection =
        connectMutex.withLock {
            connection?.takeIf { it.isConnected }?.let { return it }
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
                notificationsActive = runCatching { conn.enableNotifications() }
                    .onSuccess { Log.i(TAG, "Notifications enabled for blocks $it") }
                    .onFailure { Log.w(TAG, "Notification subscribe failed", it) }
                    .getOrDefault(emptyList())
                    .isNotEmpty()
                _state.value = _state.value.copy(connected = true, busy = false, link = link)
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

    fun runAsync(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "Device action failed", it) }
        }
    }

    fun onDeviceAppeared(automatic: Boolean = false) = runAsync {
        ensureConnected(automatic)
        refresh()
    }

    fun onDeviceDisappeared() = disconnect()

    fun autoConnectIfAvailable() = runAsync {
        if (state.value.connected) {
            refresh()
        } else if (isAclUp() || isNearby()) {
            ensureConnected(automatic = true)
            refresh()
        }
    }

    private suspend fun autoConnectOnSighting() {
        if (state.value.connected || state.value.busy) return
        if (connection?.isConnected == true) return
        if (savedDeviceMac() == null) return
        if (linkOrder(linkPreference(), classicUsable(automatic = true), automatic = true)
                .isEmpty()
        ) return
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAt < AUTO_CONNECT_RETRY_MS) return
        lastAutoConnectAt = now
        withTimeoutOrNull(AUTO_CONNECT_BUDGET_MS) {
            runCatching {
                ensureConnected(automatic = true)
                refresh()
            }.onFailure { Log.d(TAG, "Auto-connect on sighting failed: ${it.message}") }
        }
    }

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

    private suspend fun isNearby(): Boolean {
        if (linkPreference() == LinkPreference.CLASSIC_ONLY) return false
        val seen = context.dataStore.data.first()[Prefs.LAST_SEEN_AT] ?: return false
        return System.currentTimeMillis() - seen < PRESENCE_FRESH_MS
    }

    fun onAdvertisementsSeen(
        sightings: List<Pair<String, BoseAdvertisement>>,
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            try {
                val saved = savedDeviceMac() ?: return@launch
                val prefs = context.dataStore.data.first()
                val knownProduct = prefs[Prefs.DEVICE_BLE_PRODUCT_ID]

                val exact = sightings.firstOrNull { it.first.equals(saved, ignoreCase = true) }
                val match = exact
                    ?: sightings.firstOrNull { it.second.bleProductId == knownProduct }
                    ?: return@launch

                context.dataStore.edit {
                    it[Prefs.LAST_SEEN_AT] = System.currentTimeMillis()
                    it[Prefs.LAST_SEEN_AVAILABLE] = match.second.availableToConnect
                    if (exact != null) {
                        it[Prefs.DEVICE_BLE_PRODUCT_ID] = exact.second.bleProductId
                        it[devIntKey(saved, "ble_id")] = exact.second.bleProductId
                    }
                }
                _state.value = _state.value.copy(
                    lastSeenAt = System.currentTimeMillis(),
                    lastSeenAvailable = match.second.availableToConnect,
                )
                autoConnectOnSighting()
            } finally {
                onDone()
            }
        }
    }

    fun startPresenceScanIfEnabled(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                if (context.dataStore.data.first()[Prefs.PRESENCE_ENABLED] == true) {
                    PresenceScanner.start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not re-arm the nearby scan", e)
            } finally {
                onDone()
            }
        }
    }

    fun restorePresence() = runAsync {
        val prefs = context.dataStore.data.first()
        _state.value = _state.value.copy(
            lastSeenAt = prefs[Prefs.LAST_SEEN_AT],
            lastSeenAvailable = prefs[Prefs.LAST_SEEN_AVAILABLE] ?: false,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun classicUsable(automatic: Boolean): Boolean {
        if (!isAclUp()) return false
        if (!automatic) return true
        val mac = savedDeviceMac() ?: return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        if (adapter.getRemoteDevice(mac).bondState != BluetoothDevice.BOND_BONDED) return false
        return adapter.getProfileConnectionState(BluetoothProfile.A2DP) ==
                BluetoothProfile.STATE_CONNECTED
    }

    @SuppressLint("MissingPermission")
    private suspend fun isAclUp(): Boolean {
        val mac = savedDeviceMac() ?: return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        val device = adapter.getRemoteDevice(mac)
        return runCatching {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        }.getOrElse {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(if (visibleScreens > 0) POLL_FOREGROUND_MS else POLL_BACKGROUND_MS)
                if (!state.value.connected) continue
                runCatching { refreshLive() }
                    .onFailure { Log.d(TAG, "Poll failed: ${it.message}") }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun onScreenVisible() {
        visibleScreens++
        autoConnectIfAvailable()
    }

    fun onScreenHidden() {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
    }

    private suspend fun refreshLive() {
        val conn = connection?.takeIf { it.isConnected } ?: return
        val mode = conn.currentModeIdx()
        val settings = conn.audioSettings()
        if (mode == _state.value.currentModeIdx && settings == _state.value.audioSettings) return
        _state.value = _state.value.copy(
            currentModeIdx = mode ?: _state.value.currentModeIdx,
            audioSettings = settings ?: _state.value.audioSettings,
        )
        persistWidgetCache()
    }

    suspend fun refresh() {
        val conn = ensureConnected()
        try {
            val snapshot = conn.snapshot()
            val battery = runCatching { conn.battery() }.getOrNull()
            val touch = runCatching { conn.touchControls() }.getOrNull()
            val name = runCatching { conn.productName() }.getOrNull()
            val firmware = _state.value.firmware
                ?: runCatching { conn.firmware() }.getOrNull()
            _state.value = _state.value.copy(
                connected = true,
                deviceName = name ?: _state.value.deviceName,
                firmware = firmware,
                battery = battery ?: _state.value.battery,
                modes = snapshot.modes,
                currentModeIdx = snapshot.currentModeIdx,
                favorites = snapshot.favorites,
                audioSettings = snapshot.audioSettings,
                touchControls = touch ?: _state.value.touchControls,
                lastError = null,
            )
            runCatching { loadDevices(conn) }
                .onFailure { Log.d(TAG, "Device list refresh failed: ${it.message}") }
        } catch (e: Exception) {
            onIoFailure(e)
            throw e
        }
        persistWidgetCache()
    }

    private fun mergeDeviceList(entries: List<Pair<String, Boolean>>) {
        val merged = mergePairedDevices(_state.value.pairedDevices, entries)
        val stillPending = _state.value.pendingDevices.filterNot { (mac, target) ->
            merged.any { it.mac == mac && it.connected == target }
        }
        _state.value = _state.value.copy(pairedDevices = merged, pendingDevices = stillPending)
    }

    private fun markPending(mac: String, target: Boolean) {
        _state.value = _state.value.copy(
            pendingDevices = _state.value.pendingDevices + (mac to target)
        )
        scope.launch {
            delay(DEVICE_ACTION_TIMEOUT_MS)
            clearPending(mac)
        }
    }

    private fun clearPending(mac: String) {
        if (mac !in _state.value.pendingDevices) return
        _state.value = _state.value.copy(
            pendingDevices = _state.value.pendingDevices - mac
        )
    }

    private suspend fun fillDeviceNames(conn: BmapConnection) {
        val missing = _state.value.pairedDevices
            .filter { it.name.isEmpty() }
            .sortedByDescending { it.connected }
        for (target in missing) {
            val info = runCatching { conn.deviceInfo(target.mac) }.getOrNull() ?: continue
            _state.value = _state.value.copy(
                pairedDevices = _state.value.pairedDevices.map { device ->
                    if (device.mac != info.mac) device
                    else device.copy(
                        name = info.name, isLocalDevice = info.isLocalDevice,
                        isBoseProduct = info.isBoseProduct,
                    )
                }
            )
        }
    }

    private suspend fun loadDevices(conn: BmapConnection) {
        mergeDeviceList(conn.deviceList())
        runCatching { conn.activeSource() }.getOrNull()?.let {
            _state.value = _state.value.copy(activeSourceMac = it)
        }
        fillDeviceNames(conn)
    }

    suspend fun refreshDevices() = action { loadDevices(it) }

    suspend fun disconnectSource(mac: String) = action { conn ->
        markPending(mac, target = false)
        try {
            conn.disconnectDevice(mac)
        } catch (e: Exception) {
            clearPending(mac)
            throw e
        }
        loadDevices(conn)
    }

    suspend fun connectSource(mac: String) = action { conn ->
        markPending(mac, target = true)
        try {
            conn.connectDevice(mac)
        } catch (e: Exception) {
            clearPending(mac)
            throw e
        }
    }

    suspend fun forgetSource(mac: String) = action { conn ->
        conn.forgetDevice(mac)
        loadDevices(conn)
    }

    private suspend fun onUnsolicited(packet: eu.depau.bosectl.bmap.BmapPacket) {
        Log.d(TAG, "PUSH $packet")
        if (packet.op != Op.STATUS) return
        val s = _state.value
        when {
            packet.matches(BmapConnection.Addr.CURRENT_MODE) && packet.payload.isNotEmpty() ->
                _state.value = s.copy(currentModeIdx = packet.payload[0].toInt() and 0xFF)
            packet.matches(BmapConnection.Addr.AUDIO_SETTINGS) ->
                parseAudioSettings(packet.payload)?.let { _state.value = s.copy(audioSettings = it) }
            packet.matches(BmapConnection.Addr.FAVORITES) ->
                parseFavorites(packet.payload)?.let { _state.value = s.copy(favorites = it) }
            packet.matches(BmapConnection.Addr.MODE_CONFIG) ->
                parseModeConfig(packet.payload)?.let { cfg ->
                    _state.value = s.copy(modes = s.modes.map { if (it.modeIdx == cfg.modeIdx) cfg else it })
                }
            packet.matches(BmapConnection.Addr.BATTERY) ->
                _state.value = s.copy(battery = parseBattery(packet.payload))
            packet.matches(BmapConnection.Addr.DEV_LIST) -> {
                mergeDeviceList(parseDeviceList(packet.payload))
                if (_state.value.pairedDevices.any { it.name.isEmpty() }) {
                    runAsync { connection?.let { fillDeviceNames(it) } }
                }
            }
            packet.matches(BmapConnection.Addr.ACTIVE_SOURCE) ->
                parseActiveSource(packet.payload)?.let {
                    _state.value = s.copy(activeSourceMac = it)
                }
            else -> return
        }
        persistWidgetCache()
    }

    private fun onIoFailure(e: Exception) {
        Log.w(TAG, "Device operation failed", e)
        if (connection?.isConnected != true) {
            disconnect()
        }
        _state.value = _state.value.copy(lastError = e.message)
    }

    private suspend fun <T> action(block: suspend (BmapConnection) -> T): T {
        val conn = ensureConnected()
        return try {
            block(conn)
        } catch (e: Exception) {
            onIoFailure(e)
            throw e
        } finally {
            persistWidgetCache()
        }
    }

    suspend fun <T> withDevice(block: suspend (BmapConnection) -> T): T = action(block)

    suspend fun setMode(idx: Int) = action { conn ->
        conn.setMode(idx)
        _state.value = _state.value.copy(currentModeIdx = idx)
        runCatching { conn.audioSettings() }.getOrNull()?.let {
            _state.value = _state.value.copy(audioSettings = it)
        }
    }

    suspend fun setSpatial(spatial: Spatial) = updateAudioSettings { it.copy(spatial = spatial) }

    suspend fun setCnc(level: Int) = updateAudioSettings { it.copy(cncLevel = level) }

    suspend fun setAnc(enabled: Boolean) = updateAudioSettings { it.copy(ancToggle = enabled) }

    suspend fun adjustCnc(delta: Int) = updateAudioSettings {
        it.copy(cncLevel = (it.cncLevel - delta).coerceIn(0, 10))
    }

    private suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings) =
        action { conn ->
            val current = _state.value.audioSettings ?: conn.audioSettings()
                ?: throw BmapException("Audio settings unavailable")
            val updated = transform(current)
            conn.setAudioSettings(updated)
            _state.value = _state.value.copy(audioSettings = updated.copy(autoCnc = false))
        }

    suspend fun setTouchControls(enabled: Boolean) = action { conn ->
        conn.setTouchControls(enabled)
        _state.value = _state.value.copy(touchControls = enabled)
    }

    suspend fun toggleStar(modeIdx: Int) = action { conn ->
        val favorites = _state.value.favorites ?: conn.favorites()
            ?: throw BmapException("Favorites unavailable")
        val starred = favorites.starred.toMutableSet()
        if (!starred.remove(modeIdx)) starred.add(modeIdx)
        val updated = favorites.copy(starred = starred)
        conn.setFavorites(updated)
        _state.value = _state.value.copy(favorites = updated)
    }

    suspend fun saveProfile(
        slot: Int, name: String, promptId: Int, cncLevel: Int, spatial: Spatial,
        favourite: Boolean = false,
    ) = action { conn ->
        conn.writeMode(slot, name, promptId, cncLevel, spatial)
        if (favourite) {
            val current = _state.value.favorites ?: conn.favorites()
            current?.let { conn.setFavorites(it.copy(starred = it.starred + slot)) }
        }
        refresh()
    }

    suspend fun deleteProfile(slot: Int) = action { conn ->
        conn.deleteMode(slot)
        _state.value.favorites?.let { fav ->
            if (slot in fav.starred) conn.setFavorites(fav.copy(starred = fav.starred - slot))
        }
        refresh()
    }

    // ── Widget cache ─────────────────────────────────────────────────────────

    private suspend fun persistWidgetCache() {
        val s = _state.value
        context.dataStore.edit { prefs ->
            val mac = prefs[Prefs.DEVICE_MAC]
            prefs[Prefs.CACHE_CONNECTED] = s.connected
            prefs[Prefs.CACHE_PLAYING_HERE] = s.playingHere
            s.link?.let { prefs[Prefs.CACHE_LINK] = it.id }

            s.deviceName?.let {
                prefs[Prefs.CACHE_DEVICE_NAME] = it
                if (mac != null) prefs[devStrKey(mac, "name")] = it
            }
            s.currentModeIdx?.let {
                prefs[Prefs.CACHE_CURRENT_MODE] = it
                if (mac != null) prefs[devIntKey(mac, "mode")] = it
            }
            s.audioSettings?.let {
                prefs[Prefs.CACHE_SPATIAL] = it.spatial.value
                prefs[Prefs.CACHE_ANC] = it.ancToggle
                prefs[Prefs.CACHE_CNC] = it.cncLevel
                if (mac != null) {
                    prefs[devIntKey(mac, "spatial")] = it.spatial.value
                    prefs[devBoolKey(mac, "anc")] = it.ancToggle
                    prefs[devIntKey(mac, "cnc")] = it.cncLevel
                }
            }
            s.touchControls?.let {
                prefs[Prefs.CACHE_TOUCH] = it
                if (mac != null) prefs[devBoolKey(mac, "touch")] = it
            }
            s.battery?.let { b ->
                b.left?.let { prefs[Prefs.CACHE_BAT_LEFT] = it }
                b.right?.let { prefs[Prefs.CACHE_BAT_RIGHT] = it }
                b.case?.let { prefs[Prefs.CACHE_BAT_CASE] = it }
                b.overall?.let { prefs[Prefs.CACHE_BAT_OVERALL] = it }
            }
            if (s.modes.isNotEmpty()) {
                val encoded = encodeCachedModes(
                    s.starredModes.map { CachedMode(it.modeIdx, it.promptId, it.name) }
                )
                prefs[Prefs.CACHE_STARRED] = encoded
                if (mac != null) prefs[devStrKey(mac, "starred")] = encoded
            }
        }
        runCatching { BoseWidget().updateAll(context) }
            .onFailure { Log.w(TAG, "Widget update failed", it) }
    }
}
