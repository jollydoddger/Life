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
| ⚙ | API key, and the line / arrow / trail colours |

**Watch** — three controls, at the edges where a round screen has most room:
**◉ on the left** centres on you and zooms in, **● on the right** starts and
stops recording, and **⇄ at the bottom** flips the route arrows.

**Recording a walk.** Press ● on either device and both start, so the trail is
there on whichever screen you look at. It keeps running with the screen off and
the phone in a pocket, which is the point — that needs an ongoing notification
while it runs, and it does use battery on both devices. Press ■ on either to
stop. Starting again clears the previous trail; only the current walk is kept.
One caveat worth knowing: a Start pressed on the phone while the *watch app is
closed* cannot wake it (Android forbids background apps from starting this kind
of service), so the watch begins its own trail the moment you next open it.

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
