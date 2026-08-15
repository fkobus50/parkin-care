package com.example.parkincare.util

import android.content.Context
import com.example.parkincare.util.ParkinLogger as Log
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class FileManager(private val context: Context) {
    companion object {
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024 // 50 MB zapasu dla OS
    }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var jsonWriters: MutableMap<String, FileWriter> = mutableMapOf()
    private var binaryStreams: MutableMap<String, java.io.BufferedOutputStream> = mutableMapOf()
    private val gson = Gson()

    fun saveSensorBatchToBinaryAsync(
        sensorName: String,
        data: ByteArray,
        timestamp: Long,
        saveLocalOnly: Boolean = false,
        serverAvailable: Boolean = false,
        onDone: ((success: Boolean, error: Exception?) -> Unit)? = null
    ): Future<Boolean> {
        return ioExecutor.submit(Callable<Boolean> {
            try {
                // Wykorzystaj istniejącą, synchroniczną implementację - uruchomioną w executorze
                saveSensorBatchToBinary(sensorName, data, timestamp, saveLocalOnly, serverAvailable)
                onDone?.invoke(true, null)
                true
            } catch (e: Exception) {
                onDone?.invoke(false, e)
                throw e
            }
        })
    }

    fun saveSensorValueToJson(sensorName: String, value: Float, timestamp: Long, saveLocalOnly: Boolean = false, serverAvailable: Boolean = false) {
        val available = getAvailableStorage()
        if ((saveLocalOnly || !serverAvailable) && available < MIN_FREE_SPACE_BYTES) {
            Log.w("FileManager", "Brak wystarczającej ilości miejsca na zapis JSON! (available=$available, minFree=$MIN_FREE_SPACE_BYTES)")
            return
        }

        val sdfMinute = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val minuteString = sdfMinute.format(Date(timestamp))
        val key = "${sensorName}_${minuteString}"
        if (!jsonWriters.containsKey(key)) {
            jsonWriters[key]?.close()
            val filename = "${sensorName}_${minuteString}.json"
            val file = File(context.filesDir, filename)
            jsonWriters[key] = FileWriter(file, true)
        }
        val writer = jsonWriters[key] ?: return
        val json = gson.toJson(mapOf("timestamp" to timestamp, "value" to value))
        writer.append(json)
        writer.append("\n")
        writer.flush()
    }

    fun saveSensorArrayToJson(sensorName: String, values: FloatArray, timestamp: Long, saveLocalOnly: Boolean = false, serverAvailable: Boolean = false) {
        val available = getAvailableStorage()
        if ((saveLocalOnly || !serverAvailable) && available < MIN_FREE_SPACE_BYTES) {
            Log.w("FileManager", "Brak wystarczającej ilości miejsca na zapis JSON! (available=$available, minFree=$MIN_FREE_SPACE_BYTES)")
            return
        }

        val sdfMinute = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val minuteString = sdfMinute.format(Date(timestamp))
        val key = "${sensorName}_${minuteString}"
        if (!jsonWriters.containsKey(key)) {
            jsonWriters[key]?.close()
            val filename = "${sensorName}_${minuteString}.json"
            val file = File(context.filesDir, filename)
            jsonWriters[key] = FileWriter(file, true)
        }
        val writer = jsonWriters[key] ?: return
        val json = gson.toJson(mapOf("timestamp" to timestamp, "values" to values.toList()))
        writer.append(json)
        writer.append("\n")
        writer.flush()
    }

    fun saveSensorValueToBinary(sensorName: String, value: Float, timestamp: Long, saveLocalOnly: Boolean = false, serverAvailable: Boolean = false) {
        val available = getAvailableStorage()
        if ((saveLocalOnly || !serverAvailable) && available < MIN_FREE_SPACE_BYTES) {
            //Log.w("FileManager", "Brak wystarczającej ilości miejsca na zapis BIN! (available=$available, minFree=$MIN_FREE_SPACE_BYTES)")
            return
        }

        val sdfMinute = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val minuteString = sdfMinute.format(Date(timestamp))
        val key = "${sensorName}_${minuteString}"
        if (!binaryStreams.containsKey(key)) {
            binaryStreams[key]?.flush()
            binaryStreams[key]?.close()
            val filename = "${sensorName}_${minuteString}.bin"
            val file = File(context.filesDir, filename)
            binaryStreams[key] = file.outputStream().buffered()
        }
        val out = binaryStreams[key] ?: return
        val buffer = ByteBuffer.allocate(8 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(timestamp)
        buffer.putFloat(value)
        out.write(buffer.array())
        out.flush()
    }

    fun saveSensorArrayToBinary(sensorName: String, values: FloatArray, timestamp: Long, saveLocalOnly: Boolean = false, serverAvailable: Boolean = false) {
        val available = getAvailableStorage()
        if ((saveLocalOnly || !serverAvailable) && available < MIN_FREE_SPACE_BYTES) {
            //Log.w("FileManager", "Brak wystarczającej ilości miejsca na zapis BIN! (available=$available, minFree=$MIN_FREE_SPACE_BYTES)")
            return
        }

        val sdfMinute = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val minuteString = sdfMinute.format(Date(timestamp))
        val key = "${sensorName}_${minuteString}"
        if (!binaryStreams.containsKey(key)) {
            binaryStreams[key]?.flush()
            binaryStreams[key]?.close()
            val filename = "${sensorName}_${minuteString}.bin"
            val file = File(context.filesDir, filename)
            binaryStreams[key] = file.outputStream().buffered()
        }
        val out = binaryStreams[key] ?: return
        val buffer = ByteBuffer.allocate(8 + 4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(timestamp)
        values.forEach { buffer.putFloat(it) }
        out.write(buffer.array())
        out.flush()
    }

    fun saveSensorBatchToBinary(sensorName: String, data: ByteArray, timestamp: Long, saveLocalOnly: Boolean = false, serverAvailable: Boolean = false) {
        val available = getAvailableStorage()
        if ((saveLocalOnly || !serverAvailable) && available < MIN_FREE_SPACE_BYTES) {
            Log.w("FileManager", "Brak wystarczającej ilości miejsca na zapis BIN batch! (available=$available, minFree=$MIN_FREE_SPACE_BYTES)")
            return
        }

        val sdfMinute = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val minuteString = sdfMinute.format(Date(timestamp))
        val filename = "${sensorName}_${minuteString}_batch.bin"
        val file = File(context.filesDir, filename)
        file.appendBytes(data)

        // DIAGNOSTYKA: policz ile próbek jest w pliku po dopisaniu (parsujemy kolejne chunki: [int count][samples...])
        try {
            val all = file.readBytes()
            var pos = 0
            var totalSamples = 0
            // określ valueCount na podstawie nazwy sensora
            val valueCount = if (sensorName == "accelerometer" || sensorName == "gyroscope") 3 else 1
            val sampleSize = 8 + 4 * valueCount
            while (pos + 4 <= all.size) {
                val count = java.nio.ByteBuffer.wrap(all, pos, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                totalSamples += count
                val chunkLen = 4 + count * sampleSize
                if (chunkLen <= 4) break
                if (pos + chunkLen > all.size) {
                    // plik uszkodzony lub niekompletny - zakończ parsowanie
                    break
                }
                pos += chunkLen
            }
            Log.i("FILE_DIAG", "saveSensorBatchToBinary: appended ${data.size} B to $filename, totalSamples=$totalSamples, fileSize=${file.length()}")
        } catch (t: Throwable) {
            Log.w("FILE_DIAG", "saveSensorBatchToBinary: diagnostics failed for $filename: ${t.message}")
        }
    }

    fun logBatchFilesDiagnostics() {
        try {
            val files = context.filesDir.listFiles() ?: return
            files.filter { it.name.endsWith("_batch.bin") }.forEach { file ->
                try {
                    val bytes = file.readBytes()
                    var pos = 0
                    var chunks = 0
                    var totalSamples = 0
                    // określ sensor na podstawie nazwy pliku
                    val sensorName = file.name.substringBefore('_')
                    val valueCount = if (sensorName == "accelerometer" || sensorName == "gyroscope") 3 else 1
                    val sampleSize = 8 + 4 * valueCount
                    while (pos + 4 <= bytes.size) {
                        val count = java.nio.ByteBuffer.wrap(bytes, pos, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        val chunkLen = 4 + count * sampleSize
                        if (chunkLen <= 4) break
                        if (pos + chunkLen > bytes.size) break
                        chunks++
                        totalSamples += count
                        pos += chunkLen
                    }
                    Log.i("FILE_DIAG", "Batch file=${file.name}, fileSize=${file.length()}, parsedChunks=$chunks, totalSamples=$totalSamples")
                } catch (e: Exception) {
                    Log.w("FILE_DIAG", "Failed to parse batch file ${file.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("FILE_DIAG", "logBatchFilesDiagnostics failed: ${e.message}")
        }
    }

    fun cleanup() {
        jsonWriters.values.forEach {
            it.flush()
            it.close()
        }
        jsonWriters.clear()
        binaryStreams.values.forEach {
            it.flush()
            it.close()
        }
        binaryStreams.clear()
        // Zamknij executor przy cleanup, aby nie pozostawiać wątków w tle
        try {
            ioExecutor.shutdownNow()
        } catch (_: Throwable) {}
    }

    // Zwraca sumę rozmiarów plików z danymi sensorów (binarnych i json)
    fun getSensorFilesSize(): Long {
        val files = context.filesDir.listFiles() ?: return 0L
        return files.filter {
            it.name.endsWith(".bin") ||
            it.name.endsWith(".json") ||
            (it.name.startsWith("audio_fragment_") && it.name.endsWith(".3gp"))
        }.sumOf { it.length() }
    }

    fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(context.filesDir.path)
        return stat.availableBytes
    }
}
