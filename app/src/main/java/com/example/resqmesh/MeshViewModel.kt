package com.example.resqmesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
    private val alertManager = SosAlertManager(application)

    private var activeSosPacket: PacketCache? = null
    private var broadcastJob: Job? = null

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
        // Collect incoming emergency packets from repository
        // When received, switch screen to RECEIVER_ALERT and trigger alarm sound, vibration & notification
        viewModelScope.launch {
            repository.incomingPacketFlow.collect { packet ->
                _incomingAlertPacket.value = packet
                _currentScreen.value = AppScreen.RECEIVER_ALERT
                alertManager.triggerAlert(packet)
            }
        }

        // When a new peer connects, if we are actively broadcasting an SOS, immediately push it to them
        nearbyManager.onPeerConnectedListener = { endpointId ->
            activeSosPacket?.let { packet ->
                nearbyManager.sendPacketToEndpoint(endpointId, packet)
            }
        }
    }

    /**
     * Called after permissions have been confirmed.
     * Starts advertising and discovering for Nearby Connections BLE mesh.
     */
    fun onPermissionsGranted() {
        if (_permissionsGranted.value) return
        _permissionsGranted.value = true
        startMesh()
        refreshGpsLocation()
    }

    private fun startMesh() {
        nearbyManager.startAdvertising()
        nearbyManager.startDiscovery()
    }

    /**
     * Fetches current GPS coordinates and updates the GPS badge state.
     */
    fun refreshGpsLocation() {
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
     * Dismisses the Receiver Alert screen, stops the alarm sound and vibration, and returns to Home.
     */
    fun clearAlertPacket() {
        alertManager.cancelAlert()
        _incomingAlertPacket.value = null
        _currentScreen.value = AppScreen.HOME
    }

    /**
     * Triggered when user presses SOS button.
     * Fetches fresh GPS coordinates, creates packet, switches to BROADCASTING screen,
     * immediately broadcasts the packet, and initiates continuous re-broadcasting every 3 seconds.
     */
    fun triggerSos(triageType: Int) {
        viewModelScope.launch {
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
                ttl = 5,
                triage_type = triageType
            )
            activeSosPacket = packet
            _currentScreen.value = AppScreen.BROADCASTING

            // Immediate broadcast
            nearbyManager.sendPacket(packet)

            // Continuous re-broadcast loop while BROADCASTING screen is active
            broadcastJob?.cancel()
            broadcastJob = viewModelScope.launch {
                while (isActive && _currentScreen.value == AppScreen.BROADCASTING) {
                    delay(3000)
                    if (_currentScreen.value == AppScreen.BROADCASTING) {
                        activeSosPacket?.let { nearbyManager.broadcastActivePacket(it) }
                    }
                }
            }
        }
    }

    /**
     * Cancels the SOS broadcast and returns to Home screen.
     */
    fun cancelBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = null
        activeSosPacket = null
        _currentScreen.value = AppScreen.HOME
    }

    override fun onCleared() {
        super.onCleared()
        alertManager.cancelAlert()
        broadcastJob?.cancel()
        nearbyManager.stopAll()
    }
}
