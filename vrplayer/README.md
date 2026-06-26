# Orbit — Stereoscopic 180° / 360° Video Player

A small, self-contained WebXR video player for flat-screen, phone-in-Cardboard,
and full WebXR headsets. Point it at a video URL or a local file and it projects
the footage onto a sphere (360°) or forward hemisphere (180°), with proper
side-by-side / over-under stereo separation per eye.

No build step, no bundler, no CDN — `three.js` is vendored under `lib/`, so the
whole thing runs from any static file server (and offline).

## Run it

A static server is required (ES-module imports don't work over `file://`):

```bash
cd vrplayer
python3 -m http.server 8123      # or: npm start
# open http://localhost:8123/
```

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
| **Cardboard** | Split-screen stereo for a phone-in-Cardboard headset |
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
lib/            vendored three.js r160 + VRButton + StereoEffect + hls.js
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
  and seeds a library tile to confirm it renders and loads on click.
- `npm run test:unit` (`test/feed-parse.test.mjs`) unit-tests the deeplink
  parser — no browser required.

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
