package com.example.parkincare.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.example.parkincare.BuildConfig
import com.example.parkincare.presentation.SensorType
import com.example.parkincare.mqtt.MqttManager
import com.example.parkincare.audio.AudioRecorderManager
import com.example.parkincare.util.FileManager
import com.example.parkincare.util.ParkinLogger as Log
import java.io.File
import java.util.ArrayDeque

@RequiresApi(Build.VERSION_CODES.CUPCAKE)
class SensorService : Service(), SensorEventListener {

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var mqttConnected: Boolean = false
            private set
        @Volatile
        var serverConnected: Boolean = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var mqttManager: MqttManager
    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var fileManager: FileManager

    private val sensorTypeToAndroidId = mapOf(
        SensorType.ACCELEROMETER to Sensor.TYPE_ACCELEROMETER,
        SensorType.GYROSCOPE to Sensor.TYPE_GYROSCOPE,
        SensorType.HEART_RATE to Sensor.TYPE_HEART_RATE,
        SensorType.BAROMETER to Sensor.TYPE_PRESSURE,
        SensorType.LIGHT to Sensor.TYPE_LIGHT
        // SensorType.AUDIO nie jest sensorem systemowym
    )
    private val sensors: MutableMap<SensorType, Sensor?> = mutableMapOf()

    private lateinit var handler: Handler
    // Dedykowany HandlerThread dla callbacków sensorów - aby onSensorChanged nie wykonywał się na UI
    private var sensorHandlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    // Dedykowany HandlerThread dla operacji I/O (zapis/odczyt plików, flushy)
    private var fileIoThread: HandlerThread? = null
    private var fileIoHandler: Handler? = null
    private val gson = Gson()

    private var saveBinary: Boolean = true

    private lateinit var saveDataRunnable: Runnable

    private val logIntervalMillis = 30_000L
    private lateinit var logHandler: Handler
    private lateinit var logRunnable: Runnable

    // Liczniki zapisów i wysyłek MQTT dla każdego sensora
    private val fileWriteCounters = mutableMapOf<String, Int>()
    private val mqttSendCounters = mutableMapOf<String, Int>()
    private var audioFileWriteCounter = 0
    private var audioMqttSendCounter = 0

    private var lastSaveLocalOnly: Boolean? = null

    // Bufory minutowe dla każdego sensora
    private val sensorMinuteBuffers = mutableMapOf<String, MutableList<Pair<Long, Any>>>()
    private val sensorMinuteBufferStart = mutableMapOf<String, Long>()
    private val minuteBufferInterval = 60_000L // 1 minuta

    private var wakeLock: PowerManager.WakeLock? = null

    private var heartbeatUnavailable: Boolean = true // domyślnie true, aby nie wysyłać danych przed pierwszym sprawdzeniem heartbeat
    private var lastBufferLogTs: Long = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private data class DiagnosticSample(
        val ts: Long,
        val usedHeap: Long,
        val maxHeap: Long,
        val nativeHeap: Long,
        val threadCount: Int,
        val fdCount: Int,
        val audioBytes: Long,
        val logBytes: Long,
        val availFs: Long
    )

    private val diagHistory = ArrayDeque<DiagnosticSample>()
    private val diagMaxHistory = 60

