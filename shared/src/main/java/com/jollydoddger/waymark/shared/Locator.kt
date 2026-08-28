package com.jollydoddger.waymark.shared

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPS fix + compass heading, delivered as grid coordinates and a grid
 * bearing, ready to draw. Foreground only — started in onResume, stopped in
 * onPause; a nav screen that is not on screen needs no fixes.
 *
 * Heading chain: rotation-vector azimuth (magnetic) + declination
 * (GeomagneticField at the fix) − grid convergence = bearing from grid north,
 * which on a north-up grid map is exactly the arrow's screen rotation.
 * Smoothed with a circular low-pass so the arrow doesn't tremble.
 */
class Locator(
    context: Context,
    private val onFix: (en: En, stale: Boolean) -> Unit,
    private val onHeading: (Double?) -> Unit,
) {
    private val ctx = context.applicationContext
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var lastLoc: Location? = null
    private var lastFixAt = 0L
    private var declination = 0.0
    private var convergence = 0.0
    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private var haveHeading = false

    private val locationListener = LocationListener { loc -> accept(loc, stale = false) }

    private fun accept(loc: Location, stale: Boolean) {
        lastLoc = loc
        if (!stale) lastFixAt = System.currentTimeMillis()
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            loc.altitude.toFloat(), System.currentTimeMillis(),
        ).declination.toDouble()
        convergence = Bng.convergenceDeg(loc.latitude, loc.longitude)
        onFix(Bng.fromWgs84(loc.latitude, loc.longitude), stale)
    }

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val magnetic = Math.toDegrees(orientation[0].toDouble())
            val grid = magnetic + declination - convergence
            val rad = Math.toRadians(grid)
            val a = 0.15
            smoothedSin = smoothedSin * (1 - a) + sin(rad) * a
            smoothedCos = smoothedCos * (1 - a) + cos(rad) * a
            haveHeading = true
            onHeading(Math.toDegrees(atan2(smoothedSin, smoothedCos)))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // The arrow must not claim a position it no longer has: a fix that stops
    // updating goes visibly grey rather than sitting confidently wrong.
    private val staleCheck = object : Runnable {
        override fun run() {
            val loc = lastLoc
            if (loc != null && System.currentTimeMillis() - lastFixAt > 30_000) {
                onFix(Bng.fromWgs84(loc.latitude, loc.longitude), true)
            }
            handler.postDelayed(this, 10_000)
        }
    }

    /** Caller has checked ACCESS_FINE_LOCATION; a refusal just means no arrow. */
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            // Instant approximate arrow from the phone's existing fix while
            // the GPS warms up, honestly marked stale.
            val warm = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            warm?.let { accept(it, stale = System.currentTimeMillis() - it.time > 30_000) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // No permission: the map still works, there is just no arrow.
        } catch (e: IllegalArgumentException) {
            // No GPS provider (emulator); nothing to do.
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        } ?: onHeading(null)
        handler.postDelayed(staleCheck, 10_000)
    }

    fun stop() {
        lm.removeUpdates(locationListener)
        sm.unregisterListener(sensorListener)
        handler.removeCallbacks(staleCheck)
    }
}
