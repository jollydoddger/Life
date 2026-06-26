// ==UserScript==
// @name         Orbit VR — watch any video in stereoscopic VR
// @namespace    https://github.com/jollydoddger/Life
// @version      0.4.0
// @description  Adds a "VR" button to video pages. Plays the page's own video — or, on sites that hide it (e.g. SLR), fetches the scene's real stream via its deeplink using your logged-in session — in stereoscopic 180/360/fisheye with Cardboard + head tracking.
// @author       Orbit
// @match        *://*/*
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @connect      sexlikereal.com
// @connect      *
// @require      https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js
// ==/UserScript==
// GM_xmlhttpRequest lets us fetch a scene's data and video bytes past CORS using
// your logged-in cookies; the bytes become an in-memory blob the viewer can
// render (nothing is written to disk). hls.js is loaded lazily only if needed.
//
// Install on Android with Kiwi Browser or Firefox + Tampermonkey. See the
// project README for the step-by-step. To restrict it to specific sites, edit
// the @match line above (e.g. *://*.yoursite.com/*).

(function () {
  'use strict';
  if (window.__orbitVR) return;          // guard against double-injection
  window.__orbitVR = true;

  const VERSION = '0.4.0';
  // Tampermonkey's privileged request (bypasses CORS, carries cookies). Supports
  // both the classic (GM_xmlhttpRequest) and GM4 (GM.xmlHttpRequest) names.
  const gmXHR = (typeof GM_xmlhttpRequest === 'function') ? GM_xmlhttpRequest
    : (typeof GM !== 'undefined' && GM && GM.xmlHttpRequest) ? (o) => GM.xmlHttpRequest(o)
      : null;
  const THREE = window.THREE;             // loaded via @require (may be missing if that failed)

  // Equidistant fisheye dome (SLR/DeoVR mkx200/mkx220/rf52/fisheye). Forward=+X.
  function fisheyeGeometry(radius, fovDeg) {
    const half = THREE.MathUtils.degToRad(fovDeg / 2), W = 128, H = 96;
    const pos = [], uv = [], idx = [];
    for (let j = 0; j <= H; j++) {
      const t = j / H, theta = half * t, rN = 0.5 * t;
      const sinT = Math.sin(theta), cosT = Math.cos(theta);
      for (let i = 0; i <= W; i++) {
        const phi = 2 * Math.PI * (i / W), cphi = Math.cos(phi), sphi = Math.sin(phi);
        pos.push(cosT * radius, sinT * cphi * radius, -sinT * sphi * radius);
        uv.push(0.5 - rN * sphi, 0.5 + rN * cphi);
      }
    }
    for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) {
      const a = j * (W + 1) + i, b = a + 1, c = a + (W + 1), d = c + 1;
      idx.push(a, c, b, b, c, d);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setIndex(idx); g.computeVertexNormals();
    return g;
  }

  // ---- find the most relevant <video> on the page -------------------------
  // Custom players (like SLR's) often hide the <video> inside a shadow DOM or a
  // same-origin iframe, so search those too — a plain querySelectorAll misses it.
  function deepVideos() {
    const out = new Set();
    const visit = (root) => {
      if (!root || !root.querySelectorAll) return;
      root.querySelectorAll('video').forEach((v) => out.add(v));
      root.querySelectorAll('*').forEach((el) => { if (el.shadowRoot) visit(el.shadowRoot); });
    };
    visit(document);
    document.querySelectorAll('iframe').forEach((f) => {
      try { if (f.contentDocument) visit(f.contentDocument); } catch { /* cross-origin */ }
    });
    return [...out];
  }

  // Detailed report of where media might be hiding (for diagnosing sites like
  // SLR that embed the player in an iframe).
  function diagnostics() {
    const lines = [];
    lines.push(`videos(top):${document.querySelectorAll('video').length} canvas(top):${document.querySelectorAll('canvas').length}`);
    let shadowHosts = 0; document.querySelectorAll('*').forEach((el) => { if (el.shadowRoot) shadowHosts++; });
    lines.push(`shadowRoots:${shadowHosts}  three:${window.THREE ? 'yes' : 'NO'}`);
    const ifr = [...document.querySelectorAll('iframe')];
    lines.push(`iframes:${ifr.length}`);
    ifr.forEach((f, i) => {
      let acc = 'cross-origin', vids = '?';
      try { const d = f.contentDocument; if (d) { acc = 'same-origin'; vids = d.querySelectorAll('video').length; } } catch { /* cross-origin */ }
      let host = ''; try { host = new URL(f.src || '', location.href).host || '(srcdoc)'; } catch { host = '?'; }
      lines.push(`· iframe${i}: ${acc} · ${host} · vids:${vids}`);
    });
    return lines.join('\n');
  }

  function pickVideo() {
    const vids = deepVideos().filter((v) => v.videoWidth > 0 || v.currentSrc || v.srcObject || v.readyState > 0);
    if (!vids.length) return null;
    // prefer the largest, breaking ties toward the one that's actually playing
    return vids.sort((a, b) => {
      const area = (v) => (v.videoWidth || v.clientWidth || 0) * (v.videoHeight || v.clientHeight || 0);
      return (area(b) - area(a)) || ((b.paused ? 0 : 1) - (a.paused ? 0 : 1));
    })[0];
  }

  // ---- format autodetect from page text + site defaults -------------------
  function guessFormat() {
    const s = (document.title + ' ' + location.href).toLowerCase();
    let projection = '360', fov = 180;
    if (/mkx220|vrca220|\b220\b/.test(s)) { projection = 'fisheye'; fov = 220; }
    else if (/mkx200|\b200\b/.test(s)) { projection = 'fisheye'; fov = 200; }
    else if (/rf52|fisheye190|\b190\b/.test(s)) { projection = 'fisheye'; fov = 190; }
    else if (/fisheye/.test(s)) { projection = 'fisheye'; fov = 180; }
    else if (/\b180\b|dome/.test(s)) projection = '180';
    else if (/\b360\b|sphere/.test(s)) projection = '360';

    let layout = /\bsbs\b|side.?by.?side|\blr\b/.test(s) ? 'sbs'
      : /\btb\b|over.?under|top.?bottom|\bou\b/.test(s) ? 'ou' : 'mono';

    // Site default: SLR's catalogue is overwhelmingly 200° fisheye SBS, so start
    // there unless the page text clearly says otherwise.
    if (/sexlikereal\.com/.test(location.host) && projection === '360' && layout === 'mono') {
      projection = 'fisheye'; fov = 200; layout = 'sbs';
    }
    return { projection, fov, layout };
  }

  // ---- floating launch button ---------------------------------------------
  // The script runs in every frame (Tampermonkey @match *://*/*). When a site
  // (like SLR) puts its <video> in a cross-origin iframe, only the script
  // instance *inside* that iframe can reach the video — so in a subframe we show
  // the button on the video itself (top-centre), and only when a video exists.
  const inIframe = window.top !== window.self;
  const launch = document.createElement('button');
  launch.id = 'orbit-launch';
  launch.textContent = inIframe ? '🜨 VR ▶' : '🜨 VR';
  Object.assign(launch.style, {
    position: 'fixed', zIndex: 2147483646,
    padding: '10px 14px', borderRadius: '12px', border: '0', cursor: 'pointer',
    font: '600 14px system-ui, sans-serif', color: '#fff',
    background: 'linear-gradient(135deg,#6c8cff,#5b78ff)', boxShadow: '0 8px 24px rgba(0,0,0,.45)',
    ...(inIframe
      ? { left: '50%', top: '12px', transform: 'translateX(-50%)' }
      : { right: '14px', bottom: '14px' }),
  });
  launch.title = 'Watch this video in VR (Orbit ' + VERSION + ')';
  launch.addEventListener('click', () => {
    try { start(); }
    catch (e) { alert('Orbit v' + VERSION + ' error:\n' + (e && e.message ? e.message : e)); }
  });

  // Top frame: always show the button (proof it's installed). Inside a subframe:
  // only show it once that frame actually contains a video (so ad iframes stay
  // clean and the button lands on the real player frame).
  const watch = setInterval(() => {
    if (!document.body) return;
    const show = inIframe ? !!pickVideo() : true;
    if (show && !document.body.contains(launch)) document.body.appendChild(launch);
    else if (!show && document.body.contains(launch)) launch.remove();
  }, 1200);

  // Cross-origin videos can't be uploaded to WebGL (the canvas would be
  // "tainted"). Detect that up front so we can explain a black screen instead of
  // just showing one. Returns 'ok', 'tainted', or 'notready'.
  function videoTextureStatus(video) {
    if (!video.videoWidth) return 'notready';
    try {
      const c = document.createElement('canvas'); c.width = c.height = 2;
      const cx = c.getContext('2d');
      cx.drawImage(video, 0, 0, 2, 2);
      cx.getImageData(0, 0, 1, 1);   // throws SecurityError if cross-origin tainted
      return 'ok';
    } catch { return 'tainted'; }
  }

  function statusBar() {
    const s = document.createElement('div');
    Object.assign(s.style, {
      position: 'fixed', top: '10px', left: '50%', transform: 'translateX(-50%)',
      zIndex: 2147483647, maxWidth: '92vw', padding: '8px 12px', borderRadius: '10px',
      background: 'rgba(16,18,26,.82)', color: '#cdd6ec', font: '12px system-ui, sans-serif',
      textAlign: 'center', lineHeight: '1.4',
    });
    return s;
  }

  // ---- the VR overlay + engine --------------------------------------------
  function start() {
    if (!THREE) {
      alert('Orbit v' + VERSION + ': the VR engine (three.js) didn\'t load.\n'
        + 'Close this tab, reopen the scene, and try again — or check your connection.');
      return;
    }
    const video = pickVideo();
    if (video) { launchViewer(video, guessFormat()); return; }
    // No reachable <video> in this frame (e.g. SLR's canvas player) — try to
    // fetch the scene's real stream via its deeplink, using the logged-in session.
    deeplinkRoute();
  }

  // Show the cross-origin / can't-render message.
  function showTaint(status, overlay, engine) {
    if (engine) engine.dispose();
    status.style.background = 'rgba(60,20,24,.92)'; status.style.color = '#ffd0d0';
    status.style.opacity = '1';
    status.innerHTML = '⚠ The video is <b>cross-origin protected</b>, so the browser blocks rendering it. '
      + 'This stream can\'t be shown here. <u>Tap to close</u>';
    status.onclick = () => overlay.remove();
  }

  // Build the fullscreen overlay + engine around a <video> (from the page or a
  // stream we created). Handles taint detection now and once frames arrive.
  function launchViewer(video, fmt, mediaCtl) {
    const overlay = document.createElement('div');
    Object.assign(overlay.style, {
      position: 'fixed', inset: '0', zIndex: 2147483647, background: '#000', touchAction: 'none',
    });
    const canvas = document.createElement('canvas');
    Object.assign(canvas.style, { width: '100%', height: '100%', display: 'block' });
    overlay.appendChild(canvas);
    const status = statusBar();
    overlay.appendChild(status);
    document.body.appendChild(overlay);

    const lens = fmt.projection === 'fisheye' ? `fisheye ${fmt.fov}°` : `${fmt.projection}`;
    const setStatus = () => {
      status.textContent = `Orbit v${VERSION} · ${video.videoWidth || '…'}×${video.videoHeight || '…'} · ${lens} · ${fmt.layout}`;
    };
    setStatus();
    video.addEventListener('loadedmetadata', setStatus, { once: true });
    setTimeout(() => { status.style.transition = 'opacity .6s'; status.style.opacity = '0'; }, 4000);

    const engine = buildEngine(canvas, video, fmt);
    window.__orbitEngine = engine;       // for the automated test harness

    // Live FPS badge (top-left) so you can tune sharpness vs. smoothness/120Hz.
    const fpsBadge = document.createElement('div');
    Object.assign(fpsBadge.style, {
      position: 'fixed', top: '10px', left: '10px', zIndex: 2147483647, display: 'none',
      padding: '4px 8px', borderRadius: '8px', background: 'rgba(0,0,0,.6)', color: '#9eff9e',
      font: '600 12px ui-monospace, monospace',
    });
    overlay.appendChild(fpsBadge);
    const fpsTimer = setInterval(() => { fpsBadge.textContent = engine.fps + ' fps'; }, 500);

    overlay.appendChild(buildControls(engine, () => {
      clearInterval(fpsTimer); engine.dispose(); overlay.remove();
    }, mediaCtl, fpsBadge));

    // Cross-origin texture check — now if ready, else once the first frame loads.
    const check = () => { if (videoTextureStatus(video) === 'tainted') showTaint(status, overlay, engine); };
    if (video.videoWidth) check();
    else video.addEventListener('loadeddata', check, { once: true });
  }

  // ---- SLR-style deeplink route: fetch the real stream and play it --------
  // Find the scene's DeoVR JSON endpoint.
  function findDeeplinkUrl() {
    // SexLikeReal: their scene API is keyed by the numeric scene id at the end of
    // the /scenes/<slug>-<id> URL (endpoint used by xbvr and the DeoVR deeplink).
    if (/(^|\.)sexlikereal\.com$/.test(location.host)) {
      const m = location.pathname.match(/\/scenes\/.*-(\d+)(?:[/?#]|$)/);
      if (m) return `https://api.sexlikereal.com/virtualreality/video/id/${m[1]}`;
    }
    // Generic: a DeoVR/HereSphere deeplink the page exposes as a link…
    const sel = 'a[href^="deovr://"],a[href^="heresphere://"],a[href*="/deovr"],a[href$=".json"]';
    for (const a of document.querySelectorAll(sel)) {
      let h = a.getAttribute('href') || '';
      h = h.replace(/^deovr:\/\//i, '').replace(/^heresphere:\/\//i, '');
      if (/^https?:\/\//i.test(h)) return h;
    }
    // …or one embedded anywhere in the page markup.
    const m = document.documentElement.innerHTML.match(/(?:deovr|heresphere):\/\/(https?:\/\/[^"'\s<>\\]+)/i);
    return m ? m[1] : null;
  }

  // Minimal DeoVR JSON parser -> { projection, fov, layout, sources[] }.
  function parseDeoVRMini(data) {
    const s = String(data.screenType || '').toLowerCase();
    let projection = '180', fov;
    if (s.includes('sphere') || s === '360') projection = '360';
    else if (s.includes('mkx220') || s.includes('vrca220') || s.includes('220')) { projection = 'fisheye'; fov = 220; }
    else if (s.includes('mkx200') || s.includes('200')) { projection = 'fisheye'; fov = 200; }
    else if (s.includes('rf52') || s.includes('190')) { projection = 'fisheye'; fov = 190; }
    else if (s.includes('fisheye')) { projection = 'fisheye'; fov = 180; }
    else if (s.includes('dome') || s === '180') projection = '180';
    const m = String(data.stereoMode || '').toLowerCase();
    const layout = (data.is3d === false || m === 'off' || m === '') ? 'mono'
      : (m === 'tb' ? 'ou' : 'sbs'); // sbs/cuv/lr/unknown-3d -> sbs
    const sources = [];
    const push = (u, h, c, sz) => { if (u) sources.push({ url: u, height: +h || 0, codec: String(c || '').toLowerCase(), size: +sz || 0 }); };
    (data.encodings || []).forEach((e) => (e.videoSources || []).forEach((x) => push(x.url, x.resolution ?? x.height, e.name, x.size)));
    if (!sources.length && Array.isArray(data.videoSources)) data.videoSources.forEach((x) => push(x.url, x.resolution ?? x.height, x.codec, x.size));
    const rank = (c) => (/264|avc/.test(c) ? 0 : /m3u8|hls/.test(c) ? 1 : /265|hevc/.test(c) ? 3 : 2);
    sources.sort((a, b) => rank(a.codec) - rank(b.codec) || b.height - a.height);
    return { projection, fov, layout, sources };
  }

  // Load hls.js on demand (only for .m3u8 streams). Resolves true if available.
  function ensureHls() {
    if (window.Hls) return Promise.resolve(true);
    return new Promise((resolve) => {
      const s = document.createElement('script');
      s.src = 'https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.light.min.js';
      s.onload = () => resolve(!!window.Hls);
      s.onerror = () => resolve(false);
      document.head.appendChild(s);
      setTimeout(() => resolve(!!window.Hls), 6000);
    });
  }

  // Privileged GET (bypasses CORS, sends cookies). Falls back to page fetch.
  function gmGetJSON(url) {
    return new Promise((resolve, reject) => {
      if (!gmXHR) { fetch(url, { credentials: 'include' }).then((r) => r.json()).then(resolve, reject); return; }
      gmXHR({
        method: 'GET', url, withCredentials: true, responseType: 'json', timeout: 25000,
        onload: (r) => { try { resolve(r.response || JSON.parse(r.responseText)); } catch (e) { reject(e); } },
        onerror: () => reject(new Error('network/CORS error')),
        ontimeout: () => reject(new Error('timeout')),
      });
    });
  }
  function gmGetBlob(url, onProgress, maxBytes) {
    return new Promise((resolve, reject) => {
      if (!gmXHR) { reject(new Error('no privileged fetch')); return; }
      const h = gmXHR({
        method: 'GET', url, withCredentials: true, responseType: 'blob', timeout: 0,
        onprogress: (e) => {
          if (maxBytes && e.total && e.total > maxBytes) { try { h.abort(); } catch {} reject(new Error('too large (' + Math.round(e.total / 1e6) + ' MB) to cache in memory')); return; }
          if (onProgress && e.lengthComputable) onProgress(e.loaded, e.total);
        },
        onload: (r) => resolve(r.response),
        onerror: () => reject(new Error('network/CORS error')),
        ontimeout: () => reject(new Error('timeout')),
      });
    });
  }

  function progressPanel() {
    const p = document.createElement('div');
    Object.assign(p.style, {
      position: 'fixed', inset: '0', zIndex: 2147483647, background: 'rgba(0,0,0,.93)',
      color: '#cdd6ec', display: 'flex', alignItems: 'center', justifyContent: 'center',
      textAlign: 'center', font: '15px system-ui, sans-serif', padding: '24px',
    });
    const inner = document.createElement('div');
    p.appendChild(inner); document.body.appendChild(p);
    return { set: (html) => { inner.innerHTML = html; }, remove: () => p.remove() };
  }

  const MAX_CACHE_BYTES = 2000 * 1e6; // guard so we don't OOM the tab (S24 Ultra has headroom)

  async function deeplinkRoute() {
    const dl = findDeeplinkUrl();
    if (!dl) {
      alert('Orbit v' + VERSION + ': no video in this frame, and no scene deeplink found.\n\n'
        + diagnostics() + '\n\nThis page may not expose a stream the script can reach.');
      return;
    }
    const panel = progressPanel();
    panel.set('Orbit v' + VERSION + '<br><br>Fetching scene data…');

    let data;
    try { data = await gmGetJSON(dl); }
    catch (e) {
      panel.remove();
      alert('Orbit: found the scene endpoint but couldn\'t read it:\n' + dl + '\n\n' + e
        + '\n\n(A CORS/network error here means SLR\'s API refused the request.)');
      return;
    }
    let scene;
    try { scene = parseDeoVRMini(data); } catch (e) { panel.remove(); alert('Orbit: scene data not understood.\n' + e); return; }
    if (!scene.sources.length) { panel.remove(); alert('Orbit: the scene data has no stream URL.'); return; }

    const v = document.createElement('video');
    v.playsInline = true; v.preload = 'auto'; v.loop = false;
    window.__orbitVideo = v;

    const mp4 = scene.sources.filter((s) => !/\.m3u8/i.test(s.url));
    let mediaCtl = null;

    if (gmXHR && mp4.length) {
      // Cache an mp4 in memory (renderable blob, no CORS taint, no disk write).
      // The quality picker re-runs this for a different resolution on demand.
      panel.remove(); // hand off to per-cache progress panels
      let blobUrl = null;
      const cacheSource = async (src) => {
        const p = progressPanel();
        let aborted = false;
        try {
          const blob = await gmGetBlob(src.url, (loaded, total) => {
            p.set('Orbit v' + VERSION + '<br><br>Caching ' + (src.height ? src.height + 'p' : 'scene') + '… '
              + Math.round(loaded / total * 100) + '%<br><small>' + Math.round(loaded / 1e6) + ' / ' + Math.round(total / 1e6) + ' MB</small>'
              + '<br><br><small>kept in memory only — nothing saved to your phone</small>');
          }, MAX_CACHE_BYTES);
          if (blobUrl) URL.revokeObjectURL(blobUrl);
          blobUrl = URL.createObjectURL(blob);
          v.src = blobUrl; v.play().catch(() => {});
        } catch (e) {
          aborted = true;
          alert('Orbit: couldn\'t cache ' + (src.height ? src.height + 'p' : 'that quality') + ':\n' + e
            + '\n\nTry a lower quality, or use download → Open file for full size.');
        } finally { p.remove(); }
        return !aborted;
      };
      const def = pickDefaultSource(mp4);
      await cacheSource(def);
      mediaCtl = {
        sources: mp4, current: def,
        recache: async (src) => { mediaCtl.current = src; await cacheSource(src); },
      };
    } else if (mp4.length === 0) {
      // HLS only — stream it (cross-origin, so may not render).
      const url = scene.sources[0].url;
      v.crossOrigin = 'anonymous';
      if (await ensureHls() && window.Hls.isSupported()) { const hls = new window.Hls(); hls.loadSource(url); hls.attachMedia(v); }
      else v.src = url;
      panel.remove();
    } else {
      v.src = mp4[0].url; // no privileged fetch (e.g. tests) — direct
      panel.remove();
    }

    v.play().catch(() => {});
    launchViewer(v, scene, mediaCtl);
  }

  // Default cache quality: the sharpest source that should fit in memory.
  function pickDefaultSource(list) {
    const known = list.filter((s) => s.size > 0);
    if (known.length) {
      const fit = known.filter((s) => s.size <= MAX_CACHE_BYTES * 0.92).sort((a, b) => b.height - a.height);
      return fit[0] || known.slice().sort((a, b) => a.size - b.size)[0];
    }
    // No sizes given: prefer the highest resolution up to 2160p, else the lowest.
    const under = list.filter((s) => s.height && s.height <= 2160).sort((a, b) => b.height - a.height);
    return under[0] || list.slice().sort((a, b) => (a.height || 1e9) - (b.height || 1e9))[0];
  }

  // ---- stereoscopic engine (compact port of the Orbit player) -------------
  function buildEngine(canvas, video, fmt) {
    const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    // Render scale (cap on devicePixelRatio). Lower = far less GPU fill-rate =
    // smoother + cooler (less thermal throttling) on high-DPI phones; higher =
    // sharper. 1.5 is a good default on the S24 Ultra's ~3.4x display.
    let renderScale = 1.5;
    const applyScale = () => renderer.setPixelRatio(Math.min(window.devicePixelRatio, renderScale));
    applyScale();
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(72, 1, 0.1, 1100);
    camera.layers.set(1);                 // flat view = left eye
    const stereo = new THREE.StereoCamera();
    stereo.eyeSep = 0.064;

    let projection = fmt.projection, layout = fmt.layout, fov = fmt.fov || 180, mode = 'flat';
    let orientation = null, lon = 0, lat = 0, meshes = [], yawOffset = 0;
    const WORLD_UP = new THREE.Vector3(0, 1, 0);
    const Q1 = new THREE.Quaternion(-Math.SQRT1_2, 0, 0, Math.SQRT1_2);
    const ZEE = new THREE.Vector3(0, 0, 1);
    const _qoff = new THREE.Quaternion();

    function eyeTexture(eye) {
      const tex = new THREE.VideoTexture(video);
      tex.colorSpace = THREE.SRGBColorSpace;
      tex.generateMipmaps = false;
      if (layout === 'sbs') { tex.repeat.x = 0.5; tex.offset.x = eye === 'left' ? 0 : 0.5; }
      else if (layout === 'ou') { tex.repeat.y = 0.5; tex.offset.y = eye === 'left' ? 0.5 : 0; }
      return tex;
    }
    function geometry() {
      if (projection === 'fisheye') return fisheyeGeometry(500, fov);
      const g = projection === '180'
        ? new THREE.SphereGeometry(500, 64, 40, Math.PI / 2, Math.PI, 0, Math.PI)
        : new THREE.SphereGeometry(500, 64, 40);
      g.scale(-1, 1, 1);
      g.rotateY(Math.PI);
      return g;
    }
    function build() {
      for (const m of meshes) { scene.remove(m); m.geometry.dispose(); m.material.map.dispose(); m.material.dispose(); }
      meshes = [];
      const side = projection === 'fisheye' ? THREE.DoubleSide : THREE.FrontSide;
      const eyes = layout === 'mono'
        ? [{ eye: 'left', layers: [1, 2] }]
        : [{ eye: 'left', layers: [1] }, { eye: 'right', layers: [2] }];
      for (const { eye, layers } of eyes) {
        const mesh = new THREE.Mesh(geometry(), new THREE.MeshBasicMaterial({ map: eyeTexture(eye), side }));
        mesh.layers.disableAll();
        layers.forEach((l) => mesh.layers.enable(l));
        scene.add(mesh); meshes.push(mesh);
      }
    }
    build();

    function applyPointer() {
      const phi = THREE.MathUtils.degToRad(90 - lat), theta = THREE.MathUtils.degToRad(lon);
      camera.lookAt(Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta));
    }
    function deviceQuat(o, out) {
      const d = THREE.MathUtils.degToRad;
      out.setFromEuler(new THREE.Euler(d(o.beta || 0), d(o.alpha || 0), -d(o.gamma || 0), 'YXZ'));
      out.multiply(Q1);
      out.multiply(_qoff.setFromAxisAngle(ZEE, -d(o.screen || 0)));
      return out;
    }
    function applyOrientation(o) {
      deviceQuat(o, camera.quaternion);
      // Recentre: rotate the whole view about world-up so the chosen heading is forward.
      if (yawOffset) camera.quaternion.premultiply(_qoff.setFromAxisAngle(WORLD_UP, yawOffset));
    }
    // Make whatever you're currently looking at the new forward (centre of video).
    function recenter() {
      if (orientation) {
        const f = new THREE.Vector3(0, 0, -1).applyQuaternion(deviceQuat(orientation, new THREE.Quaternion()));
        yawOffset = Math.atan2(f.z, f.x); // rotate current heading onto +X (content centre)
      } else { lon = 0; lat = 0; }
    }
    // Lens (barrel) distortion correction for a real Cardboard-style headset:
    // each eye is rendered to a target, then drawn through a pre-warp shader so
    // the lens's pincushion distortion cancels to a straight, sharp image.
    let lensOn = false;
    const lens = { k1: 0.22, k2: 0.18, zoom: 1.0, ipd: 0.0 };
    let post = null;
    const FRAG = [
      'varying vec2 vUv;',
      'uniform sampler2D tDiffuse; uniform float k1, k2, zoom; uniform vec2 center;',
      'void main(){',
      '  vec2 p = (vUv - center) * 2.0;',
      '  float r2 = dot(p, p);',
      '  float f = 1.0 + k1 * r2 + k2 * r2 * r2;',
      '  vec2 uv = center + (p * f) * 0.5 / zoom;',
      '  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(0.0,0.0,0.0,1.0); return; }',
      '  gl_FragColor = texture2D(tDiffuse, uv);',
      '}',
    ].join('\n');
    const VERT = 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }';
    function ensurePost() {
      const dpr = renderer.getPixelRatio();
      const rw = Math.max(2, Math.floor(window.innerWidth / 2 * dpr));
      const rh = Math.max(2, Math.floor(window.innerHeight * dpr));
      if (!post) {
        const mkMat = (cx) => new THREE.ShaderMaterial({
          uniforms: { tDiffuse: { value: null }, k1: { value: lens.k1 }, k2: { value: lens.k2 }, zoom: { value: lens.zoom }, center: { value: new THREE.Vector2(cx, 0.5) } },
          vertexShader: VERT, fragmentShader: FRAG, depthTest: false, depthWrite: false,
        });
        const rtL = new THREE.WebGLRenderTarget(rw, rh), rtR = new THREE.WebGLRenderTarget(rw, rh);
        rtL.texture.minFilter = rtR.texture.minFilter = THREE.LinearFilter;
        const matL = mkMat(0.5), matR = mkMat(0.5);
        matL.uniforms.tDiffuse.value = rtL.texture; matR.uniforms.tDiffuse.value = rtR.texture;
        const sc = new THREE.Scene();
        const qL = new THREE.Mesh(new THREE.PlaneGeometry(1, 2), matL); qL.position.x = -0.5; qL.frustumCulled = false;
        const qR = new THREE.Mesh(new THREE.PlaneGeometry(1, 2), matR); qR.position.x = 0.5; qR.frustumCulled = false;
        sc.add(qL, qR);
        post = { rtL, rtR, matL, matR, scene: sc, cam: new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1) };
      } else { post.rtL.setSize(rw, rh); post.rtR.setSize(rw, rh); }
    }
    function renderStereo() {
      camera.updateMatrixWorld();
      stereo.update(camera);
      if (lensOn) {
        ensurePost();
        renderer.setRenderTarget(post.rtL); renderer.render(scene, stereo.cameraL);
        renderer.setRenderTarget(post.rtR); renderer.render(scene, stereo.cameraR);
        renderer.setRenderTarget(null);
        for (const m of [post.matL, post.matR]) { m.uniforms.k1.value = lens.k1; m.uniforms.k2.value = lens.k2; m.uniforms.zoom.value = lens.zoom; }
        post.matL.uniforms.center.value.set(0.5 + lens.ipd, 0.5);
        post.matR.uniforms.center.value.set(0.5 - lens.ipd, 0.5);
        renderer.render(post.scene, post.cam);
      } else {
        const s = renderer.getSize(new THREE.Vector2()), w = s.x, h = s.y;
        renderer.setScissorTest(true);
        renderer.setScissor(0, 0, w / 2, h); renderer.setViewport(0, 0, w / 2, h);
        renderer.render(scene, stereo.cameraL);
        renderer.setScissor(w / 2, 0, w / 2, h); renderer.setViewport(w / 2, 0, w / 2, h);
        renderer.render(scene, stereo.cameraR);
        renderer.setScissorTest(false);
      }
    }
    let renderErr = false, fps = 0, _fc = 0, _ft = performance.now();
    renderer.setAnimationLoop(() => {
      if (orientation) applyOrientation(orientation); else applyPointer();
      try {
        if (mode === 'cardboard') renderStereo(); else renderer.render(scene, camera);
      } catch (e) {
        if (!renderErr) { renderErr = true; console.warn('[Orbit] render error (likely cross-origin video):', e.message); }
      }
      _fc++; const n = performance.now();
      if (n - _ft >= 500) { fps = Math.round(_fc / ((n - _ft) / 1000)); _fc = 0; _ft = n; }
    });

    function resize() {
      const w = window.innerWidth, h = window.innerHeight;
      camera.aspect = (mode === 'cardboard' ? (w / 2) : w) / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
      if (post) ensurePost();
    }
    window.addEventListener('resize', resize); resize();

    return {
      get projection() { return projection; }, get layout() { return layout; }, get fov() { return fov; }, get mode() { return mode; },
      setProjection(p, f) { projection = p; if (f) fov = f; build(); },
      setLayout(l) { layout = l; build(); },
      setMode(m) { mode = m; resize(); },
      setOrientation(o) { orientation = o; },
      look(dx, dy) { lon += dx; lat = Math.max(-85, Math.min(85, lat + dy)); },
      recenter,
      get renderScale() { return renderScale; },
      setRenderScale(s) { renderScale = s; applyScale(); resize(); },
      get fps() { return fps; },
      get lens() { return Object.assign({ on: lensOn }, lens); },
      setLens(p) { Object.assign(lens, p); },
      setLensOn(b) { lensOn = b; resize(); },
      camera, scene, renderer,
      dispose() {
        window.removeEventListener('resize', resize);
        renderer.setAnimationLoop(null);
        if (post) { post.rtL.dispose(); post.rtR.dispose(); }
        renderer.dispose();
      },
    };
  }

  // ---- overlay controls ----------------------------------------------------
  function buildControls(engine, onClose, mediaCtl, fpsBadge) {
    const bar = document.createElement('div');
    Object.assign(bar.style, {
      position: 'fixed', left: '50%', bottom: '16px', transform: 'translateX(-50%)',
      display: 'flex', gap: '6px', flexWrap: 'wrap', justifyContent: 'center',
      padding: '8px', borderRadius: '14px', zIndex: 2147483647,
      background: 'rgba(16,18,26,.7)', backdropFilter: 'blur(12px)',
      font: '13px system-ui, sans-serif', maxWidth: '94vw',
    });
    const mk = (label, on, active) => {
      const b = document.createElement('button');
      b.textContent = label;
      Object.assign(b.style, {
        padding: '8px 10px', borderRadius: '9px', border: '1px solid rgba(255,255,255,.12)',
        background: active ? '#6c8cff' : 'rgba(255,255,255,.05)', color: '#fff', cursor: 'pointer',
      });
      b.addEventListener('click', () => on(b));
      return b;
    };
    const seg = (values, get, set) => values.map(([val, label]) => {
      const b = mk(label, () => { set(val); refresh(); }, get() === val);
      b.dataset.val = val; return b;
    });
    function projSelect() {
      const sel = document.createElement('select');
      Object.assign(sel.style, {
        padding: '8px', borderRadius: '9px', border: '1px solid rgba(255,255,255,.12)',
        background: 'rgba(255,255,255,.05)', color: '#fff', font: 'inherit',
      });
      const opts = [['360', '360°'], ['180', '180°'], ['f180', 'Fish 180°'],
        ['f190', 'Fish 190°'], ['f200', 'MKX200'], ['f220', 'Fish 220°']];
      const cur = engine.projection === 'fisheye' ? 'f' + engine.fov : engine.projection;
      for (const [v, l] of opts) {
        const o = document.createElement('option'); o.value = v; o.textContent = l;
        if (v === cur) o.selected = true; sel.appendChild(o);
      }
      sel.addEventListener('change', () => {
        const v = sel.value;
        if (v[0] === 'f') engine.setProjection('fisheye', parseInt(v.slice(1), 10));
        else engine.setProjection(v);
      });
      return sel;
    }
    // Quality (source resolution) picker — only for the deeplink/cache path.
    function qualitySelect() {
      const sel = document.createElement('select');
      Object.assign(sel.style, {
        padding: '8px', borderRadius: '9px', border: '1px solid rgba(255,255,255,.12)',
        background: 'rgba(255,255,255,.05)', color: '#fff', font: 'inherit',
      });
      const list = mediaCtl.sources.slice().sort((a, b) => (b.height || 0) - (a.height || 0));
      for (const s of list) {
        const o = document.createElement('option');
        o.value = s.url; o.textContent = (s.height ? s.height + 'p' : 'source') + (s.size ? ' · ' + Math.round(s.size / 1e6) + 'MB' : '');
        if (s === mediaCtl.current) o.selected = true; sel.appendChild(o);
      }
      sel.addEventListener('change', () => {
        const src = mediaCtl.sources.find((s) => s.url === sel.value);
        if (src) mediaCtl.recache(src);
      });
      return sel;
    }
    // Lens-correction settings panel (for a real Cardboard/printed headset):
    // sliders for distortion, eye offset (IPD), and zoom — tune to your lenses.
    const lensPanel = document.createElement('div');
    Object.assign(lensPanel.style, {
      position: 'fixed', right: '14px', bottom: '70px', zIndex: 2147483647, display: 'none',
      padding: '12px', borderRadius: '12px', background: 'rgba(16,18,26,.92)', color: '#cdd6ec',
      font: '12px system-ui, sans-serif', minWidth: '210px',
    });
    const slider = (label, min, max, step, get, set) => {
      const wrap = document.createElement('label');
      Object.assign(wrap.style, { display: 'block', margin: '8px 0' });
      const name = document.createElement('div'); name.textContent = label;
      const r = document.createElement('input');
      r.type = 'range'; r.min = min; r.max = max; r.step = step; r.value = get(); r.style.width = '100%';
      r.addEventListener('input', () => { set(parseFloat(r.value)); name.textContent = label + ' · ' + (+r.value).toFixed(2); });
      wrap.appendChild(name); wrap.appendChild(r); return wrap;
    };
    lensPanel.appendChild(slider('Distortion', 0, 0.5, 0.01, () => engine.lens.k1,
      (v) => engine.setLens({ k1: v, k2: v * 0.8 })));
    lensPanel.appendChild(slider('Eye offset (IPD)', -0.12, 0.12, 0.005, () => engine.lens.ipd, (v) => engine.setLens({ ipd: v })));
    lensPanel.appendChild(slider('Zoom', 0.8, 1.4, 0.02, () => engine.lens.zoom, (v) => engine.setLens({ zoom: v })));
    document.body.appendChild(lensPanel);

    let fpsOn = false;
    function refresh() {
      bar.innerHTML = '';
      bar.appendChild(projSelect());
      seg([['mono', 'Mono'], ['sbs', 'SBS'], ['ou', 'OU']], () => engine.layout, (v) => engine.setLayout(v)).forEach((b) => bar.appendChild(b));
      if (mediaCtl && mediaCtl.sources.length > 1) bar.appendChild(qualitySelect());
      bar.appendChild(mk('⟲ Recenter', () => engine.recenter(), false));
      const q = engine.renderScale, qLabel = q <= 1 ? '⚡ Perf' : q < 2 ? '◎ Balanced' : '✦ Sharp';
      bar.appendChild(mk(qLabel, () => { engine.setRenderScale(q <= 1 ? 1.5 : q < 2 ? 2 : 1); refresh(); }, false));
      bar.appendChild(mk(engine.lens.on ? '⊙ Lens' : '○ Lens', () => {
        const on = !engine.lens.on;
        engine.setLensOn(on); lensPanel.style.display = on ? 'block' : 'none'; refresh();
      }, engine.lens.on));
      if (fpsBadge) bar.appendChild(mk('fps', () => { fpsOn = !fpsOn; fpsBadge.style.display = fpsOn ? 'block' : 'none'; refresh(); }, fpsOn));
      bar.appendChild(mk(engine.mode === 'cardboard' ? '◑ Cardboard' : '◐ Cardboard', cardboard, engine.mode === 'cardboard'));
      bar.appendChild(mk(orientationOn ? '⦿ Gyro' : '○ Gyro', gyro, orientationOn));
      bar.appendChild(mk('✕ Exit', () => { stopGyro(); lensPanel.remove(); onClose(); }, false));
    }

    // drag to look (when gyro off)
    let drag = false, lx = 0, ly = 0;
    const dom = engine.renderer.domElement;
    dom.addEventListener('pointerdown', (e) => { if (orientationOn) return; drag = true; lx = e.clientX; ly = e.clientY; });
    dom.addEventListener('pointermove', (e) => { if (!drag) return; engine.look((e.clientX - lx) * -0.12, (e.clientY - ly) * 0.12); lx = e.clientX; ly = e.clientY; });
    addEventListener('pointerup', () => { drag = false; });
    // While gyro/Cardboard drives the view, a tap on the screen recenters —
    // handy inside the headset where you can't reach the button.
    dom.addEventListener('click', () => { if (orientationOn) engine.recenter(); });

    let orientationOn = false;
    function onOrient(e) {
      const sa = window.screen?.orientation?.angle ?? window.orientation ?? 0;
      engine.setOrientation({ alpha: e.alpha, beta: e.beta, gamma: e.gamma, screen: sa });
    }
    async function startGyro() {
      try {
        const DOE = window.DeviceOrientationEvent;
        if (DOE && typeof DOE.requestPermission === 'function' && await DOE.requestPermission() !== 'granted') return false;
      } catch { return false; }
      window.addEventListener('deviceorientation', onOrient); orientationOn = true; return true;
    }
    function stopGyro() { window.removeEventListener('deviceorientation', onOrient); engine.setOrientation(null); orientationOn = false; }
    async function gyro() { orientationOn ? stopGyro() : await startGyro(); refresh(); }
    async function cardboard() {
      const on = engine.mode !== 'cardboard';
      engine.setMode(on ? 'cardboard' : 'flat');
      if (on) { await startGyro(); try { await document.documentElement.requestFullscreen?.(); } catch {} window.screen?.orientation?.lock?.('landscape').catch(() => {}); }
      else if (document.fullscreenElement) document.exitFullscreen?.();
      refresh();
    }

    refresh();
    return bar;
  }
})();