    private val wakelockCheckInterval = 5 * 60_000L
    private val wakelockHandler = Handler()
    private val wakelockRenewRunnable = object : Runnable {
        override fun run() {
            try {
                if (wakeLock == null) return
                if (wakeLock?.isHeld != true) {
                    try {
                        wakeLock?.acquire(10 * 60_000L)
                        Log.i("SENSOR_WAKELOCK", "WakeLock re-acquired with timeout pid=${Process.myPid()}")
                    } catch (t: Throwable) {
                        Log.e("SENSOR_WAKELOCK", "Failed to re-acquire wakelock: ${t.message}", t)
                    }
                }
            } finally {
                wakelockHandler.postDelayed(this, wakelockCheckInterval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Foreground service notification (wymagane na Android 8+)
        val channelId = "sensor_service_channel"
        val channelName = "ParkinCare - działanie w tle"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ParkinCare")
            .setContentText("Aplikacja działa w tle i zbiera dane z sensorów")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        Log.i("SENSOR_SERVICE", "startForeground called pid=${Process.myPid()}")

        handler = Handler(mainLooper)
        logHandler = Handler(mainLooper)

        // dedykowany wątek dla sensorów
        sensorHandlerThread = HandlerThread("SensorHandlerThread").apply { start() }
        sensorHandler = Handler(sensorHandlerThread!!.looper)

        // dedykowany wątek I/O dla zapisu/odczytu plików i długich operacji
        fileIoThread = HandlerThread("FileIoThread").apply { start() }
        fileIoHandler = Handler(fileIoThread!!.looper)

        mqttManager = MqttManager(
            context = this,
            serverUri = BuildConfig.MQTT_SERVER_URI,
            username = BuildConfig.MQTT_USERNAME,
            password = BuildConfig.MQTT_PASSWORD,
            baseTopic = BuildConfig.MQTT_TOPIC
        )

        // Synchronizacja statusu serwera co 2 sekundy
        handler.post(object : Runnable {
            override fun run() {
                try {
                    // uwzględnij także stan sieci urządzenia
                    serverConnected = mqttManager.isServerConnected && isNetworkAvailable()
                } catch (t: Throwable) {
                    Log.e("SENSOR_LOOP", "serverConnected updater failed: ${t.message}", t)
                } finally {
                    handler.postDelayed(this, 2000)
                }
            }
        })

        audioRecorderManager = AudioRecorderManager(
            context = this,
            mqttManager = mqttManager,
            onFragmentSaved = { audioFileWriteCounter++ },
            onFragmentSent = { audioMqttSendCounter++ }
        )

        fileManager = FileManager(this)

        // WakeLock: utrzymanie CPU aktywnego
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParkinCare::SensorServiceWakeLock")
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(10 * 60_000L)
                Log.i("SENSOR_WAKELOCK", "WakeLock acquired with timeout pid=${Process.myPid()}")
            } else {
                Log.i("SENSOR_WAKELOCK", "WakeLock already held")
            }
        } catch (t: Throwable) {
            Log.e("SENSOR_WAKELOCK", "Exception while acquiring wakelock: ${t.message}", t)
        }
        wakelockHandler.postDelayed(wakelockRenewRunnable, wakelockCheckInterval)

        // Inicjalizacja Runnable-ów
        saveDataRunnable = object : Runnable {
            override fun run() {
                try {
                    // Aktualizuj status MQTT
                    mqttConnected = mqttManager.isConnected && isNetworkAvailable()
                    val prefs = getSharedPreferences("sensor_prefs", MODE_PRIVATE)
                    val saveLocalOnly = prefs.getBoolean("save_local_only", false)
                } catch (t: Throwable) {
                    Log.e("SENSOR_LOOP", "saveDataRunnable failed: ${t.message}", t)
                } finally {
                    // reschedule na wątku I/O
                    fileIoHandler?.postDelayed(this, 1000)
                }
            }
        }

        logRunnable = object : Runnable {
            override fun run() {
                try {
                    logDiagnostics()
                    val prefs = getSharedPreferences("sensor_prefs", MODE_PRIVATE)
                    val saveLocalOnly = prefs.getBoolean("save_local_only", false)
                    if (lastSaveLocalOnly == null) {
                        lastSaveLocalOnly = saveLocalOnly
                    } else if (lastSaveLocalOnly == true && saveLocalOnly == false) {
                        Log.i("SENSOR_SYNC", "Wykryto przejście z trybu lokalnego na serwerowy - dosyłam zaległe dane")
                        try {
                            val sendAllowed = !heartbeatUnavailable && isNetworkAvailable() && mqttManager.isConnected && mqttManager.isServerConnected
                            if (sendAllowed) {
                                val watchId = prefs.getString("watch_id", null)
                                mqttManager.sendPendingFilesToMqttThrottled(filesDir, false, watchId)
                                audioRecorderManager.sendPendingAudioFiles()
                            } else {
                                Log.i("SENSOR_SYNC", "Nie wysyłam zaległych plików: brak heartbeat, połączenia lub sieci")
                            }
                        } catch (t: Throwable) {
                            Log.w("SENSOR_SYNC", "Failed to trigger pending files send: ${t.message}")
                        }
                    }
                    lastSaveLocalOnly = saveLocalOnly
                    if (saveLocalOnly) {
                        try { fileManager.logBatchFilesDiagnostics() } catch (_: Throwable) {}
                    }
                    logSensorValues()
                } catch (t: Throwable) {
                    Log.e("SENSOR_LOOP", "logRunnable failed: ${t.message}", t)
                } finally {
                    fileIoHandler?.postDelayed(this, logIntervalMillis)
                }
            }
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val prefs = getSharedPreferences("sensor_prefs", MODE_PRIVATE)


        // Inicjalizacja i rejestracja sensorów w jednej pętli
        SensorType.entries.forEach { type ->
            if (type != SensorType.AUDIO) {
                val enabled = prefs.getBoolean(type.key, type != SensorType.AUDIO)
                val sensorId = sensorTypeToAndroidId[type]
                val sensor = sensorId?.let { sensorManager.getDefaultSensor(it) }
                sensors[type] = sensor
                if (sensor != null) {
                    Log.i("SENSOR_INIT", "Found sensor for $type: name=${sensor.name}, vendor=${sensor.vendor}, type=${sensor.type}, resolution=${sensor.resolution}")
                } else {
                    Log.i("SENSOR_INIT", "No sensor found for $type (id=$sensorId)")
                }
                if (enabled && sensor != null) {
                    val delay = when (type) {
                        SensorType.ACCELEROMETER, SensorType.GYROSCOPE -> SensorManager.SENSOR_DELAY_FASTEST
                        SensorType.BAROMETER, SensorType.LIGHT -> SensorManager.SENSOR_DELAY_GAME
                        SensorType.HEART_RATE -> SensorManager.SENSOR_DELAY_NORMAL
                        else -> SensorManager.SENSOR_DELAY_NORMAL
                    }
                    try {
                        Log.i("SENSOR_INIT", "Registering sensor for $type on sensorHandler: name=${sensor.name}, vendor=${sensor.vendor}, type=${sensor.type}, delay=$delay")
                        sensorManager.registerListener(this, sensor, delay, sensorHandler)
                    } catch (e: Exception) {
                        Log.w("SENSOR_INIT", "Failed to register listener for $type: ${e.message}")
                    }
                }
            }
        }

        val isAudioEnabled = prefs.getBoolean(SensorType.AUDIO.key, false)
        audioRecorderManager.updateSettings(isAudioEnabled)

        fileIoHandler?.post(saveDataRunnable)
        fileIoHandler?.post(logRunnable)

        fileIoHandler?.post(object : Runnable {
            override fun run() {
                try {
                    flushMinuteBuffersIfNeeded()
                } catch (t: Throwable) {
                    Log.e("SENSOR_LOOP", "flushMinuteBuffers Runnable failed: ${t.message}", t)
                } finally {
                    fileIoHandler?.postDelayed(this, 5_000)
                }
            }
        })

        mqttManager.initialize()
        mqttManager.connect {
            mqttConnected = mqttManager.isConnected && isNetworkAvailable()
        }

        // rejestracja NetworkCallback żeby wykryć przywrócenie połączenia i spróbować ponownie połączyć MQTT
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i("NETWORK", "Network available - próbuję przywrócić połączenie MQTT jeśli potrzebne")
                    handler.post {
                        if (!isNetworkAvailable()) return@post
                        // Jeśli MQTT nie jest połączony, spróbuj się ponownie połączyć (użyj reconnectIfNeeded())
                        if (!mqttManager.isConnected) {
                            try {
                                mqttManager.reconnectIfNeeded()
                            } catch (e: Exception) {
                                Log.w("NETWORK", "reconnectIfNeeded failed: ${e.message}")
                            }
                        } else {
                            Log.i("NETWORK", "MQTT już połączony, nic do zrobienia")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.i("NETWORK", "Network lost - ustawiam stany i rozłączam MQTT")
                    handler.post {
                        mqttConnected = false
                        // Nie wywołujemy od razu disconnect jeśli klient może sam obsłużyć rozłączenie,
                        // ale chcemy wymusić reset stanu, żeby aplikacja nie została w trybie offline
                        try {
                            mqttManager.disconnect()
                        } catch (e: Exception) {
                            Log.w("NETWORK", "Błąd podczas disconnect MQTT: ${e.message}")
                        }
                    }
                }
            }
            cm.registerNetworkCallback(req, networkCallback!!)
        } catch (e: Exception) {
            Log.w("NETWORK", "Nie udało się zarejestrować NetworkCallback: ${e.message}")
        }

        handler.post(object : Runnable {
            var lastHeartbeatUnavailable: Boolean? = null
            override fun run() {
                try {
                    val serverReachable = mqttManager.isServerConnected && isNetworkAvailable()
                    heartbeatUnavailable = !serverReachable
                    mqttConnected = mqttManager.isConnected && serverReachable
                    audioRecorderManager.heartbeatUnavailable = heartbeatUnavailable
                    val prefs = getSharedPreferences("sensor_prefs", MODE_PRIVATE)
                    prefs.edit().putBoolean("heartbeat_unavailable", heartbeatUnavailable).apply()
                    if (serverReachable && !mqttManager.isConnected && isNetworkAvailable()) {
                        Log.i("MQTT_RECONNECT_TIMER", "Server reachable ale MQTT rozłączony - wywołuję reconnectIfNeeded()")
                        try {
                            mqttManager.reconnectIfNeeded()
                        } catch (e: Exception) {
                            Log.w("MQTT_RECONNECT_TIMER", "reconnectIfNeeded failed: ${e.message}")
                        }
                    }
                    if (lastHeartbeatUnavailable == true && heartbeatUnavailable == false) {
                        val saveLocalOnly = prefs.getBoolean("save_local_only", false)
                        try {
                            val sendAllowed = isNetworkAvailable() && mqttManager.isConnected && mqttManager.isServerConnected && !heartbeatUnavailable && !saveLocalOnly
                            if (sendAllowed) {
                                Log.i("SENSOR_SYNC", "Heartbeat przywrócony - wysyłam zaległe pliki .bin i audio")
                                val watchId = prefs.getString("watch_id", null)
                                mqttManager.sendPendingFilesToMqttThrottled(filesDir, saveLocalOnly, watchId)
                                audioRecorderManager.sendPendingAudioFiles()
                            } else {
                                Log.i("SENSOR_SYNC", "Nie wysyłam zaległych plików po heartbeat: warunki nie spełnione (network/connected/serverHeartbeat/saveLocalOnly)")
                            }
                        } catch (t: Throwable) {
                            Log.w("SENSOR_SYNC", "Error while sending pending files after heartbeat: ${t.message}")
                        }
                    }
                    lastHeartbeatUnavailable = heartbeatUnavailable
                } catch (t: Throwable) {
                    Log.e("SENSOR_LOOP", "heartbeat timer failed: ${t.message}", t)
                } finally {
                    handler.postDelayed(this, 10_000)
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
         Log.i("SENSOR_SERVICE", "onStartCommand pid=${Process.myPid()} flags=$flags startId=$startId intentAction=${intent?.action}")
         try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
         saveBinary = intent?.getBooleanExtra("SAVE_BINARY", true) ?: true
         val prefs = getSharedPreferences("sensor_prefs", MODE_PRIVATE)
         SensorType.entries.forEach { type ->
             if (type != SensorType.AUDIO) {
                 val enabled = prefs.getBoolean(type.key, type != SensorType.AUDIO)
                 val sensor = if (enabled) sensorTypeToAndroidId[type]?.let { sensorManager.getDefaultSensor(it) } else null
                 sensors[type] = sensor
                 sensor?.let {
                    val delay = when (type) {
                        SensorType.ACCELEROMETER, SensorType.GYROSCOPE -> SensorManager.SENSOR_DELAY_FASTEST
                        SensorType.BAROMETER, SensorType.LIGHT -> SensorManager.SENSOR_DELAY_GAME
                        SensorType.HEART_RATE -> SensorManager.SENSOR_DELAY_NORMAL
                        else -> SensorManager.SENSOR_DELAY_NORMAL
                    }
                    sensorManager.registerListener(this, it, delay, sensorHandler)
                 }
             }
         }
         val isAudioEnabled = prefs.getBoolean(SensorType.AUDIO.key, false)
         audioRecorderManager.updateSettings(isAudioEnabled)

         return START_STICKY
     }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // System może wywołać to gdy użytkownik zamknie aplikację z listy zadań
        Log.i("SENSOR_SERVICE", "onTaskRemoved pid=${Process.myPid()} intentAction=${rootIntent?.action}")
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, SensorService::class.java).apply {
                action = "ACTION_RESTART_SENSOR_SERVICE"
                putExtra("SAVE_BINARY", saveBinary)
            }
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1001, restartIntent, flags)
            } else {
                PendingIntent.getService(this, 1001, restartIntent, flags)
            }
            val triggerAt = System.currentTimeMillis() + 3_000L // ~3s
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.i("SENSOR_SERVICE", "Scheduled restart via AlarmManager at $triggerAt")
        } catch (t: Throwable) {
            Log.e("SENSOR_SERVICE", "Failed to schedule restart: ${t.message}", t)
        } finally {
            super.onTaskRemoved(rootIntent)
            stopSelf()
        }
    }

