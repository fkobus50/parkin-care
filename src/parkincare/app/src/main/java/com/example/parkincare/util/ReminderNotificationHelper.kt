package com.example.parkincare.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.parkincare.model.MedicineHistory
import com.example.parkincare.presentation.ReminderReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.parkincare.util.ParkinLogger as Log
import androidx.core.content.edit

object ReminderNotificationHelper {
    private const val PREF_NAME = "reminders_prefs"
    private const val PLANNED_SET_KEY = "planned_alarm_ids"

    fun getPlannedIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(PLANNED_SET_KEY, emptySet()) ?: emptySet()
    }

    private fun markPlanned(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(PLANNED_SET_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(id)
        prefs.edit { putStringSet(PLANNED_SET_KEY, set) }
    }

    private fun unmarkPlanned(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(PLANNED_SET_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.remove(id)) {
            prefs.edit { putStringSet(PLANNED_SET_KEY, set) }
        }
    }

    fun cancelAlarm(context: Context, id: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        unmarkPlanned(context, id)
        Log.i("ReminderNotification", "Anulowano zaplanowany alarm dla id=$id")
    }

    fun scheduleReminderNotification(context: Context, reminder: MedicineHistory) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val originalTime = try {
            sdf.parse(reminder.scheduledDate)?.time ?: return
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val triggerTime = originalTime

        val plFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("pl", "PL"))
        val formattedTriggerTime = plFormat.format(Date(triggerTime))
        Log.i(
            "ReminderNotification",
            "Dodano przypomnienie o leku: ${reminder.medicineName}, dawka: ${reminder.dose}, na godzinę: $formattedTriggerTime"
        )


        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("medicineName", reminder.medicineName)
            putExtra("dose", reminder.dose.toString())
            putExtra("scheduledDate", reminder.scheduledDate)
            putExtra("id", reminder.id)
            putExtra("executionDate", reminder.executionDate)
            putExtra("medicineId", reminder.medicineId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            try {
                Log.i("ReminderNotification", "Ustawiam alarm na $triggerTime (${Date(triggerTime)}) dla ${reminder.medicineName}")
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                markPlanned(context, reminder.id)
            } catch (e: SecurityException) {
                Log.e("ReminderNotification", "Brak uprawnień do ustawienia dokładnego alarmu", e)
            }
        } else {
            Log.w("ReminderNotification", "Brak uprawnień do ustawienia dokładnych alarmów, alarm nie został ustawiony")
        }

    }
}