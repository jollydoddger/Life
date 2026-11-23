package com.jollydoddger.photoframe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.time.LocalTime

/**
 * Always-running brain: web server, presence checks and the on/off schedule.
 * Decides whether the frame should be awake and tells the screen.
 */
class FrameService : Service(), FrameState.Listener {
    private var server: WebServer? = null
    private val presence = PresenceMonitor()
    private val handler = Handler(Looper.getMainLooper())
    private var manualSleep = false
    private var manualWakeUntil = 0L
    private var lastAutoAwake = true
    private var nsd: NsdManager? = null
    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { android.util.Log.w("PhotoFrame", "mDNS advertise failed: $code") }
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 15_000)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Give the stick a moment to finish mounting, then re-pick the storage location
            handler.postDelayed({
                MediaLibrary.init(this@FrameService)
                FrameState.libraryChanged()
            }, 2500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        MediaLibrary.init(this)
        goForeground()
        server = WebServer.forContext(this).also { it.start() }
        advertise()
        presence.start()
        FrameState.add(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(usbReceiver, f)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        FrameState.remove(this)
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { nsd?.unregisterService(nsdListener) }
        presence.stop()
        server?.stop()
        super.onDestroy()
    }

    override fun onCommand(cmd: String) {
        when (cmd) {
            "wake" -> {
                manualSleep = false
                manualWakeUntil = System.currentTimeMillis() + 60 * 60_000L // stay on for an hour, overriding schedule/presence
                FrameState.touch()
                evaluate()
            }
            "sleep" -> {
                manualSleep = true
                manualWakeUntil = 0
                evaluate()
            }
        }
    }

    /** Announces the web server on the LAN (mDNS/DNS-SD) so the phone app finds the TV without an IP. */
    private fun advertise() {
        runCatching {
            val info = NsdServiceInfo().apply {
                serviceName = "Photo Frame"
                serviceType = SERVICE_TYPE
                port = WebServer.PORT
            }
            nsd = getSystemService(NsdManager::class.java).also {
                it.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener)
            }
        }
    }

    private fun inSchedule(s: FrameSettings): Boolean {
        if (!s.scheduleEnabled) return true
        val on = runCatching { LocalTime.parse(s.onTime) }.getOrDefault(LocalTime.of(7, 0))
        val off = runCatching { LocalTime.parse(s.offTime) }.getOrDefault(LocalTime.of(23, 0))
        val now = LocalTime.now()
        return if (on <= off) now >= on && now < off else now >= on || now < off
    }

    private fun evaluate() {
        val s = SettingsStore.current
        val now = System.currentTimeMillis()
        val auto: Pair<Boolean, String> = when {
            !inSchedule(s) -> false to "Outside schedule (on ${s.onTime}–${s.offTime})"
            !presence.anyoneHome() -> false to "Nobody home"
            else -> true to ""
        }
        // A manual "sleep" lasts until the automatic rules next switch the frame on (the
        // schedule's on-time, or someone coming home), so last night's Sleep doesn't leave
        // the frame dark the next morning. With no schedule or presence it lasts until a wake.
        if (manualSleep && auto.first && !lastAutoAwake) manualSleep = false
        lastAutoAwake = auto.first
        val (awake, reason) = when {
            manualSleep -> false to "Sleeping (turned off from the remote or phone)"
            now < manualWakeUntil -> true to ""
            else -> auto
        }
        val was = FrameState.awake
        FrameState.setAwake(awake, reason)
        if (awake && !was) wakeScreen()
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        // As a screensaver the frame only shows when the TV is idle: popping the app up when
        // someone comes home would cover whatever they're watching. (An active screensaver
        // picks the change up by itself.)
        if (SettingsStore.current.showAs == "screensaver") return
        if (SettingsStore.current.sleepAction == "sleep") {
            runCatching {
                val pm = getSystemService(PowerManager::class.java)
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "photoframe:wake",
                ).acquire(15_000)
            }
        }
        if (!FrameState.activityVisible) {
            // Needs "Display over other apps" (granted via ADB) when launched from the background
            runCatching {
                startActivity(Intent(this, FrameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("frame", "Photo Frame", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "frame")
            .setContentTitle("Photo Frame running")
            .setContentText("Upload at ${Net.uploadUrl()}")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
        // Android 12+ can refuse a foreground start from the background (e.g. a START_STICKY
        // restart) unless the app is on the battery allowlist (`dumpsys deviceidle whitelist`,
        // see README). Carry on regardless: the web server still runs, and the next launch from
        // the activity or boot promotes it properly. Crashing here would just crash-loop.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (e: Exception) {
            android.util.Log.w("PhotoFrame", "startForeground refused", e)
        }
    }

    companion object {
        /** Must match the phone app's discovery type. */
        const val SERVICE_TYPE = "_photoframe._tcp"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, FrameService::class.java)) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FrameService.start(context)
        SettingsStore.init(context)
        if (SettingsStore.current.showAs == "screensaver") return // the TV starts us when it's idle
        // Opening the screen from boot needs "Display over other apps" (see README)
        runCatching {
            context.startActivity(Intent(context, FrameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