    private var lastAccelTime: Long = 0
    private var lastGyroTime: Long = 0
    private var accelData: FloatArray = FloatArray(3) { Float.NaN }
    private var gyroData: FloatArray = FloatArray(3) { Float.NaN }
    private var lastHeartRate: Float = Float.NaN
    private var lastPressure: Float = Float.NaN
    private var lastLight: Float = Float.NaN

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val sensorEventTimeMillis = System.currentTimeMillis() - ((android.os.SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000)
        val type = sensorTypeToAndroidId.entries.find { it.value == event.sensor.type }?.key
        when (type) {
            SensorType.ACCELEROMETER -> {
                accelData = event.values.copyOf(); lastAccelTime = sensorEventTimeMillis
                val key = "accelerometer"
                val buf = sensorMinuteBuffers[key] ?: mutableListOf<Pair<Long, Any>>().also { sensorMinuteBuffers[key] = it }
                synchronized(buf) { if (buf.isEmpty()) sensorMinuteBufferStart[key] = sensorEventTimeMillis; buf.add(sensorEventTimeMillis to accelData.copyOf()) }
            }
            SensorType.GYROSCOPE -> {
                gyroData = event.values.copyOf(); lastGyroTime = sensorEventTimeMillis
                val key = "gyroscope"
                val buf = sensorMinuteBuffers[key] ?: mutableListOf<Pair<Long, Any>>().also { sensorMinuteBuffers[key] = it }
                synchronized(buf) { if (buf.isEmpty()) sensorMinuteBufferStart[key] = sensorEventTimeMillis; buf.add(sensorEventTimeMillis to gyroData.copyOf()) }
            }
            SensorType.HEART_RATE -> if (event.values.isNotEmpty()) {
                lastHeartRate = event.values[0]
                val key = "heart_rate"
                val buf = sensorMinuteBuffers[key] ?: mutableListOf<Pair<Long, Any>>().also { sensorMinuteBuffers[key] = it }
                synchronized(buf) { if (buf.isEmpty()) sensorMinuteBufferStart[key] = sensorEventTimeMillis; buf.add(sensorEventTimeMillis to lastHeartRate) }
            }
            SensorType.LIGHT -> if (event.values.isNotEmpty()) {
                lastLight = event.values[0]
                val key = "light"
                val buf = sensorMinuteBuffers[key] ?: mutableListOf<Pair<Long, Any>>().also { sensorMinuteBuffers[key] = it }
                synchronized(buf) { if (buf.isEmpty()) sensorMinuteBufferStart[key] = sensorEventTimeMillis; buf.add(sensorEventTimeMillis to lastLight) }
            }
            SensorType.BAROMETER -> if (event.values.isNotEmpty()) {
                lastPressure = event.values[0]
                val key = "barometer"
                val buf = sensorMinuteBuffers[key] ?: mutableListOf<Pair<Long, Any>>().also { sensorMinuteBuffers[key] = it }
                synchronized(buf) { if (buf.isEmpty()) sensorMinuteBufferStart[key] = sensorEventTimeMillis; buf.add(sensorEventTimeMillis to lastPressure) }
            }
            else -> {}
        }
    }

