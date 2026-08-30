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
The route wears its ends: a green play-triangle disc where it starts —
honouring the reverse-arrows switch — and a dark squared disc where it
finishes; on a circular walk the start draws on top, being the one that
matters.

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

**The GPS stays warm between looks.** The map screen itself only asks for GPS
while it is in front of you, so a screen sleep used to release the fix
entirely and the next glance cost twenty seconds of grey arrow — on the
device whose whole point is a quick look without getting the phone out. Now a
quiet background hold keeps the fix for **90 minutes after you last look**,
and every glance restarts the clock: instant all walk long, nothing draining
overnight. The price is watch battery while the hold runs, and a small
"Holding GPS ready" notification so it can always be seen; switch the whole
behaviour off in the phone's Settings if the trade isn't worth it to you.
Recording is unaffected either way — it holds its own GPS, as it always has.

**Recentring keeps your zoom.** The ◉ button re-follows the fix at whatever
zoom the map is already at. (It used to zoom right in as well; that turned
out to answer a question nobody asked.) The GPX menu also gains **"Drive to
the start"** — Google Maps navigation to the nearest mapped car park within
500 m of the current route's start, or the start itself, said plainly.

## Walks near me

Everything found — by the menu below or by the assistant — lands on the same
‹ › picker over the live map: flick through the candidates, each previewed as
a dashed line, and take one with Use or Start walk. Downloaded routes are
also **saved as .gpx files** the moment they parse, and the saved folder is a
source every search reads — a walk found once is findable for ever, network
or none. **"Walks on this map ‹ ›"** in the GPX menu rebuilds the picker from
everything crossing the map in view (OSM routes, your library, your
downloads), so losing the carousel to navigation or expiry costs nothing: aim
the map at an area and flick. The assistant can also be
handed a walk's *page* from a free walking website (gps-routes.co.uk,
walkingclub.org.uk and friends): `download_gpx` digs the GPX link out of the
page itself, so "find me a written-up circular near X" goes from web search
to routes on the map without copying links about. Where a site's download
sits behind a script, a login or a cookie wall the app cannot honestly get
past, the assistant hands you the walk page as a tappable link instead — a
GPX downloaded in your browser and opened or shared with Waymark imports
straight onto the map, so a failed fetch is one tap, not a dead end.
AllTrails, komoot and OS Maps stay refused — their terms.

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

## Weather over the map (optional, all off by default)

Four switches in Settings, each independent, all fed from two sources and
one timeline. Phone only.

**Rainfall radar.** Real weather radars, painted boldly over the map and
warped properly from the web's projection onto the OS grid. Drawn heavy on
purpose: the radar image carries its own transparency, so light rain used to
vanish against pale Explorer paper — each tile is now composited over itself,
which lifts the faint returns without changing what heavy rain looks like.
Rain is **colour-coded by intensity**, on whichever of RainViewer's eight
scales you pick in Settings (Original by default). Never cached: radar is
*now* by definition, so with no signal there is no radar rather than a stale
lie about the sky.

**Opacity** is a slider on the map itself, next to the timeline — beside the
thing you are judging it against, since the same rain that reads perfectly in
a dark kitchen buries the contours in bright sun. It applies to every weather
layer and is remembered.

**The key** sits under the timeline and changes with what is drawn: swatches
with the words for them (drizzle, light, steady, heavy, torrential) for the
washes this app paints itself, and the scale's name for the radar — those
colours are RainViewer's, so they are named rather than mimicked. A key that
drifted from the real palette would be worse than none.

**Wind.** Lines drifting across the map the way the air is going, coloured by
speed — grey a breath, green a breeze, amber when it starts pushing you about,
red when an exposed ridge stops being a good idea. Flow is one picture you take
in at a glance, where an arrow per reading is two dozen separate things to read
and join up; arrows remain as an option in Settings. The lines move faster than
the real wind on purpose, because at map scale a gale would take three minutes
to cross the screen — so the picture is for *which way and roughly how hard*,
and the reading beside the timeline gives the actual speed and the direction it
comes *from*, which is how a forecast states it. They only animate while the
map is in front of you. The walk brief reports it too, with gusts.

