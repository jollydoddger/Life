# Orbit — Stereoscopic 180° / 360° Video Player

A small, self-contained WebXR video player for flat-screen, phone-in-Cardboard,
and full WebXR headsets. Point it at a video URL or a local file and it projects
the footage onto a sphere (360°) or forward hemisphere (180°), with proper
side-by-side / over-under stereo separation per eye.

No build step, no bundler, no CDN — `three.js` is vendored under `lib/`, so the
whole thing runs from any static file server (and offline).

There are **two ways** to use it:

1. **The standalone player** (this PWA) — paste a video URL / deeplink, or build
   a library. Great for direct or CORS-friendly videos and local files.
2. **The userscript** (`orbit.user.js`) — for **browsing a real VR site on your
   phone, already logged in, and tapping a video to watch it in VR**. Read on.

## Watch on real VR sites — the userscript (Android)

A standalone web player **cannot** log into a third-party site or pull its video
into WebGL: the browser blocks cross-origin video and auth (CORS). That's why the
native DeoVR app is *native*. The browser-friendly way to get the same result is
a **userscript that runs on the site itself** — so it inherits your login and has
same-origin access to the video, no CORS wall — and overlays the Orbit stereo
renderer on top of the site's player.

`orbit.user.js` adds a floating **🜨 VR** button to any page with a video. Tap it
and the current video plays in stereoscopic 180°/360°, with projection/stereo
toggles, **Cardboard** split-screen, and gyro head tracking.

### Install on Android

1. Install a browser that supports extensions: **Kiwi Browser** or **Firefox for
   Android**.
2. Add the **Tampermonkey** extension from its store.
3. Open the script to install it (Tampermonkey shows an install page):
   `https://raw.githubusercontent.com/jollydoddger/Life/claude/deovr-stereo-player-g56wta/vrplayer/orbit.user.js`
4. Go to your VR site, log in as usual, open a video, tap **🜨 VR**, then
   **Cardboard** → grant motion access → drop the phone in the headset.

> **Format & CORS notes.** Projection/stereo are auto-guessed from the page title
> (e.g. "180 SBS") and adjustable with the on-screen toggles. The video must be
> usable as a WebGL texture — sites that offer their own browser VR player serve
> CORS-enabled video, so those work; a site that serves video cross-origin with
> no CORS will show a black sphere (a browser limitation, not the script). By
> default the script runs on every site; narrow the `@match` line to your sites.

## Run the standalone player

It's a client-side web app — it runs entirely in the phone's browser. You just
need to serve the static files once over HTTPS.

### Install on your Android phone (recommended — no computer needed)

Host it free on **GitHub Pages**, then install it like an app:

1. In the GitHub repo: **Settings → Pages → Build and deployment**.
   Set **Source: Deploy from a branch**, pick this branch (or `main` after
   merging), folder **`/ (root)`**, and Save.
2. After a minute, the app is live at:
   **`https://jollydoddger.github.io/Life/vrplayer/`**
3. Open that URL in **Chrome on the phone** → menu **⋮ → Add to Home screen**.
   Orbit installs as a fullscreen app with its own icon.
4. Launch it from the home screen, load a video / **test pattern**, tap
   **Cardboard**, grant motion access, and drop the phone in the headset.

Because it's a PWA, after the first load the whole app (including three.js and
hls.js) is cached on the phone and runs **offline** — only the videos themselves
need a connection.

### On your computer (quick check)

```bash
cd vrplayer
python3 -m http.server 8123      # or: npm start
# open http://localhost:8123/
```

`localhost` is a secure context, so everything works except the actual gyro
(your computer has none). Good for checking projection, stereo, and the UI.

### On your phone, in a Cardboard headset

Phones need **HTTPS** to expose motion sensors, so use the bundled HTTPS server
(self-signs a cert via `openssl` on first run):

```bash
cd vrplayer
node serve-https.mjs             # or: npm run serve:phone
```

It prints a `https://<your-LAN-IP>:8443/` URL. On the phone (same Wi-Fi):

1. Open that URL; accept the one-time certificate warning (Advanced → Proceed).
2. Load a video / deeplink, or **Load test pattern**.
3. Tap **Cardboard** — this goes fullscreen, asks for motion access (allow it),
   and splits the screen for the two lenses with head tracking.
4. Drop the phone in the headset.

Then either paste a video URL (progressive `mp4`/`webm` **or** an HLS `.m3u8`
stream), paste a **scene deeplink** (`.json`), open a local file, or hit **Load
test pattern** to see the built-in calibration card.

### Library

Hit **★ Save** while a scene is playing to add it to your library; it appears as
a tile on the home screen and reopens with one click (scenes re-parse their
deeplink; direct videos restore their saved projection/layout). The library
lives in `localStorage` — no account, no server.

### Streaming (HLS)

