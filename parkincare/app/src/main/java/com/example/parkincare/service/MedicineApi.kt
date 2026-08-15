package com.example.parkincare.service

import com.example.parkincare.model.MedicineHistory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

interface MedicineApi {
    @GET
    suspend fun getReminders(
        @Url url: String,
        @Header("X-Authorization") token: String
    ): List<MedicineHistory>

    @PUT("/api/medicinehistory")
    suspend fun sendMedicineHistory(
        @Header("X-Authorization") token: String,
        @Body body: MedicineHistory
    ): retrofit2.Response<Unit>

}