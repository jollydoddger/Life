package com.jollydoddger.waymark

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Sun
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun will be: hold the phone up and its track across today's sky
 * is drawn over the camera, with sunset marked where it will actually go
 * down behind the hills in front of you.
 *
 * The arc is real astronomy ([Sun], verified in CI), not a decoration. The
 * one soft part is the phone's compass, which is a magnetometer in a pocket
 * near a metal zip — so the view says which way it thinks it is pointing
 * and lets you judge, rather than implying survey accuracy.
 *
 * Works without the camera too: refuse the permission, or have no camera
 * free, and the same arc is drawn against a plain sky. The information is
 * the point; the camera is the nice-to-have.
 */
class SunActivity : Activity() {

    private lateinit var overlay: SkyOverlay
    private var texture: TextureView? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var bg: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var sensors: SensorManager
    private var declination = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(SensorManager::class.java)

        overlay = SkyOverlay(this)
        val root = FrameLayout(this)
        texture = TextureView(this).also {
            root.addView(it, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)

        // Where: the fix the map already had, passed in, so this screen never
        // waits on GPS of its own.
        val e = intent.getDoubleExtra("e", Double.NaN)
        val n = intent.getDoubleExtra("n", Double.NaN)
        if (!e.isNaN() && !n.isNaN()) {
            val (lat, lon) = Bng.toWgs84(En(e, n))
            overlay.setPlace(lat, lon)
            declination = GeomagneticField(
                lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis(),
            ).declination.toDouble()
        }

        overlay.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(orientation, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    override fun onPause() {
        sensors.unregisterListener(orientation)
        stopCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, granted: IntArray) {
        super.onRequestPermissionsResult(code, perms, granted)
        if (code == 7) {
            if (granted.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                // No camera is not no feature: say so and draw the sky itself.
                overlay.setNoCamera("No camera — the sun's track is drawn on a plain sky instead.")
            }
        }
    }

    // --- orientation ---------------------------------------------------------

    private val rotation = FloatArray(9)

    private val orientation = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            overlay.setCompassTrusted(accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        }

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            overlay.setAim(
                // Magnetic → true north, which is what solar azimuths are in.
                azimuth = Aim.azimuth(rotation) + declination,
                elevation = Aim.elevation(rotation),
                roll = Aim.roll(rotation),
            )
        }
    }

    // --- camera --------------------------------------------------------------

    private fun startCamera() {
        val view = texture ?: return
        if (!view.isAvailable) {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
            return
        }
        openCamera()
    }

    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java)
        val id = runCatching {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull() ?: run {
            overlay.setNoCamera("No back camera found — plain sky instead.")
            return
        }

        // The lens's real field of view, so the arc lands where the sun is
        // rather than where a guessed focal length would put it.
        runCatching {
            val ch = manager.getCameraCharacteristics(id)
            val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            if (size != null && focal != null && focal > 0f) {
                overlay.setFieldOfView(
                    2 * Math.toDegrees(atan((size.width / (2 * focal)).toDouble())),
                )
            }
        }

        bg = HandlerThread("sun-cam").apply { start() }
        bgHandler = Handler(bg!!.looper)

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val st = texture?.surfaceTexture ?: return
                    st.setDefaultBufferSize(1280, 720)
                    val surface = Surface(st)
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runCatching {
                                    request.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    )
                                    s.setRepeatingRequest(request.build(), null, bgHandler)
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                runOnUiThread { overlay.setNoCamera("Camera wouldn't start — plain sky instead.") }
                            }
                        },
                        bgHandler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) { stopCamera() }

                override fun onError(device: CameraDevice, error: Int) {
                    stopCamera()
                    runOnUiThread { overlay.setNoCamera("Camera error $error — plain sky instead.") }
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            overlay.setNoCamera("Camera permission refused — plain sky instead.")
        } catch (e: Exception) {
            overlay.setNoCamera("Camera unavailable — plain sky instead.")
        }
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
        bg?.quitSafely()
        bg = null
        bgHandler = null
    }
}

