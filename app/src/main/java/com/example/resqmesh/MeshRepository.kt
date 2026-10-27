package com.example.resqmesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MeshRepository(private val packetDao: PacketDao) {

    val allPackets: Flow<List<PacketCache>> = packetDao.getAllPackets()

    private val _incomingPacketFlow = MutableSharedFlow<PacketCache>(extraBufferCapacity = 64)
    val incomingPacketFlow: SharedFlow<PacketCache> = _incomingPacketFlow.asSharedFlow()

    suspend fun insertPacket(packet: PacketCache) {
        packetDao.insertPacket(packet)
    }

    suspend fun doesPacketExist(msgId: String): Boolean {
        return packetDao.doesPacketExist(msgId)
    }

    suspend fun handleIncomingPacket(packet: PacketCache): Boolean {
        val exists = packetDao.doesPacketExist(packet.msg_id)
        if (!exists) {
            packetDao.insertPacket(packet)
            _incomingPacketFlow.emit(packet)
            return true
        }
        return false
    }
}
