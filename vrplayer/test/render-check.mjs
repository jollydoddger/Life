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
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.mp4': 'video/mp4', '.json': 'application/json',
  '.m3u8': 'application/vnd.apple.mpegurl', '.ts': 'video/mp2t',
  '.png': 'image/png', '.webmanifest': 'application/manifest+json' };

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

  // --- fisheye (mkx200) projection renders + routes SBS per eye ---
  async function sampleFisheye(eye) {
    return page.evaluate((eye) => {
      const p = window.__player;
      p.setFormat({ projection: 'fisheye', fov: 200, layout: 'sbs' });
      return import('./testpattern.js').then(({ buildTestPattern }) => {
        p.setSource(buildTestPattern({ projection: 'fisheye', layout: 'sbs' }));
        p.renderer.setAnimationLoop(null);
        p.camera.layers.set(eye);
        p.lon = 0; p.lat = 0; p.camera.lookAt(1, 0, 0);
        p.renderer.render(p.scene, p.camera);
        const gl = p.renderer.getContext(), w = gl.drawingBufferWidth, h = gl.drawingBufferHeight, px = new Uint8Array(4);
        gl.readPixels(Math.floor(w * 0.5), Math.floor(h * 0.72), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        return [px[0], px[1], px[2]];
      });
    }, eye);
  }
  const fLeft = await sampleFisheye(1), fRight = await sampleFisheye(2);
  console.log('fisheye left =', fLeft, ' right =', fRight);
  if (!(fLeft[0] + fLeft[1] + fLeft[2] > 30)) fail('fisheye frame appears black — nothing rendered');
  if (!(fLeft[2] > fLeft[0])) fail(`fisheye left eye should be blue-dominant, got ${fLeft}`);
  if (!(fRight[0] > fRight[2])) fail(`fisheye right eye should be red-dominant, got ${fRight}`);
  if (!process.exitCode) console.log('PASS: fisheye (mkx200) projection rendered and SBS routed correctly');

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

  // --- HLS: load a master playlist and confirm hls.js attaches + lists levels ---
  await page.goto(`${base}/index.html`, { waitUntil: 'networkidle' });
  await page.fill('#url', '/test/fixtures/hls/master.m3u8');
  await page.click('#loadUrl');
  await page.waitForFunction(
    () => document.querySelector('#quality').options.length >= 4, { timeout: 8000 }).catch(() => {});
  const hls = await page.evaluate(() => {
    const q = document.querySelector('#quality');
    return {
      hasHls: !!document.querySelector('#media')._hls,
      labels: [...q.options].map((o) => o.textContent),
      visible: !q.classList.contains('hidden'),
    };
  });
  console.log('hls =', JSON.stringify(hls));
  if (!hls.hasHls) fail('hls.js instance was not attached to the <video>');
  if (hls.labels.length !== 4) fail(`expected Auto + 3 levels (got ${hls.labels.length}: ${hls.labels})`);
  if (!hls.labels.includes('Auto') || !hls.labels.includes('1080p'))
    fail(`level labels missing Auto/1080p (got ${hls.labels})`);
  if (!hls.visible) fail('quality selector should be visible for HLS levels');
  if (!process.exitCode) console.log('PASS: HLS stream attached and adaptive levels listed');

  // --- library: a seeded scene renders as a tile and loads on click ---
  await page.addInitScript(() => {
    localStorage.setItem('orbit.library.v1', JSON.stringify([{
      id: 'seed1', title: 'Seeded Beach', url: '/test/fixtures/scene.json',
      thumbnail: '', kind: 'feed', projection: '180', layout: 'sbs',
    }]));
  });
  await page.goto(`${base}/index.html`, { waitUntil: 'networkidle' });
  const lib = await page.evaluate(() => ({
    tiles: document.querySelectorAll('#tiles .tile').length,
    visible: !document.querySelector('#library').classList.contains('hidden'),
    title: document.querySelector('.tile-title')?.textContent,
  }));
  console.log('library =', JSON.stringify(lib));
  if (lib.tiles !== 1) fail(`expected 1 library tile (got ${lib.tiles})`);
  if (!lib.visible) fail('library grid should be visible when items exist');
  if (lib.title !== 'Seeded Beach') fail(`tile title wrong (got ${lib.title})`);

  await page.click('#tiles .tile');
  await page.waitForFunction(
    () => document.querySelector('#loader').classList.contains('hidden')
      && window.__player.projection === '180', { timeout: 5000 }).catch(() => {});
  const afterClick = await page.evaluate(() => ({
    loaderHidden: document.querySelector('#loader').classList.contains('hidden'),
    projection: window.__player.projection,
  }));
  if (!afterClick.loaderHidden) fail('clicking a tile should start playback (loader still visible)');
  if (afterClick.projection !== '180') fail(`tile load should configure projection 180 (got ${afterClick.projection})`);
  if (!process.exitCode) console.log('PASS: library tile rendered and loaded its scene');

  // --- PWA: service worker registers and precaches the app shell ---
  const pwaCtx = await browser.newContext();
  const pwaPage = await pwaCtx.newPage();
  await pwaPage.goto(`${base}/index.html`, { waitUntil: 'load' });
  const pwa = await pwaPage.evaluate(async () => {
    const reg = await Promise.race([
      navigator.serviceWorker.ready,
      new Promise((r) => setTimeout(() => r(null), 8000)),
    ]);
    const out = { active: !!reg?.active, app: false, three: false, hls: false, manifest: null };
    for (const n of await caches.keys()) {
      const c = await caches.open(n);
      out.app = out.app || !!(await c.match('./app.js'));
      out.three = out.three || !!(await c.match('./lib/three.module.js'));
      out.hls = out.hls || !!(await c.match('./lib/hls.js'));
    }
    out.manifest = await fetch('manifest.webmanifest').then((r) => r.json()).then((m) => m.short_name).catch(() => null);
    return out;
  });
  console.log('pwa =', JSON.stringify(pwa));
  if (!pwa.active) fail('service worker did not activate');
  if (!(pwa.app && pwa.three && pwa.hls)) fail(`app shell not fully precached: ${JSON.stringify(pwa)}`);
  if (pwa.manifest !== 'Orbit') fail(`manifest short_name should be Orbit (got ${pwa.manifest})`);
  if (!process.exitCode) console.log('PASS: PWA service worker registered and precached the app shell');
  await pwaCtx.close();
} finally {
  await browser.close();
  server.close();
}
