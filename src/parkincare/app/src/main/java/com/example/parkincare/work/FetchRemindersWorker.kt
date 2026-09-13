package com.example.parkincare.work

import android.content.Context
import com.example.parkincare.util.ParkinLogger as Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.parkincare.BuildConfig
import com.example.parkincare.service.ApiClient
import com.example.parkincare.util.ReminderNotificationHelper
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import java.util.concurrent.TimeUnit
import com.example.parkincare.presentation.ReminderReceiver
import com.google.gson.Gson
import kotlinx.coroutines.delay

class FetchRemindersWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            purgeFutureMedicineHistory(applicationContext)

            Log.i("FetchRemindersWorker", "Próba wysłania zaległych wpisów z Room przed pobraniem leków")
            resendUnsentMedicineHistory(applicationContext)
            val apiToken = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: BuildConfig.apiToken
            val bearer = "Bearer ${apiToken}"
            val sessionId = apiToken.trim('/')
            Log.i("FetchRemindersWorker", "Używam apiToken z=${if (apiToken == BuildConfig.apiToken) "BuildConfig" else "Device"}, session='${sessionId}'")

            val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
            val patientId = when {
                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                BuildConfig.PATIENT_ID.isNotBlank() -> BuildConfig.PATIENT_ID.trim('/')
                else -> ""
            }
            Log.i("FetchRemindersWorker", "Pobieram przypomnienia (session='${sessionId}', patient='${patientId}')")

            val reminders = try {
                ApiClient.reminderApi.getReminders(BuildConfig.getReminderUrl, bearer)
            } catch (e: Exception) {
                Log.e("FetchRemindersWorker", "Błąd pobierania przypomnień", e)
                val devicePrefs = applicationContext.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
                val existingPid = devicePrefs.getString("PATIENT_ID", null)
                if (existingPid.isNullOrBlank()) {
                    devicePrefs.edit().putString("PATIENT_ID", BuildConfig.PATIENT_ID).apply()
                    Log.i("FetchRemindersWorker", "PATIENT_ID nieobecny – ustawiam fallback z BuildConfig='${BuildConfig.PATIENT_ID}'")
                } else {
                    Log.i("FetchRemindersWorker", "PATIENT_ID obecny ('${existingPid}') – nie nadpisuję fallbackiem z BuildConfig")
                }

                val prefs = applicationContext.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE)
                prefs.edit().remove("next_reminder_${sessionId}").apply()
                try {
                    val plannedIds = com.example.parkincare.util.ReminderNotificationHelper.getPlannedIds(applicationContext)
                    plannedIds.forEach { id -> com.example.parkincare.util.ReminderNotificationHelper.cancelAlarm(applicationContext, id) }
                    Log.i("FetchRemindersWorker", "Anulowano ${plannedIds.size} zaplanowanych alarmów (session='${sessionId}') po błędzie API")
                } catch (ex: Exception) {
                    Log.w("FetchRemindersWorker", "Nie udało się anulować alarmów po błędzie: ${ex.message}")
                }
                return Result.success()
            }

            try {
                com.example.parkincare.util.ParkinLogger.getMqttManager()?.let { mgr ->
                    val gson = Gson()
                    val currentTokenRaw = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
                        ?.takeIf { it.isNotBlank() } ?: BuildConfig.apiToken
                    val batchSize = 50
                    val batchPauseMs = 300L
                    val windows = if (reminders.isNotEmpty()) reminders.windowed(size = batchSize, step = batchSize, partialWindows = true) else emptyList()
                    var batchIndex = 0
                    var sentCounter = 0

                    val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(applicationContext)
                    val dao = db.medicineHistoryDao()
                    val localHistoryMap: Map<String, com.example.parkincare.data.local.MedicineHistoryEntity> = try {
                        val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
                        val patientIdLocal = when {
                            !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                            BuildConfig.PATIENT_ID.isNotBlank() -> BuildConfig.PATIENT_ID.trim('/')
                            else -> ""
                        }
                        try { dao.getAllForPatient(patientIdLocal).associateBy { it.id } } catch (_: Exception) { emptyMap() }
                    } catch (_: Exception) {
                        emptyMap()
                    }

                    for (batch in windows) {
                        batchIndex++
                        Log.i("FetchRemindersWorker", "MQTT: start batch #" + batchIndex + " size=" + batch.size)
                        batch.forEach { r ->
                            // Jeśli mamy lokalny wpis historii dla tego przypomnienia, użyj jego statusów (taken/shown/executionDate/notificationDate)
                            val local = localHistoryMap[r.id]
                            val body = com.example.parkincare.model.MedicineHistory(
                                id = r.id,
                                scheduledDate = r.scheduledDate,
                                taken = local?.taken ?: r.taken,
                                shown = local?.shown ?: r.shown,
                                executionDate = local?.executionDate ?: r.executionDate,
                                notificationDate = local?.notificationDate ?: r.notificationDate,
                                dose = r.dose,
                                medicineId = r.medicineId,
                                medicineName = r.medicineName,
                                apiToken = currentTokenRaw.trim('/')
                            )
                            val json = gson.toJson(body)
                            val payload = json.toByteArray(Charsets.UTF_8)
                            val topic = "medicine/history"
                            mgr.publishData(topic, payload) { ok, err ->
                                if (!ok) {
                                    Log.w("FetchRemindersWorker", "MQTT: publish FAIL topic='" + topic + "' id=" + r.id + " err=" + err)
                                }
                            }
                            sentCounter++
                            if (sentCounter % 10 == 0) {
                                Log.i("FetchRemindersWorker", "MQTT: wysłano już " + sentCounter + " wiadomości (batch #" + batchIndex + ")")
                            }
                        }
                        if (batchIndex < windows.size) {
                            Log.i("FetchRemindersWorker", "MQTT: pause " + batchPauseMs + "ms between batches")
                            delay(batchPauseMs)
                        }
                    }
                } ?: run {
                    Log.w("FetchRemindersWorker", "MQTT: brak MqttManager — pomijam publish wpisów historii")
                }
            } catch (e: Exception) {
                Log.w("FetchRemindersWorker", "MQTT: wyjątek przy publish paczkami: ${e.message}")
            }

            val plannedIds = ReminderNotificationHelper.getPlannedIds(applicationContext)
            val newIds = reminders.map { it.id }.toSet()
            val alreadyCount = plannedIds.intersect(newIds).size // Y
            val newCount = newIds.size - alreadyCount // X
            val toRemove = plannedIds.minus(newIds)
            var removedCount = 0
            toRemove.forEach { id ->
                // Usuń tylko te, które są planowane (nie odbyły się) — mamy je w plannedIds
                ReminderNotificationHelper.cancelAlarm(applicationContext, id)
                removedCount++
            }
            Log.i(
                "FetchRemindersWorker",
                "Pobrano ${reminders.size} przypomnień (patient='${patientId}'), w tym: ${alreadyCount} już było, ${newCount} nowych. Usunięto ${removedCount} nieaktualnych planów"
            )

            val adjustedReminders = reminders.toMutableList()
            adjustedReminders.forEach {
                ReminderNotificationHelper.scheduleReminderNotification(applicationContext, it)
            }

            // Wyznacz i zapisz najbliższe przypomnienia (grupowanie +-60s jak w AlarmActivity)
            val formats = listOf(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale("pl", "PL")),
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale("pl", "PL"))
            )
            val now = System.currentTimeMillis()
            val parsed = adjustedReminders.mapNotNull { r ->
                var millis: Long? = null
                for (fmt in formats) {
                    try { val d = fmt.parse(r.scheduledDate); if (d != null) { millis = d.time; break } } catch (_: Exception) {}
                }
                millis?.let { Pair(r, it) }
            }.filter { it.second >= now }


            val minMillis = parsed.minByOrNull { it.second }?.second
            val prefs = applicationContext.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE)
            if (minMillis != null) {
                val fmtOutHourMinute = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl", "PL"))
                // Wyznacz prefiks daty względnie do dzisiaj
                val calNow = java.util.Calendar.getInstance()
                calNow.timeInMillis = now
                calNow.set(java.util.Calendar.HOUR_OF_DAY, 0)
                calNow.set(java.util.Calendar.MINUTE, 0)
                calNow.set(java.util.Calendar.SECOND, 0)
                calNow.set(java.util.Calendar.MILLISECOND, 0)
                val calTarget = java.util.Calendar.getInstance()
                calTarget.timeInMillis = minMillis
                calTarget.set(java.util.Calendar.HOUR_OF_DAY, 0)
                calTarget.set(java.util.Calendar.MINUTE, 0)
                calTarget.set(java.util.Calendar.SECOND, 0)
                calTarget.set(java.util.Calendar.MILLISECOND, 0)
                val dayDiff = ((calTarget.timeInMillis - calNow.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
                val prefix = when (dayDiff) {
                    0 -> applicationContext.getString(com.example.parkincare.R.string.today_label)
                    1 -> applicationContext.getString(com.example.parkincare.R.string.tomorrow_label)
                    2 -> applicationContext.getString(com.example.parkincare.R.string.day_after_tomorrow_label)
                    else -> {
                        val fmtFull = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("pl", "PL"))
                        fmtFull.format(java.util.Date(minMillis))
                    }
                }
                val hourMinute = fmtOutHourMinute.format(java.util.Date(minMillis))
                val group = parsed.filter { kotlin.math.abs(it.second - minMillis) <= 60_000L }.map { it.first }
                val prefixWithTime = "$prefix $hourMinute"
                // Format: prefix czasu + " - " + dawka x Nazwa
                val summaries = group.map { g -> "$prefixWithTime - ${g.dose} x ${g.medicineName}" }
                val joined = summaries.joinToString(separator = "\n")
                prefs.edit().putString("next_reminder_${sessionId}", joined).apply()
                Log.i("FetchRemindersWorker", "GUI next reminders (session='${sessionId}'):\n$joined")

                // Dodatkowo: do 8 nadchodzących grup (+-60s) — każda linia z prefiksem czasu oraz "dawka x Nazwa"
                val byTime = parsed.sortedBy { it.second }.toMutableList()
                val groups: MutableList<List<Pair<com.example.parkincare.model.MedicineHistory, Long>>> = mutableListOf()
                while (byTime.isNotEmpty() && groups.size < 8) {
                    val base = byTime.first().second
                    val thisGroup: List<Pair<com.example.parkincare.model.MedicineHistory, Long>> = byTime
                        .takeWhile { kotlin.math.abs(it.second - base) <= 60_000L }
                        .map { Pair(it.first, it.second) }
                    groups.add(thisGroup)
                    repeat(thisGroup.size) { byTime.removeAt(0) }
                }
                val upcomingJoined = groups.map { grp: List<Pair<com.example.parkincare.model.MedicineHistory, Long>> ->
                    val timeMillis: Long = grp.first().second
                    val hourMin = fmtOutHourMinute.format(java.util.Date(timeMillis))
                    val calT = java.util.Calendar.getInstance().apply {
                        timeInMillis = timeMillis
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val dd = ((calT.timeInMillis - calNow.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
                    val pref = when (dd) {
                        0 -> applicationContext.getString(com.example.parkincare.R.string.today_label)
                        1 -> applicationContext.getString(com.example.parkincare.R.string.tomorrow_label)
                        2 -> applicationContext.getString(com.example.parkincare.R.string.day_after_tomorrow_label)
                        else -> {
                            val fmtFull = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("pl", "PL"))
                            fmtFull.format(java.util.Date(timeMillis))
                        }
                    }
                    val pwt = "$pref $hourMin"
                    grp.map { p: Pair<com.example.parkincare.model.MedicineHistory, Long> -> "$pwt - ${p.first.dose} x ${p.first.medicineName}" }
                        .joinToString("\n")
                }.joinToString(separator = "\n\n")
                prefs.edit().putString("upcoming_reminders_${sessionId}", upcomingJoined).apply()
                Log.i("FetchRemindersWorker", "GUI upcoming reminders (session='${sessionId}'):\n$upcomingJoined")
            } else {
                prefs.edit().remove("next_reminder_${sessionId}").apply()
                prefs.edit().remove("upcoming_reminders_${sessionId}").apply()
                Log.i("FetchRemindersWorker", "Brak nadchodzących przypomnień (session='${sessionId}')")
            }

            // Sekcja: Poprzednie leki — grupuj zdarzenia z przeszłości (+-60s), max 8, sortuj od najbliższej przeszłości
            run {
                val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(applicationContext)
                val dao = db.medicineHistoryDao()
                // Ustal pacjenta jak wcześniej
                val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
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
                val prefsGui = applicationContext.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE)
                if (groupsPast.isNotEmpty()) {
                    val fmtOutHourMinute = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl", "PL"))
                    val fmtFull = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("pl", "PL"))
                    val calNow = java.util.Calendar.getInstance().apply {
                        timeInMillis = nowTs
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val pastJoined = groupsPast.map { grp ->
                        val timeMillis = grp.first().second
                        val hourMin = fmtOutHourMinute.format(java.util.Date(timeMillis))
                        val calT = java.util.Calendar.getInstance().apply {
                            timeInMillis = timeMillis
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        val dd = ((calT.timeInMillis - calNow.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
                        val pref = when (dd) {
                            0 -> applicationContext.getString(com.example.parkincare.R.string.today_label)
                            -1 -> applicationContext.getString(com.example.parkincare.R.string.yesterday_label)
                            -2 -> applicationContext.getString(com.example.parkincare.R.string.day_before_yesterday_label)
                            else -> fmtFull.format(java.util.Date(timeMillis))
                        }
                        val pwt = "$pref $hourMin"
                        grp.map { p ->
                            val status = when {
                                p.first.shown && p.first.taken -> "przyjęte"
                                p.first.shown && !p.first.taken -> "nieprzyjęte"
                                else -> "niewyświetlone"
                            }
                            // Jeśli rekord nie został jeszcze oznaczony jako sent -> pokaż, że trwa wysyłka
                            val statusLabel = if (!p.first.sent) "$status; wysyłanie" else status
                            "$pwt - ${p.first.dose} x ${p.first.medicineName} [$statusLabel]"
                        }.joinToString("\n")
                    }.joinToString(separator = "\n\n")
                    prefsGui.edit().putString("past_reminders_${sessionId}", pastJoined).apply()
                    Log.i("FetchRemindersWorker", "GUI past reminders (session='${sessionId}'):\n$pastJoined")
                } else {
                    prefsGui.edit().remove("past_reminders_${sessionId}").apply()
                    Log.i("FetchRemindersWorker", "Brak poprzednich leków (session='${sessionId}')")
                }
            }

            Result.success()
        } catch (e: Exception) {
            // jakikolwiek nieobsłużony wyjątek – wyczyść GUI i anuluj alarmy
            val apiToken = try { com.example.parkincare.DeviceCommunicator.getToken(applicationContext) } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: BuildConfig.apiToken
            val sessionId = apiToken.trim('/')
            val prefs = applicationContext.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE)
            prefs.edit().remove("next_reminder_${sessionId}").apply()
            try {
                val plannedIds = com.example.parkincare.util.ReminderNotificationHelper.getPlannedIds(applicationContext)
                plannedIds.forEach { id -> com.example.parkincare.util.ReminderNotificationHelper.cancelAlarm(applicationContext, id) }
                Log.i("FetchRemindersWorker", "Anulowano ${plannedIds.size} alarmów po nieobsłużonym wyjątku (session='${sessionId}')")
            } catch (ex: Exception) {
                Log.w("FetchRemindersWorker", "Nie udało się anulować alarmów: ${ex.message}")
            }
            Log.e("FetchRemindersWorker", "Błąd pobierania przypomnień", e)
            // Zakończ sukcesem, aby GUI pokazało "brak"
            Result.success()
        }
    }

    // ponowna wysyłka niewysłanych rekordów historii leków dla bieżącego pacjenta
    private suspend fun resendUnsentMedicineHistory(context: Context) {
        val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
        val dao = db.medicineHistoryDao()
        val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
        val patientId = when {
            !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
            com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim('/')
            else -> ""
        }
        val unsent = dao.getUnsentForPatient(patientId)
        Log.i("FetchRemindersWorker", "ROOM: enqueue retry for ${unsent.size} unsent records (patient='${patientId}')")
        if (unsent.isNotEmpty()) {
            try {
                val batchSize = 50
                val ids = unsent.mapNotNull { it.id }
                ids.chunked(batchSize).forEach { chunk ->
                    try {
                        val data = androidx.work.Data.Builder()
                            .putString(com.example.parkincare.work.SendMedicineHistoryWorker.DATA_IDS, chunk.joinToString(";"))
                            .build()
                        val constraints = androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
                        val request = androidx.work.OneTimeWorkRequestBuilder<com.example.parkincare.work.SendMedicineHistoryWorker>()
                            .setInputData(data)
                            .setConstraints(constraints)
                            .build()
                        androidx.work.WorkManager.getInstance(context).enqueue(request)
                        Log.i("FetchRemindersWorker", "Enqueued SendMedicineHistoryWorker for batch size=${chunk.size}")
                    } catch (e: Exception) {
                        Log.w("FetchRemindersWorker", "Failed to enqueue SendMedicineHistoryWorker for batch: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w("FetchRemindersWorker", "Exception while enqueueing retry workers: ${e.message}")
            }
        }
    }

    private suspend fun purgeFutureMedicineHistory(context: Context) {
        try {
            val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
            val dao = db.medicineHistoryDao()
            val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
            val patientId = when {
                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim('/')
                else -> ""
            }
            val all = dao.getAllForPatient(patientId)
            var deleted = 0
            val now = System.currentTimeMillis()
            val formats = listOf(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()),
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            )
            for (e in all) {
                val sd = e.scheduledDate
                var parsed: java.util.Date? = null
                for (fmt in formats) {
                    try {
                        parsed = fmt.parse(sd)
                        if (parsed != null) break
                    } catch (_: Exception) {}
                }
                if (parsed == null) continue
                if (parsed.time > now) {
                    dao.deleteById(e.id)
                    deleted++
                }
            }
            Log.i("FetchRemindersWorker", "PURGE: (patient='${patientId}') usunięto $deleted rekordów z przyszłą datą scheduledDate")
        } catch (e: Exception) {
            Log.e("FetchRemindersWorker", "PURGE: błąd podczas usuwania przyszłych rekordów", e)
        }
    }

    private suspend fun trySendMedicineHistoryToServer(context: Context, entity: com.example.parkincare.data.local.MedicineHistoryEntity): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                    ?.takeIf { it.isNotBlank() } ?: com.example.parkincare.BuildConfig.apiToken
                val token = "Bearer " + currentToken.trim().replace("\n", "").replace("\r", "")
                val body = com.example.parkincare.model.MedicineHistory(
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
                // Tylko HTTP (bez MQTT) aby uniknąć duplikatów po stronie receivera
                val response = com.example.parkincare.service.ApiClient.medicineApi.sendMedicineHistory(token, body)
                val ok = response.isSuccessful
                if (!ok) {
                    Log.w("FetchRemindersWorker", "HTTP: wysyłka NIEUDANA (id=${entity.id}, code=${response.code()})")
                }
                ok
            } catch (e: Exception) {
                Log.e("FetchRemindersWorker", "ROOM: Błąd wysyłki do API", e)
                false
            }
        }
    }

    companion object {
        const val UNIQUE_NAME = "fetch_reminders_loop"

        @JvmStatic
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = androidx.work.PeriodicWorkRequestBuilder<FetchRemindersWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            Log.i("FetchRemindersWorker", "Zaplanuowano cykliczne pobieranie co 15 minut")
        }
    }
}