`.m3u8` URLs play through a vendored `hls.js` (with native HLS on Safari). The
quality dropdown is driven by the manifest's bitrate ladder — **Auto** does
adaptive selection, or pin a specific level (switches seamlessly, no reload).

### Scene deeplinks

Paste a DeoVR / SLR-style deeplink JSON URL and the player configures itself —
projection, stereo layout, title, and a quality switcher — from the feed:

```jsonc
{
  "title": "...", "videoLength": 612,
  "is3d": true, "screenType": "dome", "stereoMode": "sbs",
  "encodings": [
    { "name": "h264", "videoSources": [
      { "resolution": 1920, "url": "https://.../1080.mp4" } ] }
  ]
}
```

`screenType` → projection (`dome`/fisheye → 180°, `sphere` → 360°), `stereoMode`
→ layout (`sbs`, `tb` → over/under, `off` → mono), and `encodings[].videoSources`
become the quality list (H.264 preferred for browser playback). Cross-origin
feeds and video need permissive CORS headers (or a proxy) to load.

> For phone-in-headset and real WebXR, the page must be served over **HTTPS**
> (WebXR and the iOS motion sensors require a secure context). `localhost` is
> exempt for desktop testing.

## Controls

| Control | What it does |
|---|---|
| Drag | Look around (desktop / touch) |
| **360° / 180°** | Spherical vs forward-hemisphere projection |
| **Mono / SBS / Over-Under** | Stereo packing of the source frame |
| **Gyro** | Use phone orientation to look around (asks permission on iOS) |
| **Cardboard** | One tap: fullscreen + split-screen stereo + head tracking |
| **Enter VR** | WebXR immersive session (shown only if the device supports it) |
| Transport | Play/pause, scrub, mute |

## How the rendering works

- **Projection.** The video is drawn on the *inside* of a sphere
  (`geometry.scale(-1,1,1)` so it isn't mirrored). 180° uses a forward
  hemisphere. The sphere is rotated so the equirect centre — the direction the
  camera faced during capture — is your starting view.
- **Stereo via render layers.** The left-eye surface is on render layer 1, the
  right-eye surface on layer 2 — never layer 0. three.js' `StereoCamera` and
  WebXR both enable layer 1 on the left eye and layer 2 on the right, so the
  same scene works for flat preview, Cardboard split-screen, and a real
  headset. The flat camera is pinned to layer 1 (left eye).
- **Per-eye cropping.** Each eye gets its own `VideoTexture` over the same
  `<video>`, with `offset`/`repeat` selecting the left/right (SBS) or top/bottom
  (over-under) half.

## Files

```
index.html      markup + import-map (maps "three" -> ./lib)
styles.css      UI styling
app.js          UI glue: loading, look controls, transport, view modes
player.js       the engine: projection, stereo layers, render loop
feed.js         DeoVR/SLR deeplink JSON -> normalized scene descriptor
media.js        attach source to <video>: HLS (hls.js) / native / progressive
library.js      persistent saved-scene library (localStorage)
testpattern.js  equirectangular calibration pattern generator
serve-https.mjs LAN HTTPS dev server (self-signed) for phone/headset testing
manifest.webmanifest + sw.js + icons/   PWA: installable, offline app shell
orbit.user.js   userscript: overlay the VR renderer on any site's video
lib/            vendored three.js r160 (+ UMD build) + VRButton + StereoEffect + hls.js
test/render-check.mjs    headless render + stereo-routing + feed e2e
test/feed-parse.test.mjs unit tests for the deeplink parser
```

## Test

Two suites:

- `npm test` (`test/render-check.mjs`) boots a throwaway server, renders the
  calibration pattern in headless Chromium (software WebGL), asserts the SBS
  frame routes the left half to the left eye and the right half to the right
  eye, then loads a deeplink fixture end-to-end and checks the player
  auto-configured projection / layout / quality list, and finally loads an HLS
  master playlist and confirms hls.js attached and listed its bitrate levels,
  seeds a library tile to confirm it renders and loads on click, and registers
  the service worker to confirm the app shell precaches for offline use.
- `npm run test:unit` (`test/feed-parse.test.mjs`) unit-tests the deeplink
  parser — no browser required.
- `npm run test:userscript` (`test/userscript-check.mjs`) loads a mock VR-site
  page, injects `orbit.user.js`, and asserts the VR button appears, the format
  autodetects, and SBS routes the correct frame-half to each eye.

```bash
npm install        # playwright (browser optional if one is already present)
npm test
# PASS: projection rendered and SBS stereo routed correctly
# PASS: deeplink feed configured player + quality list
```

Set `PW_CHROMIUM` to a Chromium binary if Playwright's bundled browser isn't
installed.

## Roadmap

The playback core, scene-feed loading, HLS streaming, and a saved-scene library
are done. The main remaining piece is a true fisheye projection — MKX200-style
200° lenses currently approximate to a 180° dome; a lens-mapping shader would
sharpen the edges.
