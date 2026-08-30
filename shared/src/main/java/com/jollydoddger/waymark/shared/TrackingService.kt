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
import android.os.Handler
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.warmUntil

/**
 * Keeps the trail growing with the screen off and the phone in a pocket.
 *
 * A foreground service, not a listener in the activity, because a recording
 * that quietly stops the moment the screen sleeps is a promise the app cannot
 * keep — and he would only find out at the end of the walk, when the trail is
 * a stub. The permanent notification is the price of that, and is honest: it
 * says recording is happening and offers Stop.
 *
 * On the watch it has a second job: **holding the GPS warm** between quick
 * looks. The map screen's own locator is foreground-only, so every screen
 * sleep used to release the GPS engine entirely and reopening cost twenty
 * seconds of grey arrow — on the device whose whole point is a glance
 * without getting the phone out. Warm mode keeps a slow location request
 * alive (which also keeps getLastKnownLocation fresh, so the locator's
 * seed on reopen is current rather than stale), records nothing, and stops
 * itself when [warmUntil] passes — a deadline the watch activity stamps
 * ninety minutes past every glance — and the phone stamps it too when a
 * marked-point buzz is armed without a recording running, since a buzz
 * that needs the app open is a promise about missed turns nobody can keep.
 *
 * Deliberately NOT using ACCESS_BACKGROUND_LOCATION: the service is only ever
 * started from the visible activity, which is exactly the case Android allows
 * without it. No extra permission, no trip to system settings. Warm's
 * self-stop and the stop→warm downgrade both happen inside the already-
 * foregrounded service, which keeps them inside that rule too.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "waymark.record.start"
        const val ACTION_STOP = "waymark.record.stop"
        const val ACTION_WARM = "waymark.record.warm"
        const val CHANNEL = "waymark_recording"
        private const val NOTIFICATION_ID = 4101

        /** Fixes vaguer than this draw a wobble, not a walk. */
        private const val WORST_ACCURACY_M = 50f

        /** Something changed the trail; the map redraws if it is on screen. */
        const val BROADCAST_TRAIL = "waymark.trail.changed"

        private const val WARM_CHECK_MS = 60_000L


        /** Warm fixes are slow on purpose: enough to hold the lock, far
         *  lighter than the 1-second foreground locator. */
        private const val WARM_INTERVAL_MS = 10_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        /** Hold the GPS ready without recording — the watch's quick-look
         *  keep-alive. Caller stamps [Prefs.warmUntil] first. */
        fun warm(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_WARM)
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
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Whether this instance is recording (true) or only holding warm. */
    private var tracking = false

    private val listener = LocationListener { loc: Location ->
        if (loc.hasAccuracy() && loc.accuracy > WORST_ACCURACY_M) return@LocationListener
        val en = Bng.fromWgs84(loc.latitude, loc.longitude)
        // The marked-point buzz fires in either mode: a hold that exists
        // because he armed a mark must be able to deliver it. Wrapped
        // because this runs every few seconds for hours in a foreground
        // service: whatever goes wrong in here, a missed buzz beats a
        // crash-looping app.
        runCatching {
            Marks.arrivedAt(this, en)?.let {
                Marks.buzz(this, it)
                sendBroadcast(Intent(BROADCAST_TRAIL).setPackage(packageName))
            }
        }
        // Warm mode records nothing beyond that: the fixes exist to keep
        // the chipset lock and getLastKnownLocation fresh, never the trail.
        if (!tracking) return@LocationListener
        if (TrailStore.add(this, en)) {
            sendBroadcast(Intent(BROADCAST_TRAIL).setPackage(packageName))
        }
    }


    /** The warm window's clock: past the deadline and not recording, stop.
     *  Checking a stored deadline is what ends the hold with no background
     *  service starts anywhere. */
    private val warmCheck = object : Runnable {
        override fun run() {
            if (tracking) return
            if (System.currentTimeMillis() > warmUntil) {
                stopSelf()
            } else {
                handler.postDelayed(this, WARM_CHECK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                recording = false
                // A walk ending mid-afternoon must not cold-kill the fix:
                // inside the warm window, drop back to holding rather than
                // stopping. This runs in an already-foregrounded service,
                // so it needs no background-start allowance.
                if (System.currentTimeMillis() < warmUntil) {
                    warmMode()
                    return START_NOT_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WARM -> {
                // Recording already holds the GPS harder than warm would.
                if (tracking) return START_STICKY
                goForeground(notification())
                warmMode()
                // Not sticky: resurrecting a convenience hold from a service
                // restart is the murky background-FGS path, and the next
                // glance restarts it legitimately anyway.
                return START_NOT_STICKY
            }
        }

        tracking = true
        recording = true
        goForeground(notification())
        handler.removeCallbacks(warmCheck)
        // A fresh request replaces the warm one if we are upgrading.
        lm.removeUpdates(listener)
        if (!request(4_000L, 8f)) return START_NOT_STICKY
        // START_STICKY: if Android kills us for memory mid-walk, come back.
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun warmMode() {
        tracking = false
        lm.removeUpdates(listener)
        if (!request(WARM_INTERVAL_MS, 0f)) return
        goForeground(notification())
        handler.removeCallbacks(warmCheck)
        handler.postDelayed(warmCheck, WARM_CHECK_MS)
    }

    @SuppressLint("MissingPermission")
    private fun request(intervalMs: Long, metres: Float): Boolean {
        return try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, intervalMs, metres, listener, Looper.getMainLooper(),
            )
            true
        } catch (e: SecurityException) {
            recording = false
            stopSelf()
            false
        } catch (e: IllegalArgumentException) {
            recording = false
            stopSelf()
            false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(warmCheck)
        lm.removeUpdates(listener)
        // Only a recording death changes recorded-state or needs the map
        // told; a warm hold ending is nobody's news.
        if (tracking) {
            recording = false
            sendBroadcast(Intent(BROADCAST_TRAIL).setPackage(packageName))
        }
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
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
        if (tracking) {
            builder.setContentTitle("Recording your walk")
                .setContentText("Leaving a trail on the map")
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop,
                    ).build(),
                )
        } else {
            builder.setContentTitle("Holding GPS ready")
                .setContentText("So a quick look at the map is instant")
        }
        return builder.build()
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
