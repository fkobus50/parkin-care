package com.example.parkincare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.parkincare.AlarmStorage
import com.example.parkincare.AlarmData
import android.app.AlarmManager
import android.app.PendingIntent
import com.example.parkincare.presentation.ReminderReceiver
import com.example.parkincare.util.ParkinLogger as Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Uruchomiono po restarcie urządzenia")
            val alarms = AlarmStorage.getAlarms(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (alarm in alarms) {
                val alarmIntent = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra("medicineList", alarm.medicineList)
                    putExtra("scheduledDate", alarm.scheduledDate)
                    putExtra("idsList", alarm.idsList)
                    putExtra("medicineIdsList", alarm.medicineIdsList)
                    putExtra("doseList", alarm.doseList)
                }
                val triggerTime = parseDateToMillis(alarm.scheduledDate)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    alarm.scheduledDate.hashCode(),
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (triggerTime > 0) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } catch (e: SecurityException) {
                        Log.e("BootReceiver", "Brak uprawnień do ustawiania alarmów: ${e.message}")
                    }
                }
            }
        }
    }

    private fun parseDateToMillis(dateStr: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val date = format.parse(dateStr)
            date?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}