**Temperature** is a bold figure in degrees at the top of the map, not a wash
of colour: two characters said more than a yellow film over everything, and
did not bury the contours saying it.

**Cloud, sunshine and fog.** The sky greys the map in as it thickens and
leaves it completely alone below a quarter cover — so a clean map means a
clear sky, and the grey patches are worth looking at because there are not
many of them. Low, mid and high cloud are weighed by what each does to a day
out: a lid of stratus paints heavily, cirrus barely registers. Fog —
visibility under a kilometre — is its own denser, warmer colour whatever the
sky above it says, because hill fog under blue sky is not "0% cloud", it is
the condition that turns walking by sight into a compass leg.

**One picture.** The layers draw together — cloud under rain, wind lines over
both, the temperature as a figure — and the key under the timeline names
every part currently painted. On the map they share a single **Weather**
chip; the path overlays keep a chip each.

**The timeline.** A scrubber along the bottom, five hours back and five
forward, with a ▶ that runs the whole span as a loop — a frame and a half a
second — until a finger on the bar or the ⏸ takes over. The past and the
future genuinely look different, on purpose: behind you is *measured radar*
(sharp cells, RainViewer's palette); past the ~30-minute nowcast no radar
exists yet, so the map switches to the *forecast* — a smooth wash on a grid
kilometres wide, in this app's own colours. Dressing the model up in the
measurement's clothes would be a lie about which kind of claim is on
screen, and the label under the scrubber always names which you are on. Drag it and watch a shower travel — which is the one thing a radar
tells you that a forecast cannot: not whether it will rain, but whether it is
coming for you. Frames are held in memory and the next few warmed as you
drag, so it animates rather than blinks, and panning the map keeps the moment
you chose.

The two halves are not made of the same stuff, and the label under the
scrubber says which you are looking at. RainViewer publishes about two hours
back and half an hour on; that is measured radar. The rest of the ten hours
is the hourly forecast on a grid a few kilometres across — a model's opinion
about the sky, not an observation. Wind, temperature and cloud are always the
model.

**Switching them on and off.** Settings decides which overlays exist; each
one allowed there gets a toggle across the top of the map, and that toggle is
what turns the layer on and off while you are out. A switch two screens away
is not something anyone operates halfway up a hill. The weather layers share
one Weather chip — it is all one sky — and turning any part on in Settings
lights the chip too, so it appears immediately rather than needing a second
switch you did not know about.

Weather data by [RainViewer](https://rainviewer.com); forecast by
[Open-Meteo](https://open-meteo.com).

The overlays stack in a deliberate order, bottom to top: the GPX route
(able to be covered), then the information layers — red dots, rights of
way, paths — then the trail you have actually walked on top of everything.

## Where the sun will be

## Tap a point, get a buzz there

Turn on the **Mark points** chip at the top of the map, then tap any point on
the route line — the tricky turn, the summit, the lunch spot. A card answers
with the distance ahead along the route, the climb between here and there
(up and down separately, from the cached elevation profile), and how long it
will take **at your pace**: your pace so far this walk if you're recording,
otherwise the median of your saved walks, otherwise Naismith's book rate —
and the card says which it used, plus a minute per ten metres of climb on
top. **"Buzz me there"** arms it: up to five numbered flags, and a strong
vibrating notification as you arrive at each, so the turn can't slip past
while the phone is in a pocket.

While marks are armed, the next one ahead counts itself down live at the top
of the map — "➤2  1.2 km · 21 min · ↑60 m" — refreshed as fixes arrive, and
tapping it opens that mark's full card. The buzz rides the recording service
when one runs, and the open map's own fixes otherwise — the phone never holds GPS on its own account (he saw the
"Holding GPS ready" notification outside a walk and called it: the hold
belongs to tracking), and the arming message says plainly where the buzz
lives. Tap a flag to see the live numbers to it —
distance left, time at your pace, climb remaining — or remove it; a new route
retires them all — a flag on a line you are no longer walking is a lie
waiting for an alarm. While the mode is on, taps pick points instead of
zooming; the chip switches it back.

## Finding a walk

Ask the assistant for one — "find me a 4–6 mile walk south-east", "anything
circular near Newborough?" — and it searches OpenStreetMap's named
walking-route relations up to 25 km out (roughly a 20-minute drive), plus
your own indexed GPX library, filtered by direction and length when you give
them. It can also fetch a GPX from a **direct link** a web search turns up —
free-download sites only; AllTrails, komoot and OS Maps are refused by name,
because their terms are not this app's to spend. A downloaded file is
sniffed as actual GPX and size-capped before it is parsed.

Everything found lands on a **picker over the live map**: each candidate
previews as a dashed line, ‹ and › cycle through them, and the map fits to
each. **Use** adopts one as the route (the previous route is banked, as
ever). **Start walk** adopts it *and* starts recording in the same tap, with
an elapsed-time clock beside the temperature readout — the clock starts
before the offline tiles fetch, because the timer is for the walk, not the
download. **Parking** finds the nearest OSM-mapped car park within 500 m of
the route start (or the start itself, and says so) and opens it in Google
Maps driving navigation. Candidates expire after half an hour: a picker
popping up over the map an hour after the question was asked is a haunting,
not a feature.

The ☀ button holds today's solar arc over the camera: point the phone at the
hills and see where the sun is now, where it goes down, and roughly when it
stops being useful light. Sunset, golden hour and dusk are marked on the arc
where they will actually happen against the skyline in front of you.

The astronomy is computed on the phone (the NOAA solar position algorithm,
checked in CI against the sky's own facts — equinox sunrise due east, solar
noon due south, published times for Anglesey), so it works with no signal,
which is exactly where "how long have I got?" gets asked.

Which way the camera is pointing is read off the phone's rotation matrix —
under whichever convention this phone actually delivers it in, which is
*measured*, not assumed. The documented recipe read ninety degrees out;
reading the matrix under its documented convention read wrong in exactly the
way its inverse would produce. So gravity referees: the gravity vector in
device coordinates is unambiguous on every Android phone and equals a known
row (or, inverted, column) of the matrix, and a running majority of
comparisons picks the convention before the bearing is trusted. Every branch
is checked in CI, in both conventions. The remaining soft part is the
magnetometer itself, so the view tells you what it thinks it is pointing at
rather than implying survey accuracy — and says "compass: mirrored" in the
small print when the inverted convention is the one in use, so a wrong
bearing can be reported with the fact that matters. Refuse the camera permission and the
same arc is drawn on a plain sky — the information is the point.

## Ask it things (optional, off by default)

While the assistant works, the reply strip over the map (and the working
line in the chat) shows a **ticking clock and what it is doing right now**
— "Working 1:47 · reading the paths and lanes round here…" — because a
five-minute route plan and a dead call look identical without one. The ✕
on the strip (or a tap on the chat's working line) **stops the run**:
cooperatively, so a call already in flight finishes and the loop stands
down having changed nothing further, and says so. A failed call says why
and invites a retry — it can no longer wedge the ask bar silently.


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
  three promises a general router cannot. It **holds the distance** (if the
  first loop comes back long it re-runs on a tighter circle until it lands),
  it **stays off the A and B roads** (they are left out of the network
  entirely, so it cannot stray onto one — say "avoid_roads false" or just
  ask for roads to be allowed if a plan won't close without them), and a
  circular walk **is a circuit**: corners are hung on junctions rather than
  dead ends, walking the same stretch twice is priced ten times higher than
  walking it once, and any out-and-back spur left over is cut out of the
  finished route. Where the paths force some doubling back, the reply says
  how much rather than calling it a clean loop. Distance gives before shape
  does — a 5 km ask can come back 5.6 km if that is what closes properly. The
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
