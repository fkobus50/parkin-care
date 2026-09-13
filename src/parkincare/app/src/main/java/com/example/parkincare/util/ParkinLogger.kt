package com.example.parkincare.util

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

object ParkinLogger {
    @Volatile private var writer: FileWriter? = null
    @Volatile private var currentDateString: String = ""
    private val dateFileFormat = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
    private val datePrefixFormat = SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS", Locale.getDefault())
    private const val PACKAGE_NAME = "com.example.parkincare"
    private var appContext: Context? = null

    private const val MAX_LOG_FILE_BYTES = 5L * 1024 * 1024 // 5 MB
    @Volatile private var currentLogFile: File? = null
    @Volatile private var currentFileIndex: Int = 1

    private val memoryBufferLock = Any()
    private var memoryBuffer = ByteArrayOutputStream()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ParkinLogger-IO").apply { isDaemon = true } }

    private const val FLUSH_INTERVAL_SECONDS = 10L
    private const val FLUSH_SIZE_BYTES = 64 * 1024

    private const val LOG_RETENTION_DAYS = 7

    @Volatile private var mqttManagerRef: java.lang.ref.WeakReference<com.example.parkincare.mqtt.MqttManager>? = null

    private val outboxLinesSentCounter = AtomicInteger(0)

    fun init(context: Context) {
        appContext = context.applicationContext
        ioExecutor.execute { try { rotateIfNeeded() } catch (t: Throwable) { Log.w("ParkinLogger", "init rotate failed: ${t.message}") } }
        scheduler.scheduleWithFixedDelay({ try { flushBufferIfNeeded(force = false) } catch (_: Throwable){} }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ try { cleanOldLogs(LOG_RETENTION_DAYS) } catch (_: Throwable){} }, 1, 24, TimeUnit.HOURS)
    }

    fun setMqttManager(manager: com.example.parkincare.mqtt.MqttManager) {
        mqttManagerRef = java.lang.ref.WeakReference(manager)
    }

    fun getMqttManager(): com.example.parkincare.mqtt.MqttManager? {
        return try { mqttManagerRef?.get() } catch (_: Throwable) { null }
    }

    @Synchronized private fun rotateIfNeeded() {
        val today = dateFileFormat.format(Date())
        val dir = File(appContext!!.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()

        if (today != currentDateString) {
            writer?.flush()
            writer?.close()
            currentDateString = today
            currentFileIndex = determineStartingIndex(dir, today)
            currentLogFile = getFileForIndex(dir, today, currentFileIndex)
            writer = FileWriter(currentLogFile, true)
            return
        }

        if (currentLogFile == null || writer == null) {
            currentFileIndex = determineStartingIndex(dir, today)
            currentLogFile = getFileForIndex(dir, today, currentFileIndex)
            writer = FileWriter(currentLogFile, true)
            return
        }

        try {
            if (currentLogFile!!.exists() && currentLogFile!!.length() >= MAX_LOG_FILE_BYTES) {
                writer?.flush()
                writer?.close()
                currentFileIndex += 1
                currentLogFile = getFileForIndex(dir, today, currentFileIndex)
                writer = FileWriter(currentLogFile, true)
            }
        } catch (e: Exception) {
            Log.w("ParkinLogger", "rotateIfNeeded size check failed: ${e.message}")
        }
    }

    private fun getFileForIndex(dir: File, date: String, index: Int): File {
        return if (index <= 1) File(dir, "parkincare_log_${date}.txt") else File(dir, "parkincare_log_${date}_${index}.txt")
    }

    private fun determineStartingIndex(dir: File, date: String): Int {
        val prefix = "parkincare_log_${date}"
        val files = dir.listFiles() ?: return 1
        var maxIndex = 0
        val re = Regex("^${Regex.escape(prefix)}(?:_(\\d+))?\\.txt$")
        for (f in files) {
            val m = re.find(f.name) ?: continue
            val idx = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            if (idx > maxIndex) maxIndex = idx
        }
        if (maxIndex == 0) return 1
        val candidate = getFileForIndex(dir, date, maxIndex)
        return if (candidate.exists() && candidate.length() >= MAX_LOG_FILE_BYTES) maxIndex + 1 else maxIndex
    }

    private fun formatLine(level: Char, tag: String, message: String, tr: Throwable?): String {
        val ts = datePrefixFormat.format(Date())
        val pid = Process.myPid()
        val tid = Process.myTid()
        val padTag = tag.padEnd(24, ' ')
        val padPkg = PACKAGE_NAME.padEnd(30, ' ')
        val throwablePart = if (tr != null) "\n" + tr.stackTraceToString() else ""
        return "$ts | $pid-$tid $padTag $padPkg $level  $message$throwablePart"
    }

    private fun log(level: Char, tag: String, message: String, tr: Throwable? = null) {
        if (appContext == null) return
        val line = formatLine(level, tag, message, tr)
        try {
            ioExecutor.execute { try { writeLineToFile(line) } catch (t: Throwable) { Log.w("ParkinLogger", "writeLineToFile failed: ${t.message}") } }
        } catch (e: Exception) {
            Log.w("ParkinLogger", "Nie udało się zaplanować zapisu linii: ${e.message}")
        }

        try {
            val lineCopy = line // capture
            scheduler.execute { try { publishLineAsync(lineCopy) } catch (_: Throwable){} }
        } catch (e: Exception) {
            Log.w("ParkinLogger", "Nie udało się zaplanować wysyłki linii: ${e.message}")
        }

        when (level) {
            'I' -> Log.i(tag, message, tr)
            'D' -> Log.d(tag, message, tr)
            'E' -> Log.e(tag, message, tr)
            'W' -> Log.w(tag, message, tr)
            'V' -> Log.v(tag, message, tr)
        }

        if (level == 'E' || level == 'W') {
            scheduler.execute { try { flushBufferIfNeeded(force = true) } catch (_: Throwable){} }
        }
    }

    private fun writeLineToFile(line: String) {
        try {
            rotateIfNeeded()
            writer?.apply {
                write(line)
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            Log.w("ParkinLogger", "Błąd zapisu do pliku logów: ${e.message}")
        }
    }

    private fun publishLineAsync(line: String) {
        val manager = mqttManagerRef?.get()
        val topic = "logs/entry"
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        if (manager == null || !manager.isServerConnected) {
            saveLineToOutbox(line)
            return
        }
        try {
            manager.publishData(topic, payload) { success, error ->
                if (success) {
                    // ok
                } else {
                    Log.w("ParkinLogger", "Publish entry failed: $error - saving to outbox_lines")
                    saveLineToOutbox(line)
                }
            }
        } catch (e: Exception) {
            Log.w("ParkinLogger", "Publish entry exception: ${e.message} - saving to outbox_lines")
            saveLineToOutbox(line)
        }
    }

    private fun saveLineToOutbox(line: String) {
        try {
            val lineCopy = line
            ioExecutor.execute {
                try {
                    val date = dateFileFormat.format(Date())
                    val dir = File(appContext!!.filesDir, "logs_outbox_lines")
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, "parkincare_lines_${date}.txt")
                    f.appendText(lineCopy + "\n")
                } catch (e: Exception) {
                    Log.e("ParkinLogger", "Nie udało się zapisać linii do outbox_lines (ioExecutor): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("ParkinLogger", "Nie udało się zaplanować zapisu linii do outbox_lines: ${e.message}")
        }
    }

    private fun flushBufferIfNeeded(force: Boolean) {
        val toSend: ByteArray? = synchronized(memoryBufferLock) {
            if (!force && memoryBuffer.size() < 1) return
            if (!force && memoryBuffer.size() < FLUSH_SIZE_BYTES) return
            if (force && memoryBuffer.size() == 0) return
            val bytes = memoryBuffer.toByteArray()
            memoryBuffer = ByteArrayOutputStream()
            bytes
        }

        if (toSend == null || toSend.isEmpty()) return

        ioExecutor.execute {
            try {
                val compressed = try { gzipCompress(toSend) } catch (e: Exception) {
                    Log.w("ParkinLogger", "gzipCompress failed: ${e.message}")
                    toSend
                }
                val fileId = currentDateString + "_${System.currentTimeMillis()}"
                val checksum = try { sha256Hex(compressed) } catch (e: Exception) { "" }
                try {
                    Log.i("ParkinLogger", "Saving compressed segment to outbox (fileId=$fileId, size=${compressed.size}, sha256=$checksum)")
                } catch (_: Throwable) {}
                saveCompressedToOutbox(fileId, compressed)
            } catch (e: Exception) {
                Log.e("ParkinLogger", "flushBuffer failed on ioExecutor: ${e.message}")
            }
        }
    }

    private fun saveCompressedToOutbox(fileId: String, data: ByteArray) {
        try {
            val dir = File(appContext!!.filesDir, "logs_outbox")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "parkincare_log_${fileId}.bin")
            if (f.exists()) {
                Log.d("ParkinLogger", "Outbox file already exists, skipping: ${f.name}")
                return
            }
            f.writeBytes(data)
            Log.i("ParkinLogger", "Zapisano segment do outbox: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e("ParkinLogger", "Nie udało się zapisać do outbox: ${e.message}")
        }
    }

    private fun gzipCompress(src: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(src) }
        return bos.toByteArray()
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @JvmStatic fun i(tag: String, message: String) = log('I', tag, message)
    @JvmStatic fun d(tag: String, message: String) = log('D', tag, message)
    @JvmStatic fun w(tag: String, message: String) = log('W', tag, message)
    @JvmStatic fun e(tag: String, message: String, tr: Throwable? = null) = log('E', tag, message, tr)
    @JvmStatic fun v(tag: String, message: String) = log('V', tag, message)

    /**
     * Pobierz i wyzeruj licznik wysłanych archiwalnych linii (np. na potrzeby raportu SENSOR_LOG_30S).
     */
    @JvmStatic fun consumeOutboxLinesSent(): Int {
        return outboxLinesSentCounter.getAndSet(0)
    }

    /**
     * Bezpieczne zamknięcie loggera przy stop serwisu.
     * Nie wykonuje długiego, synchronicznego flushu na wątku wywołującym —
     * wykonuje szybki swap-buffer i enqueuje ciężkie I/O na ioExecutor,
     * a następnie czeka maksymalnie timeoutMillis ms na zakończenie pracy.
     */
    fun shutdownAndAwait(timeoutMillis: Long = 1000L) {
        if (appContext == null) return

        val start = System.currentTimeMillis()
        try {
            scheduler.shutdown()
            if (!scheduler.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w("ParkinLogger", "shutdown scheduler failed: ${e.message}")
        }

        try {
            flushBufferIfNeeded(force = true)
        } catch (e: Exception) {
            Log.w("ParkinLogger", "final flushBuffer scheduling failed: ${e.message}")
        }

        try {
            ioExecutor.execute {
                try {
                    synchronized(this) {
                        try {
                            writer?.flush()
                        } catch (_: Throwable) {}
                        try {
                            writer?.close()
                        } catch (_: Throwable) {}
                        writer = null
                    }
                } catch (e: Exception) {
                    Log.w("ParkinLogger", "writer close task failed: ${e.message}")
                }
            }

            val elapsed = System.currentTimeMillis() - start
            val remaining = (timeoutMillis - elapsed).coerceAtLeast(0L)

            ioExecutor.shutdown()
            if (!ioExecutor.awaitTermination(remaining, TimeUnit.MILLISECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w("ParkinLogger", "shutdown ioExecutor failed: ${e.message}")
            try {
                ioExecutor.shutdownNow()
            } catch (_: Throwable) {}
        }
    }

    private fun cleanOldLogs(retentionDays: Int) {
        val ctx = appContext ?: return
        val cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        ioExecutor.execute {
            try {
                val dirs = listOf(
                    File(ctx.filesDir, "logs"),
                    File(ctx.filesDir, "logs_outbox"),
                    File(ctx.filesDir, "logs_outbox_lines")
                )
                for (dir in dirs) {
                    if (!dir.exists() || !dir.isDirectory) continue
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        val lastMod = runCatching { f.lastModified() }.getOrDefault(0L)
                        if (lastMod > 0L && lastMod < cutoffMillis) {
                            runCatching { f.delete() }.onFailure {
                                Log.w("ParkinLogger", "Nie udało się usunąć starego pliku: ${f.name} -> ${it.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ParkinLogger", "cleanOldLogs failed: ${e.message}")
            }
        }
    }
}
