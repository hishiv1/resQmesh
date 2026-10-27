package com.example.resqmesh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {
    // Inserts the packet. If the msg_id already exists, it ignores it.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketCache)

    // Checks if a message exists in O(1) time. Returns true if found.
    @Query("SELECT EXISTS(SELECT 1 FROM packet_cache WHERE msg_id = :msgId LIMIT 1)")
    suspend fun doesPacketExist(msgId: String): Boolean

    // Retrieves all cached SOS packets ordered by timestamp for the UI to observe
    @Query("SELECT * FROM packet_cache ORDER BY timestamp DESC")
    fun getAllPackets(): Flow<List<PacketCache>>
}