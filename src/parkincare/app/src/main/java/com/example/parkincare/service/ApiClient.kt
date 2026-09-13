package com.example.parkincare.service

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.example.parkincare.BuildConfig


object ApiClient {
    // Ustaw baseUrl na podstawie sendHistoryUrl z local.properties
    private val BASE_URL = BuildConfig.sendHistoryUrl.substringBefore("/api/") + "/api/"

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(UnsafeOkHttpClient.getUnsafeOkHttpClient())
            .build()
    }

    val medicineApi: MedicineApi by lazy {
        retrofit.create(MedicineApi::class.java)
    }

    // Osobny Retrofit do pobierania notyfikacji (getReminderUrl)
    private val REMINDER_BASE_URL = BuildConfig.getReminderUrl.substringBefore("/api/") + "/api/"
    val reminderRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(REMINDER_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(UnsafeOkHttpClient.getUnsafeOkHttpClient())
            .build()
    }
    val reminderApi: MedicineApi by lazy {
        reminderRetrofit.create(MedicineApi::class.java)
    }
}
