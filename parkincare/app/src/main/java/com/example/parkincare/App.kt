package com.example.parkincare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.app.Activity
import android.os.Bundle
import com.example.parkincare.util.ParkinLogger

class App : Application() {
    private val heartbeatInterval = 30_000L
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            ParkinLogger.i("APP_HEARTBEAT", "alive pid=${Process.myPid()} ts=${System.currentTimeMillis()}")
            heartbeatHandler.postDelayed(this, heartbeatInterval)
        }
    }

    // prosty licznik aktywności do wykrywania foreground/background
    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()
        DeviceCommunicator.getInstance(this)
        ParkinLogger.init(this)

        ParkinLogger.i("APP", "onCreate pid=${Process.myPid()}")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                if (activityCount == 1) {
                    ParkinLogger.i("APP_LIFECYCLE", "foreground pid=${Process.myPid()}")
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount == 0) {
                    ParkinLogger.i("APP_LIFECYCLE", "background pid=${Process.myPid()}")
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ParkinLogger.e("UNCAUGHT", "Uncaught exception in thread=${thread.name} pid=${Process.myPid()} message=${throwable.message}", throwable)
                if (throwable is OutOfMemoryError) {
                    ParkinLogger.e("OOM", "OutOfMemoryError detected in thread=${thread.name} pid=${Process.myPid()}", throwable)
                }
            } catch (t: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        heartbeatHandler.post(heartbeatRunnable)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                "medicine_channel",
                getString(R.string.medicine_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)

            val alarmChannel = NotificationChannel(
                "medicine_alarm_channel",
                getString(R.string.medicine_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            alarmChannel.enableVibration(true)
            alarmChannel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                null
            )
            alarmChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            alarmChannel.description = getString(R.string.medicine_alarm_channel_description)
            alarmChannel.setShowBadge(true)
            alarmChannel.setBypassDnd(true)
            alarmChannel.enableLights(true)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ParkinLogger.i("APP_MEMORY", "onTrimMemory level=$level pid=${Process.myPid()}")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ParkinLogger.i("APP_MEMORY", "onLowMemory pid=${Process.myPid()}")
    }

    override fun onTerminate() {
        super.onTerminate()
        ParkinLogger.i("APP", "onTerminate pid=${Process.myPid()}")
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    companion object {
        lateinit var deviceCommunicator: DeviceCommunicator
            private set
    }
}
