package com.example.parkincare.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.parkincare.util.ParkinLogger as Log
import com.example.parkincare.data.local.DatabaseProvider
import com.example.parkincare.service.ApiClient
import com.example.parkincare.model.MedicineHistory
import com.google.gson.Gson
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SendMedicineHistoryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val DATA_IDS = "ids" // oczekujemy stringa z id rozdzielonych średnikiem
        private const val MAX_ATTEMPTS = 6
    }

    override suspend fun doWork(): Result {
        return try {
            val idsRaw = inputData.getString(DATA_IDS) ?: ""
            try { com.example.parkincare.util.ParkinLogger.init(applicationContext) } catch (_: Exception) {}
            try { Log.i("SendMedicineHistoryWorker", "START doWork idsRaw='${idsRaw}'") } catch (_: Exception) {}
             if (idsRaw.isBlank()) {
                Log.w("SendMedicineHistoryWorker", "No ids provided")
                return Result.success()
            }

            val ids = idsRaw.split(";").map { it.trim() }.filter { it.isNotBlank() }
            val db = DatabaseProvider.getDatabase(applicationContext)
            val dao = db.medicineHistoryDao()
            val gson = Gson()

            val currentTokenRaw = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: com.example.parkincare.BuildConfig.apiToken
            val bearer = "Bearer ${currentTokenRaw.trim().replace("\n", "").replace("\r", "") }"

            val entities = try { dao.getByIds(ids) } catch (e: Exception) { emptyList() }
            if (entities.isEmpty()) {
                Log.w("SendMedicineHistoryWorker", "No entities found for ids count=${ids.size}")
                return Result.success()
            }

            var anyFailure = false
            for (entity in entities) {
                try {
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
                        apiToken = currentTokenRaw.trim('/')
                    )
                    val json = gson.toJson(body)
                    val payload = json.toByteArray(Charsets.UTF_8)

                    try {
                        val curl =
                            "curl -X PUT \"${com.example.parkincare.BuildConfig.sendHistoryUrl}\" -H 'X-Authorization: $bearer' -H 'Content-Type: application/json' -d '$json'"
                        Log.i("SendMedicineHistoryWorker", "ROOM: próbuję wysłać na serwer: $json")
                        Log.i("SendMedicineHistoryWorker", "cURL: $curl")
                    } catch (e: Exception) {
                        Log.w("SendMedicineHistoryWorker", "Logging body failed: ${e.message}")
                    }

                     var mqttOk = false
                     try {
                         val topic = "medicine/history"
                        Log.i("SendMedicineHistoryWorker", "MQTT: attempting publish topic='$topic' id=${entity.id} bytes=${payload.size}")
                         var mqttMgr: com.example.parkincare.mqtt.MqttManager? = try { com.example.parkincare.util.ParkinLogger.getMqttManager() } catch (_: Exception) { null }
                         if (mqttMgr == null) {
                            try {
                                val svcIntent = android.content.Intent(applicationContext, com.example.parkincare.service.SensorService::class.java)
                                applicationContext.startService(svcIntent)
                            } catch (e: Exception) {
                                Log.w("SendMedicineHistoryWorker", "Failed to start SensorService: ${e.message}")
                            }
                            val start = System.currentTimeMillis()
                            val timeoutMs = 5000L
                            while (System.currentTimeMillis() - start < timeoutMs && mqttMgr == null) {
                                delay(200)
                                mqttMgr = try { com.example.parkincare.util.ParkinLogger.getMqttManager() } catch (_: Exception) { null }
                            }
                        }

                        if (mqttMgr != null) {
                            mqttOk = try {
                                val ok = publishSuspend(mqttMgr, topic, payload, timeoutMs = 6000L)
                                Log.i("SendMedicineHistoryWorker", "MQTT: publish result id=${entity.id} ok=$ok")
                                ok
                            } catch (e: Exception) {
                                Log.w("SendMedicineHistoryWorker", "MQTT publish exception id=${entity.id}: ${e.message}")
                                false
                            }
                            if (!mqttOk) Log.w("SendMedicineHistoryWorker", "MQTT: publish FAIL topic='medicine/history' id=${entity.id}")
                        } else {
                             try {
                                 val tempMgr = com.example.parkincare.mqtt.MqttManager(applicationContext)
                                 tempMgr.initialize()
                                 try { tempMgr.connect() } catch (_: Exception) {}
                                 val start2 = System.currentTimeMillis()
                                 val timeout2 = 5000L
                                 while (!tempMgr.isServerConnected && System.currentTimeMillis() - start2 < timeout2) {
                                    delay(200)
                                 }
                                 if (tempMgr.isServerConnected) {
                                    mqttOk = try {
                                        val ok = publishSuspend(tempMgr, topic, payload, timeoutMs = 6000L)
                                        Log.i("SendMedicineHistoryWorker", "MQTT: temp publish result id=${entity.id} ok=$ok")
                                        ok
                                    } catch (e: Exception) {
                                        Log.w("SendMedicineHistoryWorker", "MQTT: temp publish exception id=${entity.id}: ${e.message}")
                                        false
                                    }
                                     try { tempMgr.disconnect() } catch (_: Exception) {}
                                     if (!mqttOk) Log.w("SendMedicineHistoryWorker", "Temp MQTT publish failed id=${entity.id}")
                                 } else {
                                    Log.w("SendMedicineHistoryWorker", "MQTT: tempMgr not connected - skipping MQTT for id=${entity.id}")
                                 }
                             } catch (e: Exception) {
                                 Log.w("SendMedicineHistoryWorker", "Exception while creating temp MqttManager: ${e.message}")
                             }
                         }
                     } catch (e: Exception) {
                         Log.w("SendMedicineHistoryWorker", "MQTT exception for id=${entity.id}: ${e.message}")
                     }

                     // Wysylka HTTP
                    val response = try {
                        try {
                            Log.i("SendMedicineHistoryWorker", "HTTP: attempting PUT id=${entity.id} url=${com.example.parkincare.BuildConfig.sendHistoryUrl}")
                        } catch (_: Exception) {}
                        ApiClient.medicineApi.sendMedicineHistory(bearer, body)
                    } catch (e: Exception) {
                        Log.w("SendMedicineHistoryWorker", "HTTP request failed id=${entity.id}: ${e.message}")
                        null
                    }
                    val httpOk = response?.isSuccessful == true
                    try {
                        val code = response?.code()
                        val ok = response?.isSuccessful == true
                        val bodyResp = try { response?.body() } catch (_: Exception) { null }
                        val errResp = try { response?.errorBody()?.string() } catch (_: Exception) { null }
                        Log.i("SendMedicineHistoryWorker", "ROOM: HTTP response: code=${code}, isSuccessful=${ok}, body=${bodyResp}, errorBody=${errResp}")
                        if (ok) {
                            Log.i("SendMedicineHistoryWorker", "HTTP: historia leku wysłana pomyślnie (id=${entity.id}, code=${code})")
                        } else {
                            Log.w("SendMedicineHistoryWorker", "HTTP: wysyłka NIEUDANA (id=${entity.id}, code=${code}) — ${errResp ?: "brak treści błędu"}")
                        }
                    } catch (e: Exception) {
                        Log.w("SendMedicineHistoryWorker", "HTTP: response logging failed for id=${entity.id}: ${e.message}")
                    }
                    if (!httpOk) {
                        Log.w("SendMedicineHistoryWorker", "HTTP failed id=${entity.id} code=${response?.code()}")
                    }

                     if (mqttOk && httpOk) {
                         try {
                             dao.markAsSent(entity.id)
                             Log.i("SendMedicineHistoryWorker", "Marked as sent id=${entity.id}")
                         } catch (e: Exception) {
                             Log.w("SendMedicineHistoryWorker", "Failed to markAsSent id=${entity.id}: ${e.message}")
                             anyFailure = true
                         }
                     } else {
                         Log.w("SendMedicineHistoryWorker", "Not marking as sent id=${entity.id} mqttOk=${mqttOk} httpOk=${httpOk}")
                         anyFailure = true
                     }

                } catch (e: Exception) {
                    Log.e("SendMedicineHistoryWorker", "Exception processing id=${entity.id}: ${e.message}")
                    anyFailure = true
                }
            }

            if (anyFailure) Result.retry() else {
                try { refreshPastRemindersGui(applicationContext, currentTokenRaw, attemptExceeded = false) } catch (_: Exception) {}
                Result.success()
            }
        } catch (e: Exception) {
            Log.e("SendMedicineHistoryWorker", "Worker crashed: ${e.message}")
            try {
                val attemptExceeded = runAttemptCount >= MAX_ATTEMPTS
                val currentTokenRaw = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
                    ?.takeIf { it.isNotBlank() } ?: com.example.parkincare.BuildConfig.apiToken
                if (attemptExceeded) refreshPastRemindersGui(applicationContext, currentTokenRaw, attemptExceeded = true)
            } catch (_: Exception) {}
            Result.retry()
        }
    }

    private suspend fun refreshPastRemindersGui(context: Context, apiTokenRaw: String, attemptExceeded: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val sessionId = apiTokenRaw.trim('/')
                val prefsGui = context.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE)
                val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
                val dao = db.medicineHistoryDao()
                val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                val patientId = when {
                    !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                    com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim('/')
                    else -> ""
                }
                val allForPatient = try { dao.getAllForPatient(patientId) } catch (_: Exception) { emptyList() }
                val formats = listOf(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale("pl", "PL")),
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale("pl", "PL")),
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale("pl", "PL"))
                )
                val nowTs = System.currentTimeMillis()
                val pastParsed = allForPatient.mapNotNull { e ->
                    var millis: Long? = null
                    val sd = e.scheduledDate
                    for (fmt in formats) {
                        try { val d = fmt.parse(sd); if (d != null) { millis = d.time; break } } catch (_: Exception) {}
                    }
                    millis?.let { Pair(e, it) }
                }.filter { it.second <= nowTs }
                val byTimePast = pastParsed.sortedByDescending { it.second }.toMutableList()
                val groupsPast: MutableList<List<Pair<com.example.parkincare.data.local.MedicineHistoryEntity, Long>>> = mutableListOf()
                while (byTimePast.isNotEmpty() && groupsPast.size < 8) {
                    val base = byTimePast.first().second
                    val thisGroup = byTimePast.takeWhile { kotlin.math.abs(it.second - base) <= 60_000L }
                    groupsPast.add(thisGroup)
                    repeat(thisGroup.size) { byTimePast.removeAt(0) }
                }
                if (groupsPast.isEmpty()) {
                    prefsGui.edit().remove("past_reminders_${sessionId}").apply()
                    return@withContext
                }
                val fmtOutHourMinute = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl", "PL"))
                val fmtFull = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("pl", "PL"))
                val calNow = java.util.Calendar.getInstance().apply { timeInMillis = nowTs; set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0) }
                val pastJoined = groupsPast.map { grp ->
                    val timeMillis = grp.first().second
                    val hourMin = fmtOutHourMinute.format(java.util.Date(timeMillis))
                    val calT = java.util.Calendar.getInstance().apply { timeInMillis = timeMillis; set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0) }
                    val dd = ((calT.timeInMillis - calNow.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
                    val pref = when (dd) { 0 -> context.getString(com.example.parkincare.R.string.today_label); -1 -> context.getString(com.example.parkincare.R.string.yesterday_label); -2 -> context.getString(com.example.parkincare.R.string.day_before_yesterday_label); else -> fmtFull.format(java.util.Date(timeMillis)) }
                    val pwt = "$pref $hourMin"
                    grp.map { p ->
                        val status = when {
                            p.first.shown && p.first.taken -> "przyjęte"
                            p.first.shown && !p.first.taken -> "nieprzyjęte"
                            else -> "niewyświetlone"
                        }
                        val statusLabel = if (!p.first.sent) {
                            if (attemptExceeded) "$status; błąd wysyłki" else "$status; wysyłanie"
                        } else status
                        "$pwt - ${p.first.dose} x ${p.first.medicineName} [$statusLabel]"
                    }.joinToString("\n")
                }.joinToString(separator = "\n\n")
                prefsGui.edit().putString("past_reminders_${sessionId}", pastJoined).apply()
            } catch (e: Exception) {
                Log.w("SendMedicineHistoryWorker", "refreshPastRemindersGui failed: ${e.message}")
            }
        }
    }

    private suspend fun publishSuspend(mgr: com.example.parkincare.mqtt.MqttManager, topic: String, payload: ByteArray, timeoutMs: Long = 6000L): Boolean {
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    var resumed = false
                    fun safeResume(value: Boolean) {
                        if (resumed) return
                        resumed = true
                        try { cont.resume(value) } catch (_: Exception) {}
                    }
                    try {
                        mgr.publishData(topic, payload) { ok, _ -> safeResume(ok) }
                    } catch (e: Exception) {
                        safeResume(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SendMedicineHistoryWorker", "publishSuspend timeout/exception: ${e.message}")
            false
        }
    }
}
