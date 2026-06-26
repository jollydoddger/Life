// userscript-check.mjs — verify orbit.user.js on a mock VR-site page:
//   * the VR launch button appears once a video is present,
//   * clicking it overlays the stereo renderer,
//   * autodetect reads "180 SBS" from the title,
//   * SBS routes the left frame-half to the left eye and the right to the right.
//
// Loads the vendored UMD three.js (so no network needed), then injects the
// userscript exactly as a userscript manager would after its @require.

import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
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
page.on('pageerror', (e) => fail('page error: ' + e.message));

try {
  await page.goto(`${base}/test/fixtures/mocksite.html`, { waitUntil: 'load' });
  // wait for the page's video to actually have frames
  await page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.videoWidth > 0; }, { timeout: 8000 });

  // userscript manager would load @require first, then the script:
  await page.addScriptTag({ path: path.join(ROOT, 'lib/three.umd.min.js') });
  await page.addScriptTag({ path: path.join(ROOT, 'orbit.user.js') });

  await page.waitForSelector('#orbit-launch', { timeout: 6000 });
  console.log('launch button appeared');
  await page.click('#orbit-launch');
  await page.waitForFunction(() => !!window.__orbitEngine, { timeout: 5000 });

  const fmt = await page.evaluate(() => ({ projection: window.__orbitEngine.projection, fov: window.__orbitEngine.fov, layout: window.__orbitEngine.layout }));
  console.log('autodetected =', JSON.stringify(fmt));
  if (fmt.projection !== 'fisheye') fail(`title "MKX200" should autodetect fisheye (got ${fmt.projection})`);
  if (fmt.fov !== 200) fail(`MKX200 should autodetect fov 200 (got ${fmt.fov})`);
  if (fmt.layout !== 'sbs') fail(`title "SBS" should autodetect layout sbs (got ${fmt.layout})`);

  function sampleEye(layer) {
    return page.evaluate((layer) => {
      const e = window.__orbitEngine;
      e.renderer.setAnimationLoop(null);
      e.camera.layers.set(layer);
      e.camera.lookAt(1, 0, 0);              // look at equirect centre (front)
      e.renderer.render(e.scene, e.camera);
      const gl = e.renderer.getContext(), w = gl.drawingBufferWidth, h = gl.drawingBufferHeight, px = new Uint8Array(4);
      gl.readPixels(Math.floor(w * 0.5), Math.floor(h * 0.35), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      return [px[0], px[1], px[2]];
    }, layer);
  }
  const left = await sampleEye(1), right = await sampleEye(2);
  console.log('left eye =', left, ' right eye =', right);
  if (!(left[0] > left[2])) fail(`left eye should be red-dominant (got ${left})`);
  if (!(right[2] > right[0])) fail(`right eye should be blue-dominant (got ${right})`);

  if (!process.exitCode) console.log('PASS: userscript injects, autodetects format, and renders SBS stereo from the page video');
} finally {
  await browser.close();
  server.close();
}
