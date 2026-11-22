# Photo Frame (TCL 55C7K Google TV): handoff for Claude Code

This is a brief for continuing the project. The full source is in this folder. Read this file first, then README.md.

## Goal
A personal digital-photo-frame app for John's **TCL 55C7K** (55" QD-Mini LED, **Google TV**, 3 GB RAM, 64 GB storage, 1× USB 2.0 + 1× USB 3.0, UK model). The requirements:
- Upload photos and videos from phones over Wi-Fi (no cloud).
- Store them on a **USB stick in the TV**, which acts as the "NAS".
- Show them as slideshows, photo walls, and a mix of the two.
- Run all day without damaging the TV, at a reasonable power draw.
- Only be on when John's or his partner's phone is home (Wi-Fi presence).

## Status
- Lives in the `photoframe/` module of the `Life` repo and builds with the repo's Gradle 8.11.1, AGP 8.7.3 and Kotlin 2.0.21. compileSdk is 35; targetSdk stays at 34. CI builds `photoframe.apk` with Waymark, and the build and the tests pass.
- JVM tests (`./gradlew :photoframe:testDebugUnitTest`) run the real web server on a temp folder: upload, album, delete, path traversal, Expect: 100-continue and 411. They also cover the playlist, settings parsing and the blur maths.
- It has **never run on a device**. Expect a round of on-device fixes.
- Fixed after the first read-through: a manual Sleep now ends at the next automatic on; a late-mounting USB stick no longer plays the library in date order; file dates are cached (no stat() per sort compare); the background blur is pre-computed once per photo (no live RenderEffect); thumbnail decodes are capped at 3 at a time and written atomically; uploads take the EXIF capture date; startForeground can't crash-loop the service.

