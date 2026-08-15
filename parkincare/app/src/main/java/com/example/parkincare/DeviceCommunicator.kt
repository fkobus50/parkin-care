package com.example.parkincare

import android.content.Context
import com.example.parkincare.util.ParkinLogger as Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*

class DeviceCommunicator private constructor(val context: Context) : MessageClient.OnMessageReceivedListener {
    private var heartbeatJob: Job? = null
    private var myNodeId: String? = null
    init {
        val appContext = context.applicationContext
        Log.i("DeviceCommunicator", "[INIT] Rejestruję listener na wiadomości (context: ${appContext.packageName})")
        Wearable.getMessageClient(appContext).addListener(this)
        Wearable.getNodeClient(appContext).localNode
            .addOnSuccessListener { node ->
                myNodeId = node.id
                Log.i("DeviceCommunicator", "[NODE] Mój nodeId: ${node.id}")
            }
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Log.i("DeviceCommunicator", "Sparowano z urządzeniem: ${node.displayName} (ID: ${node.id})")
                }
            }
        isActive = true
        heartbeatJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                Log.i("DeviceCommunicator", "[HEARTBEAT] DeviceCommunicator jest aktywny, myNodeId: ${myNodeId ?: "nieznany"}")
                delay(15000)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        incrementMessageCount()
        val size = messageEvent.data?.size ?: 0
        Log.i("DeviceCommunicator", "[MSG] Odebrano dowolną wiadomość (licznik=${getMessageCount(context)}, path=${messageEvent.path}, bytes=$size)")
        Log.i("DeviceCommunicator", "Odebrano wiadomość: $messageEvent (path: ${messageEvent.path})")
        if (messageEvent.path == "/login_token") {
            val token = String(messageEvent.data)
            Log.i("DeviceCommunicator", "Odebrano token: $token")
            val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            if (token.isBlank()) {
                val fallback = com.example.parkincare.BuildConfig.PATIENT_ID
                prefs.edit().putString("PATIENT_ID", fallback).putString("login_token", null).apply()
                Log.w("DeviceCommunicator", "Pusty JWT — ustawiam PATIENT_ID fallback='${fallback}' i czyszczę login_token")
                enqueueImmediateFetch(context.applicationContext)
                return
            }
            saveToken(token)
            try {
                val pid = decodePatientIdFromJwt(token)
                val dprefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
                if (!pid.isNullOrBlank()) {
                    dprefs.edit().putString("PATIENT_ID", pid).apply()
                    Log.i("DeviceCommunicator", "Zapisano PATIENT_ID='${pid}' z tokenu")
                } else {
                    val fallback = com.example.parkincare.BuildConfig.PATIENT_ID
                    dprefs.edit().putString("PATIENT_ID", fallback).apply()
                    Log.w("DeviceCommunicator", "Nie udało się wyciągnąć PATIENT_ID z tokenu — ustawiam fallback='${fallback}'")
                }
            } catch (e: Exception) {
                val dprefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
                val fallback = com.example.parkincare.BuildConfig.PATIENT_ID
                dprefs.edit().putString("PATIENT_ID", fallback).apply()
                Log.w("DeviceCommunicator", "Decode JWT failed: ${e.message}. Ustawiono fallback='${fallback}'")
            }
            enqueueImmediateFetch(context.applicationContext)
        } else {
            Log.i("DeviceCommunicator", "Nieznana ścieżka: ${messageEvent.path}")
        }
    }

    private fun incrementMessageCount() {
        val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val current = prefs.getInt("message_count", 0) + 1
        prefs.edit().putInt("message_count", current).apply()
    }

    fun cancel() {
        val appContext = context.applicationContext
        Log.i("DeviceCommunicator", "[CANCEL] Usuwam listener z MessageClient")
        Wearable.getMessageClient(appContext).removeListener(this)
        isActive = false
        heartbeatJob?.cancel()
    }

    private fun saveToken(token: String) {
        val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("login_token", token).apply()
        // Zdekoduj patientId z tokenu i zapisz do PATIENT_ID (jak w onMessageReceived)
        val pid = try { decodePatientIdFromJwt(token) } catch (_: Exception) { null }
        val dprefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val finalPid = if (!pid.isNullOrBlank()) {
            dprefs.edit().putString("PATIENT_ID", pid).apply(); pid
        } else {
            val fallback = com.example.parkincare.BuildConfig.PATIENT_ID
            dprefs.edit().putString("PATIENT_ID", fallback).apply(); fallback
        }
        // Zarejestruj zmianę tokena w historii (nie blokujemy UI)
        try {
            com.example.parkincare.util.TokenHistoryRecorder.record(context.applicationContext, token, finalPid)
        } catch (_: Exception) {
            // ignore
        }
    }

    // Dekoduje payload JWT i zwraca patient id z pola "sub"
    private fun decodePatientIdFromJwt(jwt: String): String? {
        return try {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
            val decoded = android.util.Base64.decode(payloadB64.replace('-', '+').replace('_', '/'), android.util.Base64.NO_WRAP)
            val json = String(decoded)
            val obj = org.json.JSONObject(json)
            obj.optString("sub").takeIf { !it.isNullOrBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun enqueueImmediateFetch(appContext: Context) {
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val request = androidx.work.OneTimeWorkRequestBuilder<com.example.parkincare.work.FetchRemindersWorker>()
                .setConstraints(constraints)
                .build()
            androidx.work.WorkManager.getInstance(appContext)
                .enqueueUniqueWork(
                    com.example.parkincare.work.FetchRemindersWorker.UNIQUE_NAME + "_token_update",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request
                )
            Log.i("DeviceCommunicator", "Uruchomiono jednorazowe pobranie przypomnień po aktualizacji tokenu")
        } catch (e: Exception) {
            Log.w("DeviceCommunicator", "Nie udało się uruchomić fetch po tokenie: ${e.message}")
        }
    }

    companion object {
        @Volatile private var instance: DeviceCommunicator? = null
        private var isActive: Boolean = false

        fun getInstance(context: Context): DeviceCommunicator {
            return instance ?: synchronized(this) {
                instance ?: DeviceCommunicator(context.applicationContext).also {
                    instance = it
                    Log.i("DeviceCommunicator", "[SINGLETON] Utworzono instancję singletona")
                }
            }
        }

        fun isListenerActive(): Boolean = isActive

        fun getToken(context: Context): String? {
            val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            return prefs.getString("login_token", null)
        }
        fun getPatientId(context: Context): String? {
            val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            return prefs.getString("PATIENT_ID", null)
        }

        fun getMessageCount(context: Context): Int {
            val prefs = context.applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            return prefs.getInt("message_count", 0)
        }
    }
}
