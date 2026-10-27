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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class NearbyConnectionsManager(
    private val context: Context,
    private val repository: MeshRepository,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val TAG = "ResQMesh_Manager"
    private val SERVICE_ID = "com.example.resqmesh.SERVICE_ID"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val gson = Gson()

    private val _connectedPeersCount = MutableStateFlow(0)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val connectedEndpoints = mutableSetOf<String>()

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with ${info.endpointName} ($endpointId). Auto-accepting.")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(TAG, "Successfully connected to $endpointId")
                synchronized(connectedEndpoints) {
                    connectedEndpoints.add(endpointId)
                }
                updatePeersCount()
            } else {
                Log.d(TAG, "Connection failed with $endpointId: ${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            synchronized(connectedEndpoints) {
                connectedEndpoints.remove(endpointId)
            }
            updatePeersCount()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: ${info.endpointName} ($endpointId). Requesting connection.")
            connectionsClient.requestConnection(
                android.os.Build.MODEL,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                Log.e(TAG, "Failed to request connection to $endpointId", e)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val jsonString = String(bytes, StandardCharsets.UTF_8)
                externalScope.launch {
                    try {
                        val packet = gson.fromJson(jsonString, PacketCache::class.java)
                        // Handle incoming packet through repository (checks existence, saves, and emits alert if new)
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
            // Optional: track transfer progress if needed
        }
    }

    fun startAdvertising(deviceName: String = android.os.Build.MODEL) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Started advertising successfully.")
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

    /**
     * Stops advertising only, keeping discovery and existing connections alive.
     * Used when canceling a broadcast so the device returns to Passive Discovering
     * Mode (Mule Mode) per Algorithm 1, rather than going fully silent.
     */
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
        _isAdvertising.value = false
        _isDiscovering.value = false
        updatePeersCount()
        Log.d(TAG, "Stopped all advertising, discovery, and connections.")
    }

    fun sendPacket(packet: PacketCache) {
        externalScope.launch {
            // Save locally first
            repository.insertPacket(packet)
            // Broadcast to mesh
            broadcastPacket(packet)
        }
    }

    private fun broadcastPacket(packet: PacketCache, excludeEndpoint: String? = null) {
        try {
            val jsonString = gson.toJson(packet)
            val payload = Payload.fromBytes(jsonString.toByteArray(StandardCharsets.UTF_8))

            synchronized(connectedEndpoints) {
                for (endpointId in connectedEndpoints) {
                    if (endpointId != excludeEndpoint) {
                        connectionsClient.sendPayload(endpointId, payload)
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to send payload to $endpointId", e)
                            }
                    }
                }
            }
            Log.d(TAG, "Broadcasted packet ${packet.msg_id} to mesh.")
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
