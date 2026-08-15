package com.example.parkincare.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Handler
import androidx.core.content.ContextCompat
import com.example.parkincare.mqtt.MqttManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.example.parkincare.util.ParkinLogger as Log

class AudioRecorderManager(
    private val context: Context,
    private val mqttManager: MqttManager,
    private val onFragmentSaved: (() -> Unit)? = null,
    private val onFragmentSent: (() -> Unit)? = null
) {
    private var mediaRecorder: MediaRecorder? = null
    private var audioTempFile: File? = null
    private val isAudioRecording = AtomicBoolean(false)
    private val audioHandler = Handler(context.mainLooper)
    private val audioFragmentMillis = 60_000L

    private var isMediaRecorderRecording = false
    private val mediaRecorderLock = Any()

    @Volatile
    var isAudioActive = false
        private set

    @Volatile
    var heartbeatUnavailable: Boolean = false
        set(value) {
            field = value
        }

    fun startRecording() {
        synchronized(mediaRecorderLock) {
            if (mediaRecorder != null || isAudioRecording.get()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("AUDIO", "Brak uprawnień do nagrywania dźwięku")
                return
            }
            try {
                audioTempFile = File.createTempFile("audio_fragment_", ".3gp", context.cacheDir)
                mediaRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(audioTempFile!!.absolutePath)
                    setOnErrorListener { mr, what, extra ->
                        Log.e("AUDIO", "MediaRecorder error: what=$what, extra=$extra hash=${mr.hashCode()}")
                        try { mr.release() } catch (_: Exception) {}
                        if (mediaRecorder === mr) mediaRecorder = null
                        isAudioRecording.set(false)
                        isMediaRecorderRecording = false
                    }
                    setOnInfoListener { mr, what, extra ->
                        Log.i("AUDIO", "MediaRecorder info: what=$what, extra=$extra hash=${mr.hashCode()}")
                    }
                    prepare()
                    start()
                    isMediaRecorderRecording = true
                    Log.d("AUDIO", "MediaRecorder start hash=${this.hashCode()}")
                }
                isAudioRecording.set(true)
                isAudioActive = true
                Log.i("AUDIO", "Rozpoczęto nagrywanie dźwięku: ${audioTempFile!!.absolutePath}")
                audioHandler.postDelayed(audioFragmentRunnable, audioFragmentMillis)
            } catch (e: Exception) {
                Log.e("AUDIO", "Błąd nagrywania dźwięku: ${e.message}")
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
                isAudioRecording.set(false)
                isMediaRecorderRecording = false
                isAudioActive = false
            }
        }
    }

    fun stopRecording() {
        synchronized(mediaRecorderLock) {
            isAudioRecording.set(false)
            isAudioActive = false
            audioHandler.removeCallbacks(audioFragmentRunnable)
            try {
                mediaRecorder?.let { mr ->
                    if (isMediaRecorderRecording) {
                        try {
                            Log.d("AUDIO", "Wywołanie stop() na MediaRecorder (stopAudioRecording) hash=${mr.hashCode()}")
                            mr.stop()
                            isMediaRecorderRecording = false
                        } catch (e: IllegalStateException) {
                            Log.e("AUDIO", "IllegalStateException przy stop() MediaRecorder (stopAudioRecording): hash=${mr.hashCode()}", e)
                            isMediaRecorderRecording = false
                        } catch (e: Exception) {
                            Log.e("AUDIO", "Błąd stop() MediaRecorder (stopAudioRecording): hash=${mr.hashCode()}", e)
                            isMediaRecorderRecording = false
                        }
                    }
                    try {
                        Log.d("AUDIO", "Wywołanie release() na MediaRecorder (stopAudioRecording) hash=${mr.hashCode()}")
                        mr.release()
                    } catch (e: Exception) {
                        Log.e("AUDIO", "Błąd release() MediaRecorder (stopAudioRecording): hash=${mr.hashCode()}", e)
                    }
                    if (mediaRecorder === mr) mediaRecorder = null
                }
            } catch (e: Exception) {
                Log.e("AUDIO", "Błąd zatrzymania MediaRecorder: ", e)
            }
            // Zapisz niepełny fragment
            saveCurrentFragment()
            audioTempFile = null
            isMediaRecorderRecording = false
        }
    }

    fun updateSettings(isEnabled: Boolean) {
        if (isEnabled && !isAudioActive) {
            startRecording()
        } else if (!isEnabled && isAudioActive) {
            stopRecording()
        }
    }

    fun sendPendingAudioFiles() {
        val prefs = context.getSharedPreferences("sensor_prefs", Context.MODE_PRIVATE)
        val saveLocalOnly = prefs.getBoolean("save_local_only", false)
        val heartbeatUnavailableNow = prefs.getBoolean("heartbeat_unavailable", heartbeatUnavailable)
        Log.i("AUDIO", "sendPendingAudioFiles: saveLocalOnly=$saveLocalOnly, mqttConnected=${mqttManager.isConnected}, heartbeatUnavailable=$heartbeatUnavailableNow")
        if (!saveLocalOnly && mqttManager.isConnected && !heartbeatUnavailableNow) {

            val files = context.filesDir.listFiles()?.filter { it.name.startsWith("audio_fragment_") && it.name.endsWith(".3gp") } ?: emptyList()
            if (files.isEmpty()) {
                Log.i("AUDIO", "Brak zaległych plików audio do wysłania")
            } else {
                Log.i("AUDIO", "Wysyłam ${files.size} zaległych plików audio")
                files.forEach { file ->
                    try {
                        val bytes = file.readBytes()
                        mqttManager.publishAudio(bytes) { success, error ->
                            if (success) {
                                try { file.delete() } catch (_: Exception) {}
                                Log.i("AUDIO", "Wysłano zaległy plik audio: ${file.name}")
                                onFragmentSent?.invoke()
                            } else {
                                Log.w("AUDIO", "Nie udało się wysłać pliku ${file.name}: $error")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AUDIO", "Błąd podczas wysyłki pliku ${file.name}: ${e.message}")
                    }
                }
            }
        } else {
            Log.i("AUDIO", "Pomijam wysyłkę zaległych plików audio: brak połączenia lub heartbeat")
        }
    }

    private val audioFragmentRunnable = object : Runnable {
        override fun run() {
            synchronized(mediaRecorderLock) {
                if (!isAudioRecording.get()) return
                try {
                    mediaRecorder?.let { mr ->
                        if (isMediaRecorderRecording) {
                            try {
                                Log.d("AUDIO", "Wywołanie stop() na MediaRecorder hash=${mr.hashCode()}")
                                mr.stop()
                                isMediaRecorderRecording = false
                            } catch (e: IllegalStateException) {
                                Log.e("AUDIO", "IllegalStateException przy stop() MediaRecorder: hash=${mr.hashCode()}", e)
                                isMediaRecorderRecording = false
                            } catch (e: Exception) {
                                Log.e("AUDIO", "Błąd stop() MediaRecorder: hash=${mr.hashCode()}", e)
                                isMediaRecorderRecording = false
                            }
                        }
                        try {
                            Log.d("AUDIO", "Wywołanie release() na MediaRecorder hash=${mr.hashCode()}")
                            mr.release()
                        } catch (e: Exception) {
                            Log.e("AUDIO", "Błąd release() MediaRecorder: hash=${mr.hashCode()}", e)
                        }
                        if (mediaRecorder === mr) mediaRecorder = null
                    }

                    saveCurrentFragment()
                    if (isAudioActive) {
                        startNextFragment()
                    }
                } catch (e: Exception) {
                    Log.e("AUDIO", "Błąd obsługi fragmentu audio: ${e.message}")
                    try { mediaRecorder?.release() } catch (_: Exception) {}
                    mediaRecorder = null
                    isAudioRecording.set(false)
                    isMediaRecorderRecording = false
                }
            }
        }
    }

    private fun saveCurrentFragment() {
        val fragmentFile = audioTempFile
        if (fragmentFile != null && fragmentFile.exists()) {
            val audioBytes = fragmentFile.readBytes()
            val prefs = context.getSharedPreferences("sensor_prefs", Context.MODE_PRIVATE)
            val saveLocalOnly = prefs.getBoolean("save_local_only", false)
            val heartbeatUnavailableNow = prefs.getBoolean("heartbeat_unavailable", heartbeatUnavailable)
            Log.i("AUDIO", "Próba wysyłki audio: saveLocalOnly=$saveLocalOnly, mqttConnected=${mqttManager.isConnected}, heartbeatUnavailable=$heartbeatUnavailableNow")
            if (!saveLocalOnly && mqttManager.isConnected && !heartbeatUnavailableNow) {
                mqttManager.publishAudio(audioBytes) { success, error ->
                    if (success) {
                        Log.i("AUDIO", "Wysłano fragment audio do MQTT (${audioBytes.size} bajtów)")
                        fragmentFile.delete()
                        onFragmentSent?.invoke()
                    } else {
                        Log.e("AUDIO", "Błąd wysyłki fragmentu audio: $error")
                        saveAudioFragmentToFile(audioBytes)
                        fragmentFile.delete()
                        onFragmentSaved?.invoke()
                    }
                }
            } else {
                saveAudioFragmentToFile(audioBytes)
                fragmentFile.delete()
                onFragmentSaved?.invoke()
            }
        }
    }

    private fun startNextFragment() {
        try {
            audioTempFile = File.createTempFile("audio_fragment_", ".3gp", context.cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioTempFile!!.absolutePath)
                setOnErrorListener { mr, what, extra ->
                    Log.e("AUDIO", "MediaRecorder error: what=$what, extra=$extra hash=${mr.hashCode()}")
                    try { mr.release() } catch (_: Exception) {}
                    if (mediaRecorder === mr) mediaRecorder = null
                    isAudioRecording.set(false)
                    isMediaRecorderRecording = false
                }
                setOnInfoListener { mr, what, extra ->
                    Log.i("AUDIO", "MediaRecorder info: what=$what, extra=$extra hash=${mr.hashCode()}")
                }
                prepare()
                start()
                isMediaRecorderRecording = true
                Log.d("AUDIO", "MediaRecorder start hash=${this.hashCode()}")
            }
            audioHandler.postDelayed(audioFragmentRunnable, audioFragmentMillis)
        } catch (e: Exception) {
            Log.e("AUDIO", "Błąd rozpoczęcia kolejnego fragmentu: ${e.message}")
            mediaRecorder = null
            isAudioRecording.set(false)
            isMediaRecorderRecording = false
        }
    }

    private fun saveAudioFragmentToFile(audioBytes: ByteArray) {
        try {
            val sdfMinute = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val ts = sdfMinute.format(Date())
            val file = File(context.filesDir, "audio_fragment_$ts.3gp")
            FileOutputStream(file).use { it.write(audioBytes) }
            Log.i("AUDIO", "Zapisano fragment audio do pliku: ${file.name}")
        } catch (e: Exception) {
            Log.e("AUDIO", "Błąd zapisu fragmentu audio: ${e.message}")
        }
    }

    fun cleanup() {
        stopRecording()
    }
}
