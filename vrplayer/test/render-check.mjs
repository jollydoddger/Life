// render-check.mjs — headless rendering test for the Orbit VR player.
//
// Boots a throwaway static server, loads the app in headless Chromium with a
// software-WebGL fallback, renders the calibration pattern, and asserts that:
//   * something actually rendered (not a black frame),
//   * SBS stereo routes the LEFT half to the left eye (blue tint) and the
//     RIGHT half to the right eye (red tint).
//
// Usage:  node test/render-check.mjs
// Exit code 0 = pass, 1 = fail. Requires `playwright` installed; set
// PW_CHROMIUM to point at a Chromium binary if not on the default path.

import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.mp4': 'video/mp4' };

const server = http.createServer((req, res) => {
  const file = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404); return res.end('not found');
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

function findChromium() {
  const cands = [process.env.PW_CHROMIUM,
    '/opt/pw-browsers/chromium/chrome-linux/chrome',
    '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'].filter(Boolean);
  return cands.find((p) => fs.existsSync(p)); // undefined => playwright's default
}

const fail = (msg) => { console.error('FAIL:', msg); process.exitCode = 1; };

await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({
  executablePath: findChromium(),
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--no-sandbox', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
page.on('pageerror', (e) => fail('page error: ' + e.message));

try {
  await page.goto(`${base}/index.html`, { waitUntil: 'networkidle' });

  // Sample a pixel from one eye after rendering the SBS calibration pattern.
  // Returns [r,g,b] at (15% width, 35% height) — away from centre text/bars.
  async function sampleEye(eye) {
    return page.evaluate(async (eye) => {
      const p = window.__player;
      p.setFormat({ projection: '360', layout: 'sbs' });
      const { buildTestPattern } = await import('./testpattern.js');
      p.setSource(buildTestPattern({ projection: '360', layout: 'sbs' }));
      p.renderer.setAnimationLoop(null);
      p.camera.layers.set(eye);
      p.lon = 0; p.lat = 0; p._applyPointerView();
      p.renderer.render(p.scene, p.camera);
      const gl = p.renderer.getContext();
      const w = gl.drawingBufferWidth, h = gl.drawingBufferHeight;
      const px = new Uint8Array(4);
      gl.readPixels(Math.floor(w * 0.15), Math.floor(h * 0.35), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      return [px[0], px[1], px[2]];
    }, eye);
  }

  const left = await sampleEye(1);
  const right = await sampleEye(2);
  console.log('left-eye  rgb =', left);
  console.log('right-eye rgb =', right);

  const bright = (c) => c[0] + c[1] + c[2] > 30;
  if (!bright(left) || !bright(right)) fail('frame appears black — nothing rendered');
  if (!(left[2] > left[0])) fail(`left eye should be blue-dominant, got ${left}`);
  if (!(right[0] > right[2])) fail(`right eye should be red-dominant, got ${right}`);

  if (!process.exitCode) console.log('PASS: projection rendered and SBS stereo routed correctly');

  // --- end-to-end: load a deeplink scene through the real UI ---
  await page.goto(`${base}/index.html`, { waitUntil: 'networkidle' });
  await page.fill('#url', '/test/fixtures/scene.json');
  await page.click('#loadUrl');
  await page.waitForFunction(() => window.__player?.projection === '180', { timeout: 5000 })
    .catch(() => {});
  const feed = await page.evaluate(() => ({
    projection: window.__player.projection,
    layout: window.__player.layout,
    qualityCount: document.querySelector('#quality').options.length,
    qualityVisible: !document.querySelector('#quality').classList.contains('hidden'),
    hint: document.querySelector('#hint').textContent,
  }));
  console.log('feed e2e =', JSON.stringify(feed));
  if (feed.projection !== '180') fail(`deeplink should set projection 180 (got ${feed.projection})`);
  if (feed.layout !== 'sbs') fail(`deeplink should set layout sbs (got ${feed.layout})`);
  if (feed.qualityCount !== 3) fail(`expected 3 quality options (got ${feed.qualityCount})`);
  if (!feed.qualityVisible) fail('quality selector should be visible for multi-source scene');
  if (!feed.hint.includes('Beach')) fail(`hint should show scene title (got "${feed.hint}")`);
  if (!process.exitCode) console.log('PASS: deeplink feed configured player + quality list');
} finally {
  await browser.close();
  server.close();
}
