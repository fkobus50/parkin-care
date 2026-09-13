package com.example.parkincare.presentation

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.parkincare.R
import com.example.parkincare.util.ParkinLogger as Log
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

object AlarmQueue {
    private val groupedAlarms = mutableMapOf<Long, MutableList<AlarmData>>()
    @Volatile
    var isAlarmActive: Boolean = false

    fun setActive() { isAlarmActive = true }
    fun clearActive() { isAlarmActive = false }

    data class AlarmData(
        val medicineName: String,
        val dose: String,
        val scheduledDate: String,
        val id: String?,
        val executionDate: String?,
        val medicineId: String?
    )

    private fun parseScheduledDateToEpochSeconds(scheduledDate: String): Long? {
        val formats = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        )
        for (fmt in formats) {
            try {
                val d = fmt.parse(scheduledDate)
                if (d != null) return d.time / 1000L
            } catch (ignored: Exception) {
            }
        }

        return try {
            if (scheduledDate.length >= 16) {
                val sub = scheduledDate.substring(0, 16)
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                fmt.parse(sub)?.time?.div(1000L)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Konwersja klucza epochSeconds na czytelny string yyyy-MM-dd HH:mm
    private fun epochSecondsToGroupKey(epochSeconds: Long): String {
        val date = java.util.Date(epochSeconds * 1000L)
        val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return out.format(date)
    }

    fun add(alarm: AlarmData) {
        val epoch = parseScheduledDateToEpochSeconds(alarm.scheduledDate) ?: (System.currentTimeMillis() / 1000L)
        // Szukamy istniejącej grupy leków w przedziale +- 60 sekund
        val existingKey = groupedAlarms.keys.firstOrNull { key -> abs(key - epoch) <= 60L }
        if (existingKey != null) {
            groupedAlarms[existingKey]?.add(alarm)
            Log.i("AlarmQueue", "Dodano do istniejącej grupy: epoch=$epoch, key=$existingKey, medicine=${alarm.medicineName}")
        } else {
            groupedAlarms[epoch] = mutableListOf(alarm)
            Log.i("AlarmQueue", "Utworzono nową grupę: epoch=$epoch, medicine=${alarm.medicineName}")
        }
        Log.i("AlarmQueue", "pendingCount=${pendingCount()}")
    }

    fun pollGroup(): Pair<String, List<AlarmData>>? {
        val entry = groupedAlarms.entries.firstOrNull() ?: return null
        val key = entry.key
        val list = entry.value.toList()
        groupedAlarms.remove(key)
        return epochSecondsToGroupKey(key) to list
    }

    fun isEmpty(): Boolean = groupedAlarms.isEmpty()

    fun pendingCount(): Int {
        synchronized(groupedAlarms) {
            return groupedAlarms.values.sumOf { it.size }
        }
    }

    fun launchNextAlarm(context: Context) {
        if (isAlarmActive) {
            Log.w("AlarmQueue", "launchNextAlarm: alarm już aktywny – przerwano próbę")
            return
        }
        if (isEmpty()) {
            Log.w("AlarmQueue", "launchNextAlarm: kolejka jest pusta (pendingCount=0) – brak czego uruchomić")
            return
        }
        val group = pollGroup()
        if (group == null) {
            Log.w("AlarmQueue", "launchNextAlarm: pollGroup zwrócił null – nic do uruchomienia")
            return
        }
        val groupKey = group.first
        val alarms = group.second
        Log.i("AlarmQueue", "launchNextAlarm: uruchamiam grupę ${alarms.size} leków dla ${groupKey}")

        val directIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra("medicineList", alarms.joinToString(";") { it.medicineName })
            putExtra("scheduledDate", groupKey)
            putExtra("idsList", alarms.mapNotNull { it.id }.joinToString(";"))
            putExtra("medicineIdsList", alarms.mapNotNull { it.medicineId }.joinToString(";"))
            putExtra("doseList", alarms.map { it.dose }.joinToString(";"))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        var activityStarted = false
        val notificationPosted = showFullScreenNotification(context, groupKey, alarms)

        if (notificationPosted) {
            try {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val attempts = listOf(150L, 500L, 1200L)
                attempts.forEachIndexed { idx, delayMs ->
                    handler.postDelayed({
                        try {
                            context.startActivity(directIntent)
                            Log.i("AlarmQueue", "AlarmActivity: próba uruchomienia pełnoekranowej aktywności po notify (startActivity) attempt=${idx + 1} delay=${delayMs}ms")
                            activityStarted = true
                        } catch (e: Exception) {
                            Log.w("AlarmQueue", "Nie udało się uruchomić AlarmActivity po notify attempt=${idx + 1}: ${e.message}")
                        }
                    }, delayMs)
                }
            } catch (e: Exception) {
                Log.w("AlarmQueue", "Błąd podczas opóźnionego startActivity: ${e.message}")
            }
        } else {
            try {
                context.startActivity(directIntent)
                Log.i("AlarmQueue", "AlarmActivity: próba uruchomienia pełnoekranowej aktywności (startActivity)")
                activityStarted = true
            } catch (e: Exception) {
                Log.w("AlarmQueue", "Nie udało się uruchomić AlarmActivity bezpośrednio: ${e.message}")
            }
        }

        if (notificationPosted || activityStarted) {
            val checkDelayMs = 5000L
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (!isAlarmActive) {
                        Log.i("AlarmQueue", "Brak aktywnej AlarmActivity po ${checkDelayMs}ms — zapisuję historię jako niewyświetlone")
                        writeNotShownHistory(context, alarms)
                    } else {
                        Log.i("AlarmQueue", "AlarmActivity jest aktywna — nie zapisuję historii jako niewyświetlone")
                    }
                } catch (e: Exception) {
                    Log.w("AlarmQueue", "Błąd podczas sprawdzania aktywności alarmu: ${e.message}")
                }
            }, checkDelayMs)
        }