    // Funkcja do wysyłania buforów minutowych
    private fun flushMinuteBuffersIfNeeded() {
        val now = System.currentTimeMillis()
        Log.i("SENSOR_DIAG", "flushMinuteBuffersIfNeeded wywołana, buforów: ${sensorMinuteBuffers.size}")
        val prefs = getSharedPreferences("sensor_prefs", MODE_PRIVATE)
        val saveLocalOnly = prefs.getBoolean("save_local_only", false)
        val heartbeatUnavailableNow = prefs.getBoolean("heartbeat_unavailable", heartbeatUnavailable)
        Log.i("SENSOR_DIAG", "flushMinuteBuffersIfNeeded: saveLocalOnly=$saveLocalOnly, mqttConnected=${mqttManager.isConnected}, heartbeatUnavailable=$heartbeatUnavailableNow")
        val keys = sensorMinuteBuffers.keys.toList()
        val maxChunkSamples = 512
        for (key in keys) {
            val buf = sensorMinuteBuffers[key]
            val start = sensorMinuteBufferStart[key]
            if (now - lastBufferLogTs >= 30_000L) {
                Log.i("SENSOR_DIAG", "Bufor $key: rozmiar=${buf?.size ?: 0}")
            }
            if (buf == null || start == null) continue
            val toFlush: List<Pair<Long, Any>>
            var shouldFlush = false
            synchronized(buf) {
                if (!buf.isEmpty() && now - start >= minuteBufferInterval) {
                    toFlush = ArrayList(buf)
                    buf.clear()
                    sensorMinuteBufferStart.remove(key)
                    shouldFlush = true
                } else {
                    toFlush = emptyList()
                }
            }
            if (!shouldFlush) continue
            val sampleCount = toFlush.size
            if (sampleCount == 0) continue
            var idx = 0
            while (idx < sampleCount) {
                val end = (idx + maxChunkSamples).coerceAtMost(sampleCount)
                val chunk = toFlush.subList(idx, end)
                fileIoHandler?.post {
                    try {
                        val firstSample = chunk.first().second
                        val isArray = firstSample is FloatArray
                        val valueCount = if (isArray) (firstSample as FloatArray).size else 1
                        val sampleSize = 8 + 4 * valueCount
                        val totalSize = 4 + chunk.size * sampleSize
                        val buffer = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        buffer.putInt(chunk.size)
                        chunk.forEach {
                            buffer.putLong(it.first)
                            if (isArray) {
                                (it.second as FloatArray).forEach { v -> buffer.putFloat(v) }
                            } else {
                                buffer.putFloat(it.second as Float)
                            }
                        }
                        val data = buffer.array()
                        if (saveLocalOnly || !mqttManager.isConnected || heartbeatUnavailableNow) {
                            Log.i("SENSOR_FILE", "Tryb lokalny/serwer niedostępny, zapisuję chunk jako _batch: $key, ${chunk.size} próbek, start=$start")
                            fileManager.saveSensorBatchToBinaryAsync(key, data, start, saveLocalOnly = true, serverAvailable = false) { ok, ex ->
                                if (ok) {
                                    val added = getSampleCountFromChunk(data)
                                    synchronized(fileWriteCounters) { fileWriteCounters[key] = (fileWriteCounters[key] ?: 0) + added }
                                } else {
                                    Log.e("SENSOR_FILE", "Async save failed: ${ex?.message}")
                                }
                            }
                        } else {
                            Log.i("SENSOR_FILE", "Próba wysyłki do serwera: $key, ${chunk.size} próbek, start=$start (chunk)")
                            mqttManager.publishData(key, data) { success, error ->
                                if (success) {
                                    mqttSendCounters[key] = (mqttSendCounters[key] ?: 0) + 1
                                } else {
                                    Log.i("SENSOR_FILE", "Błąd wysyłki ($error), zapisuję batch: $key, ${chunk.size} próbek, start=$start")
                                    fileManager.saveSensorBatchToBinaryAsync(key, data, start, saveLocalOnly = true, serverAvailable = false) { ok, ex ->
                                        if (ok) {
                                            val added = getSampleCountFromChunk(data)
                                            synchronized(fileWriteCounters) { fileWriteCounters[key] = (fileWriteCounters[key] ?: 0) + added }
                                        } else {
                                            Log.e("SENSOR_FILE", "Async save failed: ${ex?.message}")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e("SENSOR_LOOP", "flush chunk failed: ${t.message}", t)
                    }
                }
                idx = end
            }
        }
        if (now - lastBufferLogTs >= 30_000L) lastBufferLogTs = now
    }

    private fun logSensorValues() {
        Log.i("SENSOR_LOG_30S", "Akcelerometr: x=${accelData[0]}, y=${accelData[1]}, z=${accelData[2]}")
        Log.i("SENSOR_LOG_30S", "Żyroskop: x=${gyroData[0]}, y=${gyroData[1]}, z=${gyroData[2]}")
        Log.i("SENSOR_LOG_30S", "Tętno: $lastHeartRate")
        Log.i("SENSOR_LOG_30S", "Ciśnienie: $lastPressure")
        Log.i("SENSOR_LOG_30S", "Natężenie światła: $lastLight")
        val archivedLinesSent = com.example.parkincare.util.ParkinLogger.consumeOutboxLinesSent()
        Log.i("SENSOR_LOG_30S", "Archiwalne logi wysłane w ostatnich 30s: $archivedLinesSent linii")
        val allSensors = listOf(
            "accelerometer", "gyroscope", "heart_rate", /*"spo2",*/ "barometer", /*"ekg",*/ "light"
        )
        allSensors.forEach { sensor ->
            val mqttCount = mqttSendCounters[sensor] ?: 0
            val fileCount = fileWriteCounters[sensor] ?: 0
            Log.i(
                "SENSOR_LOG_30S",
                "[$sensor] ostatnie 30s: $mqttCount próbek wysłano do MQTT, $fileCount próbek zapisano do pliku"
            )
        }

        val audioFiles = filesDir.listFiles { f -> f.name.startsWith("audio_fragment_") && f.name.endsWith(".3gp") }
        val audioBytesLocal = audioFiles?.sumOf { it.length() } ?: 0L
        // Wysłane audio: licznik fragmentów * średni rozmiar fragmentu (przybliżenie)
        val avgAudioSize = if (audioFiles != null && audioFiles.isNotEmpty()) audioFiles.sumOf { it.length() } / audioFiles.size else 0L
        val audioBytesSent = audioMqttSendCounter * avgAudioSize
        Log.i(
            "SENSOR_LOG_30S",
            "[audio] ostatnie 30s: $audioMqttSendCounter fragmentów wysłano do MQTT (~${audioBytesSent} B), $audioFileWriteCounter fragmentów zapisano do pliku (~${audioBytesLocal} B)"
        )
        // Zerowanie liczników
        fileWriteCounters.keys.forEach { fileWriteCounters[it] = 0 }
        mqttSendCounters.keys.forEach { mqttSendCounters[it] = 0 }
        audioFileWriteCounter = 0
        audioMqttSendCounter = 0
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        try { wakelockHandler.removeCallbacks(wakelockRenewRunnable) } catch (_: Throwable) {}
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    Log.i("SENSOR_WAKELOCK", "WakeLock released in onDestroy")
                }
            } catch (t: Throwable) {
                Log.w("SENSOR_WAKELOCK", "Failed to release wakelock in onDestroy: ${t.message}")
            }
        }
        wakeLock = null
        Log.i("SENSOR_SERVICE", "SensorService destroyed")

        try { fileIoHandler?.removeCallbacks(saveDataRunnable) } catch (_: Throwable) {}
        try { fileIoHandler?.removeCallbacks(logRunnable) } catch (_: Throwable) {}

        val ioShutdownWatchdog = Runnable {
            try {
                Log.w("SENSOR_DESTROY", "Forcing fileIoThread.quit() due to timeout")
                try { fileIoThread?.quit() } catch (t: Throwable) { Log.w("SENSOR_DESTROY", "Force quit failed: ${t.message}") }
            } catch (_: Throwable) {}
        }

        // Zaplanuj finalne operacje I/O asynchronicznie - nie blokujemy onDestroy
        fileIoHandler?.post {
            try {
                flushMinuteBuffersIfNeeded()
            } catch (t: Throwable) {
                Log.e("SENSOR_LOOP", "flushMinuteBuffers failed in onDestroy: ${t.message}", t)
            }
            try {
                audioRecorderManager.cleanup()
            } catch (t: Throwable) {
                Log.e("SENSOR_LOOP", "audioRecorderManager.cleanup failed in onDestroy: ${t.message}", t)
            }
            try {
                fileManager.cleanup()
            } catch (t: Throwable) {
                Log.e("SENSOR_LOOP", "fileManager.cleanup failed in onDestroy: ${t.message}", t)
            }

                try {
                try {
                    mqttManager.disconnect()
                    Log.i("SENSOR_DESTROY", "mqttManager.disconnect() called from fileIoHandler")
                } catch (t: Throwable) {
                    Log.w("SENSOR_DESTROY", "mqtt disconnect failed in fileIoHandler: ${t.message}")
                }
            } catch (_: Throwable) {}

            try {
                fileIoThread?.quitSafely()
            } catch (t: Throwable) {
                Log.w("SENSOR_DESTROY", "Failed to quit fileIoThread from inside handler: ${t.message}")
                try { fileIoThread?.quit() } catch (_: Throwable) {}
            }

            fileIoThread = null
            fileIoHandler = null

            try { handler.removeCallbacks(ioShutdownWatchdog) } catch (_: Throwable) {}
        }

        try { handler.postDelayed(ioShutdownWatchdog, 5_000) } catch (_: Throwable) {}

        try { sensorManager.unregisterListener(this) } catch (e: Exception) { Log.w("SENSOR_DESTROY", "Error unregistering sensor listener: ${e.message}") }
        sensorHandlerThread?.let { try { it.quitSafely() } catch (e: Exception) { Log.w("SENSOR_DESTROY", "Failed to quit sensorHandlerThread: ${e.message}") } }
        try { networkCallback?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it); Log.i("NETWORK", "NetworkCallback unregistered in onDestroy") } } catch (t: Throwable) { Log.e("NETWORK", "Failed to unregister NetworkCallback: ${t.message}", t) }
    }

