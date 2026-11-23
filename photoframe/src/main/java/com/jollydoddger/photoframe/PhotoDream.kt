package com.jollydoddger.photoframe

import android.service.dreams.DreamService

/**
 * The frame as the TV's screensaver. Google TV starts it after the TV has been idle for the
 * screensaver delay; any button on the remote ends it and you're back where you were.
 *
 * Schedule and "only when we're home" still apply: outside them the screensaver shows black,
 * and the TV's own "turn off display" timer can then put it into standby.
 *
 * Google TV hides third-party screensavers in its menus, so it's switched on over ADB once
 * (see README, "Screensaver").
 */
class PhotoDream : DreamService() {
    private var screen: FrameScreen? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // any key wakes the TV back to what you were doing
        isFullscreen = true
        isScreenBright = true
        FrameService.start(this) // normally already running from boot; makes sure
        screen = FrameScreen(this, keepScreenOn = {}, introInfo = false).also { setContentView(it.root) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        screen?.start()
    }

    override fun onDreamingStopped() {
        screen?.stop()
        // Usually a remote press ended it: someone's home and using the TV
        FrameState.touch()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        screen?.stop()
        screen = null
        super.onDetachedFromWindow()
    }
}
