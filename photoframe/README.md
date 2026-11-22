# Photo Frame for Google TV (TCL C7K)

Turns the TV into a digital photo frame. Photos and videos live on a USB stick in the TV, you upload them from your phone over Wi-Fi, and the TV shows them as a slideshow or photo wall. It can switch itself off when nobody's home.

## Features
- **Upload from any phone or computer**: open `http://<TV-IP>:8080` (the QR code is on the TV screen). Drag-drop or pick files, with progress bars. New uploads show up next. Photos keep their capture date (read from EXIF), even though the phone browser reports every picked photo as taken "now".
- **Albums**: one folder per album on the stick. You choose which albums play.
- **Modes**: full-screen slideshow (Ken Burns pan/zoom, crossfade or none), photo wall (mosaic or grids), or mixed (alternates between the two).
- **Portrait photos** get a blurred copy of themselves behind them instead of black bars.
- **Videos** play inline, muted by default, with a max length per clip.
- **Clock/date** overlay.
- **Schedule**: on and off times.
- **Only when we're home**: pings your phones on the Wi-Fi and goes dark when nobody's been seen for X minutes.
- **TV remote**: ◀ ▶ skip · OK pause · ▲ change mode · ▼ info/QR panel · any key wakes it.
- **Phone remote and status page** in the web app.

## 1. Get the APK
This app lives in the `photoframe/` module of the `Life` repo, alongside Waymark. GitHub Actions builds it on every push, together with Waymark, and runs its tests:
- **From a branch:** *Actions › latest run › waymark-debug* (a zip holding `photoframe.apk`).
- **From `main`:** *Releases › latest-debug › photoframe.apk*. It's a direct link, handy for Downloader on the TV.

Or build it on the Mac: `./gradlew :photoframe:assembleDebug` from the repo root, which puts the APK in `photoframe/build/outputs/apk/debug/`.

Every build is signed with the repo's pinned debug key, so `adb install -r` updates in place and keeps your settings.

## 2. Install on the TV
1. On the TV: *Settings › System › About*, then click **Android TV OS build** 7 times. That unlocks Developer options.
2. *Settings › System › Developer options*: turn on **USB debugging** (on some TCLs this is called *Network debugging*).
3. Find the TV's IP in *Settings › Network & Internet*.
4. On the Mac (`brew install android-platform-tools`):
   ```
   adb connect TV-IP:5555        # accept the prompt on the TV
   adb install -r photoframe.apk
   ```
5. Grant the two extra permissions. These are one-off and the reason ADB is worth the bother:
   ```
   # Keep photos in a normal "PhotoFrame" folder on the stick (survives reinstalls)
   adb shell appops set com.jollydoddger.photoframe MANAGE_EXTERNAL_STORAGE allow
   # Lets the frame open itself at boot and when it wakes
   adb shell appops set com.jollydoddger.photoframe SYSTEM_ALERT_WINDOW allow
   # Stop Android killing the background service
   adb shell dumpsys deviceidle whitelist +com.jollydoddger.photoframe
   ```
   Without the first one the app still works, but it stores photos in its own app folder on the stick, which is wiped if you uninstall the app.

**No-computer alternative:** install **Downloader** (by AFTVnews) from the Play Store on the TV and point it at the Releases APK link. You'll still need ADB for step 5.

## 3. USB stick
- Use the **USB 3.0** port and format the stick as **exFAT**.
- Folder layout: `PhotoFrame/<Album name>/photo.jpg`. You can also copy files onto it directly from a computer.
- Supported: JPG, PNG, HEIC, WebP, GIF (first frame), MP4, MOV, MKV, WebM.

## 4. TV settings for 24/7 use
| Setting | Value | Why |
|---|---|---|
| Picture mode | Standard / Eco (not Vivid) | Less power and less heat |
| Backlight / brightness | ~30–50% | Plenty for photos |
| Light sensor / auto brightness | On | Dims with the room |
| Local dimming | On | Black areas, and the "off" screen, use almost no backlight |
| HDR / Dolby Vision | Off or Auto (photos are SDR) | HDR draws up to ~3× the power |
| **Auto standby / "no operation" sleep** (*System › Power & Energy*) | **Off** | UK/EU TVs switch off after 4 h with no remote use. Use the app's schedule instead |
| Screen saver / Ambient mode | Off or a long delay | Shouldn't trigger while the app runs, but just in case |

There's no burn-in risk: it's an LCD with a Mini-LED backlight, not OLED, and the pictures keep changing anyway.

## 5. "Only when we're home"
1. In your router, give each phone a **fixed IP** (DHCP reservation).
2. On each S24 Ultra, turn off the random Wi-Fi address for the home network: *Settings › Connections › Wi-Fi*, tap ⚙ next to your network, *View more › MAC address type › **Phone MAC***. The phone reconnects. Do this **before** you make the router reservation, because the router has to see the phone's real MAC.
   - If a locked phone keeps showing "not seen" for long stretches while it's at home, turn off *Wi-Fi › ⋮ › Intelligent Wi-Fi › **Wi-Fi power saving mode***. It's optional: the 20-minute grace period usually covers the gaps.
   - Quick test from the Mac with a phone locked on the table: `ping <phone-IP>`. Replies arriving every second or so (even slowly) means presence will work.
3. In the web app go to **Settings › Only when we're home**, add both phones, tick the box and save. **Remote › Status** shows when each phone was last seen.

Phones nap their Wi-Fi, so the frame only turns off after nobody has been seen for the grace period (20 min by default). Using the TV remote or the web page also counts as someone being home.

**S24 Ultra photos and videos:** JPEG and HEIF photos, Motion Photos (they show as the still) and Ultra HDR photos (they show as normal SDR) all work. Videos up to **4K** play. **8K** video is beyond the TV's decoder, so the frame skips those clips; record in 4K if you want them on the TV. Expert RAW `.dng` files are refused at upload. Pressing any key on the remote wakes the frame for an hour. **Sleep** (from the phone's Remote tab) keeps it dark until the next time the schedule or presence would turn it on, such as tomorrow's on-time or someone coming home, or until you wake it.

## Power: "When off" options
- **Black screen** (default, reliable): the app stays open on pure black. With local dimming the backlight is almost off, but the TV's electronics still draw some power.
- **Let TV sleep** (experimental): the app stops holding the screen on, so the TV's own screen-off timer can put it into real standby (<0.5 W). The app then tries to wake it when you're back, but whether that works depends on TCL's firmware. Test it.

For a guaranteed real off and on, a Home Assistant box or Raspberry Pi can switch the TV via ADB or HDMI-CEC using the same presence logic.

## Project layout
```
photoframe/src/main/java/.../FrameActivity.kt   screen, remote keys, overlays
                            FrameService.kt     always-on brain: web server, schedule, presence
                            Controllers.kt      slideshow + photo wall
                            Playlist.kt         shuffled queue, history, new-uploads-first
                            WebServer.kt        upload/settings HTTP API
                            PresenceMonitor.kt  phone pinging
                            MediaLibrary.kt     USB storage + albums
                            Imaging.kt          decoding, background blur, thumbnails, QR
photoframe/src/main/assets/web/index.html       the phone web app
photoframe/src/test/                            JVM tests: the real web server against a temp folder, playlist, settings
```
