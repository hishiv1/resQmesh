package com.example.resqmesh

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "packet_cache")
data class PacketCache(
    @PrimaryKey val msg_id: String,
    val origin_lat: Double,
    val origin_lon: Double,
    val timestamp: Long,
    val ttl: Int,
    val triage_type: Int
)