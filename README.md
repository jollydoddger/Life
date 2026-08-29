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

## Offline maps

Two ways, both permanent until the app is uninstalled:

- **Automatic, per route**: importing a GPX (or adopting a found walk)
  fetches every tile within ~500 m of the line at **every zoom level**, and
  ships them to the watch with the route — so on the hill, zoomed in or all
  the way out, the map is simply there.
- **The ⬇ button**: saves everything currently on screen, at every zoom.
  It counts the tiles first and asks — OS bills per tile served, so a
  whole-county tap should be a decision, not an accident (over ~20 000
  tiles it refuses and asks you to zoom in). Tap ⬇ again mid-download to
  stop; whatever is already saved stays saved. Phone only.

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
| ☀ | where the sun is, and where it sets, over the camera |
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

## Saved walks

Stopping a recording (■) offers to save the walk: a name, your own note,
and — captured automatically — the date, how long it took, how far it was,
and where it started. They live under the GPX button → **Saved walks**:
load one back as the route to walk it again, share it as a GPX file (to
komoot, a friend, or OpenStreetMap's public-traces upload — which is
exactly what makes the red dots grow), or delete it. The GPX menu can also
**hide the route** on the phone to read the map under it; the stored route
and the watch are untouched, and adopting any new route un-hides it.

## Rights of way (optional, off by default)

A switch in ⚙ draws the paths you are **legally entitled to walk**, bold and
colour-coded: green public footpaths, amber bridleways, purple restricted
byways, brown byways open to all traffic. Cached as you browse, so a
revisited area works with no signal.

The source is OpenStreetMap's record of what each council's definitive map
says — the `designation` tagging used across England and Wales. That means
one source covering every council at once, instead of a different feed,
schema and licence per authority. It is a *copy* of the legal record rather
than the record itself, and the honest check is built into the screen: OS
Explorer draws the same paths in green dashes underneath, so where the two
disagree, believe the printed map. For a formal question — a blocked path, a
diversion order — the council's own definitive map is the only authority.

A second checkbox, **Every mapped path and track**, adds the physical
network OSM knows about — paths with no recorded legal status — in thin
grey under the coloured rights. It answers "is there a path", not "may I
walk it".

## Where people have walked (optional, off by default)

A switch in ⚙ draws flashing red dots on the phone's map wherever anyone has
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

## Rainfall radar (optional, off by default)

A switch in ⚙ paints live rainfall over the map — blue where real weather
radars see rain falling now, warped properly from the web's projection
onto the OS grid, refreshed about every five minutes as you browse.
Deliberately never cached: radar is *now* by definition, so with no signal
there is no radar rather than a stale lie about the sky. Weather data by
[RainViewer](https://rainviewer.com). Phone only.

The overlays stack in a deliberate order, bottom to top: the GPX route
(able to be covered), then the information layers — red dots, rights of
way, paths — then the trail you have actually walked on top of everything.

## Where the sun will be

The ☀ button holds today's solar arc over the camera: point the phone at the
hills and see where the sun is now, where it goes down, and roughly when it
stops being useful light. Sunset, golden hour and dusk are marked on the arc
where they will actually happen against the skyline in front of you.

The astronomy is computed on the phone (the NOAA solar position algorithm,
checked in CI against the sky's own facts — equinox sunrise due east, solar
noon due south, published times for Anglesey), so it works with no signal,
which is exactly where "how long have I got?" gets asked. The soft part is
the phone's compass, so the view tells you what it thinks it is pointing at
rather than implying survey accuracy. Refuse the camera permission and the
same arc is drawn on a plain sky — the information is the point.

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
- **"Plan me a 3 mile countryside walk from here"** — planned on Waymark's
  **own** walking network, not a public router, which is what lets it keep
  two promises a general router cannot. It **holds the distance** (if the
  first loop comes back long it re-runs on a tighter circle until it lands)
  and it **stays off the A and B roads** (they are left out of the network
  entirely, so it cannot stray onto one — say "avoid_roads false" or just
  ask for roads to be allowed if a plan won't close without them). The
  network is built from OpenStreetMap paths, tracks, bridleways and quiet
  lanes, each weighted by how pleasant it is to walk, and the percentages
  reported back are counted off the route actually built rather than
  estimated. Takes up to a minute and says what it's doing meanwhile. You
  can also plan from somewhere else — "a 5 km loop from Newborough" — for
  working out tomorrow from the sofa. *"Put my old route back"* undoes it.
  OSM paths are usually right, not gospel — glance at the OS map before
  trusting a stile.
- **"Will it rain?" / "how much climbing?"** — Open-Meteo forecast and terrain.
- **"Any walks near me?"** — the same search as the GPX button's Walks near
  me, as a list; loading one stays a deliberate act in that menu, where the
  preview is.
- **"Where am I?"** — an OS grid reference (the form mountain rescue wants),
  the nearest named place, and today's sunset.
- **"Should I set off now?" / "will I be back before dark?"** — the walk
  brief: length and climb, a Naismith time estimate, the rain forecast
  across the walk's own time window, and the finish measured against
  sunset — including which way the sun goes down. Works for a later start
  too: "brief me for setting off at 3".

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
