package com.jollydoddger.waymark

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import java.io.File

/**
 * Exists for one reason: a crash on a phone with no debugger attached is
 * invisible, and "it won't open" is all anyone can honestly report. The
 * uncaught-exception handler writes the stack to a file — shown in full by
 * the next successful open — and posts it as a notification at the moment
 * of death, so the report can carry the fact that matters instead of a
 * guess. Debugging by screenshot is still debugging.
 */
class WaymarkApp : Application() {

    companion object {
        const val CRASH_FILE = "crash.txt"
        private const val CRASH_CHANNEL = "waymark_crash"
    }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Everything here is best-effort: the process is dying and the
            // catcher must never be the thing that makes that worse.
            runCatching {
                val trace = "${java.util.Date()}\nthread ${thread.name}\n" +
                    Log.getStackTraceString(e)
                File(filesDir, CRASH_FILE).writeText(trace)
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CRASH_CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CRASH_CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_HIGH,
                        ),
                    )
                }
                nm.notify(
                    4300,
                    Notification.Builder(this, CRASH_CHANNEL)
                        .setContentTitle("Waymark crashed — this is why")
                        .setStyle(Notification.BigTextStyle().bigText(trace.take(1500)))
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .build(),
                )
            }
            previous?.uncaughtException(thread, e) ?: Runtime.getRuntime().exit(1)
        }
    }
}
