package com.jollydoddger.waymark.shared

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.jollydoddger.waymark.shared.Prefs.recording

/**
 * Keeps the trail growing with the screen off and the phone in a pocket.
 *
 * A foreground service, not a listener in the activity, because a recording
 * that quietly stops the moment the screen sleeps is a promise the app cannot
 * keep — and he would only find out at the end of the walk, when the trail is
 * a stub. The permanent notification is the price of that, and is honest: it
 * says recording is happening and offers Stop.
 *
 * Deliberately NOT using ACCESS_BACKGROUND_LOCATION: the service is only ever
 * started from the visible activity, which is exactly the case Android allows
 * without it. No extra permission, no trip to system settings.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "waymark.record.start"
        const val ACTION_STOP = "waymark.record.stop"
        const val CHANNEL = "waymark_recording"
        private const val NOTIFICATION_ID = 4101

        /** Fixes vaguer than this draw a wobble, not a walk. */
        private const val WORST_ACCURACY_M = 50f

        /** Something changed the trail; the map redraws if it is on screen. */
        const val BROADCAST_TRAIL = "waymark.trail.changed"

        fun start(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP)
            // Stopping must work even if the service already died; startService
            // on a dead service just starts and immediately stops it.
            try {
                ctx.startService(i)
            } catch (e: IllegalStateException) {
                ctx.recording = false
            }
        }
    }

    private val lm by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private val listener = LocationListener { loc: Location ->
        if (loc.hasAccuracy() && loc.accuracy > WORST_ACCURACY_M) return@LocationListener
        if (TrailStore.add(this, Bng.fromWgs84(loc.latitude, loc.longitude))) {
            sendBroadcast(Intent(BROADCAST_TRAIL).setPackage(packageName))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            recording = false
            stopSelf()
            return START_NOT_STICKY
        }

        goForeground(notification())
        recording = true
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 4_000L, 8f, listener, Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            recording = false
            stopSelf()
        } catch (e: IllegalArgumentException) {
            recording = false
            stopSelf()
        }
        // START_STICKY: if Android kills us for memory mid-walk, come back.
        return START_STICKY
    }

    override fun onDestroy() {
        lm.removeUpdates(listener)
        recording = false
        sendBroadcast(Intent(BROADCAST_TRAIL).setPackage(packageName))
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording a walk", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Recording your walk")
            .setContentText("Leaving a trail on the map")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stop,
                ).build(),
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}
