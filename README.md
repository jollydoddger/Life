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
| GPX | import a route file, find walks near you, or set up your GPX library |
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

## Walks near me

Community route apps show a pin where a walk *starts*; a walk that passes 400 m
from your door can start two villages away. The GPX button's **Walks near me**
answers the better question: which walks' *lines* come within 500 m / 1 km /
2 km / 5 km of where you stand. Results are ranked by how close the line comes;
tap one and it is drawn dashed on the map first — **Use it** makes it the
route (offline tiles fetched, watch updated, and the previous route banked so
it can be put back).

Two sources, searched together:

- **OpenStreetMap's walking routes** — the named and community
  walking/hiking routes mapped in OSM, with their full geometry. Nothing to
  set up. An OSM route is stitched from its mapped sections, so the line can
  have gaps; the shape is right, the join-up isn't guaranteed. Long trails
  are clipped to the ~20 km around you.
- **Your own GPX library** — point Waymark at a folder (GPX button → *GPX
  library folder…*) and every `.gpx` inside, subfolders included, is indexed
  and searched the same way. Fill it yourself: komoot, AllTrails, OS Maps and
  the rest all let a signed-in user **export their own saved routes** as GPX,
  and that export is yours to make. Waymark deliberately does not scrape any
  route website — their terms forbid it, and it would put your accounts at
  risk. Rescan from the same menu after adding files.

## Where people have walked (optional, off by default)

A switch in ⚙ draws faint purple dots on the phone's map wherever anyone has
publicly recorded a GPS track — OpenStreetMap's public traces, fetched for the
area you're looking at (zoomed in) and cached, so a revisited area works
offline. Dots along a path mean the path really gets walked; a right-of-way
on the map with no dots at all is worth a raised eyebrow.

The honest caveat, stated here because the feature was asked for as "a Strava
heatmap": the dots are **cumulative, not recent** — a dotted path was walked
at some point since people started uploading traces, not necessarily this
year. Nothing purchasable fixes that: Strava's heatmap has no API, its tiles
sit behind a login, and Strava's API agreement forbids third-party surfacing
of aggregate activity data — at any price. This overlay is the legal
substitute, and its one weakness is recency.

## Ask it things (optional, off by default)

Waymark's job is a map, an arrow and a line, and out of the box that is all it
is. **Switch on "Ask bar" in ⚙** and a question box appears at the bottom of
the phone's map, talking to Claude (hold 🎤 to speak — release to send — or
type). Switch it off and the box is gone, not merely idle: the map has the
whole screen again. It is the newest and roughest part of the app.

It answers from the app's own arithmetic and real databases, never from its own
head; anything it changes shows as a ✓ receipt.

- **"How far is left?"** — measured along the route from your GPS position.
- **"Toilets on the route?" / "cafés near me?"** — OpenStreetMap (plus the
  Toilet Map for toilets), dropped on the map as markers on both devices.
  Finding nothing is a fact about the database, not the ground — bin coverage
  especially is patchy, because no national bin registry exists.
- **"Plan me a 3 mile countryside walk from here"** — and *countryside* is a
  measurement, not a hope. It fetches the footpaths, tracks and bridleways
  actually mapped around you, hangs three candidate loops off them in
  different directions, routes each with the FOSSGIS foot router, and keeps
  whichever runs most on paths — telling you the figure ("82% on mapped
  paths"). Takes about half a minute and says what it's doing meanwhile. The
  loop replaces the current route (offline tiles fetched, watch updated);
  *"put my old route back"* undoes it. OSM paths are usually right, not
  gospel — glance at the OS map before trusting a stile.
- **"Will it rain?" / "how much climbing?"** — Open-Meteo forecast and terrain.
- **"Any walks near me?"** — the same search as the GPX button's Walks near
  me, as a list; loading one stays a deliberate act in that menu, where the
  preview is.
- **"Where am I?"** — an OS grid reference (the form mountain rescue wants),
  the nearest named place, and today's sunset.

It needs your own Anthropic API key (console.anthropic.com) in ⚙, phone-only —
the key field appears once the switch is on. A question costs a few pence. Place data © OpenStreetMap contributors; routing
by FOSSGIS; weather by Open-Meteo; toilets also from the Toilet Map.

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
