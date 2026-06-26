// ==UserScript==
// @name         Orbit VR — watch any video in stereoscopic VR
// @namespace    https://github.com/jollydoddger/Life
// @version      0.1.0
// @description  Adds a "VR" button to video pages. Plays the page's own video in stereoscopic 180/360 with Cardboard split-screen + head tracking. Because it runs on the site, it uses your existing login and the site's video directly (no CORS wall).
// @author       Orbit
// @match        *://*/*
// @run-at       document-idle
// @grant        none
// @require      https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js
// ==/UserScript==
//
// Install on Android with Kiwi Browser or Firefox + Tampermonkey. See the
// project README for the step-by-step. To restrict it to specific sites, edit
// the @match line above (e.g. *://*.yoursite.com/*).

(function () {
  'use strict';
  if (window.__orbitVR) return;          // guard against double-injection
  window.__orbitVR = true;

  const THREE = window.THREE;
  if (!THREE) { console.warn('[Orbit] three.js failed to load'); return; }

  // ---- find the most relevant <video> on the page -------------------------
  function pickVideo() {
    const vids = [...document.querySelectorAll('video')].filter((v) => v.videoWidth || v.readyState);
    if (!vids.length) return null;
    // prefer the largest, breaking ties toward the one that's playing
    return vids.sort((a, b) => {
      const area = (v) => (v.videoWidth || v.clientWidth) * (v.videoHeight || v.clientHeight);
      return (area(b) - area(a)) || ((b.paused ? 0 : 1) - (a.paused ? 0 : 1));
    })[0];
  }

  // ---- naive format autodetect from page text -----------------------------
  function guessFormat() {
    const s = (document.title + ' ' + location.href).toLowerCase();
    const projection = /\b180\b|dome|fisheye|mkx|vrca/.test(s) ? '180'
      : /\b360\b|sphere/.test(s) ? '360' : '360';
    const layout = /\bsbs\b|side.?by.?side|lr\b/.test(s) ? 'sbs'
      : /\btb\b|over.?under|top.?bottom|ou\b/.test(s) ? 'ou' : 'mono';
    return { projection, layout };
  }

  // ---- floating launch button ---------------------------------------------
  const launch = document.createElement('button');
  launch.id = 'orbit-launch';
  launch.textContent = '🜨 VR';
  Object.assign(launch.style, {
    position: 'fixed', zIndex: 2147483646, right: '14px', bottom: '14px',
    padding: '10px 14px', borderRadius: '12px', border: '0', cursor: 'pointer',
    font: '600 14px system-ui, sans-serif', color: '#fff',
    background: 'linear-gradient(135deg,#6c8cff,#5b78ff)', boxShadow: '0 8px 24px rgba(0,0,0,.45)',
  });
  launch.title = 'Watch this video in VR';
  launch.addEventListener('click', start);

  // show the button only once there's a video worth playing
  const watch = setInterval(() => {
    if (pickVideo() && !document.body.contains(launch)) document.body.appendChild(launch);
  }, 1500);

  // ---- the VR overlay + engine --------------------------------------------
  function start() {
    const video = pickVideo();
    if (!video) { alert('Orbit: no video found on this page yet.'); return; }

    const overlay = document.createElement('div');
    Object.assign(overlay.style, {
      position: 'fixed', inset: '0', zIndex: 2147483647, background: '#000', touchAction: 'none',
    });
    const canvas = document.createElement('canvas');
    Object.assign(canvas.style, { width: '100%', height: '100%', display: 'block' });
    overlay.appendChild(canvas);
    document.body.appendChild(overlay);

    const fmt = guessFormat();
    const engine = buildEngine(canvas, video, fmt);
    window.__orbitEngine = engine;       // for the automated test harness
    overlay.appendChild(buildControls(engine, () => { engine.dispose(); overlay.remove(); }));
  }

  // ---- stereoscopic engine (compact port of the Orbit player) -------------
  function buildEngine(canvas, video, fmt) {
    const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(72, 1, 0.1, 1100);
    camera.layers.set(1);                 // flat view = left eye
    const stereo = new THREE.StereoCamera();
    stereo.eyeSep = 0.064;

    let projection = fmt.projection, layout = fmt.layout, mode = 'flat';
    let orientation = null, lon = 0, lat = 0, meshes = [];

    function eyeTexture(eye) {
      const tex = new THREE.VideoTexture(video);
      tex.colorSpace = THREE.SRGBColorSpace;
      tex.generateMipmaps = false;
      if (layout === 'sbs') { tex.repeat.x = 0.5; tex.offset.x = eye === 'left' ? 0 : 0.5; }
      else if (layout === 'ou') { tex.repeat.y = 0.5; tex.offset.y = eye === 'left' ? 0.5 : 0; }
      return tex;
    }
    function geometry() {
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
      const eyes = layout === 'mono'
        ? [{ eye: 'left', layers: [1, 2] }]
        : [{ eye: 'left', layers: [1] }, { eye: 'right', layers: [2] }];
      for (const { eye, layers } of eyes) {
        const mesh = new THREE.Mesh(geometry(), new THREE.MeshBasicMaterial({ map: eyeTexture(eye) }));
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
    function applyOrientation(o) {
      const d = THREE.MathUtils.degToRad;
      const e = new THREE.Euler(d(o.beta || 0), d(o.alpha || 0), -d(o.gamma || 0), 'YXZ');
      camera.quaternion.setFromEuler(e);
      camera.quaternion.multiply(new THREE.Quaternion(-Math.SQRT1_2, 0, 0, Math.SQRT1_2));
      camera.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), -d(o.screen || 0)));
    }
    function renderStereo() {
      camera.updateMatrixWorld();
      stereo.update(camera);
      const s = renderer.getSize(new THREE.Vector2()), w = s.x, h = s.y;
      renderer.setScissorTest(true);
      renderer.setScissor(0, 0, w / 2, h); renderer.setViewport(0, 0, w / 2, h);
      renderer.render(scene, stereo.cameraL);
      renderer.setScissor(w / 2, 0, w / 2, h); renderer.setViewport(w / 2, 0, w / 2, h);
      renderer.render(scene, stereo.cameraR);
      renderer.setScissorTest(false);
    }
    renderer.setAnimationLoop(() => {
      if (orientation) applyOrientation(orientation); else applyPointer();
      if (mode === 'cardboard') renderStereo(); else renderer.render(scene, camera);
    });

    function resize() {
      const w = window.innerWidth, h = window.innerHeight;
      camera.aspect = (mode === 'cardboard' ? (w / 2) : w) / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
    }
    window.addEventListener('resize', resize); resize();

    return {
      get projection() { return projection; }, get layout() { return layout; }, get mode() { return mode; },
      setProjection(p) { projection = p; build(); },
      setLayout(l) { layout = l; build(); },
      setMode(m) { mode = m; resize(); },
      setOrientation(o) { orientation = o; },
      look(dx, dy) { lon += dx; lat = Math.max(-85, Math.min(85, lat + dy)); },
      camera, scene, renderer,
      dispose() {
        window.removeEventListener('resize', resize);
        renderer.setAnimationLoop(null); renderer.dispose();
      },
    };
  }

  // ---- overlay controls ----------------------------------------------------
  function buildControls(engine, onClose) {
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
    let buttons = [];
    function refresh() {
      bar.innerHTML = ''; buttons = [];
      seg([['360', '360°'], ['180', '180°']], () => engine.projection, (v) => engine.setProjection(v)).forEach((b) => buttons.push(b));
      seg([['mono', 'Mono'], ['sbs', 'SBS'], ['ou', 'OU']], () => engine.layout, (v) => engine.setLayout(v)).forEach((b) => buttons.push(b));
      buttons.push(mk(engine.mode === 'cardboard' ? '◑ Cardboard' : '◐ Cardboard', cardboard, engine.mode === 'cardboard'));
      buttons.push(mk(orientationOn ? '⦿ Gyro' : '○ Gyro', gyro, orientationOn));
      buttons.push(mk('✕ Exit', () => { stopGyro(); onClose(); }, false));
      buttons.forEach((b) => bar.appendChild(b));
    }

    // drag to look (when gyro off)
    let drag = false, lx = 0, ly = 0;
    const dom = engine.renderer.domElement;
    dom.addEventListener('pointerdown', (e) => { if (orientationOn) return; drag = true; lx = e.clientX; ly = e.clientY; });
    dom.addEventListener('pointermove', (e) => { if (!drag) return; engine.look((e.clientX - lx) * -0.12, (e.clientY - ly) * 0.12); lx = e.clientX; ly = e.clientY; });
    addEventListener('pointerup', () => { drag = false; });

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
