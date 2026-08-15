package com.example.parkincare.model;

data class MedicineHistory(
        val type: String = "MedicineHistory",
        val id: String,
        val scheduledDate: String,
        val taken: Boolean,
        val shown: Boolean,
        val executionDate: String?,
        val notificationDate: String?,
        val dose: Double,
        val medicineId: String,
        val medicineName: String,
        val apiToken: String
)