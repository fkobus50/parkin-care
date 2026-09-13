package com.example.parkincare.presentation

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.parkincare.BuildConfig
import com.example.parkincare.R
import com.example.parkincare.data.local.DatabaseProvider
import com.example.parkincare.data.local.MedicineHistoryEntity
import com.example.parkincare.model.MedicineHistory
import com.example.parkincare.service.ApiClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.parkincare.util.ParkinLogger as Log
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive START, intent=$intent")
        try {
            val action = intent.action
            Log.i(TAG, "Received action: $action")

            val medicineName = intent.getStringExtra("medicineName") ?: "Lek"
            val doseStr = intent.getStringExtra("dose") ?: ""
            val scheduledDate = intent.getStringExtra("scheduledDate") ?: ""
            val id = intent.getStringExtra("id")
            val executionDate = intent.getStringExtra("executionDate")
            val medicineId = intent.getStringExtra("medicineId")

            val idsList = intent.getStringExtra("idsList") ?: ""
            val medicineIdsList = intent.getStringExtra("medicineIdsList") ?: ""
            val medicineList = intent.getStringExtra("medicineList") ?: ""
            val doseList = intent.getStringExtra("doseList") ?: ""

            val notificationId = ("${medicineName}_${doseStr}_${scheduledDate}_${id ?: System.nanoTime()}").hashCode()
            Log.i(TAG, "notificationId=$notificationId")

            when (action) {
                ACTION_ACCEPT -> {
                    handleAccept(
                        context, id, scheduledDate, executionDate, doseStr,
                        medicineId, medicineName, notificationId,
                        idsList, medicineIdsList, medicineList, doseList
                    )
                }
                ACTION_REJECT -> {
                    handleReject(
                        context, id, scheduledDate, executionDate, doseStr,
                        medicineId, medicineName, notificationId,
                        idsList, medicineIdsList, medicineList, doseList
                    )
                }
                else -> {
                    // Zawsze dodajemy do kolejki i uruchamiamy, jeśli nie jest aktywna
                    val roundedScheduledDate = try {
                        val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val outputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) // Zaokrąglamy scheduledDate do pełnej minuty
                        val date = inputFormat.parse(scheduledDate)
                        outputFormat.format(date ?: java.util.Date())
                    } catch (e: Exception) {
                        if (scheduledDate.length >= 16) scheduledDate.substring(0, 16) else scheduledDate
                    }
                    val alarm = AlarmQueue.AlarmData(medicineName, doseStr, roundedScheduledDate, id, executionDate, medicineId)
                    AlarmQueue.add(alarm)
                    lastContextRef = java.lang.ref.WeakReference(context)

                    alarmHandler.removeCallbacks(launchAlarmRunnable) // usuń poprzednie wywołanie i zaplanuj ponownie
                    launchAlarmPending = true
                    val debounceMs = 10000L
                    alarmHandler.postDelayed(launchAlarmRunnable, debounceMs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
        }
    }

    // Obsługa przycisku "Akceptuj"
    private fun handleAccept(
        context: Context,
        id: String?,
        scheduledDate: String,
        executionDate: String?,
        doseStr: String,
        medicineId: String?,
        medicineName: String,
        notificationId: Int,
        idsList: String,
        medicineIdsList: String,
        medicineList: String,
        doseList: String
    ) {
        Log.i(TAG, "handleAccept: idsList=$idsList, medicineIdsList=$medicineIdsList, medicineList=$medicineList, doseList=$doseList")
        if (id == null || id.isEmpty()) {
            val ids = idsList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val medIds = medicineIdsList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val names = medicineList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val doses = doseList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val count = listOf(ids.size, medIds.size, names.size, doses.size).minOrNull() ?: 0
            for (i in 0 until count) {
                Log.i(TAG, "handleAccept: Lek $i: id=${ids[i]}, medicineId=${medIds[i]}, name=${names[i]}, dose=${doses[i]}")
                handleMedicineAction(
                    context = context,
                    id = ids[i],
                    scheduledDate = scheduledDate,
                    executionDate = executionDate,
                    doseStr = doses[i],
                    medicineId = medIds[i],
                    medicineName = names[i],
                    notificationId = notificationId,
                    taken = true
                )
            }
        } else {
            Log.i(TAG, "handleAccept: Pojedynczy lek: id=$id, medicineId=$medicineId, name=$medicineName, dose=$doseStr")
            handleMedicineAction(
                context = context,
                id = id,
                scheduledDate = scheduledDate,
                executionDate = executionDate,
                doseStr = doseStr,
                medicineId = medicineId,
                medicineName = medicineName,
                notificationId = notificationId,
                taken = true
            )
        }
        NotificationManagerCompat.from(context).cancel(notificationId)
        AlarmQueue.clearActive()

        // Usuwanie alarmu po wywołaniu
        com.example.parkincare.AlarmStorage.removeAlarm(context, scheduledDate, idsList)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val alarmIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("scheduledDate", scheduledDate)
            putExtra("idsList", idsList)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduledDate.hashCode(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        enqueueImmediateFetch(context)
    }

    // Obsługa przycisku "Odrzuć"
    private fun handleReject(
        context: Context,
        id: String?,
        scheduledDate: String,
        executionDate: String?,
        doseStr: String,
        medicineId: String?,
        medicineName: String,
        notificationId: Int,
        idsList: String,
        medicineIdsList: String,
        medicineList: String,
        doseList: String
    ) {
        Log.i(TAG, "handleReject: idsList=$idsList, medicineIdsList=$medicineIdsList, medicineList=$medicineList, doseList=$doseList")
        if (id == null || id.isEmpty()) {
            val ids = idsList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val medIds = medicineIdsList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val names = medicineList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val doses = doseList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val count = listOf(ids.size, medIds.size, names.size, doses.size).minOrNull() ?: 0
            for (i in 0 until count) {
                Log.i(TAG, "handleReject: Lek $i: id=${ids[i]}, medicineId=${medIds[i]}, name=${names[i]}, dose=${doses[i]}")
                handleMedicineAction(
                    context = context,
                    id = ids[i],
                    scheduledDate = scheduledDate,
                    executionDate = executionDate,
                    doseStr = doses[i],
                    medicineId = medIds[i],
                    medicineName = names[i],
                    notificationId = notificationId,
                    taken = false
                )
            }
        } else {
            Log.i(TAG, "handleReject: Pojedynczy lek: id=$id, medicineId=$medicineId, name=$medicineName, dose=$doseStr")
            handleMedicineAction(
                context = context,
                id = id,
                scheduledDate = scheduledDate,
                executionDate = executionDate,
                doseStr = doseStr,
                medicineId = medicineId,
                medicineName = medicineName,
                notificationId = notificationId,
                taken = false
            )
        }
        NotificationManagerCompat.from(context).cancel(notificationId)
        AlarmQueue.clearActive()
        AlarmQueue.launchNextAlarm(context)
        enqueueImmediateFetch(context)
    }

    private fun handleMedicineAction(
        context: Context,
        id: String?,
        scheduledDate: String,
        executionDate: String?,
        doseStr: String,
        medicineId: String?,
        medicineName: String,
        notificationId: Int,
        taken: Boolean
    ) {
        val actionStr = if (taken) "ACCEPT" else "REJECT"
        Log.i(TAG, "DAWKA $actionStr: $medicineName $doseStr $scheduledDate")
        NotificationManagerCompat.from(context).cancel(notificationId)
        Log.i(TAG, "Notification cancelled ($actionStr), id=$notificationId")

        if (id == null) {
            Log.e(TAG, "Brak id w Intent! NIE wywołuję sendMedicineHistory")
            return
        }
        if (medicineId == null) {
            Log.e(TAG, "Brak medicineId w Intent! NIE wywołuję sendMedicineHistory")
            return
        }
        if (id.isEmpty()) {
            Log.e(TAG, "Pusty id! NIE wywołuję sendMedicineHistory")
            return
        }
        if (medicineId.isEmpty()) {
            Log.e(TAG, "Pusty medicineId! NIE wywołuję sendMedicineHistory")
            return
        }

        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale("pl", "PL"))
        val nowStr = fmt.format(java.util.Date(System.currentTimeMillis()))
        if (taken) {
            Log.i(TAG, "Ustawiam executionDate na ${nowStr} (potwierdzenie przyjęcia)")
        } else {
            Log.i(TAG, "Ustawiam executionDate na ${nowStr} (odrzucenie dawki)")
        }
        val finalExecutionDate = nowStr
        val finalNotificationDate = nowStr

        val doseDouble = doseStr.toDoubleOrNull() ?: 0.0
        Log.i(
            TAG,
            "Przekazane do modelu: id=$id, scheduledDate=$scheduledDate, executionDate=$finalExecutionDate, notificationDate=$finalNotificationDate, dose=$doseDouble, medicineId=$medicineId, medicineName=$medicineName, taken=$taken"
        )

        // --- ZAPIS DO ROOM I PRÓBA WYSYŁKI ---
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = DatabaseProvider.getDatabase(context)
                val dao = db.medicineHistoryDao()
                // POBIERZ patientId z tokenu z telefonu (DeviceCommunicator) lub fallback do BuildConfig
                val patientIdFromToken = try {
                    com.example.parkincare.DeviceCommunicator.getToken(context)
                } catch (e: Exception) { null }
                val patientId = when {
                    !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                    BuildConfig.PATIENT_ID.isNotBlank() -> BuildConfig.PATIENT_ID.trim('/')
                    else -> ""
                }
                val entity = MedicineHistoryEntity(
                    id = id!!,
                    patientId = patientId,
                    scheduledDate = scheduledDate,
                    executionDate = finalExecutionDate,
                    notificationDate = finalNotificationDate,
                    dose = doseDouble,
                    medicineId = medicineId!!,
                    medicineName = medicineName,
                    taken = taken,
                    shown = true,
                    type = "MedicineHistory",
                    sent = false
                )
                Log.i(TAG, "ROOM: zapisuję historię do bazy lokalnej: $entity")
                dao.insert(entity)
                try {
                    val data = androidx.work.Data.Builder()
                        .putString(com.example.parkincare.work.SendMedicineHistoryWorker.DATA_IDS, entity.id)
                        .build()
                    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    val request = OneTimeWorkRequestBuilder<com.example.parkincare.work.SendMedicineHistoryWorker>()
                        .setInputData(data)
                        .setConstraints(constraints)
                        .build()
                    WorkManager.getInstance(context).enqueue(request)
                    Log.i(TAG, "Enqueued SendMedicineHistoryWorker for id=${entity.id}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enqueue SendMedicineHistoryWorker for id=${entity.id}: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while processing medicine action: ${e.message}", e)
            } finally {
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }

    private fun allowAllSSL() {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sc = javax.net.ssl.SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "allowAllSSL error: ${e.message}", e)
        }
    }

    private suspend fun trySendMedicineHistoryToServer(context: Context, entity: com.example.parkincare.data.local.MedicineHistoryEntity): Boolean = withContext(Dispatchers.IO) {
        // OBEJŚCIE SSL - tylko na debug!
        if (BuildConfig.DEBUG) allowAllSSL()
        try {
            val currentToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: BuildConfig.apiToken
            val token = "Bearer " + currentToken.trim().replace("\n", "").replace("\r", "")
            Log.i(TAG, "TOKEN USED IN REQUEST: $token")
            val body = MedicineHistory(
                id = entity.id,
                scheduledDate = entity.scheduledDate,
                taken = entity.taken,
                shown = entity.shown,
                executionDate = entity.executionDate,
                notificationDate = entity.notificationDate,
                dose = entity.dose,
                medicineId = entity.medicineId,
                medicineName = entity.medicineName,
                apiToken = currentToken.trim('/')
            )
            Log.i(TAG, "ROOM: próbuję wysłać na serwer: $body")
            val gsonBody = Gson().toJson(body)
            val curl = "curl -X PUT \"${BuildConfig.sendHistoryUrl}\" -H 'X-Authorization: $token' -H 'Content-Type: application/json' -d '$gsonBody'"
            Log.i(TAG, "cURL: $curl")

            var mqttOk = false
            try {
                val payload = gsonBody.toByteArray(Charsets.UTF_8)
                val topic = "medicine/history"
                Log.i(TAG, "MQTT: attempting publish via helper topic='$topic' bytes=${payload.size}")
                mqttOk = publishMedicineHistoryViaMqtt(context, topic, payload, timeoutMs = 5000L)
                if (mqttOk) Log.i(TAG, "MQTT: publish OK (helper) topic='$topic' id=${entity.id}") else Log.w(TAG, "MQTT: publish FAIL (helper) topic='$topic' id=${entity.id}'")
            } catch (e: Exception) {
                Log.w(TAG, "MQTT: exception in helper publish: ${e.message}")
            }

            val response = ApiClient.medicineApi.sendMedicineHistory(token, body)
            val httpOk = response.isSuccessful
            val code = response.code()
            val err = response.errorBody()?.string()
            Log.i(TAG, "ROOM: HTTP response: code=${code}, isSuccessful=${httpOk}, body=${response.body()}, errorBody=${err}")
            if (httpOk) {
                Log.i(TAG, "HTTP: historia leku wysłana pomyślnie (id=${entity.id}, code=${code})")
            } else {
                Log.w(TAG, "HTTP: wysyłka NIEUDANA (id=${entity.id}, code=${code}) — ${err ?: "brak treści błędu"}")
            }

            // Wymagamy, żeby obie drogi (MQTT i HTTP) zakończyły się sukcesem
            val overallOk = mqttOk && httpOk
            if (!mqttOk) Log.w(TAG, "MQTT: publish did not succeed — overall send will be considered FAILED until MQTT works")
            if (!httpOk) Log.w(TAG, "HTTP: publish did not succeed — overall send will be considered FAILED until HTTP works")
            overallOk
        } catch (e: Exception) {
            Log.e(TAG, "ROOM: Błąd wysyłki do API", e)
            Log.w(TAG, "HTTP: wyjątek podczas wysyłki (id=${entity.id}) — ${e.message}")
            false
        }
    }

    private suspend fun publishMedicineHistoryViaMqtt(context: Context, topic: String, payload: ByteArray, timeoutMs: Long = 10000L): Boolean {
        return withContext(Dispatchers.IO) {
            val ok = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    // zabezpieczenie przed dwukrotnym resume
                    var resumed = false
                    fun safeResume(value: Boolean) {
                        if (resumed) return
                        resumed = true
                        try { cont.resume(value) } catch (_: Exception) {}
                    }
                    try {
                        val mgr = com.example.parkincare.util.ParkinLogger.getMqttManager()
                        if (mgr != null) {
                            try {
                                if (!mgr.isServerConnected) {
                                    try { mgr.reconnectIfNeeded() } catch (_: Exception) {}
                                    val start = System.currentTimeMillis()
                                    while (!mgr.isServerConnected && System.currentTimeMillis() - start < timeoutMs) {
                                        Thread.sleep(200)
                                    }
                                }
                                if (!mgr.isServerConnected) {
                                    Log.w(TAG, "MQTT_HELPER: existing mgr not connected after wait")
                                    safeResume(false)
                                } else {
                                    mgr.publishData(topic, payload) { success, _ -> safeResume(success) }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "MQTT_HELPER: publish via existing mgr exception: ${e.message}")
                                safeResume(false)
                            }
                        } else {
                            try {
                                Log.i(TAG, "MQTT_HELPER: no global mgr - attempting to start SensorService to initialize it")
                                try {
                                    val svcIntent = Intent(context, com.example.parkincare.service.SensorService::class.java)
                                    context.startService(svcIntent)
                                } catch (startEx: Exception) {
                                    Log.w(TAG, "MQTT_HELPER: startService failed: ${startEx.message}")
                                }
                                val start = System.currentTimeMillis()
                                var newMgr: com.example.parkincare.mqtt.MqttManager? = null
                                while (System.currentTimeMillis() - start < timeoutMs) {
                                    newMgr = com.example.parkincare.util.ParkinLogger.getMqttManager()
                                    if (newMgr != null) break
                                    Thread.sleep(200)
                                }
                                if (newMgr != null) {
                                    try {
                                        if (!newMgr.isServerConnected) {
                                            try { newMgr.reconnectIfNeeded() } catch (_: Exception) {}
                                            val start2 = System.currentTimeMillis()
                                            while (!newMgr.isServerConnected && System.currentTimeMillis() - start2 < timeoutMs) {
                                                Thread.sleep(200)
                                            }
                                        }
                                        if (!newMgr.isServerConnected) {
                                            Log.w(TAG, "MQTT_HELPER: newMgr not connected after wait")
                                            safeResume(false)
                                        } else {
                                            newMgr.publishData(topic, payload) { success, _ -> safeResume(success) }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "MQTT_HELPER: publish via newMgr exception: ${e.message}")
                                        safeResume(false)
                                    }
                                } else {
                                    // fallback: tymczasowy manager
                                    try {
                                        val tempMgr = com.example.parkincare.mqtt.MqttManager(context)
                                        tempMgr.initialize()
                                        try { tempMgr.connect() } catch (_: Exception) {}
                                        val start3 = System.currentTimeMillis()
                                        while (!tempMgr.isServerConnected && System.currentTimeMillis() - start3 < timeoutMs) {
                                            Thread.sleep(200)
                                        }
                                        if (!tempMgr.isServerConnected) {
                                            Log.w(TAG, "MQTT_HELPER: tempMgr not connected after wait")
                                            try { tempMgr.disconnect() } catch (_: Exception) {}
                                            safeResume(false)
                                        } else {
                                            try {
                                                tempMgr.publishData(topic, payload) { success, _ ->
                                                    try { tempMgr.disconnect() } catch (_: Exception) {}
                                                    safeResume(success)
                                                }
                                            } catch (pubEx: Exception) {
                                                Log.w(TAG, "MQTT_HELPER: publish exception on tempMgr: ${pubEx.message}")
                                                try { tempMgr.disconnect() } catch (_: Exception) {}
                                                safeResume(false)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "MQTT_HELPER: failed creating/connecting tempMgr: ${e.message}")
                                        safeResume(false)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "MQTT_HELPER: unexpected error in mgr-null branch: ${e.message}")
                                safeResume(false)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MQTT_HELPER: unexpected exception: ${e.message}")
                        safeResume(false)
                    }
                }
            }
            ok == true
        }
    }

    // Uruchamia jednorazowy fetch przypomnień (używane po akcji pacjenta)
    private fun enqueueImmediateFetch(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<com.example.parkincare.work.FetchRemindersWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    com.example.parkincare.work.FetchRemindersWorker.UNIQUE_NAME + "_after_action",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            Log.i(TAG, "Uruchomiono jednorazowy FetchRemindersWorker po akcji pacjenta")
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się uruchomić jednorazowego fetch po akcji: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_ACCEPT = "com.example.parkincare.ACTION_ACCEPT"
        const val ACTION_REJECT = "com.example.parkincare.ACTION_REJECT"

        // Mechanizm opóźnienia uruchamiania alarmu
        private var launchAlarmPending = false
        private val alarmHandler = android.os.Handler(android.os.Looper.getMainLooper())
        // WeakReference na Context, by uniknąć memory leak
        private var lastContextRef: java.lang.ref.WeakReference<Context>? = null

        private val launchAlarmRunnable = Runnable {
            val ctx = lastContextRef?.get()
            ctx?.let { AlarmQueue.launchNextAlarm(it) }
            launchAlarmPending = false
        }
    }
}
