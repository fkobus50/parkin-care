// ...new file...
package com.example.parkincare.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "api_token_history",
    indices = [Index(value = ["api_token"], name = "idx_api_token_history_api_token"), Index(value = ["patient_id"], name = "idx_api_token_history_patient_id")]
)
data class ApiTokenHistory(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "api_token")
    val apiToken: String,

    @ColumnInfo(name = "patient_id")
    val patientId: String,

    @ColumnInfo(name = "fromTs")
    val fromTs: Long,

    @ColumnInfo(name = "toTs")
    val toTs: Long? = null
)

