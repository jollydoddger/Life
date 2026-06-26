// feed-parse.test.mjs — unit tests for the deeplink parser (no browser needed).
//   node test/feed-parse.test.mjs
import { parseDeoVR, looksLikeFeed } from '../feed.js';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
let failed = 0;
const ok = (cond, msg) => { if (!cond) { console.error('FAIL:', msg); failed++; } };

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
ok(parseDeoVR({ screenType: 'fisheye190', stereoMode: 'sbs', is3d: true,
  videoSources: [{ url: 'x' }] }).projection === '180', 'fisheye -> 180');

// --- error + detection helpers ---
let threw = false;
try { parseDeoVR({ title: 'no sources' }); } catch { threw = true; }
ok(threw, 'throws when no playable sources');
ok(looksLikeFeed('https://x/y/deeplink.json'), 'detects .json deeplink');
ok(!looksLikeFeed('https://x/y/video.mp4'), 'does not flag plain mp4');

if (failed) { console.error(`\n${failed} assertion(s) failed`); process.exit(1); }
console.log('PASS: feed parser (10 assertions)');
