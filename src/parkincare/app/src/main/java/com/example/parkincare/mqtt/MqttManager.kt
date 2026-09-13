package com.example.parkincare.mqtt

import android.content.Context
import android.os.Handler
import com.example.parkincare.BuildConfig
import com.example.parkincare.presentation.SensorType
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import com.example.parkincare.util.ParkinLogger as Log

class MqttManager(
    private val context: Context,
    private val serverUri: String = BuildConfig.MQTT_SERVER_URI,
    private val username: String = BuildConfig.MQTT_USERNAME,
    private val password: String = BuildConfig.MQTT_PASSWORD,
    private val baseTopic: String = BuildConfig.MQTT_TOPIC
) {
    private val trimmedBaseTopic: String = baseTopic.trimEnd('/')

    private lateinit var mqttClient: Mqtt3AsyncClient
    private val handler = Handler()

    private val outboxLinesEnabled: Boolean = true
    @Volatile private var outboxSendingInProgress: Boolean = false
    private val outboxRetryIntervalMs = 30_000L

    private val outboxRetryRunnable = object : Runnable {
        override fun run() {
            try {
                if (!outboxLinesEnabled) {
                    Log.i("OUTBOX_DIAG", "outboxRetry: wysyłka outbox_lines WYŁĄCZONA globalnie — pomijam")
                } else {
                    val mqttReady = try { ::mqttClient.isInitialized && mqttClient.state.isConnected } catch (_: Throwable) { false }
                    val dir = File(context.filesDir, "logs_outbox_lines")
                    val hasFiles = dir.exists() && (dir.listFiles()?.isNotEmpty() == true)

                    Log.i(
                        "OUTBOX_DIAG",
                        "outboxRetry: state hasFiles=${hasFiles}, inProgress=${outboxSendingInProgress}, isServerConnected=${isServerConnected}, mqttReady=${mqttReady}, currentFile=${currentOutboxFileName ?: "-"}, sessionSent=${currentOutboxSessionSent}, lastProgressTs=${lastOutboxProgressTs}"
                    )

                    val now = System.currentTimeMillis()
                    if (outboxSendingInProgress && lastOutboxProgressTs != 0L && (now - lastOutboxProgressTs) > outboxWatchdogNoProgressMs) {
                        Log.w("OUTBOX_DIAG", "outboxRetry: WATCHDOG — brak postępu od ${(now - lastOutboxProgressTs)} ms, zatrzymuję sesję dla file=${currentOutboxFileName}")
                        outboxSendingInProgress = false
                    }
                    if (outboxSendingInProgress) {
                        Log.i("OUTBOX_DIAG", "outboxRetry: już trwa wysyłka — pomijam próbę")
                    } else if (!hasFiles) {
                        Log.i("OUTBOX_DIAG", "outboxRetry: brak plików — pomijam próbę")
                    } else if (!isServerConnected || !mqttReady) {
                        Log.i("OUTBOX_DIAG", "outboxRetry: pomijam — isServerConnected=$isServerConnected, mqttReady=$mqttReady")
                    }
                }
            } catch (t: Throwable) {
                Log.w("OUTBOX_DIAG", "outboxRetry: wyjątek: ${t.message}")
            } finally {
                handler.postDelayed(this, outboxRetryIntervalMs)
            }
        }
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    private val connecting = AtomicBoolean(false)

    private var pendingFilesToSend: List<File> = emptyList()
    private var sendingFiles = false

    private val maxFilesPerMinute = 20

    private val minIntervalBetweenFilesMs: Long = 60_000L / maxFilesPerMinute
    private var lastFileSentTimestamp: Long = 0L

    // Mapowanie plik->liczba prób wysyłki
    private val fileRetryAttempts: MutableMap<String, Int> = mutableMapOf()
    private val retryBaseDelayMs = 1000L // 1s
    private val retryMaxDelayMs = 60_000L // max 60s
    private val retryMaxAttempts = 8

    @Volatile
    var lastHeartbeatTimestamp: Long = 0L
        private set

    @Volatile
    var isServerConnected: Boolean = false
        private set
    @Volatile
    var wasEverConnectedToServer: Boolean = false
        private set
    private val heartbeatTimeoutMs = 25_000L

    private val heartbeatCheckRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if ((lastHeartbeatTimestamp == 0L || now - lastHeartbeatTimestamp > heartbeatTimeoutMs) && isServerConnected) {
                isServerConnected = false
                isConnected = false
                lastHeartbeatTimestamp = 0L
                Log.w("MQTT_HEARTBEAT", "Brak heartbeat >25s, ustawiam jako niepołączony z serwerem (isConnected=false)")
            }

            if (!isServerConnected) {
                Log.i("MQTT_HEARTBEAT", "Stan: isConnected=$isConnected, isServerConnected=$isServerConnected, lastHeartbeatTimestamp=$lastHeartbeatTimestamp")
            }

            handler.postDelayed(this, 1000)
        }
    }

    fun initialize() {
        val clientId = "ParkinCare_" + System.currentTimeMillis()
        val builder = MqttClient.builder()
            .identifier(clientId)
            .serverHost(serverUri.substringBefore(":"))
            .serverPort(serverUri.substringAfter(":").toIntOrNull() ?: 8883)
            .useMqttVersion3()

        if (BuildConfig.MQTT_TLS_ENABLED && BuildConfig.MQTT_CA_CERT_PATH.isNotEmpty() && BuildConfig.MQTT_CLIENT_CERT_PATH.isNotEmpty() && BuildConfig.MQTT_CLIENT_KEY_PATH.isNotEmpty()) {
            val (tmf, kmf) = createTrustAndKeyManagerFromAssets()
            val sslConfig = MqttClientSslConfig.builder()
                .trustManagerFactory(tmf)
                .keyManagerFactory(kmf)
                .protocols(listOf("TLSv1.2"))
                .build()
            builder.sslConfig(sslConfig)
        } else {
            builder.sslWithDefaultConfig()
        }
        mqttClient = builder.buildAsync()
    }

    private fun createTrustAndKeyManagerFromAssets(): Pair<TrustManagerFactory, KeyManagerFactory> {
        val caFile = copyAssetToCache(BuildConfig.MQTT_CA_CERT_PATH, "ca.crt")
        val clientCertFile = copyAssetToCache(BuildConfig.MQTT_CLIENT_CERT_PATH, "client.crt")
        val clientKeyFile = copyAssetToCache(BuildConfig.MQTT_CLIENT_KEY_PATH, "client.key")
        val caInput = caFile.inputStream()
        val caKs = KeyStore.getInstance(KeyStore.getDefaultType())

        caKs.load(null, null)
        caKs.setCertificateEntry("ca-cert", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(caInput))

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(caKs)

        val clientKs = KeyStore.getInstance(KeyStore.getDefaultType())
        clientKs.load(null, null)

        val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(clientCertFile.inputStream())

        val key = loadPrivateKey(clientKeyFile)
        clientKs.setKeyEntry("client-key", key, null, arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(clientKs, null)

        return tmf to kmf
    }

    private fun copyAssetToCache(assetName: String, fileName: String): File {
        val file = File(context.cacheDir, fileName)
        val input: InputStream = when {
            assetName.startsWith("res/raw/") -> {
                val nameWithExt = assetName.removePrefix("res/raw/")
                val resName = nameWithExt.substringBeforeLast('.', nameWithExt)
                val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
                if (resId == 0) throw java.io.FileNotFoundException(assetName)
                context.resources.openRawResource(resId)
            }
            assetName.startsWith("assets/") -> {
                context.assets.open(assetName.removePrefix("assets/"))
            }
            else -> {
                context.assets.open(assetName)
            }
        }
        input.use { ins -> FileOutputStream(file).use { outs -> ins.copyTo(outs) } }
        return file
    }

    private fun loadPrivateKey(file: File): java.security.PrivateKey? {
        val keyString = String(file.readBytes())
        return try {
            when {
                keyString.contains("-----BEGIN PRIVATE KEY-----") -> {
                    // PKCS#8
                    val privateKeyPEM = keyString
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("\r", "")
                        .replace("\n", "")
                        .replace(" ", "")
                        .replace("\t", "")
                    val keyBytes = android.util.Base64.decode(privateKeyPEM, android.util.Base64.DEFAULT)
                    val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
                    java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                }
                keyString.contains("-----BEGIN RSA PRIVATE KEY-----") -> {
                    Log.e("MqttManager", "Klucz w formacie PKCS#1. Skonwertuj do PKCS#8.")
                    null
                }
                else -> {
                    Log.e("MqttManager", "Nieznany format klucza prywatnego")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("MqttManager", "Błąd ładowania klucza prywatnego: ${e.message}")
            null
        }
    }

    fun connect(onConnected: (() -> Unit)? = null) {
        if (connecting.getAndSet(true)) {
            Log.i("MQTT", "connect() - już próbowano łączyć (connecting=true), pomijam kolejną próbę")
            return
        }

        mqttClient.connect().whenComplete { _, throwable ->
            isConnected = throwable == null
            connecting.set(false)
            if (isConnected) {
                mqttClient.subscribeWith()
                    .topicFilter("parkincare/receiver/heartbeat")
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback { publish ->
                        val payload = publish.payload.orElse(null)?.let { buffer ->
                            val bytes = if (buffer.hasArray()) {
                                buffer.array()
                            } else {
                                val arr = ByteArray(buffer.remaining())
                                buffer.get(arr)
                                arr
                            }
                            String(bytes, StandardCharsets.UTF_8)
                        }

                        // Parsowanie timestamp z payload
                        val regex = "'timestamp': (\\d+)".toRegex()
                        val match = regex.find(payload ?: "")
                        val ts = match?.groupValues?.getOrNull(1)?.toLongOrNull()

                        lastHeartbeatTimestamp = System.currentTimeMillis()
                        isServerConnected = true
                        isConnected = true
                        Log.i("MQTT_HEARTBEAT", "Odebrano heartbeat: $ts, payload=$payload, localTs=${lastHeartbeatTimestamp}, isConnected=$isConnected")

                        if (!wasEverConnectedToServer) {
                            wasEverConnectedToServer = true
                            Log.i("MQTT_HEARTBEAT", "Pierwszy heartbeat, ustawiam jako połączony z serwerem")
                        } else {
                            Log.i("MQTT_HEARTBEAT", "Ponowny heartbeat, przywracam status połączony z serwerem")
                        }

                        // Resetowanie timera po każdym heartbeat
                        handler.removeCallbacks(heartbeatCheckRunnable)
                        handler.postDelayed(heartbeatCheckRunnable, 1000)
                    }
                    .send()
                handler.post(heartbeatCheckRunnable)

                try {
                    Log.setMqttManager(this)
                } catch (e: Exception) {
                    Log.w("MQTT", "setMqttManager failed: ${e.message}")
                }

                // Po połączeniu opóźnij pierwszą próbę wysyłki outbox_lines o 2s, ale tylko jeśli włączone
                try {
                    handler.removeCallbacks(outboxRetryRunnable)
                    if (outboxLinesEnabled) {
                        handler.postDelayed({
                            Log.i("OUTBOX_DIAG", "Połączenie zestawione — opóźniona pierwsza próba sendOutboxLines (enabled)")
                        }, 2000)
                        // Uruchom cykliczny retry co 30s
                        handler.postDelayed(outboxRetryRunnable, outboxRetryIntervalMs)
                    } else {
                        Log.i("OUTBOX_DIAG", "Połączenie zestawione — wysyłka outbox_lines WYŁĄCZONA, nie planuję startu ani retry")
                    }
                } catch (e: Exception) {
                    Log.w("OUTBOX_DIAG", "Nie udało się zaplanować cyklicznego retry: ${e.message}")
                }
                onConnected?.invoke()
            } else {
                // Połączenie nieudane lub zerwane
                isServerConnected = false
                wasEverConnectedToServer = false
                handler.removeCallbacks(heartbeatCheckRunnable)
                handler.removeCallbacks(outboxRetryRunnable)
                Log.w("MQTT_HEARTBEAT", "Połączenie MQTT nieudane lub zerwane, ustawiam jako niepołączony z serwerem")
            }
        }
    }

    fun reconnectIfNeeded() {
        try {
            if (::mqttClient.isInitialized && mqttClient.state.isConnected) {
                Log.i("MQTT_RECONNECT", "Klient już połączony, nic do zrobienia")
                return
            }
        } catch (e: Exception) {
            // jeśli sprawdzenie stanu wykazuje błąd, kontynuujemy próbę ponownego utworzenia klienta
        }

        if (connecting.get()) {
            Log.i("MQTT_RECONNECT", "Już trwa próba łączenia, pomijam kolejną")
            return
        }
        Log.i("MQTT_RECONNECT", "Spróbuję zainicjować i połączyć MQTT ponownie")
        try {
            initialize()
        } catch (e: Exception) {
            Log.w("MQTT_RECONNECT", "initialize() failed: ${e.message}")
        }
        connect()
    }

    fun disconnect() {
        if (::mqttClient.isInitialized) {
            mqttClient.disconnect()
        }
        isConnected = false
        isServerConnected = false
        wasEverConnectedToServer = false
        handler.removeCallbacks(heartbeatCheckRunnable)
    }

    fun publishData(topic: String, data: ByteArray, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (!::mqttClient.isInitialized || !mqttClient.state.isConnected) {
            Log.e("MQTT_PUBLISH", "MQTT client not connected. Topic='$topic'")
            onComplete?.invoke(false, "Client not connected")
            return
        }

        val apiToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (e: Exception) { null }?.trim('/') ?: ""

        val finalTopic = when {
            topic.isBlank() -> if (apiToken.isNotBlank()) "$trimmedBaseTopic/$apiToken" else trimmedBaseTopic
            topic.contains('/') -> "$trimmedBaseTopic/$topic"
            apiToken.isNotBlank() -> "$trimmedBaseTopic/$apiToken/$topic"
            else -> "$trimmedBaseTopic/$topic"
        }

        if (!finalTopic.contains("/logs/entry")) {
            Log.d("MQTT_PUBLISH", "publishData called with topic='$finalTopic', data.size=${data.size}, isConnected=${::mqttClient.isInitialized && mqttClient.state.isConnected}")
        }

        mqttClient.publishWith()
            .topic(finalTopic)
            .payload(data)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, throwable ->
                if (throwable == null) {
                    if (!finalTopic.contains("/logs/entry")) {
                        Log.i("MQTT_PUBLISH", "Successfully published to topic='$finalTopic', bytes=${data.size}")
                    }
                    onComplete?.invoke(true, null)
                } else {
                    Log.e("MQTT_PUBLISH", "Failed to publish to topic='$finalTopic': ${throwable.message}")
                    onComplete?.invoke(false, throwable.message)
                }
            }
    }

    fun publishDataWithRetry(topic: String, data: ByteArray, maxAttempts: Int = 6, onComplete: ((Boolean, String?) -> Unit)? = null) {
        try {
            publishWithRetry(topic, data, maxAttempts, 1, onComplete)
        } catch (t: Throwable) {
            Log.e("MQTT_PUBLISH_WRAPPER", "publishDataWithRetry threw: ${t.message}")
            onComplete?.invoke(false, t.message)
        }
    }

    private fun publishWithRetry(topic: String, data: ByteArray, maxAttempts: Int = 5, attempt: Int = 1, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (!::mqttClient.isInitialized || !mqttClient.state.isConnected) {
            Log.e("MQTT_PUBLISH_RETRY", "Client not connected before attempt $attempt for topic=$topic")
            onComplete?.invoke(false, "Client not connected")
            return
        }

        val apiToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (e: Exception) { null }?.trim('/') ?: ""
        val finalTopic = when {
            topic.isBlank() -> if (apiToken.isNotBlank()) "$trimmedBaseTopic/$apiToken" else trimmedBaseTopic
            topic.contains('/') -> "$trimmedBaseTopic/$topic"
            apiToken.isNotBlank() -> "$trimmedBaseTopic/$apiToken/$topic"
            else -> "$trimmedBaseTopic/$topic"
        }

        mqttClient.publishWith()
            .topic(finalTopic)
            .payload(data)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, throwable ->
                if (throwable == null) {
                    Log.i("MQTT_PUBLISH_RETRY", "Publish succeeded (attempt $attempt) topic=$finalTopic, bytes=${data.size}")
                    onComplete?.invoke(true, null)
                } else {
                    Log.w("MQTT_PUBLISH_RETRY", "Publish failed (attempt $attempt) topic=$finalTopic: ${throwable.message}")
                    // jeśli brak połączenia, przerwij natychmiast
                    if (!::mqttClient.isInitialized || !mqttClient.state.isConnected || !isServerConnected) {
                        Log.w("MQTT_PUBLISH_RETRY", "Brak połączenia, przerwanie retry (topic=$finalTopic)")
                        onComplete?.invoke(false, throwable.message)
                        return@whenComplete
                    }
                    if (attempt >= maxAttempts) {
                        Log.e("MQTT_PUBLISH_RETRY", "Osiągnięto max prób ($attempt) dla topic=$finalTopic")
                        onComplete?.invoke(false, throwable.message)
                        return@whenComplete
                    }
                    val backoff = (retryBaseDelayMs * (1L shl (attempt - 1))).coerceAtMost(retryMaxDelayMs)
                    Log.i("MQTT_PUBLISH_RETRY", "Planowanie retry #${attempt + 1} za $backoff ms dla topic=$finalTopic")
                    handler.postDelayed({ publishWithRetry(topic, data, maxAttempts, attempt + 1, onComplete) }, backoff)
                }
            }
    }

    fun publishAudio(audioData: ByteArray, onComplete: ((Boolean, String?) -> Unit)? = null) {
        publishData("audio", audioData, onComplete)
    }

    fun sendPendingFilesToMqttThrottled(filesDir: File, saveLocalOnly: Boolean, watchId: String?) {
        if (saveLocalOnly) {
            sendingFiles = false
            return
        }

        if (sendingFiles) {
            Log.i("MQTT", "sendPendingFilesToMqttThrottled(watchId): already sending, ignoring call")
            return
        }

        sendingFiles = true
        val files = filesDir.listFiles()?.filter {
            val name = it.name
            (name.endsWith("_batch.bin") || name.endsWith(".json")) &&
                SensorType.entries.any { sensor -> name.startsWith(sensor.key + "_") }
        } ?: emptyList()
        pendingFilesToSend = files

        if (pendingFilesToSend.isEmpty()) {
            sendingFiles = false
            return
        }

        tempWatchIdForPendingSend = watchId
        sendNextFileThrottled()
    }

    @Volatile private var tempWatchIdForPendingSend: String? = null

    @Volatile private var currentOutboxFileName: String? = null
    @Volatile private var currentOutboxSessionSent: Int = 0
    @Volatile private var lastOutboxProgressTs: Long = 0L
    private val outboxWatchdogNoProgressMs = 5000L

    fun sendPendingAudioFilesToMqtt(filesDir: File) {
        val files = filesDir.listFiles()?.filter { f -> f.name.startsWith("audio_fragment_") && f.name.endsWith(".3gp") } ?: return
        if (files.isEmpty()) return
        Log.i("MQTT_AUDIO", "Sending ${files.size} pending audio files")
        files.forEach { file ->
            try {
                val bytes = file.readBytes()
                publishWithRetry("audio", bytes, maxAttempts = 6) { success, error ->
                    if (success) {
                        try { file.delete() } catch (_: Exception) {}
                        Log.i("MQTT_AUDIO", "Sent audio file: ${file.name}")
                    } else {
                        Log.w("MQTT_AUDIO", "Failed to send audio file ${file.name}: $error")
                    }
                }
            } catch (e: Exception) {
                Log.e("MQTT_AUDIO", "Exception sending audio file ${file.name}: ${e.message}")
            }
        }
    }

    private fun sendNextFileThrottled() {
        if (pendingFilesToSend.isEmpty()) {
            sendingFiles = false
            return
        }

        if (!isServerConnected || !::mqttClient.isInitialized || !mqttClient.state.isConnected) {
            Log.w("MQTT", "sendNextFileThrottled: serwer nieosiągalny lub client rozłączony — przerywam wysyłkę, pliki pozostają na dysku")
            sendingFiles = false
            return
        }

        // Rate limiting
        val now = System.currentTimeMillis()
        if (lastFileSentTimestamp != 0L) {
            val elapsed = now - lastFileSentTimestamp
            if (elapsed < minIntervalBetweenFilesMs) {
                val wait = minIntervalBetweenFilesMs - elapsed
                Log.i("MQTT", "Rate spacing active. Poczekam $wait ms przed kolejną wysyłką")
                handler.postDelayed({ sendNextFileThrottled() }, if (wait > 0) wait else 1000)
                return
            }
        }

        val file = pendingFilesToSend.first()
        val originalName = file.name
        val isBatchFileOriginal = originalName.endsWith("_batch.bin")
        val originalSensorKey = SensorType.entries.firstOrNull { sensor -> originalName.startsWith(sensor.key + "_") }?.key ?: ""

        val parent = file.parentFile
        val sendingFile = File(parent, file.name + ".sending")
        var processingFile = file
        try {
            if (file.exists()) {
                val renamed = file.renameTo(sendingFile)
                if (renamed) {
                    Log.i("MQTT", "Renamed ${file.name} -> ${sendingFile.name} for safe sending")
                    processingFile = sendingFile
                } else {
                    Log.i("MQTT", "Could not rename ${file.name}, will process in-place")
                    processingFile = file
                }
            }
        } catch (t: Throwable) {
            Log.w("MQTT", "Rename for sending failed: ${t.message}")
            processingFile = file
        }

        var bytes: ByteArray? = null
        var sensorKey = ""
        var resolvedPatientId: String? = null
        var ts: Long? = null

        try {
            bytes = processingFile.readBytes()
            sensorKey = originalSensorKey
            try {
                val watchId = tempWatchIdForPendingSend
                val parts = processingFile.name.split('_')
                if (parts.size >= 2) {
                    val timestampStr = if (parts.size >= 3 && parts[2].matches(Regex("^\\d{4}.*"))) {
                        parts[1] + "_" + parts[2]
                    } else parts[1]
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                        val dt = sdf.parse(timestampStr)
                        if (dt != null) {
                            ts = dt.time
                            try {
                                val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
                                val dao = db.watchPairingDao()
                                val tsVal = ts!!
                                if (!watchId.isNullOrBlank()) {
                                    val pairing = kotlinx.coroutines.runBlocking { dao.getPairingForTime(watchId, tsVal) }
                                    resolvedPatientId = pairing?.patientId
                                }
                                if (resolvedPatientId.isNullOrBlank()) {
                                    try {
                                        val any = kotlinx.coroutines.runBlocking { dao.getPairingForTimeAny(tsVal) }
                                        resolvedPatientId = any?.patientId
                                    } catch (_: Exception) { /* ignore */ }
                                }
                            } catch (e: Exception) {
                                Log.w("MQTT", "Error while resolving patientId for file ${processingFile.name}: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w("MQTT", "Error while resolving patientId for file ${processingFile.name}: ${e.message}")
            }

            // Jeśli nadal nie mamy resolvedPatientId, sprawdź historię apiTokenów dla tego timestampu
            if (resolvedPatientId.isNullOrBlank() && ts != null) {
                try {
                    val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
                    val tsVal = ts
                    val tokenEntry = kotlinx.coroutines.runBlocking {
                        try {
                            db.apiTokenHistoryDao().getForTime(tsVal!!)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (tokenEntry != null) {
                        resolvedPatientId = tokenEntry.patientId
                        Log.i("MQTT", "Resolved patientId from ApiTokenHistory for file=${processingFile.name} -> $resolvedPatientId")
                    } else {
                        Log.i("MQTT", "No ApiTokenHistory entry for ts=${tsVal}, will fall back to sensor-only topic")
                    }
                } catch (e: Exception) {
                    Log.w("MQTT", "Error querying ApiTokenHistory for file ${processingFile.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("MQTT", "Error while resolving patientId for file ${processingFile.name}: ${e.message}")
        }

        var tokenSegment: String? = null
        try {
            if (!resolvedPatientId.isNullOrBlank() && ts != null) {
                try {
                    val db = com.example.parkincare.data.local.DatabaseProvider.getDatabase(context)
                    val entry = kotlinx.coroutines.runBlocking {
                        try {
                            db.apiTokenHistoryDao().getForPatientAtTime(resolvedPatientId!!, ts)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (entry != null) {
                        tokenSegment = entry.apiToken
                        Log.i("MQTT", "Using historical apiToken from ApiTokenHistory for patient=${resolvedPatientId} ts=${ts}")
                    } else {
                        val anyEntry = kotlinx.coroutines.runBlocking {
                            try { db.apiTokenHistoryDao().getForTime(ts) } catch (e: Exception) { null }
                        }
                        if (anyEntry != null) {
                            tokenSegment = anyEntry.apiToken
                            Log.i("MQTT", "Using fallback apiToken from ApiTokenHistory for ts=${ts}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MQTT", "Error while fetching ApiTokenHistory entry for patient=${resolvedPatientId}: ${e.message}")
                }
            }
            // jeśli nadal nie mamy tokenSegment, użyj aktualnego tokenu
            if (tokenSegment.isNullOrBlank()) {
                try {
                    val currentToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                    if (!currentToken.isNullOrBlank()) {
                        tokenSegment = currentToken.trim('/')
                        Log.i("MQTT", "No historical token found for file=${processingFile.name}, using current token as fallback")
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w("MQTT", "Error resolving tokenSegment for file ${processingFile.name}: ${e.message}")
        }

        var finalToken = tokenSegment
        if (finalToken.isNullOrBlank()) {
            try {
                val currentToken = try { com.example.parkincare.DeviceCommunicator.getToken(context) } catch (_: Exception) { null }
                if (!currentToken.isNullOrBlank()) finalToken = currentToken.trim('/')
            } catch (_: Exception) { /* ignore */ }
        }

        if (finalToken.isNullOrBlank() && !resolvedPatientId.isNullOrBlank()) {
            Log.w("MQTT", "No apiToken found for historical patient=${resolvedPatientId} ts=${ts}. Will publish without patient-token segment to avoid receiver JWT decode errors.")
        }

        val topic = if (!finalToken.isNullOrBlank()) {
            if (sensorKey.isBlank()) finalToken else "${finalToken}/${sensorKey}"
        } else sensorKey

        val sendBytes = bytes ?: run {
            Log.w("MQTT", "No bytes to send for file ${processingFile.name}, skipping")
            // pomiń ten plik i kontynuuj z następnym
            pendingFilesToSend = pendingFilesToSend.drop(1)
            handler.postDelayed({ sendNextFileThrottled() }, 1000)
            return
        }

        try {
            // Jeśli to plik batch (zawiera wiele chunków), rozbij go i wyślij każdy chunk sekwencyjnie
            if (isBatchFileOriginal) {
                val chunks = mutableListOf<ByteArray>()
                try {
                    var pos = 0
                    while (pos + 4 <= bytes.size) {
                        val count = java.nio.ByteBuffer.wrap(bytes, pos, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        val valueCount = if (originalSensorKey == "accelerometer" || originalSensorKey == "gyroscope") 3 else 1
                        val sampleSize = 8 + 4 * valueCount
                        val chunkLen = 4 + count * sampleSize
                        if (chunkLen <= 4 || pos + chunkLen > bytes.size) break
                        val chunk = bytes.copyOfRange(pos, pos + chunkLen)
                        chunks.add(chunk)
                        pos += chunkLen
                    }
                } catch (t: Throwable) {
                    Log.w("MQTT", "Failed to parse chunks from ${processingFile.name}: ${t.message}")
                }

                if (chunks.isEmpty()) {
                    // fallback: wysyłaj cały plik jak wcześniej
                    publishWithRetry(topic, bytes, maxAttempts = 6) { success, error ->
                        if (success) {
                            fileRetryAttempts.remove(processingFile.absolutePath)
                            lastFileSentTimestamp = System.currentTimeMillis()
                            try { processingFile.delete() } catch (_: Exception) {}
                            Log.i("MQTT", "Dosłano zaległy plik (fallback single) z danymi sensora: ${processingFile.name}")
                            pendingFilesToSend = pendingFilesToSend.drop(1)
                            if (pendingFilesToSend.isNotEmpty()) handler.postDelayed({ sendNextFileThrottled() }, 1000) else sendingFiles = false
                        } else {
                            Log.e("MQTT", "Błąd wysyłki pliku ${processingFile.name}: $error")
                            sendingFiles = false
                        }
                    }
                    return
                }

                var sentCount = 0

                fun sendChunkAt(idx: Int) {
                    if (idx >= chunks.size) {
                        // wszystkie wysłane
                        try { processingFile.delete() } catch (_: Exception) {}
                        fileRetryAttempts.remove(file.absolutePath)
                        lastFileSentTimestamp = System.currentTimeMillis()
                        Log.i("MQTT", "Dosłano wszystkie chunki (${chunks.size}) z pliku ${file.name}")
                        pendingFilesToSend = pendingFilesToSend.drop(1)
                        if (pendingFilesToSend.isNotEmpty()) handler.postDelayed({ sendNextFileThrottled() }, 1000)
                        else sendingFiles = false
                        return
                    }

                    val chunk = chunks[idx]
                    val chunkSamples = try { java.nio.ByteBuffer.wrap(chunk, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int } catch (_: Throwable) { -1 }
                    Log.i("MQTT", "Publishing chunk #$idx/${chunks.size} for ${file.name}, samples=$chunkSamples, bytes=${chunk.size}")

                    publishWithRetry(topic, chunk, maxAttempts = 6) { success, error ->
                        if (success) {
                            sentCount++
                            Log.i("MQTT", "Chunk #$idx published OK for ${file.name}")
                            handler.postDelayed({ sendChunkAt(idx + 1) }, 50)
                        } else {
                            Log.e("MQTT", "Publish chunk #$idx failed for file ${file.name}: $error")
                            try {
                                val remaining = java.io.ByteArrayOutputStream()
                                for (j in idx until chunks.size) remaining.write(chunks[j])
                                val tmp = File(processingFile.parentFile, processingFile.name + ".remaining")
                                tmp.writeBytes(remaining.toByteArray())
                                try { if (processingFile.exists()) processingFile.delete() } catch (_: Throwable) {}
                                tmp.renameTo(File(processingFile.parentFile, file.name))
                                Log.i("MQTT", "Zapisano z powrotem ${chunks.size - idx} pozostałych chunków do ${file.name}")
                            } catch (t: Throwable) {
                                Log.w("MQTT", "Failed to write remaining chunks back to ${file.name}: ${t.message}")
                            }

                            val attempts = fileRetryAttempts.getOrDefault(file.absolutePath, 0) + 1
                            fileRetryAttempts[file.absolutePath] = attempts

                            if (!::mqttClient.isInitialized || !mqttClient.state.isConnected || !isServerConnected) {
                                Log.w("MQTT", "Brak połączenia po błędzie chunk publish — przerywam wysyłkę")
                                sendingFiles = false
                                return@publishWithRetry
                            }

                            if (attempts > retryMaxAttempts) {
                                Log.w("MQTT", "Plik ${file.name} przekroczył maks. prób po błędzie chunka ($attempts). Odwieszam dalsze próby na retryMaxDelayMs=$retryMaxDelayMs ms")
                                handler.postDelayed({ sendNextFileThrottled() }, retryMaxDelayMs)
                            } else {
                                val delay = (retryBaseDelayMs * (1L shl (attempts - 1))).coerceAtMost(retryMaxDelayMs)
                                Log.i("MQTT", "Retry attempt #$attempts for file ${file.name} after chunk failure, delay=$delay ms")
                                handler.postDelayed({ sendNextFileThrottled() }, delay)
                            }
                        }
                    }
                }
                sendChunkAt(0)
                return
            }

            publishWithRetry(topic, sendBytes, maxAttempts = 6) { success, error ->
                  if (success) {
                      fileRetryAttempts.remove(file.absolutePath)

                      lastFileSentTimestamp = System.currentTimeMillis()

                      try { processingFile.delete() } catch (_: Exception) {}
                      Log.i("MQTT", "Dosłano zaległy plik z danymi sensora: ${file.name}")
                      pendingFilesToSend = pendingFilesToSend.drop(1)

                      if (pendingFilesToSend.isNotEmpty()) {
                          handler.postDelayed({ sendNextFileThrottled() }, 1000)
                      } else {
                          sendingFiles = false
                      }
                  } else {
                      Log.e("MQTT", "Błąd wysyłki pliku ${file.name}: $error")

                      if (!::mqttClient.isInitialized || !mqttClient.state.isConnected || !isServerConnected) {
                          Log.w("MQTT", "Błąd wskazuje na brak połączenia — przerywam wysyłkę, pliki pozostają na dysku")
                          sendingFiles = false
                          return@publishWithRetry
                      }

                      val attempts = fileRetryAttempts.getOrDefault(processingFile.absolutePath, 0) + 1
                      fileRetryAttempts[processingFile.absolutePath] = attempts
                       val delay = retryBaseDelayMs * (1L shl (attempts - 1)).coerceAtMost(Long.MAX_VALUE)
                       val cappedDelay = delay.coerceAtMost(retryMaxDelayMs)
                       if (attempts > retryMaxAttempts) {
                          Log.w("MQTT", "Plik ${file.name} przekroczył maksymalną liczbę prób ($attempts). Odwieszam dalsze próby na dłuższy okres.")
                           handler.postDelayed({ sendNextFileThrottled() }, retryMaxDelayMs)
                       } else {
                          Log.i("MQTT", "Retry attempt #$attempts for file ${file.name}, warunkowe opóźnienie $cappedDelay ms")
                           handler.postDelayed({ sendNextFileThrottled() }, cappedDelay)
                       }
                   }
               }
         } catch (e: Exception) {
            Log.e("MQTT", "Wyjątek przy wysyłce pliku ${file.name}: ${e.message}")
             if (!::mqttClient.isInitialized || !mqttClient.state.isConnected || !isServerConnected) {
                 sendingFiles = false
                 return
             }
             pendingFilesToSend = pendingFilesToSend.drop(1)
             handler.postDelayed({ sendNextFileThrottled() }, 1000)
         }
     }
 }