## Phone companion (`photoframe-phone/`)
- Native Android app, since both phones are S24 Ultras. It finds the TV over mDNS: the TV's FrameService advertises `_photoframe._tcp` on port 8080, and the app falls back to a typed IP.
- ShareActivity takes ACTION_SEND/SEND_MULTIPLE for image/* and video/* and shows an album picker. UploadService (a dataSync foreground service, holding wake and Wi-Fi locks) streams each content URI to `/api/upload` one at a time, sending the size from OpenableColumns and the MediaStore DATE_TAKEN as mtime.
- MainActivity is a WebView of the TV's web app, with a file chooser.
- To check on device: the read grant reaching UploadService for Samsung Gallery shares; mDNS getting through the router; a big 4K video uploading with the screen off.

## Mac uploads
- `photoframe/mac/photoframe-upload` (bash 3.2 + curl + dns-sd: nothing to install) handles one-off sends, a Shortcuts Quick Action/Share Sheet, and `--watch`, a launchd agent (WatchPaths plus every 60 s) syncing `~/Pictures/Photo Frame/<Album>/`. A sent-list lives in `~/.photoframe/sent`.
- Server: a same-name, same-size file in the album is answered `duplicate:true`. With `Expect: 100-continue` this happens before the body is sent; otherwise the body is drained. `replace=1` (which the script uses) overwrites a same-name file of a different size, so edited re-exports replace the old copy. The web app drops folders as albums.
- `MacScriptTest` runs the real script against the real server (in CI too). Not checked on a real Mac: dns-sd parsing, the Shortcuts hand-off, and whether macOS 15 Local Network privacy blocks the launch agent.

## Tech decisions (keep these unless there's a reason)
| Area | Decision | Why |
|---|---|---|
| Language/UI | Kotlin, plain `android.app.Activity`, views built in code (no Compose, AppCompat or Leanback library) | Fewest dependencies and less to break on TV |
| Build | The `Life` repo's Gradle 8.11.1 / AGP 8.7.3 / Kotlin 2.0.21, compileSdk 35, targetSdk 34, minSdk 28, JDK 17 | minSdk 28 gives `ImageDecoder` (HEIC + EXIF rotation) |
| Dependencies | Only `com.google.zxing:core:3.5.3` (QR code on screen) | |
| Web server | Own tiny HTTP server on `ServerSocket`, port **8080** | Uploads stream raw to disk, so multi-GB videos work; no NanoHTTPD memory limits |
| Upload protocol | `POST /api/upload?album=&name=&mtime=` with the raw file as the body (XHR, one at a time). Written to `.name.part`, fsync'd, then renamed | Simple and resumable-friendly, keeps the original dates |
| Storage | USB `PhotoFrame/` folder if All-files access is granted. Otherwise the app's folder on the USB (`Android/data/.../files/media`, wiped on uninstall). Otherwise TV internal storage | Many Android TVs have no SAF folder picker, so all-files access is granted via ADB `appops` |
| Albums | A sub-folder = an album. Files in the root are "Unsorted" | You can also copy files on from a computer |
| Background | Foreground service (`specialUse` type) runs the web server, presence checks and schedule. Started by the activity and on `BOOT_COMPLETED` | |
| Off state | Default **"black"**: the activity stays open on full black, and local dimming keeps the backlight nearly off. Optional **"sleep"** (experimental): clear `FLAG_KEEP_SCREEN_ON` so the TV's own timer goes to standby, then wake with an `ACQUIRE_CAUSES_WAKEUP` wakelock plus a relaunch | Apps can't power the TV off themselves without system permissions |
| Presence | Wi-Fi, not Bluetooth. Every 30 s, ping each phone's fixed IP (`/system/bin/ping`), then a TCP knock (ports 80/443; ECONNREFUSED counts as present). Both phones are Galaxy S24 Ultras (One UI), so there are no iPhone special cases. Away = nobody seen for `awayGraceMin` (default 20). Web requests and remote key presses also count as presence. A 3-minute grace period after boot | Phones randomise BLE addresses, so Bluetooth would need a companion app. Wi-Fi needs no phone app |

## Files
```
photoframe/src/main/java/com/jollydoddger/photoframe/
  FrameActivity.kt    Full-screen UI built in code: stage, clock, info panel with QR, flash message, blackout.
                      Remote keys: ◀▶ skip, OK pause, ▲ cycle mode, ▼ info, any key wakes.
                      Mixed mode alternates controllers every mixedMinutes. Library changes are debounced 2.5 s.
                      Asks once for All-files access on first run.
  FrameService.kt     Foreground service plus BootReceiver. evaluate() every 15 s:
                      manual sleep > manual wake (1 h) > schedule > presence → FrameState.setAwake().
                      Re-picks storage on USB mount/unmount broadcasts.
  Controllers.kt      ShowController interface.
                      SlideshowController: photo slides with optional blurred background
                        (a 96 px pre-blurred copy), Ken Burns, crossfade; videos on TextureView + MediaPlayer
                        (muted by default, capped at videoMaxSec, rotation-aware fit).
                      WallController: mosaic (1 big + 4) / grid 3×2 / grid 4×3. Swaps one tile every interval/2 with a fade.
  Playlist.kt         Shuffled endless queue with history for "prev". update() puts new uploads at the front.
  WebServer.kt        HTTP API (see below). Every request calls FrameState.touch().
  PresenceMonitor.kt  Phone pinging (see above).
  MediaLibrary.kt     Storage resolution, albums, listing, path sanitising/resolution (blocks traversal).
  Imaging.kt          BitmapLoader (ImageDecoder, power-of-two downsampling, 2-thread pool), Thumbs (cacheDir JPEGs),
                      Qr, Net.localIp()/uploadUrl().
  Settings.kt         FrameSettings data class ⇄ JSON (validated/clamped). SettingsStore saves to filesDir/settings.json
                      and notifies listeners.
  FrameState.kt       Shared singleton: awake, sleepReason, lastUserActivity, presence map. Listener callbacks
                      (onAwakeChanged/onLibraryChanged/onCommand) run on the main thread.
photoframe/src/main/assets/web/index.html   Single-page phone web app (vanilla JS): Upload / Library / Settings / Remote tabs.
(repo root) .github/workflows/build.yml  Builds and tests it with Waymark; photoframe.apk goes in the latest-debug release from main.
```

## HTTP API
| Method | Path | Notes |
|---|---|---|
| GET | `/` | Web app |
| GET | `/api/status` | storage, onUsb, allFilesAccess, free/total bytes, photo/video counts, awake, sleepReason, url, phones[{name, ip, secondsAgo}] |
| GET/POST | `/api/settings` | JSON. POST takes a partial patch |
| GET/POST | `/api/albums` | POST `?name=` creates an album |
| GET | `/api/media[?album=]` | List, newest first |
| GET | `/api/thumb?path=` / `/api/file?path=` | Thumbnail JPEG / original file |
| POST | `/api/upload?album=&name=&mtime=` | Raw body. Needs Content-Length |
| POST | `/api/delete?path=` | File, or an empty album folder |
| POST | `/api/control?action=` | next, prev, pause, info, wake, sleep, reload |

## Settings (defaults)
mode `slideshow`|`wall`|`mixed`; intervalSec 12; transition `kenburns`|`fade`|`none`; fit `blur`|`fit`|`fill`; shuffle true; showClock true; includeVideos true; videoMaxSec 60; videoSound false; albums [] (empty = all); wallLayout `mosaic`|`grid`|`grid12`; mixedMinutes 5; scheduleEnabled false, onTime 07:00, offTime 23:00; presenceEnabled false, phones [{name, ip}], awayGraceMin 20; sleepAction `black`|`sleep`.

## Install / one-off setup (on John's Mac)
```
adb connect TV-IP:5555
adb install -r photoframe.apk
adb shell appops set com.jollydoddger.photoframe MANAGE_EXTERNAL_STORAGE allow
adb shell appops set com.jollydoddger.photoframe SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.jollydoddger.photoframe
```
- USB stick: exFAT, in the USB 3.0 port.
- Phones: a DHCP reservation in the router for each, and **private/random Wi-Fi MAC turned off** for the home network.

## TV settings for 24/7 use (for the user, not code)
- Picture mode Standard/Eco. Backlight around 30–50%. Light sensor ON. **Local dimming ON**. HDR off or auto.
- **Turn OFF auto standby / "no operation" sleep** (*System › Power & Energy*). UK/EU TVs shut off after 4 h without a remote press.
- No burn-in risk (LCD + Mini-LED, not OLED).
- Power: about 76 W SDR at stock settings, 242 W HDR (Currys spec). Dimmed photos are likely 40–60 W, roughly £4–6/month at 12 h/day. Standby is under 0.5 W.

## Next steps / known risks to verify on device
1. ~~First Gradle build~~: done, and it builds cleanly.
2. **USB detection** on TCL firmware: check that `StorageVolume.isRemovable`/`directory` and `getExternalFilesDirs` actually return the stick.
3. **All-files access**: check the `appops` grant works, and whether TCL has the Settings screen at all (the activity tries `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` once).
4. **Presence**: check whether `/system/bin/ping` is allowed for apps on this firmware. Check how often a locked S24 Ultra answers; ping is 2 tries, 2 s timeout.
5. **"sleep" mode**: does the TV really go to standby, and does the wakelock/relaunch bring it back? If not, consider a Home Assistant/Pi using ADB (`input keyevent 223/224`) or HDMI-CEC.
6. **Background launch** after boot/wake needs SYSTEM_ALERT_WINDOW. Confirm.
7. **Memory/perf**: 4K-plus photos are downsampled to the screen size, and the wall uses tile-sized decodes. The blur is now a 96 px pre-blurred bitmap, not a RenderEffect. Check that the Ken Burns pan stays smooth on the TV GPU.
8. Nice-to-haves: register as a screensaver (`DreamService`), a "new photos" badge, an on-this-day album, a weather overlay, upload resume.
