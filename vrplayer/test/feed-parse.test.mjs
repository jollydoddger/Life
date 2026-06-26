// feed-parse.test.mjs — unit tests for the deeplink parser (no browser needed).
//   node test/feed-parse.test.mjs
import { parseDeoVR, looksLikeFeed } from '../feed.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
let failed = 0, total = 0;
const ok = (cond, msg) => { total++; if (!cond) { console.error('FAIL:', msg); failed++; } };

// --- representative dome/SBS scene with mixed codecs ---
const scene = parseDeoVR(JSON.parse(
  fs.readFileSync(path.join(here, 'fixtures/scene.json'), 'utf8')));

ok(scene.projection === '180', `dome -> 180 (got ${scene.projection})`);
ok(scene.layout === 'sbs', `sbs -> sbs (got ${scene.layout})`);
ok(scene.title.includes('Beach'), 'title preserved');
ok(scene.duration === 612, 'duration parsed');
ok(scene.sources.length === 3, `3 sources (got ${scene.sources.length})`);
// H.264 preferred over H.265, and within a codec, highest resolution first.
ok(scene.sources[0].url.includes('h264-1920'),
  `best source is h264 1920 (got ${scene.sources[0].url})`);
ok(scene.sources[2].codec.includes('265'), 'h265 sorted last');

// --- stereo / projection mappings ---
ok(parseDeoVR({ screenType: 'sphere', stereoMode: 'tb', is3d: true,
  videoSources: [{ url: 'x', resolution: 1080 }] }).projection === '360', 'sphere -> 360');
ok(parseDeoVR({ screenType: 'sphere', stereoMode: 'tb', is3d: true,
  videoSources: [{ url: 'x', resolution: 1080 }] }).layout === 'ou', 'tb -> ou');
ok(parseDeoVR({ screenType: 'flat', stereoMode: 'off', is3d: false,
  videoSources: [{ url: 'x' }] }).layout === 'mono', 'off/not-3d -> mono');
// fisheye lenses -> fisheye projection with the right FOV
const mkx = parseDeoVR({ screenType: 'mkx200', stereoMode: 'sbs', is3d: true, videoSources: [{ url: 'x', resolution: 2880 }] });
ok(mkx.projection === 'fisheye' && mkx.fov === 200, `mkx200 -> fisheye 200 (got ${mkx.projection}/${mkx.fov})`);
const rf = parseDeoVR({ screenType: 'fisheye190', stereoMode: 'sbs', is3d: true, videoSources: [{ url: 'x' }] });
ok(rf.projection === 'fisheye' && rf.fov === 190, `fisheye190 -> fisheye 190 (got ${rf.projection}/${rf.fov})`);
const v220 = parseDeoVR({ screenType: 'vrca220', stereoMode: 'sbs', is3d: true, videoSources: [{ url: 'x' }] });
ok(v220.projection === 'fisheye' && v220.fov === 220, `vrca220 -> fisheye 220 (got ${v220.projection}/${v220.fov})`);
ok(parseDeoVR({ screenType: 'rf52', stereoMode: 'cuv', is3d: true, videoSources: [{ url: 'x' }] }).layout === 'sbs', 'cuv -> sbs');

// --- error + detection helpers ---
let threw = false;
try { parseDeoVR({ title: 'no sources' }); } catch { threw = true; }
ok(threw, 'throws when no playable sources');
ok(looksLikeFeed('https://x/y/deeplink.json'), 'detects .json deeplink');
ok(!looksLikeFeed('https://x/y/video.mp4'), 'does not flag plain mp4');

if (failed) { console.error(`\n${failed} assertion(s) failed`); process.exit(1); }
console.log(`PASS: feed parser (${total} assertions)`);
