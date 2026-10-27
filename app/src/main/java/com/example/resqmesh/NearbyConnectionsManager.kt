package com.example.resqmesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

class NearbyConnectionsManager(
    private val context: Context,
    private val repository: MeshRepository,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val TAG = "ResQMesh_Manager"
    private val SERVICE_ID = "com.example.resqmesh.SERVICE_ID"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val gson = Gson()

    val localEndpointName: String = "${android.os.Build.MODEL.take(8)}_${UUID.randomUUID().toString().take(4)}"

    private val _connectedPeersCount = MutableStateFlow(0)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val connectedEndpoints = mutableSetOf<String>()
    private val connectingEndpoints = mutableSetOf<String>()

    var onPeerConnectedListener: ((endpointId: String) -> Unit)? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with ${info.endpointName} ($endpointId). Auto-accepting.")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to accept connection from $endpointId: ${e.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            synchronized(connectingEndpoints) {
                connectingEndpoints.remove(endpointId)
            }

            if (result.status.isSuccess) {
                Log.d(TAG, "Successfully connected to $endpointId")
                synchronized(connectedEndpoints) {
                    connectedEndpoints.add(endpointId)
                }
                updatePeersCount()
                onPeerConnectedListener?.invoke(endpointId)
            } else {
                Log.w(TAG, "Connection failed with $endpointId: status=${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            synchronized(connectedEndpoints) {
                connectedEndpoints.remove(endpointId)
            }
            synchronized(connectingEndpoints) {
                connectingEndpoints.remove(endpointId)
            }
            updatePeersCount()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint discovered: ${info.endpointName} ($endpointId)")

            synchronized(connectedEndpoints) {
                if (connectedEndpoints.contains(endpointId)) return
            }
            synchronized(connectingEndpoints) {
                if (connectingEndpoints.contains(endpointId)) return
            }

            // Tie-breaking logic to prevent race-condition connection collisions when both devices discover each other:
            // Device with lexicographically higher name initiates immediately.
            // Device with lower name waits 1.5s as fallback in case the other didn't initiate.
            if (localEndpointName > info.endpointName) {
                initiateConnection(endpointId)
            } else {
                externalScope.launch {
                    delay(1500)
                    var alreadyConnectedOrConnecting: Boolean
                    synchronized(connectedEndpoints) {
                        alreadyConnectedOrConnecting = connectedEndpoints.contains(endpointId)
                    }
                    if (!alreadyConnectedOrConnecting) {
                        synchronized(connectingEndpoints) {
                            alreadyConnectedOrConnecting = connectingEndpoints.contains(endpointId)
                        }
                    }
                    if (!alreadyConnectedOrConnecting) {
                        Log.d(TAG, "Fallback initiating connection to $endpointId")
                        initiateConnection(endpointId)
                    }
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            synchronized(connectingEndpoints) {
                connectingEndpoints.remove(endpointId)
            }
        }
    }

    private fun initiateConnection(endpointId: String) {
        synchronized(connectingEndpoints) {
            if (connectingEndpoints.contains(endpointId)) return
            connectingEndpoints.add(endpointId)
        }

        Log.d(TAG, "Requesting connection to $endpointId as $localEndpointName")
        connectionsClient.requestConnection(
            localEndpointName,
            endpointId,
            connectionLifecycleCallback
        ).addOnFailureListener { e ->
            Log.w(TAG, "Failed to request connection to $endpointId: ${e.message}")
            synchronized(connectingEndpoints) {
                connectingEndpoints.remove(endpointId)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val jsonString = String(bytes, StandardCharsets.UTF_8)
                externalScope.launch {
                    try {
                        val packet = gson.fromJson(jsonString, PacketCache::class.java)
                        // Check deduplication & persist
                        val isNew = repository.handleIncomingPacket(packet)
                        if (isNew) {
                            Log.d(TAG, "New SOS packet received & saved: ${packet.msg_id}")
                            // Mesh Relay (Flooding): If TTL > 1, decrement TTL and forward to other connected peers
                            if (packet.ttl > 1) {
                                val relaidPacket = packet.copy(ttl = packet.ttl - 1)
                                broadcastPacket(relaidPacket, excludeEndpoint = endpointId)
                            }
                        } else {
                            Log.d(TAG, "Duplicate SOS packet ignored: ${packet.msg_id}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing received payload", e)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Optional transfer progress
        }
    }

    fun startAdvertising(deviceName: String = localEndpointName) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Started advertising successfully as $deviceName.")
            _isAdvertising.value = true
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start advertising", e)
            _isAdvertising.value = false
        }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Started discovery successfully.")
            _isDiscovering.value = true
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start discovery", e)
            _isDiscovering.value = false
        }
    }

    fun stopAdvertisingOnly() {
        connectionsClient.stopAdvertising()
        _isAdvertising.value = false
        Log.d(TAG, "Stopped advertising only. Discovery and connections remain active.")
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        synchronized(connectedEndpoints) {
            connectedEndpoints.clear()
        }
        synchronized(connectingEndpoints) {
            connectingEndpoints.clear()
        }
        _isAdvertising.value = false
        _isDiscovering.value = false
        updatePeersCount()
        Log.d(TAG, "Stopped all advertising, discovery, and connections.")
    }

    /**
     * Saves packet locally and broadcasts it to all currently connected peers.
     */
    fun sendPacket(packet: PacketCache) {
        externalScope.launch {
            repository.insertPacket(packet)
            broadcastPacket(packet)
        }
    }

    /**
     * Broadcasts an active SOS packet to all peers (for periodic re-broadcasting).
     */
    fun broadcastActivePacket(packet: PacketCache) {
        broadcastPacket(packet)
    }

    /**
     * Sends packet directly to a newly connected endpoint.
     */
    fun sendPacketToEndpoint(endpointId: String, packet: PacketCache) {
        try {
            val jsonString = gson.toJson(packet)
            val payload = Payload.fromBytes(jsonString.toByteArray(StandardCharsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send packet to $endpointId", e)
                }
            Log.d(TAG, "Direct-sent packet ${packet.msg_id} to $endpointId")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet to $endpointId", e)
        }
    }

    private fun broadcastPacket(packet: PacketCache, excludeEndpoint: String? = null) {
        try {
            val jsonString = gson.toJson(packet)
            val payload = Payload.fromBytes(jsonString.toByteArray(StandardCharsets.UTF_8))

            val targets = mutableListOf<String>()
            synchronized(connectedEndpoints) {
                targets.addAll(connectedEndpoints)
            }

            for (endpointId in targets) {
                if (endpointId != excludeEndpoint) {
                    connectionsClient.sendPayload(endpointId, payload)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send payload to $endpointId", e)
                        }
                }
            }
            Log.d(TAG, "Broadcasted packet ${packet.msg_id} to ${targets.size} peers.")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting packet", e)
        }
    }

    private fun updatePeersCount() {
        synchronized(connectedEndpoints) {
            _connectedPeersCount.value = connectedEndpoints.size
        }
    }
}
