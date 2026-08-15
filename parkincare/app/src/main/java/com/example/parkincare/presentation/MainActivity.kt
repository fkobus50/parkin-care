package com.example.parkincare.presentation

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.AlarmManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.parkincare.R
import com.example.parkincare.service.SensorService
import com.example.parkincare.util.ParkinLogger as Log
import com.example.parkincare.work.FetchRemindersWorker
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

fun restartSensorService(context: Context) {
    ContextCompat.startForegroundService(context, android.content.Intent(context, SensorService::class.java))
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    private val basePermissions = mutableListOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.RECORD_AUDIO
    )

    private fun ensureRuntimePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        permissionsToRequest += basePermissions

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest += Manifest.permission.ACTIVITY_RECOGNITION
        }
        val notGranted = permissionsToRequest.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (notGranted.isNotEmpty()) {
            Log.i("MainActivity", "Poproszę o uprawnienia: ${notGranted.joinToString()}")
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
        } else {
            Log.i("MainActivity", "Wszystkie krytyczne uprawnienia już przyznane")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Nie udało się otworzyć żądania dokładnych alarmów: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureRuntimePermissions()

        getSharedPreferences("reminders_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("debug_adjust_done", false)
            .apply()
        prefs = getSharedPreferences("sensor_prefs", Context.MODE_PRIVATE)

        prefs.edit().apply {
            SensorType.entries.forEach { putBoolean(it.key, it != SensorType.AUDIO) }
        }.apply()

        restartSensorService(this)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val immediate = OneTimeWorkRequestBuilder<FetchRemindersWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                com.example.parkincare.work.FetchRemindersWorker.UNIQUE_NAME + "_immediate", // jednorazowe pobranie przypomnień
                ExistingWorkPolicy.KEEP,
                immediate
            )

        FetchRemindersWorker.schedulePeriodic(this) // cykliczne pobieranie przypomnień
        setContent { MainScreen(prefs) }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i("MainActivity", "Wszytkie żądane uprawnienia przyznane")
                restartSensorService(this)
            } else {
                val denied = permissions.zip(grantResults.toTypedArray().toList()).filter { it.second != PackageManager.PERMISSION_GRANTED }.map { it.first }
                Log.w("MainActivity", "Niektóre uprawnienia zostały odmówione: ${denied.joinToString()}")
                if (denied.contains(Manifest.permission.POST_NOTIFICATIONS)) {
                    Log.w("MainActivity", "Brak zgody POST_NOTIFICATIONS — powiadomienia będą niespójne na Android 13+")
                }
            }
        }
    }
}