    private fun logDiagnostics() {
        try {
            val rt = Runtime.getRuntime()
            val usedHeap = rt.totalMemory() - rt.freeMemory()
            val maxHeap = rt.maxMemory()
            val nativeHeap = Debug.getNativeHeapAllocatedSize()
            val threadCount = Thread.getAllStackTraces().keys.size
            val fdCount = File("/proc/self/fd").list()?.size ?: -1

            val audioFiles = filesDir.listFiles { f -> f.name.startsWith("audio_fragment_") } ?: emptyArray()
            val audioBytes = audioFiles.sumOf { it.length() }
            val logFiles = File(filesDir, "logs").listFiles() ?: emptyArray()
            val logBytes = logFiles.sumOf { it.length() }

            val stat = StatFs(filesDir.path)
            val availBytes = stat.availableBytes

            Log.i("DIAG", "heap_used=${usedHeap} max_heap=${maxHeap} native_alloc=${nativeHeap} threads=${threadCount} fds=${fdCount} audio_bytes=${audioBytes} log_bytes=${logBytes} avail_fs=${availBytes}")

            val sample = DiagnosticSample(
                ts = System.currentTimeMillis(),
                usedHeap = usedHeap,
                maxHeap = maxHeap,
                nativeHeap = nativeHeap,
                threadCount = threadCount,
                fdCount = fdCount,
                audioBytes = audioBytes,
                logBytes = logBytes,
                availFs = availBytes
            )
            synchronized(diagHistory) {
                diagHistory.addLast(sample)
                while (diagHistory.size > diagMaxHistory) diagHistory.removeFirst()
            }

            synchronized(diagHistory) {
                if (diagHistory.size >= 6) {
                    val oldest = diagHistory.first()
                    val newest = diagHistory.last()
                    val heapIncreasePct = if (oldest.maxHeap > 0) (newest.usedHeap - oldest.usedHeap).toDouble() / oldest.maxHeap * 100.0 else 0.0
                    val nativeIncreasePct = if (oldest.nativeHeap > 0) (newest.nativeHeap - oldest.nativeHeap).toDouble() / oldest.nativeHeap * 100.0 else 0.0
                    val fdIncrease = newest.fdCount - oldest.fdCount
                    val threadsIncrease = newest.threadCount - oldest.threadCount
                    val audioBytesIncrease = newest.audioBytes - oldest.audioBytes
                    if (heapIncreasePct > 20.0 || nativeIncreasePct > 20.0 || fdIncrease > 50 || threadsIncrease > 20 || audioBytesIncrease > 5_000_000L) {
                        Log.i("DIAG_ALERT", "trend detected: heap%=${String.format("%.1f", heapIncreasePct)} native%=${String.format("%.1f", nativeIncreasePct)} fdDiff=${fdIncrease} threadsDiff=${threadsIncrease} audioDiff=${audioBytesIncrease}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("DIAG", "logDiagnostics failed: ${t.message}", t)
        }
    }

    private fun sendChunksSequentially(key: String, chunks: List<ByteArray>, startTs: Long) {
        if (chunks.isEmpty()) return
        fileIoHandler?.post(object : Runnable {
            var idx = 0
            override fun run() {
                if (idx >= chunks.size) return
                val chunk = chunks[idx]
                try {
                    // Jeśli w trakcie wysyłki MQTT przestaje być dostępne, zapisujemy pozostałe
                    if (!mqttManager.isConnected || !mqttManager.isServerConnected || heartbeatUnavailable) {
                        Log.i("SENSOR_FILE", "MQTT disconnected mid-session - zapisuję pozostałe ${chunks.size - idx} chunków dla $key")
                        for (j in idx until chunks.size) {
                            fileManager.saveSensorBatchToBinaryAsync(key, chunks[j], startTs, saveLocalOnly = true, serverAvailable = false) { ok, ex ->
                                if (ok) {
                                    val added = getSampleCountFromChunk(chunks[j])
                                    synchronized(fileWriteCounters) { fileWriteCounters[key] = (fileWriteCounters[key] ?: 0) + added }
                                } else Log.e("SENSOR_FILE", "Async save failed: ${ex?.message}")
                            }
                        }
                        return
                    }
                    Log.i("SENSOR_FILE", "sendChunksSequentially: publishing chunk #$idx for $key, size=${chunks[idx].size}")
                    mqttManager.publishDataWithRetry(key, chunk, maxAttempts = 6) { success, err ->
                        if (success) {
                            mqttSendCounters[key] = (mqttSendCounters[key] ?: 0) + 1
                            Log.i("SENSOR_FILE", "sendChunksSequentially: chunk #$idx published for $key")
                            idx++
                            // małe opóźnienie pomiędzy chunkami, aby nie przeciążać
                            fileIoHandler?.postDelayed(this, 50L)
                        } else {
                            Log.w("SENSOR_FILE", "sendChunksSequentially: publish failed for chunk #$idx ($err) - zapisuję pozostałe")
                            for (j in idx until chunks.size) {
                                fileManager.saveSensorBatchToBinaryAsync(key, chunks[j], startTs, saveLocalOnly = true, serverAvailable = false) { ok, ex ->
                                    if (ok) {
                                        val added = getSampleCountFromChunk(chunks[j])
                                        synchronized(fileWriteCounters) { fileWriteCounters[key] = (fileWriteCounters[key] ?: 0) + added }
                                    } else Log.e("SENSOR_FILE", "Async save failed: ${ex?.message}")
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("SENSOR_LOOP", "sendChunksSequentially failed: ${t.message}", t)
                    // przy błędzie zapisz pozostałe
                    for (j in idx until chunks.size) {
                        fileManager.saveSensorBatchToBinaryAsync(key, chunks[j], startTs, saveLocalOnly = true, serverAvailable = false) { ok, ex ->
                            if (ok) {
                                val added = getSampleCountFromChunk(chunks[j])
                                synchronized(fileWriteCounters) { fileWriteCounters[key] = (fileWriteCounters[key] ?: 0) + added }
                            }
                            else Log.e("SENSOR_FILE", "Async save failed: ${ex?.message}")
                        }
                    }
                }
            }
        })
    }

    // Pomocnik: wyciąga liczbę próbek z zapisanego chunku (pierwsze 4 bajty, little-endian). Jeśli niepoprawne, zwraca 0
    private fun getSampleCountFromChunk(data: ByteArray): Int {
        return try {
            if (data.size < 4) return 0
            java.nio.ByteBuffer.wrap(data, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        } catch (_: Throwable) { 0 }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