        if (!activityStarted && !notificationPosted) {
            showSimpleFallbackNotification(context, groupKey, alarms)
        }
    }

    private fun ensureAlarmChannelExists(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val existing = nm.getNotificationChannel("medicine_alarm_channel")
                if (existing == null) {
                    Log.i("AlarmQueue", "Kanał 'medicine_alarm_channel' nie istnieje — tworzę defensywnie")
                    val alarmChannel = android.app.NotificationChannel(
                        "medicine_alarm_channel",
                        context.getString(R.string.medicine_alarm_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    alarmChannel.enableVibration(true)
                    alarmChannel.setSound(
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                        null
                    )
                    alarmChannel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    alarmChannel.description = context.getString(R.string.medicine_alarm_channel_description)
                    alarmChannel.setShowBadge(true)
                    alarmChannel.setBypassDnd(true)
                    alarmChannel.enableLights(true)
                    nm.createNotificationChannel(alarmChannel)
                    Log.i("AlarmQueue", "Kanał 'medicine_alarm_channel' utworzony defensywnie")
                }
            }
        } catch (e: Exception) {
            Log.w("AlarmQueue", "Nie udało się utworzyć defensywnego kanału alarmu: ${e.message}")
        }
    }

    private fun showFullScreenNotification(context: Context, groupKey: String, alarms: List<AlarmData>): Boolean {
        val notificationId = (groupKey + alarms.joinToString { it.medicineName }).hashCode()

        ensureAlarmChannelExists(context)

        val fsIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra("medicineList", alarms.joinToString(";") { it.medicineName })
            putExtra("scheduledDate", groupKey)
            putExtra("idsList", alarms.mapNotNull { it.id }.joinToString(";"))
            putExtra("medicineIdsList", alarms.mapNotNull { it.medicineId }.joinToString(";"))
            putExtra("doseList", alarms.map { it.dose }.joinToString(";"))
            putExtra("from_fullscreen", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            notificationId,
            fsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hourMinute = try {
            val parts = groupKey.split(" ")
            if (parts.size > 1) parts[1] else groupKey
        } catch (e: Exception) { groupKey }
        val title = "$hourMinute - Czas na lek"
        val contentText = alarms.joinToString("\n") { "${it.dose} x ${it.medicineName}" }
        val wearableExt = NotificationCompat.WearableExtender().setHintShowBackgroundOnly(false)
        val notification = NotificationCompat.Builder(context, "medicine_alarm_channel")
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .extend(wearableExt)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
                Log.i("AlarmQueue", "Powiadomienie opublikowane z fullScreenIntent; system może pokazać HUN lub zwykłe powiadomienie (fallback) w zależności od ustawień")
                true
            } else {
                Log.w("AlarmQueue", "Brak POST_NOTIFICATIONS – nie można opublikować powiadomienia")
                false
            }
        } catch (se: SecurityException) {
            Log.e("AlarmQueue", "SecurityException podczas publikacji fullScreen notification: ${se.message}")
            false
        } catch (e: Exception) {
            Log.e("AlarmQueue", "Błąd publikacji powiadomienia: ${e.message}")
            false
        }
    }

    private fun showSimpleFallbackNotification(context: Context, groupKey: String, alarms: List<AlarmData>) {
        val notificationId = (groupKey + alarms.joinToString { it.medicineName }).hashCode()

        ensureAlarmChannelExists(context)

        fun buildActionPendingIntent(action: String, requestCode: Int): PendingIntent {
            val actionIntent = Intent(context, ReminderReceiver::class.java).apply {
                setAction(action)
                val firstAlarm = alarms.first()
                putExtra("medicineName", firstAlarm.medicineName)
                putExtra("dose", firstAlarm.dose)
                putExtra("scheduledDate", firstAlarm.scheduledDate)
                putExtra("id", firstAlarm.id)
                putExtra("executionDate", firstAlarm.executionDate)
                putExtra("medicineId", firstAlarm.medicineId)
            }
            return PendingIntent.getBroadcast(
                context, requestCode, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val acceptPendingIntent = buildActionPendingIntent(ReminderReceiver.ACTION_ACCEPT, notificationId)
        val rejectPendingIntent = buildActionPendingIntent(ReminderReceiver.ACTION_REJECT, notificationId + 1)
        val hourMinute = try {
            val parts = groupKey.split(" ")
            if (parts.size > 1) parts[1] else groupKey
        } catch (e: Exception) { groupKey }
        val title = "$hourMinute - Czas na lek"
        val contentText = alarms.joinToString("\n") { "${it.dose} x ${it.medicineName}" }
        val notification = NotificationCompat.Builder(context, "medicine_alarm_channel")
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(R.drawable.splash_icon, "Akceptuj", acceptPendingIntent)
            .addAction(R.drawable.splash_icon, "Odrzuć", rejectPendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
                Log.i("AlarmQueue", "Fallback: zwykłe powiadomienie pokazane przez NotificationManagerCompat")

                try {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
                            val dao = db.medicineHistoryDao()

                            val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                            val patientId = when {
                                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                                com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim('/')
                                else -> ""
                            }

                            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale("pl", "PL"))
                            val nowStr = fmt.format(java.util.Date(System.currentTimeMillis()))

                            val ids = alarms.mapNotNull { it.id }.filter { it.isNotBlank() }
                            val existing = try { if (ids.isNotEmpty()) dao.getByIds(ids) else emptyList() } catch (_: Exception) { emptyList() }
                            val existingMap = existing.associateBy { it.id }

                            for (alarm in alarms) {
                                val id = alarm.id ?: continue
                                val doseDouble = alarm.dose.toDoubleOrNull() ?: 0.0
                                val already = existingMap[id]
                                if (already != null) {
                                    if (already.taken || already.shown || already.executionDate.isNotBlank()) {
                                        Log.i("AlarmQueue", "SKIP fallback write for id=${id} — already handled: taken=${already.taken}, shown=${already.shown}, exec='${already.executionDate}'")
                                        continue
                                    } else {
                                        try {
                                            val updated = dao.updateNotificationDateIfUnHandled(id, nowStr)
                                            if (updated > 0) {
                                                Log.i("AlarmQueue", "ROOM: zaktualizowano notificationDate dla id=${id}")
                                            } else {
                                                Log.i("AlarmQueue", "SKIP update dla id=${id} — rekord został obsłużony równolegle")
                                            }
                                        } catch (e: Exception) {
                                            Log.w("AlarmQueue", "ROOM: nie udało się zaktualizować notificationDate dla id=${id}: ${e.message}")
                                        }
                                        continue
                                    }
                                }

                                val entity = com.example.parkincare.data.local.MedicineHistoryEntity(
                                    id = id,
                                    patientId = patientId,
                                    scheduledDate = alarm.scheduledDate,
                                    executionDate = "",
                                    notificationDate = nowStr,
                                    dose = doseDouble,
                                    medicineId = alarm.medicineId ?: "",
                                    medicineName = alarm.medicineName,
                                    taken = false,
                                    shown = false,
                                    type = "MedicineHistory",
                                    sent = false
                                )
                                try {
                                    val row = dao.insertIgnore(entity)
                                    if (row == -1L) {
                                        Log.i("AlarmQueue", "insertIgnore conflict for id=${id} (another writer won) — skip")
                                    } else {
                                        Log.i("AlarmQueue", "ROOM: zapisano niewyświetlone powiadomienie do historii id=${entity.id} row=$row")
                                    }
                                } catch (e: Exception) {
                                    Log.w("AlarmQueue", "ROOM: nie udało się zapisać niewyświetlonego wpisu id=${id}: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("AlarmQueue", "ROOM: wyjątek podczas zapisu niewyświetlonych wpisów: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AlarmQueue", "Nie udało się uruchomić zapisu historii w tle: ${e.message}")
                }

            } else {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(notificationId, notification)
                Log.i("AlarmQueue", "Fallback: zwykłe powiadomienie pokazane przez NotificationManager")
            }
        } catch (se: SecurityException) {
            Log.e("AlarmQueue", "SecurityException podczas publikacji fallback notification: ${se.message}")
        } catch (e: Exception) {
            Log.e("AlarmQueue", "Nie udało się pokazać fallback powiadomienia: ${e.message}")
        }
    }

    private fun writeNotShownHistory(context: Context, alarms: List<AlarmData>) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
                    val dao = db.medicineHistoryDao()
                    val patientIdFromToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                    val patientId = when {
                        !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim('/')
                        com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim('/')
                        else -> ""
                    }
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale("pl", "PL"))
                    val nowStr = fmt.format(java.util.Date(System.currentTimeMillis()))

                    val ids = alarms.mapNotNull { it.id }.filter { it.isNotBlank() }
                    val existing = try { if (ids.isNotEmpty()) dao.getByIds(ids) else emptyList() } catch (_: Exception) { emptyList() }
                    val existingMap = existing.associateBy { it.id }

                    for (alarm in alarms) {
                        val id = alarm.id ?: continue
                        val doseDouble = alarm.dose.toDoubleOrNull() ?: 0.0
                        val already = existingMap[id]
                        if (already != null) {
                            if (already.taken || already.shown || already.executionDate.isNotBlank()) {
                                Log.i("AlarmQueue", "SKIP not-shown write for id=${id} — already handled: taken=${already.taken}, shown=${already.shown}, exec='${already.executionDate}'")
                                continue
                            } else {
                                try {
                                    val updated = dao.updateNotificationDateIfUnHandled(id, nowStr)
                                    if (updated > 0) {
                                        Log.i("AlarmQueue", "ROOM: zaktualizowano notificationDate dla id=${id}")
                                    } else {
                                        Log.i("AlarmQueue", "SKIP update dla id=${id} — rekord został obsłużony równolegle")
                                    }
                                } catch (e: Exception) {
                                    Log.w("AlarmQueue", "ROOM: nie udało się zaktualizować notificationDate dla id=${id}: ${e.message}")
                                }
                                continue
                            }
                        }

                        val entity = com.example.parkincare.data.local.MedicineHistoryEntity(
                            id = id,
                            patientId = patientId,
                            scheduledDate = alarm.scheduledDate,
                            executionDate = "",
                            notificationDate = nowStr,
                            dose = doseDouble,
                            medicineId = alarm.medicineId ?: "",
                            medicineName = alarm.medicineName,
                            taken = false,
                            shown = false,
                            type = "MedicineHistory",
                            sent = false
                        )
                        try {
                            val row = dao.insertIgnore(entity)
                            if (row == -1L) {
                                Log.i("AlarmQueue", "insertIgnore conflict for id=${id} (another writer won) — skip")
                            } else {
                                Log.i("AlarmQueue", "ROOM: zapisano niewyświetlone powiadomienie do historii id=${entity.id} row=$row")
                            }
                        } catch (e: Exception) {
                            Log.w("AlarmQueue", "ROOM: nie udało się zapisać niewyświetlonego wpisu id=${id}: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AlarmQueue", "ROOM: wyjątek podczas zapisu niewyświetlonych wpisów: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("AlarmQueue", "Nie udało się uruchomić zapisu historii w tle: ${e.message}")
        }
    }
}