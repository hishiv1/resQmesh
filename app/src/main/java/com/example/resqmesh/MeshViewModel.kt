package com.example.resqmesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppScreen {
    HOME, BROADCASTING, RECEIVER_ALERT
}

enum class GpsStatus {
    UNAVAILABLE, ACQUIRING, LOCKED
}

data class GpsState(
    val status: GpsStatus = GpsStatus.UNAVAILABLE,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = MeshRepository(database.packetDao())
    private val nearbyManager = NearbyConnectionsManager(application, repository, viewModelScope)
    private val locationTracker = LocationTracker(application)

    val packets: StateFlow<List<PacketCache>> = repository.allPackets
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _incomingAlertPacket = MutableStateFlow<PacketCache?>(null)
    val incomingAlertPacket: StateFlow<PacketCache?> = _incomingAlertPacket.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _gpsState = MutableStateFlow(GpsState())
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    val connectedPeersCount: StateFlow<Int> = nearbyManager.connectedPeersCount
    val isAdvertising: StateFlow<Boolean> = nearbyManager.isAdvertising
    val isDiscovering: StateFlow<Boolean> = nearbyManager.isDiscovering

    init {
        // IMPORTANT: Do NOT call startMesh() here.
        // At ViewModel construction time, runtime permissions (BT_SCAN, BT_ADVERTISE,
        // BT_CONNECT, ACCESS_FINE_LOCATION) have NOT been granted yet.
        // The Nearby Connections API will silently fail without them.
        // Mesh initialization is deferred to onPermissionsGranted(), which the UI layer
        // calls after the user has accepted the permission dialog.

        // Wire the reactive pipeline: collect incoming emergency packets from the repository
        // and update the UI state to show the Receiver Alert screen.
        viewModelScope.launch {
            repository.incomingPacketFlow.collect { packet ->
                _incomingAlertPacket.value = packet
                _currentScreen.value = AppScreen.RECEIVER_ALERT
            }
        }
    }

    /**
     * Called by the UI layer AFTER all runtime permissions (BT, Location) have been granted.
     * This is the ONLY safe point to initialize the Nearby Connections radios.
     * Idempotent — safe to call multiple times (e.g., on config change).
     */
    fun onPermissionsGranted() {
        if (_permissionsGranted.value) return
        _permissionsGranted.value = true
        startMesh()
        refreshGpsLocation()
    }

    /**
     * Starts both advertising and discovery for P2P_CLUSTER mesh formation.
     * Per Algorithm 1: the device enters both advertising mode (for mesh topology)
     * and discovering mode (passive scanning / "Mule Mode").
     */
    private fun startMesh(deviceName: String = android.os.Build.MODEL) {
        nearbyManager.startAdvertising(deviceName)
        nearbyManager.startDiscovery()
    }

    /**
     * Fetches current GPS coordinates and updates the GPS badge state.
     * Retries once after 3 seconds if the first attempt returns no fix.
     */
    private fun refreshGpsLocation() {
        _gpsState.value = GpsState(status = GpsStatus.ACQUIRING)
        viewModelScope.launch {
            try {
                val (lat, lon) = locationTracker.getCurrentLocation()
                if (lat != 0.0 || lon != 0.0) {
                    _gpsState.value = GpsState(
                        status = GpsStatus.LOCKED,
                        latitude = lat,
                        longitude = lon
                    )
                } else {
                    // Retry once after a short delay for GPS cold-start
                    delay(3000)
                    val (lat2, lon2) = locationTracker.getCurrentLocation()
                    _gpsState.value = if (lat2 != 0.0 || lon2 != 0.0) {
                        GpsState(status = GpsStatus.LOCKED, latitude = lat2, longitude = lon2)
                    } else {
                        GpsState(status = GpsStatus.ACQUIRING)
                    }
                }
            } catch (_: Exception) {
                _gpsState.value = GpsState(status = GpsStatus.UNAVAILABLE)
            }
        }
    }

    /**
     * Dismisses the Receiver Alert screen and returns to Home.
     */
    fun clearAlertPacket() {
        _incomingAlertPacket.value = null
        _currentScreen.value = AppScreen.HOME
    }

    /**
     * Triggered when the user presses the SOS button.
     * Fetches GPS internally (MVVM: the View should not handle raw location data),
     * creates the SOS packet, switches to Broadcasting screen,
     * and sends the packet into the mesh network.
     */
    fun triggerSos(triageType: Int) {
        viewModelScope.launch {
            // Refresh GPS to get latest coordinates for the SOS payload
            val (lat, lon) = locationTracker.getCurrentLocation()
            if (lat != 0.0 || lon != 0.0) {
                _gpsState.value = GpsState(
                    status = GpsStatus.LOCKED,
                    latitude = lat,
                    longitude = lon
                )
            }

            val packet = PacketCache(
                msg_id = UUID.randomUUID().toString(),
                origin_lat = lat,
                origin_lon = lon,
                timestamp = System.currentTimeMillis(),
                ttl = 5, // Default time-to-live hops for mesh flood relay
                triage_type = triageType
            )
            _currentScreen.value = AppScreen.BROADCASTING
            nearbyManager.sendPacket(packet)
        }
    }

    /**
     * Cancels the current SOS broadcast and returns to the Home screen.
     * Per Algorithm 1: the device returns to Passive Discovering Mode (Mule Mode).
     * The mesh stays alive — we do NOT tear down all connections. The SOS packet
     * was already sent as a one-shot broadcast; cancel is purely a UI state change.
     */
    fun cancelBroadcast() {
        _currentScreen.value = AppScreen.HOME
    }

    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopAll()
    }
}
