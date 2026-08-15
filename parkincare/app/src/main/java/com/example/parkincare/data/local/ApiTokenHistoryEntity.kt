package com.example.parkincare.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "api_token_history")
data class ApiTokenHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "apiToken")
    val apiToken: String,

    @ColumnInfo(name = "patientId")
    val patientId: String?,

    @ColumnInfo(name = "watchId")
    val watchId: String?,

    @ColumnInfo(name = "startTs")
    val startTs: Long,

    @ColumnInfo(name = "endTs")
    val endTs: Long?
)

