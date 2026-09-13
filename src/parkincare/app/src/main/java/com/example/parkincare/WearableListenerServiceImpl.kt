package com.example.parkincare

import android.content.Context
import com.example.parkincare.util.ParkinLogger as Log
import com.google.android.gms.wearable.*

class WearableListenerServiceImpl : WearableListenerService() {
    private val TAG = "WearableListenerSrv"

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "onMessageReceived: path=${messageEvent.path}, source=${messageEvent.sourceNodeId}")
        if (messageEvent.path == "/login_token") {
            val token = String(messageEvent.data)
            Log.i(TAG, "Odebrano token (message): $token")
            saveToken(token)
        } else {
            Log.i(TAG, "Nieznana ścieżka (message): ${messageEvent.path}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    val uri = event.dataItem.uri
                    val path = uri.path
                    Log.i(TAG, "onDataChanged: path=$path, uri=$uri")
                    if (path == "/login_token") {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val token = dataMap.getString("token")
                        if (token != null) {
                            Log.i(TAG, "Odebrano token (data): $token")
                            saveToken(token)
                        } else {
                            Log.i(TAG, "Brak tokenu w DataItem")
                        }
                    }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun saveToken(token: String) {
        val prefs = applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("login_token", token).apply()
        Log.i(TAG, "Zapisano token w SharedPreferences")
    }
}
