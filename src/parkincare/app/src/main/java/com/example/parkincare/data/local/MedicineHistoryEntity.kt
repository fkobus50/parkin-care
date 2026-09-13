package com.example.parkincare.data.local

import androidx.room.*

@Entity(tableName = "medicine_history")
data class MedicineHistoryEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val scheduledDate: String,
    val executionDate: String,
    val notificationDate: String?,
    val dose: Double,
    val medicineId: String,
    val medicineName: String,
    val taken: Boolean,
    val shown: Boolean,
    val type: String,
    val sent: Boolean = false
)
