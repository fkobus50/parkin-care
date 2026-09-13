package com.example.parkincare.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_pairing",
    indices = [
        Index(value = ["watchId"], name = "idx_watch_pairing_watchId"),
        Index(value = ["patientId"], name = "idx_watch_pairing_patientId"),
        Index(value = ["watchId", "fromTs"], name = "idx_watch_pairing_watchId_fromTs")
    ]
)
data class WatchPairingEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "watchId")
    val watchId: String?,

    @ColumnInfo(name = "patientId")
    val patientId: String,

    @ColumnInfo(name = "fromTs")
    val fromTs: Long,

    @ColumnInfo(name = "toTs")
    val toTs: Long?
)

