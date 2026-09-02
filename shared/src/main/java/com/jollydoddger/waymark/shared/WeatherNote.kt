package com.jollydoddger.waymark.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

/**
 * "Rain from 14:00", said out loud on whichever device is on him.
 *
 * Shared because it is posted twice: on the phone by the job that reads
 * the forecast, and on the watch by the listener that receives the same
 * words over the Data Layer. The phone's copy is marked local-only, so
 * the wrist gets exactly one buzz — the watch app's own — rather than the
 * phone's mirrored by the wearable manager on top of it.
 */
object WeatherNote {
    const val CHANNEL = "waymark_weather"
    const val ID = 4401

    fun show(ctx: Context, title: String, text: String, localOnly: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Weather ahead", NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Rain coming, rain clearing, sun coming — while a walk is recorded"
                    enableVibration(true)
                    // Two short, one long: not the mark's pattern, so the
                    // wrist can tell a shower from a summit without looking.
                    vibrationPattern = longArrayOf(0, 200, 150, 200, 150, 700)
                },
            )
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 3, it, PendingIntent.FLAG_IMMUTABLE)
        }
        nm.notify(
            ID,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(open)
                .setLocalOnly(localOnly)
                .setAutoCancel(true)
                .build(),
        )
    }
}
