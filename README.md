# Waymark

One job: open it and you are a little arrow on a proper Ordnance Survey map —
the Explorer 1:25k / Landranger 1:50k Leisure styles, the paper ones. Import a
GPX and there's a line to follow, with arrows along it (a ⇄ button flips them
to walk the route the other way). The same app runs on the phone and on the
Galaxy Watch 5 Pro, and a GPX imported on the phone is simply there when the
watch opens — along with the map tiles for the route, fetched ahead so a dead
zone on a hillside doesn't matter.

## The OS map key

The maps come from Ordnance Survey's own Maps API and need your personal key:

1. Sign up at [osdatahub.os.uk](https://osdatahub.os.uk), create a project and
   add the **OS Maps API** to it.
2. Put the project on the **Premium (pay-as-you-go)** plan. This matters: the
   free plan serves the overview zooms but refuses the Explorer 1:25k detail —
   which would look like the app breaking exactly when the map gets good.
3. Copy the Project API Key into Waymark's ⚙ screen on the phone and press
   **Test key** — it fetches a real Explorer-zoom tile and says in words
   whether the key and the plan are right. The key is passed to the watch
   automatically; it lives on your devices and nowhere else.

Tiles are cached on both devices, and the offline pre-fetch happens once per
imported route, so ordinary use costs pennies.

## Installing

**Phone** — tap this on the phone and install (allow installs from browser):

> https://github.com/jollydoddger/Life/releases/download/latest-debug/waymark-phone.apk

**Watch** — Wear OS has no tap-to-install; it's a one-time ADB dance per
update, from any computer on the same Wi-Fi as the watch:

1. On the watch: Settings → About watch → Software info → tap **Software
   version** 5 times (developer mode) → back to Settings → Developer options →
   turn on **ADB debugging** and **Wireless debugging**, note the IP and port.
2. On the computer: download `waymark-watch.apk` from the same release, then
   `adb connect <watch-ip>:<port>` (accept the prompt on the watch) and
   `adb install -r waymark-watch.apk`.

## Using it

**Zoom, on both devices: tap the map to zoom in, press and hold to zoom out.**
Pinch works on the phone too. The watch's physical bottom button is left alone
on purpose — on Wear OS it is the same navigation path as swipe-to-dismiss, so
an app that steals it for zoom is an app you cannot reliably get out of.

**Phone** — the buttons down the right-hand side:

| | |
|---|---|
| GPX | import a route file |
| ⇄ | flip the direction of the arrows along the route |
| ◉ | centre on you, and zoom right in |
| ● | start recording a trail (■ stops it) |
| ⚙ | API key, colours, and the watch's screen timeout |

**Watch** — one button. **◉ at the middle of the left edge** centres on you and
zooms in. There is room on a 45mm circle for a map and almost nothing else, so
everything else is set on the phone and follows across: colours, arrow
direction, recording, and how long the watch screen stays lit.

**Recording a walk.** Press ● on the phone. Both devices record — each from its
own GPS — so the trail is there on whichever screen you look at, with no button
needed on the wrist. It keeps running with the screen off and the phone in a
pocket, which is the point: that needs an ongoing notification while it runs,
and it does use battery on both devices. Stop it with ■ on the phone, or from
the **Stop** action on the watch's own notification. Starting again clears the
previous trail; only the current walk is kept.

Two caveats worth knowing. A Start pressed on the phone while the *watch app is
closed* cannot wake it (Android forbids background apps from starting this kind
of service), so the watch begins its own trail the moment you next open it. And
the **watch screen going dark does not stop a recording** — the trail is kept by
a background service, not by the map being on show.

**The watch screen.** Waymark holds it awake for the time set on the phone
(default 2 minutes, from 3 seconds up, or "Never"), and any touch restarts the
clock. It cannot switch the display off itself — no app can without device-admin
powers — so when the hold expires your watch's own screen timeout takes over.
That makes the short settings mean "stop interfering and let the watch behave
normally", which is also the kindest to the battery, since the watch then really
sleeps instead of being held awake. For darker sooner, lower the watch's own
Settings → Display → Screen timeout.

Both maps are always north-up, like the paper they're printed from; the arrow
is you, rotated to the compass (corrected to grid north). A grey arrow means
the GPS fix has gone stale — the arrow never pretends.

## Building

Everything is built by GitHub Actions (`.github/workflows/build.yml`): every
push builds both APKs, and pushes to `main` attach them to the rolling
`latest-debug` release. The debug keystore is committed so every build signs
with the same key — CI runners would otherwise mint a fresh key per build and
Android would refuse each update. Local builds are a normal
`./gradlew :phone:assembleDebug :wear:assembleDebug` with the Android SDK
installed.