private fun replaceGroupStatus(original: String?, targetIndex: Int, newStatus: String): String {
    val orig = original ?: return ""
    val groups = orig.split("\n\n").toMutableList()
    if (targetIndex < 0 || targetIndex >= groups.size) return orig
    val lines = groups[targetIndex].lines().map { line ->
        if (line.isBlank()) line else {
            if (line.trim().matches(Regex(".*\\[.*]\\s*$"))) {
                line.replace(Regex("\\s*\\[.*]\\s*$"), " [${newStatus}]")
            } else {
                line + " [${newStatus}]"
            }
        }
    }
    groups[targetIndex] = lines.joinToString("\n")
    return groups.joinToString("\n\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val serverStatus = remember { mutableStateOf(false) }
    val saveLocalOnly = remember { mutableStateOf(prefs.getBoolean("save_local_only", false)) }
    var showMedicines by remember { mutableStateOf(false) }

    val sensorStates = remember {
        mutableStateMapOf(
            *SensorType.entries.filter { it.showInGui }.map {
                it to prefs.getBoolean(it.key, true)
            }.toTypedArray()
        )
    }

    val fileManager = remember { com.example.parkincare.util.FileManager(context) }
    val sensorFilesSize = remember { mutableStateOf(fileManager.getSensorFilesSize()) }
    val availableStorage = remember { mutableStateOf(fileManager.getAvailableStorage()) }
    val minFreeSpaceBytes = 50L * 1024 * 1024 // 50 MB zapasu dla OS

    LaunchedEffect(Unit) {
        while (true) {
            serverStatus.value = SensorService.serverConnected
            delay(5000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            sensorFilesSize.value = fileManager.getSensorFilesSize()
            availableStorage.value = fileManager.getAvailableStorage()
            delay(5000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AutoResizeText(
            text = stringResource(R.string.app_name),
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp),
            maxFontSize = 14.sp
        )

        // Sekcja podsumowania
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(Color.Transparent)
        ) {
            Text(
                text = if (serverStatus.value) stringResource(R.string.server_available) else stringResource(
                    R.string.server_unavailable
                ),
                color = if (serverStatus.value) Color(0xFF4CAF50) else Color(0xFFD32F2F),
                modifier = Modifier.padding(bottom = 4.dp),
                fontSize = 10.sp
            )
            Text(
                text = when {
                    (saveLocalOnly.value || !serverStatus.value) && availableStorage.value < minFreeSpaceBytes -> stringResource(R.string.low_free_space)
                    saveLocalOnly.value || !serverStatus.value -> stringResource(R.string.save_on_watch)
                    else -> stringResource(R.string.save_on_server)
                },
                color = when {
                    (saveLocalOnly.value || !serverStatus.value) && availableStorage.value < minFreeSpaceBytes -> Color(
                        0xFFD32F2F
                    )
                    saveLocalOnly.value || !serverStatus.value -> Color(0xFFFFEB3B)
                    else -> Color(0xFF4CAF50)
                },
                modifier = Modifier.padding(bottom = 4.dp),
                fontSize = 10.sp
            )
            val devicePrefs =
                remember { context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE) }
            val patientIdKey = "PATIENT_ID"
            var patientDisplay by remember {
                mutableStateOf(
                    devicePrefs.getString(patientIdKey, null)
                        ?: com.example.parkincare.BuildConfig.PATIENT_ID.takeIf { it.isNotBlank() }
                        ?: "-"
                )
            }
            DisposableEffect(patientIdKey) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (key == patientIdKey) {
                        val newPid = prefs.getString(patientIdKey, null)
                        patientDisplay = newPid
                            ?: com.example.parkincare.BuildConfig.PATIENT_ID.takeIf { it.isNotBlank() }
                                    ?: "-"
                    }
                }
                devicePrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { devicePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val apiToken = try {
                com.example.parkincare.DeviceCommunicator.getToken(context)
            } catch (e: Exception) {
                null
            }
            val apiTokenKey = apiToken?.trim('/') ?: ""
            Text(
                text = stringResource(R.string.current_patient_label, patientDisplay),
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp),
                fontSize = 10.sp
            )
            val sp =
                remember { context.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE) }
            val nextKey = remember(apiTokenKey) { "next_reminder_${apiTokenKey}" }
            var nextReminderSummary by remember(apiTokenKey) {
                mutableStateOf(
                    sp.getString(
                        nextKey,
                        null
                    )
                )
            }
            DisposableEffect(nextKey) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (key == nextKey) {
                        nextReminderSummary = prefs.getString(nextKey, null)
                    }
                }
                sp.registerOnSharedPreferenceChangeListener(listener)
                onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val nextLines = nextReminderSummary?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (nextLines.isNotEmpty()) {
                val first = nextLines.first()
                val prefix = first.substringBefore(" - ", missingDelimiterValue = "")
                val header =
                    if (prefix.isNotBlank()) "Najbliższe leki (${prefix}):" else stringResource(R.string.next_medicines_label)
                Text(
                    text = header,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                val names = nextLines.map { line ->
                    val afterDash = line.substringAfter(" - ", missingDelimiterValue = line)
                    val name = afterDash.substringBefore(" [", missingDelimiterValue = afterDash)
                    try { Log.i("MainActivity", "PARSE upcoming: raw='$afterDash' -> display='$name'") } catch (_: Exception) {}
                    name
                }
                names.forEach { name ->
                    Text(
                        text = name,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 2.dp),
                        fontSize = 10.sp
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.none_placeholder_medicines),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 4.dp),
                    fontSize = 10.sp
                )
            }
            Text(
                text = stringResource(R.string.medicine_data_warning),
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp),
                fontSize = 10.sp,
                textAlign = TextAlign.Start
            )
            Text(
                text = stringResource(R.string.sensor_count, sensorStates.values.count { it }, sensorStates.size),
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp),
                fontSize = 10.sp
            )
            Text(
                text = stringResource(R.string.storage_occupied, humanReadableSize(sensorFilesSize.value)),
                color = Color.White,
                modifier = Modifier.padding(bottom = 2.dp),
                fontSize = 10.sp
            )
            Text(
                text = stringResource(R.string.free_space, humanReadableSize(availableStorage.value)),
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp),
                fontSize = 10.sp
            )
        }
        HorizontalDivider(
            color = Color.DarkGray,
            thickness = 1.dp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        // Sekcja Konfiguracja
        Button(
            onClick = { showSettings = !showSettings },
            modifier = Modifier.padding(bottom = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            AutoResizeText(
                text = stringResource(R.string.settings_label),
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxFontSize = 14.sp
            )
        }
        if (showSettings) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(R.string.save_on), color = Color.White, modifier = Modifier.padding(end = 8.dp), fontSize = 14.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !saveLocalOnly.value, onClick = { saveLocalOnly.value = false; prefs.edit().putBoolean("save_local_only", false).apply() }, colors = RadioButtonDefaults.colors(selectedColor = Color.White))
                    Column {
                        Text(text = stringResource(R.string.server_radio), color = Color.White, modifier = Modifier.padding(start = 4.dp), fontSize = 14.sp)
                        Text(text = stringResource(R.string.server_radio_hint), color = Color.White, modifier = Modifier.padding(start = 4.dp), fontSize = 14.sp)
                        Text(text = stringResource(R.string.recommended), color = Color.White, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 4.dp), fontSize = 14.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = saveLocalOnly.value, onClick = { saveLocalOnly.value = true; prefs.edit().putBoolean("save_local_only", true).apply() }, colors = RadioButtonDefaults.colors(selectedColor = Color.White))
                    Text(text = stringResource(R.string.watch_radio), color = Color.White, modifier = Modifier.padding(start = 4.dp), fontSize = 14.sp)
                }

                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Button(onClick = {
                        SensorType.entries.filter { it.showInGui }.forEach { sensorStates[it] = true; prefs.edit().putBoolean(it.key, true).apply() }
                        restartSensorService(context)
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        AutoResizeText(text = stringResource(R.string.enable_all), color = Color.White, maxFontSize = 14.sp)
                    }
                    Button(onClick = {
                        SensorType.entries.filter { it.showInGui }.forEach { sensorStates[it] = false; prefs.edit().putBoolean(it.key, false).apply() }
                        restartSensorService(context)
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), modifier = Modifier.fillMaxWidth()) {
                        AutoResizeText(text = stringResource(R.string.disable_all), color = Color.White, maxFontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    SensorType.entries.filter { it.showInGui }.forEach { type ->
                        SensorSwitch(label = stringResource(type.displayNameRes), checked = sensorStates[type] ?: false, onCheckedChange = {
                            sensorStates[type] = it; prefs.edit().putBoolean(type.key, it).apply(); restartSensorService(context)
                        })
                    }
                }
            }
        }
        // Sekcja Leki
        Button(
            onClick = { showMedicines = !showMedicines },
            modifier = Modifier.padding(bottom = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
        ) {
            Icon(Icons.Filled.Medication, contentDescription = "Leki", tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            AutoResizeText(
                text = "Leki",
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxFontSize = 14.sp
            )
        }
        if (showMedicines) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Nadchodzące leki:",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                val sp =
                    remember { context.getSharedPreferences("reminders_gui", Context.MODE_PRIVATE) }
                val apiToken = try {
                    com.example.parkincare.DeviceCommunicator.getToken(context)
                } catch (_: Exception) {
                    null
                }
                val apiTokenKey = apiToken?.trim('/') ?: ""
                val upcomingKey = remember(apiTokenKey) { "upcoming_reminders_${apiTokenKey}" }
                var upcomingSummary by remember(apiTokenKey) {
                    mutableStateOf(
                        sp.getString(
                            upcomingKey,
                            null
                        )
                    )
                }
                DisposableEffect(upcomingKey) {
                    val listener =
                        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                            if (key == upcomingKey) {
                                upcomingSummary = prefs.getString(upcomingKey, null)
                            }
                        }
                    sp.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
                }
                val groups = upcomingSummary?.split("\n\n")
                    ?.map { it.lines().filter { ln -> ln.isNotBlank() } }
                    ?.filter { it.isNotEmpty() } ?: emptyList()
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.none_placeholder),
                        color = Color.White,
                        fontSize = 10.sp
                    )
                } else {
                    groups.forEachIndexed { index, grp ->
                        val first = grp.first()
                        val prefix = first.substringBefore(" - ", missingDelimiterValue = "")
                        val header =
                            if (prefix.isNotBlank()) "${index + 1}. ${prefix}:" else "${index + 1}."
                        // Pogrub tylko część prefix (data/godzina) w nagłówku grupy
                        Text(
                            text = buildAnnotatedString {
                                append("${index + 1}. ")
                                if (prefix.isNotBlank()) {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(prefix) }
                                    append(":")
                                }
                            },
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        val names = grp.map { line ->
                            val afterDash = line.substringAfter(" - ", missingDelimiterValue = line)
                            // Zachowaj dawkę + nazwę: odetnij tylko ewentualny status w nawiasie kwadratowym
                            val name = afterDash.substringBefore(" [", missingDelimiterValue = afterDash)
                            try { Log.i("MainActivity", "PARSE upcoming group: raw='$afterDash' -> display='$name'") } catch (_: Exception) {}
                            name
                        }
                        names.forEach { name ->
                            Text(
                                text = name,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        HorizontalDivider(
                            color = Color.DarkGray,
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "Poprzednie leki:",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    val spPast =
                        remember {
                            context.getSharedPreferences(
                                "reminders_gui",
                                Context.MODE_PRIVATE
                            )
                        }
                    val apiTokenPast = try {
                        com.example.parkincare.DeviceCommunicator.getToken(context)
                    } catch (_: Exception) {
                        null
                    }
                    val apiTokenKeyPast = apiTokenPast?.trim('/') ?: ""
                    val pastKey = remember(apiTokenKeyPast) { "past_reminders_${apiTokenKeyPast}" }
                    var pastSummary by remember(apiTokenKeyPast) {
                        mutableStateOf(
                            spPast.getString(
                                pastKey,
                                null
                            )
                        )
                    }
                    DisposableEffect(pastKey) {
                        val listener =
                            SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                                if (key == pastKey) {
                                    pastSummary = prefs.getString(pastKey, null)
                                }
                            }
                        spPast.registerOnSharedPreferenceChangeListener(listener)
                        onDispose { spPast.unregisterOnSharedPreferenceChangeListener(listener) }
                    }
                    val pastGroups =
                        pastSummary?.split("\n\n")
                            ?.map { it.lines().filter { ln -> ln.isNotBlank() } }
                            ?.filter { it.isNotEmpty() } ?: emptyList()
                    if (pastGroups.isEmpty()) {
                        Text(
                            text = stringResource(R.string.none_placeholder),
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    } else {
                        pastGroups.forEachIndexed { index, grp ->
                            val first = grp.first()
                            val prefix = first.substringBefore(" - ", missingDelimiterValue = "")
                            val statusRaw = first.substringAfter("[", missingDelimiterValue = "")
                                .substringBefore("]", missingDelimiterValue = "")
                            val statusBase = statusRaw.substringBefore(";").trim()
                            val statusText = when (statusBase.lowercase()) {
                                "przyjęte" -> "przyjęte"
                                "nieprzyjęte" -> "nieprzyjęte"
                                "niewyświetlone" -> "niewyświetlone"
                                else -> ""
                            }
                            Text(
                                text = buildAnnotatedString {
                                    append("${index + 1}. ")
                                    if (prefix.isNotBlank()) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(prefix) }
                                    }
                                    if (statusText.isNotBlank()) {
                                        append(" - ${statusText}")
                                    }
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )

                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                if (statusText == "niewyświetlone") {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        if (statusText == "niewyświetlone") {
                                            Button(
                                                onClick = {
                                                    // reuse przyjęte handler (analogiczny do powyżej)
                                                    val ctx = context
                                                    (ctx as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                                                        try {
                                                            val db =
                                                                com.example.parkincare.data.local.DatabaseProvider.getDatabase(
                                                                    ctx
                                                                )
                                                            val dao = db.medicineHistoryDao()
                                                            val patientIdFromToken = try {
                                                                com.example.parkincare.DeviceCommunicator.getToken(
                                                                    ctx
                                                                )
                                                            } catch (_: Exception) {
                                                                null
                                                            }
                                                            val patientId = when {
                                                                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim(
                                                                    '/'
                                                                )

                                                                com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim(
                                                                    '/'
                                                                )

                                                                else -> ""
                                                            }
                                                            val all = try {
                                                                withContext(Dispatchers.IO) {
                                                                    dao.getAllForPatient(
                                                                        patientId
                                                                    )
                                                                }
                                                            } catch (_: Exception) {
                                                                emptyList()
                                                            }
                                                            val formats = listOf(
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss.SSS",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm",
                                                                    java.util.Locale("pl", "PL")
                                                                )
                                                            )
                                                            val pastParsed = all.mapNotNull { e ->
                                                                var millis: Long? = null
                                                                val sd = e.scheduledDate
                                                                for (fmt in formats) {
                                                                    try {
                                                                        val d =
                                                                            fmt.parse(sd); if (d != null) {
                                                                            millis = d.time; break
                                                                        }
                                                                    } catch (_: Exception) {
                                                                    }
                                                                }
                                                                millis?.let { Pair(e, it) }
                                                            }
                                                                .filter { it.second <= System.currentTimeMillis() }
                                                            val byTimePast =
                                                                pastParsed.sortedByDescending { it.second }
                                                                    .toMutableList()
                                                            val groupsPast: MutableList<List<Pair<com.example.parkincare.data.local.MedicineHistoryEntity, Long>>> =
                                                                mutableListOf()
                                                            while (byTimePast.isNotEmpty() && groupsPast.size < 1000) {
                                                                val base = byTimePast.first().second
                                                                val thisGroup =
                                                                    byTimePast.takeWhile {
                                                                        kotlin.math.abs(it.second - base) <= 60_000L
                                                                    }
                                                                groupsPast.add(thisGroup)
                                                                repeat(thisGroup.size) {
                                                                    byTimePast.removeAt(
                                                                        0
                                                                    )
                                                                }
                                                            }
                                                            if (index >= 0 && index < groupsPast.size) {
                                                                val ids =
                                                                    groupsPast[index].mapNotNull { it.first.id }
                                                                if (ids.isNotEmpty()) {
                                                                    val fmt =
                                                                        java.text.SimpleDateFormat(
                                                                            "yyyy-MM-dd HH:mm:ss.SSS",
                                                                            java.util.Locale(
                                                                                "pl",
                                                                                "PL"
                                                                            )
                                                                        )
                                                                    val nowStr = fmt.format(
                                                                        java.util.Date(System.currentTimeMillis())
                                                                    )
                                                                    try {
                                                                        dao.updateStatusForIds(
                                                                            ids,
                                                                            taken = true,
                                                                            shown = true,
                                                                            executionDate = nowStr,
                                                                            notificationDate = nowStr,
                                                                            sent = false
                                                                        )
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "ROOM: błąd updateStatusForIds: ${e.message}"
                                                                        )
                                                                    }
                                                                    try {
                                                                        val data =
                                                                            androidx.work.Data.Builder()
                                                                                .putString(
                                                                                    com.example.parkincare.work.SendMedicineHistoryWorker.DATA_IDS,
                                                                                    ids.joinToString(
                                                                                        ";"
                                                                                    )
                                                                                ).build()
                                                                        val constraints =
                                                                            androidx.work.Constraints.Builder()
                                                                                .setRequiredNetworkType(
                                                                                    androidx.work.NetworkType.CONNECTED
                                                                                ).build()
                                                                        val request =
                                                                            androidx.work.OneTimeWorkRequestBuilder<com.example.parkincare.work.SendMedicineHistoryWorker>()
                                                                                .setInputData(data)
                                                                                .setConstraints(
                                                                                    constraints
                                                                                ).build()
                                                                        androidx.work.WorkManager.getInstance(
                                                                            ctx
                                                                        ).enqueue(request)
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "Nie udało się enqueue SendMedicineHistoryWorker: ${e.message}"
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w(
                                                                "MainActivity",
                                                                "Wyjątek podczas obsługi przycisku przyjęte: ${e.message}"
                                                            )
                                                        }
                                                    }
                                                    val newText = replaceGroupStatus(
                                                        pastSummary,
                                                        index,
                                                        "przyjęte; wysyłanie"
                                                    )
                                                    spPast.edit().putString(pastKey, newText)
                                                        .apply()
                                                    pastSummary = newText
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(
                                                        0xFF4CAF50
                                                    )
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(bottom = 8.dp)
                                            ) {
                                                Text(
                                                    text = "Zmień na przyjęte",
                                                    color = Color.Black
                                                )
                                            }
                                        }
                                        if (statusText == "niewyświetlone") {
                                            Button(
                                                onClick = {
                                                    val ctx = context
                                                    (ctx as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                                                        try {
                                                            val db =
                                                                com.example.parkincare.data.local.DatabaseProvider.getDatabase(
                                                                    ctx
                                                                )
                                                            val dao = db.medicineHistoryDao()
                                                            val patientIdFromToken = try {
                                                                com.example.parkincare.DeviceCommunicator.getToken(
                                                                    ctx
                                                                )
                                                            } catch (_: Exception) {
                                                                null
                                                            }
                                                            val patientId = when {
                                                                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim(
                                                                    '/'
                                                                )

                                                                com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim(
                                                                    '/'
                                                                )

                                                                else -> ""
                                                            }
                                                            val all = try {
                                                                withContext(Dispatchers.IO) {
                                                                    dao.getAllForPatient(
                                                                        patientId
                                                                    )
                                                                }
                                                            } catch (_: Exception) {
                                                                emptyList()
                                                            }
                                                            val formats = listOf(
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss.SSS",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm",
                                                                    java.util.Locale("pl", "PL")
                                                                )
                                                            )
                                                            val pastParsed = all.mapNotNull { e ->
                                                                var millis: Long? = null
                                                                val sd = e.scheduledDate
                                                                for (fmt in formats) {
                                                                    try {
                                                                        val d =
                                                                            fmt.parse(sd); if (d != null) {
                                                                            millis = d.time; break
                                                                        }
                                                                    } catch (_: Exception) {
                                                                    }
                                                                }
                                                                millis?.let { Pair(e, it) }
                                                            }
                                                                .filter { it.second <= System.currentTimeMillis() }
                                                            val byTimePast =
                                                                pastParsed.sortedByDescending { it.second }
                                                                    .toMutableList()
                                                            val groupsPast: MutableList<List<Pair<com.example.parkincare.data.local.MedicineHistoryEntity, Long>>> =
                                                                mutableListOf()
                                                            while (byTimePast.isNotEmpty() && groupsPast.size < 1000) {
                                                                val base = byTimePast.first().second
                                                                val thisGroup =
                                                                    byTimePast.takeWhile {
                                                                        kotlin.math.abs(it.second - base) <= 60_000L
                                                                    }
                                                                groupsPast.add(thisGroup)
                                                                repeat(thisGroup.size) {
                                                                    byTimePast.removeAt(
                                                                        0
                                                                    )
                                                                }
                                                            }
                                                            if (index >= 0 && index < groupsPast.size) {
                                                                val ids =
                                                                    groupsPast[index].mapNotNull { it.first.id }
                                                                if (ids.isNotEmpty()) {
                                                                    val fmt =
                                                                        java.text.SimpleDateFormat(
                                                                            "yyyy-MM-dd HH:mm:ss.SSS",
                                                                            java.util.Locale(
                                                                                "pl",
                                                                                "PL"
                                                                            )
                                                                        )
                                                                    val nowStr = fmt.format(
                                                                        java.util.Date(System.currentTimeMillis())
                                                                    )
                                                                    try {
                                                                        dao.updateStatusForIds(
                                                                            ids,
                                                                            taken = false,
                                                                            shown = true,
                                                                            executionDate = nowStr,
                                                                            notificationDate = nowStr,
                                                                            sent = false
                                                                        )
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "ROOM: błąd updateStatusForIds: ${e.message}"
                                                                        )
                                                                    }
                                                                    try {
                                                                        val data =
                                                                            androidx.work.Data.Builder()
                                                                                .putString(
                                                                                    com.example.parkincare.work.SendMedicineHistoryWorker.DATA_IDS,
                                                                                    ids.joinToString(
                                                                                        ";"
                                                                                    )
                                                                                ).build();
                                                                        val constraints =
                                                                            androidx.work.Constraints.Builder()
                                                                                .setRequiredNetworkType(
                                                                                    androidx.work.NetworkType.CONNECTED
                                                                                ).build();
                                                                        val request =
                                                                            androidx.work.OneTimeWorkRequestBuilder<com.example.parkincare.work.SendMedicineHistoryWorker>()
                                                                                .setInputData(data)
                                                                                .setConstraints(
                                                                                    constraints
                                                                                )
                                                                                .build(); androidx.work.WorkManager.getInstance(
                                                                            ctx
                                                                        ).enqueue(request)
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "Nie udało się enqueue SendMedicineHistoryWorker: ${e.message}"
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w(
                                                                "MainActivity",
                                                                "Wyjątek podczas obsługi przycisku nieprzyjete: ${e.message}"
                                                            )
                                                        }
                                                    }
                                                    val newText = replaceGroupStatus(
                                                        pastSummary,
                                                        index,
                                                        "nieprzyjęte; wysyłanie"
                                                    )
                                                    spPast.edit().putString(pastKey, newText)
                                                        .apply()
                                                    pastSummary = newText
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(
                                                        0xFFFFEB3B
                                                    )
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "Zmień na nieprzyjęte",
                                                    color = Color.Black
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        if (statusText == "nieprzyjęte") {
                                            Button(
                                                onClick = {
                                                    val ctx = context
                                                    (ctx as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                                                        try {
                                                            val db =
                                                                com.example.parkincare.data.local.DatabaseProvider.getDatabase(
                                                                    ctx
                                                                )
                                                            val dao = db.medicineHistoryDao()
                                                            val patientIdFromToken = try {
                                                                com.example.parkincare.DeviceCommunicator.getToken(
                                                                    ctx
                                                                )
                                                            } catch (_: Exception) {
                                                                null
                                                            }
                                                            val patientId = when {
                                                                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim(
                                                                    '/'
                                                                )

                                                                com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim(
                                                                    '/'
                                                                )

                                                                else -> ""
                                                            }
                                                            val all = try {
                                                                withContext(Dispatchers.IO) {
                                                                    dao.getAllForPatient(
                                                                        patientId
                                                                    )
                                                                }
                                                            } catch (_: Exception) {
                                                                emptyList()
                                                            }
                                                            val formats = listOf(
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss.SSS",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm",
                                                                    java.util.Locale("pl", "PL")
                                                                )
                                                            )
                                                            val pastParsed = all.mapNotNull { e ->
                                                                var millis: Long? = null
                                                                val sd = e.scheduledDate
                                                                for (fmt in formats) {
                                                                    try {
                                                                        val d =
                                                                            fmt.parse(sd); if (d != null) {
                                                                            millis = d.time; break
                                                                        }
                                                                    } catch (_: Exception) {
                                                                    }
                                                                }
                                                                millis?.let { Pair(e, it) }
                                                            }
                                                                .filter { it.second <= System.currentTimeMillis() }
                                                            val byTimePast =
                                                                pastParsed.sortedByDescending { it.second }
                                                                    .toMutableList()
                                                            val groupsPast: MutableList<List<Pair<com.example.parkincare.data.local.MedicineHistoryEntity, Long>>> =
                                                                mutableListOf()
                                                            while (byTimePast.isNotEmpty() && groupsPast.size < 1000) {
                                                                val base = byTimePast.first().second
                                                                val thisGroup =
                                                                    byTimePast.takeWhile {
                                                                        kotlin.math.abs(it.second - base) <= 60_000L
                                                                    }
                                                                groupsPast.add(thisGroup)
                                                                repeat(thisGroup.size) {
                                                                    byTimePast.removeAt(
                                                                        0
                                                                    )
                                                                }
                                                            }
                                                            if (index >= 0 && index < groupsPast.size) {
                                                                val ids =
                                                                    groupsPast[index].mapNotNull { it.first.id }
                                                                if (ids.isNotEmpty()) {
                                                                    val fmt =
                                                                        java.text.SimpleDateFormat(
                                                                            "yyyy-MM-dd HH:mm:ss.SSS",
                                                                            java.util.Locale(
                                                                                "pl",
                                                                                "PL"
                                                                            )
                                                                        )
                                                                    val nowStr = fmt.format(
                                                                        java.util.Date(System.currentTimeMillis())
                                                                    )
                                                                    try {
                                                                        dao.updateStatusForIds(
                                                                            ids,
                                                                            taken = true,
                                                                            shown = true,
                                                                            executionDate = nowStr,
                                                                            notificationDate = nowStr,
                                                                            sent = false
                                                                        )
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "ROOM: błąd updateStatusForIds: ${e.message}"
                                                                        )
                                                                    }
                                                                    try {
                                                                        val data =
                                                                            androidx.work.Data.Builder()
                                                                                .putString(
                                                                                    com.example.parkincare.work.SendMedicineHistoryWorker.DATA_IDS,
                                                                                    ids.joinToString(
                                                                                        ";"
                                                                                    )
                                                                                ).build()
                                                                        val constraints =
                                                                            androidx.work.Constraints.Builder()
                                                                                .setRequiredNetworkType(
                                                                                    androidx.work.NetworkType.CONNECTED
                                                                                ).build()
                                                                        val request =
                                                                            androidx.work.OneTimeWorkRequestBuilder<com.example.parkincare.work.SendMedicineHistoryWorker>()
                                                                                .setInputData(data)
                                                                                .setConstraints(
                                                                                    constraints
                                                                                ).build()
                                                                        androidx.work.WorkManager.getInstance(
                                                                            ctx
                                                                        ).enqueue(request)
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "Nie udało się enqueue SendMedicineHistoryWorker: ${e.message}"
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w(
                                                                "MainActivity",
                                                                "Wyjątek podczas obsługi przycisku przyjęte: ${e.message}"
                                                            )
                                                        }
                                                    }
                                                    val newText = replaceGroupStatus(
                                                        pastSummary,
                                                        index,
                                                        "przyjęte; wysyłanie"
                                                    )
                                                    spPast.edit().putString(pastKey, newText)
                                                        .apply()
                                                    pastSummary = newText
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(
                                                        0xFF4CAF50
                                                    )
                                                ),
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text(
                                                    text = "Zmień na przyjęte",
                                                    color = Color.Black
                                                )
                                            }
                                        }

                                        if (statusText == "niewyświetlone") {
                                            // intentionally left - handled in vertical branch
                                        }

                                        if (statusText == "przyjęte") {
                                            Button(
                                                onClick = {
                                                    val ctx = context
                                                    (ctx as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                                                        try {
                                                            val db =
                                                                com.example.parkincare.data.local.DatabaseProvider.getDatabase(
                                                                    ctx
                                                                )
                                                            val dao = db.medicineHistoryDao()
                                                            val patientIdFromToken = try {
                                                                com.example.parkincare.DeviceCommunicator.getToken(
                                                                    ctx
                                                                )
                                                            } catch (_: Exception) {
                                                                null
                                                            }
                                                            val patientId = when {
                                                                !patientIdFromToken.isNullOrBlank() -> patientIdFromToken.trim(
                                                                    '/'
                                                                )

                                                                com.example.parkincare.BuildConfig.PATIENT_ID.isNotBlank() -> com.example.parkincare.BuildConfig.PATIENT_ID.trim(
                                                                    '/'
                                                                )

                                                                else -> ""
                                                            }
                                                            val all = try {
                                                                withContext(Dispatchers.IO) {
                                                                    dao.getAllForPatient(
                                                                        patientId
                                                                    )
                                                                }
                                                            } catch (_: Exception) {
                                                                emptyList()
                                                            }
                                                            val formats = listOf(
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss.SSS",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm:ss",
                                                                    java.util.Locale("pl", "PL")
                                                                ),
                                                                java.text.SimpleDateFormat(
                                                                    "yyyy-MM-dd HH:mm",
                                                                    java.util.Locale("pl", "PL")
                                                                )
                                                            )
                                                            val pastParsed = all.mapNotNull { e ->
                                                                var millis: Long? = null
                                                                val sd = e.scheduledDate
                                                                for (fmt in formats) {
                                                                    try {
                                                                        val d =
                                                                            fmt.parse(sd); if (d != null) {
                                                                            millis = d.time; break
                                                                        }
                                                                    } catch (_: Exception) {
                                                                    }
                                                                }
                                                                millis?.let { Pair(e, it) }
                                                            }
                                                                .filter { it.second <= System.currentTimeMillis() }
                                                            val byTimePast =
                                                                pastParsed.sortedByDescending { it.second }
                                                                    .toMutableList()
                                                            val groupsPast: MutableList<List<Pair<com.example.parkincare.data.local.MedicineHistoryEntity, Long>>> =
                                                                mutableListOf()
                                                            while (byTimePast.isNotEmpty() && groupsPast.size < 1000) {
                                                                val base = byTimePast.first().second
                                                                val thisGroup =
                                                                    byTimePast.takeWhile {
                                                                        kotlin.math.abs(it.second - base) <= 60_000L
                                                                    }
                                                                groupsPast.add(thisGroup)
                                                                repeat(thisGroup.size) {
                                                                    byTimePast.removeAt(
                                                                        0
                                                                    )
                                                                }
                                                            }
                                                            if (index >= 0 && index < groupsPast.size) {
                                                                val ids =
                                                                    groupsPast[index].mapNotNull { it.first.id }
                                                                if (ids.isNotEmpty()) {
                                                                    val fmt =
                                                                        java.text.SimpleDateFormat(
                                                                            "yyyy-MM-dd HH:mm:ss.SSS",
                                                                            java.util.Locale(
                                                                                "pl",
                                                                                "PL"
                                                                            )
                                                                        )
                                                                    val nowStr = fmt.format(
                                                                        java.util.Date(System.currentTimeMillis())
                                                                    )
                                                                    try {
                                                                        dao.updateStatusForIds(
                                                                            ids,
                                                                            taken = false,
                                                                            shown = true,
                                                                            executionDate = nowStr,
                                                                            notificationDate = nowStr,
                                                                            sent = false
                                                                        )
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "ROOM: błąd updateStatusForIds: ${e.message}"
                                                                        )
                                                                    }
                                                                    try {
                                                                        val data =
                                                                            androidx.work.Data.Builder()
                                                                                .putString(
                                                                                    com.example.parkincare.work.SendMedicineHistoryWorker.DATA_IDS,
                                                                                    ids.joinToString(
                                                                                        ";"
                                                                                    )
                                                                                ).build()
                                                                        val constraints =
                                                                            androidx.work.Constraints.Builder()
                                                                                .setRequiredNetworkType(
                                                                                    androidx.work.NetworkType.CONNECTED
                                                                                ).build()
                                                                        val request =
                                                                            androidx.work.OneTimeWorkRequestBuilder<com.example.parkincare.work.SendMedicineHistoryWorker>()
                                                                                .setInputData(data)
                                                                                .setConstraints(
                                                                                    constraints
                                                                                ).build()
                                                                        androidx.work.WorkManager.getInstance(
                                                                            ctx
                                                                        ).enqueue(request)
                                                                    } catch (e: Exception) {
                                                                        Log.w(
                                                                            "MainActivity",
                                                                            "Nie udało się enqueue SendMedicineHistoryWorker: ${e.message}"
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w(
                                                                "MainActivity",
                                                                "Wyjątek podczas obsługi przycisku nieprzyjete: ${e.message}"
                                                            )
                                                        }
                                                    }
                                                    val newText = replaceGroupStatus(
                                                        pastSummary,
                                                        index,
                                                        "nieprzyjęte; wysyłanie"
                                                    )
                                                    spPast.edit().putString(pastKey, newText)
                                                        .apply()
                                                    pastSummary = newText
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(
                                                        0xFFFFEB3B
                                                    )
                                                ),
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text(
                                                    text = "Zmień na nieprzyjęte",
                                                    color = Color.Black
                                                )
                                            }
                                        }
                                    }

                                    val namesWithStatus = grp.map { line ->
                                        val afterDash =
                                            line.substringAfter(" - ", missingDelimiterValue = line)
                                        val name = afterDash.substringBefore(" [", missingDelimiterValue = afterDash)
                                        try { Log.i("MainActivity", "PARSE past group: raw='$afterDash' -> display='$name'") } catch (_: Exception) {}
                                        name
                                    }
                                    namesWithStatus.forEach { item ->
                                        Text(
                                            text = item,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    color = Color.DarkGray,
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

