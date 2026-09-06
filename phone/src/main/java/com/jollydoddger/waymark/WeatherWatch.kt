package com.jollydoddger.waymark

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.weatherAlerts
import com.jollydoddger.waymark.shared.Prefs.weatherLine
import com.jollydoddger.waymark.shared.Prefs.weatherLineAt
import com.jollydoddger.waymark.shared.Prefs.weatherSaid
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TrailStore
import com.jollydoddger.waymark.shared.WeatherNote
import kotlinx.coroutines.runBlocking

/**
 * The forecast, re-read for where he is while a walk is being recorded,
 * and said out loud when it changes.
 *
 * Every twenty minutes: the point forecast at the end of the trail,
 * [WeatherAhead]'s sentence for it, and — for each headline it carries
 * that this walk has not yet been told — one notification on the phone
 * and the same words to the watch. "Rain from 14:00" once; not every
 * twenty minutes until it rains. The keys that have been said reset on
 * every ●, because a new walk is a new day.
 *
 * A scheduled job rather than a thread in the recording service: the
 * service is shared with the watch and holds a GPS lock, and a forecast
 * read is a network call that should run when the phone has a network,
 * which the scheduler already knows. JobScheduler is framework, so it
 * costs no dependency, and its floor of fifteen minutes is about right —
 * a forecast that changed in the last ten is not one to act on yet.
 */
class WeatherWatchJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching { WeatherWatch.check(this) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

object WeatherWatch {
    private const val JOB_ID = 4402
    private const val PERIOD_MS = 20 * 60_000L

    /** Called on ●: forget what the last walk was told, read now, and keep reading. */
    fun start(ctx: Context) {
        ctx.weatherSaid = ""
        // Never let the forecast take the recording down with it: the
        // scheduler refused this once (a missing permission) and the crash
        // landed on ●, the one button that must always work. A refusal now
        // costs the alerts, not the walk.
        runCatching {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, WeatherWatchJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }
        // The first read happens now, not in twenty minutes: the walk is
        // starting, and "rain from two" is most useful at the car.
        Thread { runCatching { check(ctx) } }.start()
    }

    fun stop(ctx: Context) {
        runCatching { ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }

    /** Where he is: the trail's last point, or the phone's last fix. */
    private fun here(ctx: Context): En? {
        TrailStore.points(ctx).lastOrNull()?.let { return it }
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                Bng.fromWgs84(it.latitude, it.longitude)
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /** One read. Safe to call from any thread; does its own network. */
    fun check(ctx: Context) {
        if (!ctx.weatherAlerts || !ctx.recording) return
        val at = here(ctx) ?: return
        val (lat, lon) = Bng.toWgs84(at)
        val hours = Weather.point(lat, lon)
        val now = System.currentTimeMillis()
        val line = WeatherAhead.describe(hours, now)
        ctx.weatherLine = line
        ctx.weatherLineAt = now
        val said = ctx.weatherSaid.split('|').filter { it.isNotBlank() }.toMutableSet()
        var fresh: WeatherAhead.Headline? = null
        for (h in WeatherAhead.headlines(hours, now)) {
            if (said.add(h.key) && fresh == null) fresh = h
        }
        ctx.weatherSaid = said.joinToString("|")
        if (fresh != null) WeatherNote.show(ctx, fresh.title, fresh.text, localOnly = true)
        runBlocking {
            runCatching { Sync.sendWeather(ctx, line, fresh?.title, fresh?.text) }
        }
    }
}
