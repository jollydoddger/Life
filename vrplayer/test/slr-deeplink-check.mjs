// slr-deeplink-check.mjs — verify the userscript's SLR-style deeplink route:
// on a page with NO reachable <video> but a DeoVR deeplink, tapping VR should
// fetch the scene JSON, parse projection/stereo, and create a stream <video>
// with the best source — i.e. the path that streams SLR's real video.
//
// (Real playback needs an actual media file; here we assert the wiring: deeplink
//  found -> fetched -> parsed -> video element created with the right URL/format.
//  The rendering itself is covered by userscript-check.mjs.)

import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  const file = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

const exe = ['/opt/pw-browsers/chromium/chrome-linux/chrome', '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  process.env.PW_CHROMIUM].find((p) => p && fs.existsSync(p));
const fail = (m) => { console.error('FAIL:', m); process.exitCode = 1; };

await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ executablePath: exe, args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 900, height: 600 } });

try {
  await page.goto(`${base}/test/fixtures/mocksite-slr.html`, { waitUntil: 'load' });
  await page.addScriptTag({ path: path.join(ROOT, 'lib/three.umd.min.js') });
  await page.addScriptTag({ path: path.join(ROOT, 'orbit.user.js') });

  await page.waitForSelector('#orbit-launch', { timeout: 6000 });
  await page.click('#orbit-launch');
  // deeplink route is async (fetch) — wait for the engine + stream video
  await page.waitForFunction(() => !!window.__orbitEngine && !!window.__orbitVideo, { timeout: 6000 });

  const out = await page.evaluate(() => ({
    projection: window.__orbitEngine.projection,
    fov: window.__orbitEngine.fov,
    layout: window.__orbitEngine.layout,
    src: window.__orbitVideo.getAttribute('src') || window.__orbitVideo.src,
  }));
  console.log('deeplink stream =', JSON.stringify(out));
  if (out.projection !== 'fisheye' || out.fov !== 200) fail(`deeplink mkx200 should give fisheye 200 (got ${out.projection}/${out.fov})`);
  if (out.layout !== 'sbs') fail(`deeplink sbs should give sbs (got ${out.layout})`);
  if (!/scene_h264_1920\.mp4/.test(out.src)) fail(`should pick best h264 source (got ${out.src})`);

  if (!process.exitCode) console.log('PASS: deeplink found, fetched, parsed, and streamed the best source into the viewer');
} finally {
  await browser.close();
  server.close();
}