/**
 * The drawing half: today's solar arc, the sun on it, and the markers that
 * matter to someone deciding whether to set off — golden hour, sunset,
 * dark. Projected gnomonically through the lens's own field of view.
 */
class SkyOverlay(activity: Activity) : View(activity) {

    private val density = resources.displayMetrics.density
    private var lat = Double.NaN
    private var lon = Double.NaN
    private var fovDeg = 66.0 // a sane phone-camera default until the lens says
    private var aimAz = 0.0
    private var aimEl = 0.0
    private var aimRoll = 0.0
    private var compassTrusted = true
    private var cameraNote: String? = null

    private val clock = SimpleDateFormat("HH:mm", Locale.UK)

    fun setPlace(latitude: Double, longitude: Double) {
        lat = latitude
        lon = longitude
        invalidate()
    }

    fun setFieldOfView(deg: Double) {
        if (deg > 20 && deg < 140) fovDeg = deg
        invalidate()
    }

    fun setAim(azimuth: Double, elevation: Double, roll: Double) {
        aimAz = azimuth
        aimEl = elevation
        aimRoll = roll
        invalidate()
    }

    fun setCompassTrusted(trusted: Boolean) {
        if (compassTrusted != trusted) { compassTrusted = trusted; invalidate() }
    }

    fun setNoCamera(note: String) {
        cameraNote = note
        invalidate()
    }

    // --- paints ---------------------------------------------------------------

