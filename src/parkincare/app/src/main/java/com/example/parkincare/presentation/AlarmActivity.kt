package com.example.parkincare.presentation

import android.annotation.SuppressLint
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.parkincare.R
import com.example.parkincare.AlarmData
import com.example.parkincare.AlarmStorage
import com.example.parkincare.util.ParkinLogger as Log
import androidx.activity.OnBackPressedCallback

class AlarmActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val medicineList = intent.getStringExtra("medicineList") ?: ""
        val scheduledDate = intent.getStringExtra("scheduledDate") ?: ""
        val idsList = intent.getStringExtra("idsList") ?: ""
        val medicineIdsList = intent.getStringExtra("medicineIdsList") ?: ""
        val doseList = intent.getStringExtra("doseList") ?: ""

        // Zapisz alarm do SharedPreferences
        AlarmStorage.saveAlarm(this, AlarmData(medicineList, scheduledDate, idsList, medicineIdsList, doseList))

        // Wyciągnij godzinę z daty
        val hourMinute = try {
            val parts = scheduledDate.split(" ")
            if (parts.size > 1) parts[1] else scheduledDate
        } catch (e: Exception) {
            scheduledDate
        }

        val medicineLines = medicineList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val doseLines = doseList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val displayLines = medicineLines.mapIndexed { index, med ->
            val dose = doseLines.getOrNull(index).orEmpty()
            if (dose.isNotEmpty()) "${dose} x $med" else med
        }

        setContent {
            AlarmScreen(
                hourMinute = hourMinute,
                medicineLines = displayLines,
                onAccept = {
                    stopAlarm()
                    val intent = Intent(this, ReminderReceiver::class.java).apply {
                        action = ReminderReceiver.ACTION_ACCEPT
                        putExtra("idsList", idsList)
                        putExtra("medicineIdsList", medicineIdsList)
                        putExtra("medicineList", medicineList)
                        putExtra("doseList", doseList)
                        putExtra("scheduledDate", scheduledDate)
                    }
                    sendBroadcast(intent)
                    finish()
                },
                onReject = {
                    stopAlarm()
                    val intent = Intent(this, ReminderReceiver::class.java).apply {
                        action = ReminderReceiver.ACTION_REJECT
                        putExtra("idsList", idsList)
                        putExtra("medicineIdsList", medicineIdsList)
                        putExtra("medicineList", medicineList)
                        putExtra("doseList", doseList)
                        putExtra("scheduledDate", scheduledDate)
                    }
                    sendBroadcast(intent)
                    finish()
                }
            )
        }

        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, alarmUri)
        ringtone?.play()

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 1000, 1000)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            vibrator?.vibrate(pattern, 0)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stopAlarm()
                AlarmQueue.clearActive()
                Log.i("AlarmActivity", "onBackPressedDispatcher: zatrzymano alarm i zresetowano AlarmQueue.isAlarmActive")
                finish()
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    private fun stopAlarm() {
        ringtone?.stop()
        vibrator?.cancel()
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
        AlarmQueue.clearActive()
        AlarmQueue.launchNextAlarm(this)
    }

    override fun onStart() {
        super.onStart()
        AlarmQueue.setActive()
        Log.i("AlarmActivity", "onStart: AlarmQueue.setActive()")
    }

    override fun onStop() {
        super.onStop()
        AlarmQueue.clearActive()
        Log.i("AlarmActivity", "onStop: AlarmQueue.clearActive()")
    }
}

@Composable
fun AlarmScreen(
    hourMinute: String,
    medicineLines: List<String>,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$hourMinute - " + stringResource(R.string.medicine_alarm_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            for (medicine in medicineLines) {
                val fontSize = if (medicine.length > 22) 10.sp else 12.sp
                Text(
                    text = medicine,
                    color = Color.White,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF669900) // holo_green_dark
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.accept_symbol),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onReject,
                    modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCC0000) // holo_red_dark
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
