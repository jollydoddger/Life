package com.jollydoddger.photoframe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/** The frame as an app you open. The same screen also runs as the TV's screensaver ([PhotoDream]). */
class FrameActivity : Activity() {
    private lateinit var screen: FrameScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FrameService.start(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        screen = FrameScreen(this, keepScreenOn = { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }, introInfo = true)
        setContentView(screen.root)
        maybeAskForStorageAccess()
    }

    override fun onStart() {
        super.onStart()
        FrameState.activityVisible = true
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        screen.start()
    }

    override fun onStop() {
        FrameState.activityVisible = false
        screen.stop()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        screen.onKey(keyCode) || super.onKeyDown(keyCode, event)

    /** Once, on first run: offer All-files access so photos go in a normal folder on the USB stick. */
    private fun maybeAskForStorageAccess() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return
        val prefs = getSharedPreferences("frame", MODE_PRIVATE)
        if (prefs.getBoolean("askedStorage", false)) return
        prefs.edit().putBoolean("askedStorage", true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