    private val skyPaint = Paint()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 214, 92)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 2f * density
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 206, 61) }
    private val sunGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 206, 61) }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1.5f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 20, 22, 24) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }
    private val titleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
    }
    private val path = Path()

    /** Screen position of a sky direction, or null if it is behind you. */
    private fun project(azimuth: Double, elevation: Double): FloatArray? {
        var dAz = (azimuth - aimAz).mod(360.0)
        if (dAz > 180) dAz -= 360
        val dEl = elevation - aimEl
        if (abs(dAz) > 80 || abs(dEl) > 80) return null
        val focal = (width / 2.0) / tan(Math.toRadians(fovDeg / 2))
        val x = tan(Math.toRadians(dAz)) * focal
        val y = -tan(Math.toRadians(dEl)) * focal
        // Undo the phone's roll so the arc stays level with the real horizon.
        val r = Math.toRadians(-aimRoll)
        val rx = x * cos(r) - y * sin(r)
        val ry = x * sin(r) + y * cos(r)
        return floatArrayOf((width / 2 + rx).toFloat(), (height / 2 + ry).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        if (cameraNote != null) {
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.rgb(38, 86, 148), Color.rgb(176, 196, 205),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)
        }

        if (lat.isNaN()) {
            box(canvas, listOf("No GPS fix yet — the sun's track needs to know where you are."), 16f)
            return
        }

        val now = System.currentTimeMillis()
        val day = Sun.dayStart(now)
        val rise = Sun.sunrise(now, lat, lon)
        val set = Sun.sunset(now, lat, lon)

        drawHorizonAndCompass(canvas)

        // The whole day's arc, sampled every four minutes: solid ahead of
        // now, ghosted behind it, so "where it still has to go" reads at a
        // glance.
        val from = rise ?: day
        val to = set ?: (day + 86_400_000L)
        var started = false
        path.rewind()
        var t = from
        while (t <= to) {
            val p = Sun.positionAt(t, lat, lon)
            val xy = project(p.azimuth, p.elevation)
            if (xy == null) { started = false } else {
                if (!started) { path.moveTo(xy[0], xy[1]); started = true } else path.lineTo(xy[0], xy[1])
            }
            t += 4 * 60_000L
        }
        canvas.drawPath(path, arcPaint)

        // Markers along it.
        Sun.goldenHourStart(now, lat, lon)?.let { mark(canvas, it, "golden hour") }
        set?.let { mark(canvas, it, "sunset ${clock.format(Date(it))}") }
        Sun.civilDusk(now, lat, lon)?.let { mark(canvas, it, "dark ${clock.format(Date(it))}") }
        rise?.let { if (now < it) mark(canvas, it, "sunrise ${clock.format(Date(it))}") }

        // The sun itself.
        val here = Sun.positionAt(now, lat, lon)
        project(here.azimuth, here.elevation)?.let { xy ->
            canvas.drawCircle(xy[0], xy[1], 26f * density, sunGlow)
            canvas.drawCircle(xy[0], xy[1], 11f * density, sunPaint)
        }

        // The words, bottom-left: what the arc cannot say by itself.
        val lines = ArrayList<String>()
        lines.add(
            if (here.elevation > 0) {
                "Sun now: %.0f° up, %s (%.0f°)".format(
                    here.elevation, Sun.compass(here.azimuth), here.azimuth,
                )
            } else {
                "Sun is below the horizon (%.0f°)".format(here.elevation)
            },
        )
        set?.let {
            val az = Sun.positionAt(it, lat, lon).azimuth
            val mins = (it - now) / 60_000L
            lines.add(
                "Sets ${clock.format(Date(it))} at ${Sun.compass(az)} (%.0f°)".format(az) +
                    if (mins in 0..600) ", in ${mins / 60}h ${mins % 60}m" else "",
            )
        } ?: lines.add("The sun does not set here today.")
        Sun.civilDusk(now, lat, lon)?.let { lines.add("Useful light until ${clock.format(Date(it))}") }
        if (!compassTrusted) {
            lines.add("Compass unsure — figure-of-eight the phone to settle it.")
        }
        cameraNote?.let { lines.add(it) }
        lines.add("Tap to close")
        box(canvas, lines, 12f)
    }

    private fun mark(canvas: Canvas, timeMs: Long, label: String) {
        val p = Sun.positionAt(timeMs, lat, lon)
        val xy = project(p.azimuth, p.elevation) ?: return
        canvas.drawCircle(xy[0], xy[1], 7f * density, markPaint)
        val w = labelText.measureText(label)
        canvas.drawRoundRect(
            xy[0] + 10f * density, xy[1] - 11f * density,
            xy[0] + 18f * density + w, xy[1] + 8f * density,
            4f * density, 4f * density, labelBg,
        )
        canvas.drawText(label, xy[0] + 14f * density, xy[1] + 3f * density, labelText)
    }

    /** The true horizon, and where the cardinal points sit along it. */
    private fun drawHorizonAndCompass(canvas: Canvas) {
        path.rewind()
        var started = false
        var a = aimAz - 80
        while (a <= aimAz + 80) {
            val xy = project(a, 0.0)
            if (xy == null) started = false
            else if (!started) { path.moveTo(xy[0], xy[1]); started = true } else path.lineTo(xy[0], xy[1])
            a += 4.0
        }
        canvas.drawPath(path, horizonPaint)

        for (deg in 0 until 360 step 30) {
            val xy = project(deg.toDouble(), 0.0) ?: continue
            val name = Sun.compass(deg.toDouble())
            canvas.drawLine(xy[0], xy[1] - 8f * density, xy[0], xy[1] + 8f * density, pastPaint)
            canvas.drawText(name, xy[0] - labelText.measureText(name) / 2, xy[1] + 24f * density, labelText)
        }
    }

    private fun box(canvas: Canvas, lines: List<String>, textSp: Float) {
        labelText.textSize = textSp * density
        val pad = 12f * density
        val lh = 20f * density
        val w = (lines.maxOfOrNull { labelText.measureText(it) } ?: 0f) + pad * 2
        val h = lines.size * lh + pad * 2 - (lh - labelText.textSize)
        val top = height - h - 24f * density
        canvas.drawRoundRect(
            16f * density, top, 16f * density + w, top + h,
            8f * density, 8f * density, labelBg,
        )
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, 16f * density + pad, top + pad + labelText.textSize + i * lh, titleText)
        }
    }
}
