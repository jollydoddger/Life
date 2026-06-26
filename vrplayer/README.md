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

Then either paste a video URL, open a local file, or hit **Load test pattern**
to see the built-in equirectangular calibration card.

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
testpattern.js  equirectangular calibration pattern generator
lib/            vendored three.js r160 + VRButton + StereoEffect
test/render-check.mjs   headless render + stereo-routing assertion
```

## Test

`test/render-check.mjs` boots a throwaway server, renders the calibration
pattern in headless Chromium (software WebGL), and asserts the SBS frame routes
the left half to the left eye and the right half to the right eye.

```bash
npm install        # playwright (browser optional if one is already present)
npm test
# PASS: projection rendered and SBS stereo routed correctly
```

Set `PW_CHROMIUM` to a Chromium binary if Playwright's bundled browser isn't
installed.

## Roadmap

This is the playback core. Natural next layers: a library/browse grid, HLS
(`.m3u8`) support via `hls.js`, and scene-feed parsing for sites that expose a
JSON deeplink describing stream URLs, projection, and stereo layout.
