package com.example.parkincare.presentation

import com.example.parkincare.R

enum class SensorType(val key: String, val displayNameRes: Int, val showInGui: Boolean = true) {
    ACCELEROMETER("accelerometer", R.string.accelerometer),
    GYROSCOPE("gyroscope", R.string.gyroscope),
    HEART_RATE("heart_rate", R.string.heart_rate),
    BAROMETER("barometer", R.string.barometer),
    LIGHT("light", R.string.light),
    AUDIO("audio", R.string.audio)
}
