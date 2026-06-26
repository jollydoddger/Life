import * as THREE from 'three';

// ── Storage ────────────────────────────────────────────────────────────────
const STORE = 'vr-player-v2';
function loadLib() { try { return JSON.parse(localStorage.getItem(STORE)) || []; } catch { return []; } }
function saveLib() { localStorage.setItem(STORE, JSON.stringify(library)); }

let library = loadLib();

// ── DOM ────────────────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);
const viewLib      = $('view-library');
const viewPlayer   = $('view-player');
const videoGrid    = $('video-grid');
const emptyState   = $('empty-state');
const canvasWrap   = $('player-canvas-wrap');
const vrLine       = $('vr-line');
const buffering    = $('buffering');
const playerError  = $('player-error');
const ctrlOverlay  = $('player-controls');
const ctrlTitle    = $('ctrl-title');
const playPauseBtn = $('play-pause-btn');
const ppSmall      = $('pp-small-btn');
const progressFill = $('progress-fill');
const progressDot  = $('progress-dot');
const timeLabel    = $('time-label');
const volSlider    = $('vol-slider');
const gyroBtn      = $('gyro-btn');
const vrBtn        = $('vr-btn');
const toast        = $('toast');
const fovSlider    = $('fov-slider');
const fovVal       = $('fov-val');
const distToggle   = $('distortion-toggle');

// ── Player state ───────────────────────────────────────────────────────────
let scene, camera, renderer;
let sphere = null;
let sphereMat = null;
let videoEl = null;
let videoTex = null;

// Post-processing for barrel distortion
let vrRT = null;          // WebGLRenderTarget
let distortMesh = null;   // fullscreen quad
let distortScene = null;
let distortCam = null;

let isVR        = false;
let distortOn   = false;
let gyroOn      = false;
let hasGyro     = false;
let isPlaying   = false;
let stereoMode  = 'mono';   // 'mono' | 'tb' | 'lr'
let fov         = 75;
let animId      = null;
let ctrlShown   = false;
let ctrlTimer   = null;
let currentVid  = null;

// Drag
let dragging  = false;
let dragX = 0, dragY = 0;
let rotH = 0, rotV = 0;

// Pinch zoom
let pinchDist = null;

// Gyro quaternion state
const zee  = new THREE.Vector3(0, 0, 1);
const gEul = new THREE.Euler();
const gQ0  = new THREE.Quaternion();
const gQ1  = new THREE.Quaternion(-Math.sqrt(.5), 0, 0, Math.sqrt(.5));
const gQ   = new THREE.Quaternion();
let gyroOffset = new THREE.Quaternion(); // recenter offset

// Manual look quaternion
const mEul = new THREE.Euler(0, 0, 0, 'YXZ');
const mQ   = new THREE.Quaternion();

// ── Shaders ────────────────────────────────────────────────────────────────
const SPHERE_VERT = `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}`;

const SPHERE_FRAG = `
uniform sampler2D videoTex;
uniform float uvOX; // offset x
uniform float uvSX; // scale x
uniform float uvOY; // offset y
uniform float uvSY; // scale y
varying vec2 vUv;
void main() {
  vec2 uv = vec2(vUv.x * uvSX + uvOX, vUv.y * uvSY + uvOY);
  gl_FragColor = texture2D(videoTex, uv);
}`;

// Barrel / pincushion distortion for Cardboard
const DISTORT_VERT = `
varying vec2 vUv;
void main() { vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`;

const DISTORT_FRAG = `
uniform sampler2D tDiffuse;
uniform float k1;
uniform float k2;
varying vec2 vUv;

vec2 barrel(vec2 p, vec2 c) {
  vec2 d = (p - c) * 2.0;
  float r2 = dot(d, d);
  float f = 1.0 + k1 * r2 + k2 * r2 * r2;
  return c + d * f * 0.5;
}

void main() {
  vec2 uv = vUv;
  vec2 d;
  bool ok;
  if (uv.x < 0.5) {
    d = barrel(uv, vec2(0.25, 0.5));
    ok = d.x >= 0.0 && d.x <= 0.5 && d.y >= 0.0 && d.y <= 1.0;
  } else {
    d = barrel(uv, vec2(0.75, 0.5));
    ok = d.x >= 0.5 && d.x <= 1.0 && d.y >= 0.0 && d.y <= 1.0;
  }
  gl_FragColor = ok ? texture2D(tDiffuse, d) : vec4(0.0, 0.0, 0.0, 1.0);
}`;

// ── Three.js init ──────────────────────────────────────────────────────────
function initThree() {
  if (renderer) return;

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(fov, 1, 0.1, 2000);
  camera.position.set(0, 0, 0);

  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  canvasWrap.appendChild(renderer.domElement);

  // Distortion post-process setup
  distortScene = new THREE.Scene();
  distortCam   = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
  const quadGeo  = new THREE.PlaneGeometry(2, 2);
  const distMat  = new THREE.ShaderMaterial({
    uniforms: { tDiffuse: { value: null }, k1: { value: 0.441 }, k2: { value: 0.156 } },
    vertexShader: DISTORT_VERT, fragmentShader: DISTORT_FRAG,
  });
  distortMesh = new THREE.Mesh(quadGeo, distMat);
  distortScene.add(distortMesh);

  onResize();
  window.addEventListener('resize', onResize);
  setupPointerEvents();
}

function onResize() {
  if (!renderer) return;
  const w = canvasWrap.clientWidth || window.innerWidth;
  const h = canvasWrap.clientHeight || window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  rebuildRT(w, h);
}

function rebuildRT(w, h) {
  if (vrRT) vrRT.dispose();
  vrRT = new THREE.WebGLRenderTarget(w, h, { minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter });
  distortMesh.material.uniforms.tDiffuse.value = vrRT.texture;
}

// ── Sphere ─────────────────────────────────────────────────────────────────
function buildSphere() {
  if (sphere) { scene.remove(sphere); sphere.geometry.dispose(); sphere.material.dispose(); }

  const geo = new THREE.SphereGeometry(500, 64, 32);
  geo.scale(-1, 1, 1);

  sphereMat = new THREE.ShaderMaterial({
    uniforms: {
      videoTex: { value: videoTex },
      uvOX: { value: 0 }, uvSX: { value: 1 },
      uvOY: { value: 0 }, uvSY: { value: 1 },
    },
    vertexShader: SPHERE_VERT,
    fragmentShader: SPHERE_FRAG,
  });

  sphere = new THREE.Mesh(geo, sphereMat);
  scene.add(sphere);
  applyStereoUniforms('left');
}

// Set UV uniforms for the current stereo mode + eye
function applyStereoUniforms(eye) {
  if (!sphereMat) return;
  const u = sphereMat.uniforms;
  if (stereoMode === 'mono') {
    u.uvOX.value = 0; u.uvSX.value = 1;
    u.uvOY.value = 0; u.uvSY.value = 1;
  } else if (stereoMode === 'tb') {
    // Top = left eye (UV.y 0→0.5), Bottom = right eye (UV.y 0.5→1)
    u.uvOX.value = 0; u.uvSX.value = 1;
    u.uvOY.value = eye === 'left' ? 0   : 0.5;
    u.uvSY.value = 0.5;
  } else if (stereoMode === 'lr') {
    // Left half = left eye, Right half = right eye
    u.uvOX.value = eye === 'left' ? 0   : 0.5;
    u.uvSX.value = 0.5;
    u.uvOY.value = 0; u.uvSY.value = 1;
  }
}

// ── Render loop ─────────────────────────────────────────────────────────────
function startLoop() {
  if (animId) cancelAnimationFrame(animId);
  function loop() {
    animId = requestAnimationFrame(loop);
    if (videoTex && videoEl?.readyState >= 2) videoTex.needsUpdate = true;

    // Camera rotation
    if (gyroOn && hasGyro) {
      const q = gQ.clone().premultiply(gyroOffset);
      camera.quaternion.slerp(q, 0.2);
    } else {
      mEul.set(rotV, rotH, 0, 'YXZ');
      mQ.setFromEuler(mEul);
      camera.quaternion.slerp(mQ, 0.1);
    }

    if (isVR) renderVR(); else renderMono();
  }
  loop();
}

function renderMono() {
  const w = renderer.domElement.width, h = renderer.domElement.height;
  applyStereoUniforms('left');
  renderer.setViewport(0, 0, w, h);
  renderer.setScissorTest(false);
  renderer.setRenderTarget(null);
  renderer.render(scene, camera);
}

function renderVR() {
  const w = renderer.domElement.width, h = renderer.domElement.height;
  const hw = Math.floor(w / 2);
  const origAspect = camera.aspect;

  camera.aspect = hw / h;
  camera.updateProjectionMatrix();

  const target = distortOn ? vrRT : null;
  renderer.setRenderTarget(target);
  renderer.setScissorTest(true);

  // Left eye
  applyStereoUniforms('left');
  renderer.setViewport(0, 0, hw, h);
  renderer.setScissor(0, 0, hw, h);
  renderer.render(scene, camera);

  // Right eye
  applyStereoUniforms('right');
  renderer.setViewport(hw, 0, hw, h);
  renderer.setScissor(hw, 0, hw, h);
  renderer.render(scene, camera);

  renderer.setScissorTest(false);
  renderer.setRenderTarget(null);

  // Apply distortion pass if on
  if (distortOn) {
    renderer.setViewport(0, 0, w, h);
    renderer.render(distortScene, distortCam);
  }

  // Restore camera aspect for non-VR use
  camera.aspect = origAspect;
  camera.updateProjectionMatrix();
}

// ── Library UI ──────────────────────────────────────────────────────────────
function renderLibrary() {
  videoGrid.innerHTML = '';
  const empty = library.length === 0;
  emptyState.classList.toggle('show', empty);
  videoGrid.style.display = empty ? 'none' : 'grid';

  library.forEach((vid, i) => {
    const card = document.createElement('div');
    card.className = 'video-card';
    const stereoLabel = vid.stereoMode === 'tb' ? '3D TB'
                      : vid.stereoMode === 'lr' ? '3D LR' : '';
    card.innerHTML = `
      <div class="card-thumb">
        ${vid.thumb ? `<img src="${esc(vid.thumb)}" loading="lazy" onerror="this.style.display='none'">` : '🎬'}
        <div class="card-overlay"><div class="card-play-icon">▶</div></div>
        <span class="stereo-badge${stereoLabel ? ' show' : ''}">${stereoLabel}</span>
      </div>
      <div class="card-info">
        <div class="card-title">${esc(vid.title || 'Untitled')}</div>
        <div class="card-meta">360° · tap to play</div>
      </div>
      <button class="card-delete" data-i="${i}">✕</button>`;
    card.addEventListener('click', e => {
      if (e.target.closest('.card-delete')) return;
      openPlayer(vid);
    });
    card.querySelector('.card-delete').addEventListener('click', e => {
      e.stopPropagation();
      library.splice(i, 1); saveLib(); renderLibrary();
    });
    videoGrid.appendChild(card);
  });
}

function esc(s) {
  return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ── Add modal ───────────────────────────────────────────────────────────────
function openAddModal() {
  $('input-url').value = $('input-title').value = $('input-thumb').value = '';
  // Try to paste clipboard
  navigator.clipboard?.readText().then(t => {
    if (/^https?:\/\//i.test(t)) $('input-url').value = t;
  }).catch(() => {});
  $('add-modal').classList.add('open');
  setTimeout(() => $('input-url').focus(), 200);
}
function closeAddModal() { $('add-modal').classList.remove('open'); }

function addAndPlay() {
  const url = $('input-url').value.trim();
  if (!url) { $('input-url').focus(); return; }
  const vid = {
    id: Date.now(), url,
    title: $('input-title').value.trim() || deriveTitle(url),
    thumb: $('input-thumb').value.trim(),
    stereoMode: 'mono',
  };
  library.unshift(vid); saveLib(); renderLibrary();
  closeAddModal();
  openPlayer(vid);
}

function deriveTitle(url) {
  try { return decodeURIComponent(new URL(url).pathname.split('/').pop().replace(/\.[^.]+$/,'')) || 'Video'; }
  catch { return 'Video'; }
}

$('add-btn').addEventListener('click', openAddModal);
$('empty-add-btn').addEventListener('click', openAddModal);
$('modal-cancel').addEventListener('click', closeAddModal);
$('modal-add').addEventListener('click', addAndPlay);
$('add-modal').addEventListener('click', e => { if (e.target === $('add-modal')) closeAddModal(); });

// ── Settings panel ──────────────────────────────────────────────────────────
function openSettings() {
  // Sync controls to current state
  document.querySelectorAll('[data-stereo]').forEach(p =>
    p.classList.toggle('active', p.dataset.stereo === stereoMode));
  fovSlider.value = fov;
  fovVal.textContent = fov + '°';
  distToggle.checked = distortOn;
  $('settings-panel').classList.add('open');
}
function closeSettings() { $('settings-panel').classList.remove('open'); }

document.querySelectorAll('[data-stereo]').forEach(btn => {
  btn.addEventListener('click', () => {
    stereoMode = btn.dataset.stereo;
    document.querySelectorAll('[data-stereo]').forEach(p =>
      p.classList.toggle('active', p.dataset.stereo === stereoMode));
    // Save to current video in library
    if (currentVid) {
      const entry = library.find(v => v.id === currentVid.id);
      if (entry) { entry.stereoMode = stereoMode; saveLib(); renderLibrary(); }
    }
  });
});

fovSlider.addEventListener('input', () => {
  fov = parseInt(fovSlider.value);
  fovVal.textContent = fov + '°';
  if (camera) { camera.fov = fov; camera.updateProjectionMatrix(); }
});

distToggle.addEventListener('change', () => { distortOn = distToggle.checked; });

$('settings-btn').addEventListener('click', openSettings);
$('close-settings').addEventListener('click', closeSettings);
$('settings-panel').addEventListener('click', e => { if (e.target === $('settings-panel')) closeSettings(); });

// ── View switching ──────────────────────────────────────────────────────────
function showLibrary() {
  stopPlayer();
  viewPlayer.classList.remove('active');
  viewLib.classList.add('active');
  document.getElementById('tab-bar').style.display = 'flex';
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}
function goPlayer() {
  viewLib.classList.remove('active');
  viewBrowse?.classList.remove('active');
  viewPlayer.classList.add('active');
  document.getElementById('tab-bar').style.display = 'none';
}

// ── Player ──────────────────────────────────────────────────────────────────
function openPlayer(vid) {
  currentVid = vid;
  stereoMode = vid.stereoMode || 'mono';
  goPlayer();

  buffering.classList.add('show');
  playerError.classList.remove('show');
  ctrlOverlay.classList.remove('show');
  ctrlShown = false;

  ctrlTitle.textContent = vid.title || 'Video';

  if (videoEl) { videoEl.pause(); videoEl.src = ''; videoEl.load(); }

  videoEl = document.createElement('video');
  videoEl.crossOrigin = 'anonymous';
  videoEl.playsInline = true;
  videoEl.loop = true;
  videoEl.preload = 'auto';
  videoEl.src = vid.url;

  videoEl.addEventListener('canplay', onCanPlay, { once: true });
  videoEl.addEventListener('waiting', () => buffering.classList.add('show'));
  videoEl.addEventListener('playing', () => buffering.classList.remove('show'));
  videoEl.addEventListener('error', onVideoError, { once: true });
  videoEl.addEventListener('timeupdate', updateProgress);
  videoEl.addEventListener('ended', () => { isPlaying = false; syncPlayBtn(); });

  videoEl.load();
  initThree();
  startLoop();
  showControls();
}

function onCanPlay() {
  if (videoTex) videoTex.dispose();
  videoTex = new THREE.VideoTexture(videoEl);
  videoTex.colorSpace = THREE.SRGBColorSpace;
  videoTex.minFilter = THREE.LinearFilter;
  videoTex.magFilter = THREE.LinearFilter;
  buildSphere();
  buffering.classList.remove('show');

  videoEl.play().then(() => { isPlaying = true; syncPlayBtn(); showControls(); })
         .catch(()  => { isPlaying = false; syncPlayBtn(); showControls(); });
}

function onVideoError() {
  buffering.classList.remove('show');
  playerError.classList.add('show');
}

function stopPlayer() {
  if (animId) { cancelAnimationFrame(animId); animId = null; }
  if (videoEl) { videoEl.pause(); videoEl.src = ''; videoEl = null; }
  if (sphere) { scene?.remove(sphere); sphere = null; }
  isPlaying = false; isVR = false; distortOn = false;
  vrLine.style.display = 'none';
  vrBtn.classList.remove('active');
  distToggle.checked = false;
  rotH = 0; rotV = 0;
  disableGyro();
}

// ── Pointer events ──────────────────────────────────────────────────────────
function setupPointerEvents() {
  const el = renderer.domElement;

  // Click on canvas = toggle controls (not when drag moved)
  let didDrag = false;
  let lastTap = 0;

  el.addEventListener('touchstart', e => {
    if (e.touches.length === 1) {
      dragging = true; didDrag = false;
      dragX = e.touches[0].clientX; dragY = e.touches[0].clientY;
    } else if (e.touches.length === 2) {
      pinchDist = getPinchDist(e);
    }
  }, { passive: true });

  el.addEventListener('touchmove', e => {
    if (e.touches.length === 2 && pinchDist != null) {
      const d = getPinchDist(e);
      fov = clamp(fov - (d - pinchDist) * 0.08, 30, 120);
      pinchDist = d;
      if (camera) { camera.fov = fov; camera.updateProjectionMatrix(); }
      fovSlider.value = fov; fovVal.textContent = Math.round(fov) + '°';
      return;
    }
    if (!dragging || e.touches.length !== 1 || gyroOn) return;
    const dx = e.touches[0].clientX - dragX;
    const dy = e.touches[0].clientY - dragY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) didDrag = true;
    rotH -= dx * 0.006;
    rotV  = clamp(rotV - dy * 0.004, -Math.PI/2, Math.PI/2);
    dragX = e.touches[0].clientX; dragY = e.touches[0].clientY;
  }, { passive: true });

  el.addEventListener('touchend', e => {
    dragging = false; pinchDist = null;
    if (!didDrag) {
      const now = Date.now();
      if (now - lastTap < 280) { recenterView(); lastTap = 0; }
      else { lastTap = now; toggleControls(); }
    }
  });

  // Mouse
  el.addEventListener('mousedown', e => { dragging = true; didDrag = false; dragX = e.clientX; dragY = e.clientY; });
  el.addEventListener('mousemove', e => {
    if (!dragging) return;
    const dx = e.clientX - dragX, dy = e.clientY - dragY;
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) didDrag = true;
    rotH -= dx * 0.005; rotV = clamp(rotV - dy * 0.003, -Math.PI/2, Math.PI/2);
    dragX = e.clientX; dragY = e.clientY;
  });
  window.addEventListener('mouseup', e => {
    if (!didDrag && e.target === el) toggleControls();
    dragging = false;
  });

  // Scroll to zoom (desktop)
  el.addEventListener('wheel', e => {
    fov = clamp(fov + e.deltaY * 0.05, 30, 120);
    if (camera) { camera.fov = fov; camera.updateProjectionMatrix(); }
    fovSlider.value = fov; fovVal.textContent = Math.round(fov) + '°';
  }, { passive: true });
}

function getPinchDist(e) {
  const dx = e.touches[0].clientX - e.touches[1].clientX;
  const dy = e.touches[0].clientY - e.touches[1].clientY;
  return Math.sqrt(dx*dx + dy*dy);
}

// ── Gyroscope ───────────────────────────────────────────────────────────────
function onDeviceOrientation(e) {
  if (!e.alpha && !e.beta && !e.gamma) return;
  hasGyro = true;
  const a = THREE.MathUtils.degToRad(e.alpha ?? 0);
  const b = THREE.MathUtils.degToRad(e.beta  ?? 0);
  const g = THREE.MathUtils.degToRad(e.gamma ?? 0);
  const o = THREE.MathUtils.degToRad(screen.orientation?.angle ?? window.orientation ?? 0);
  gEul.set(b, a, -g, 'YXZ');
  gQ.setFromEuler(gEul).multiply(gQ1);
  gQ0.setFromAxisAngle(zee, -o);
  gQ.multiply(gQ0);
}

async function toggleGyro() {
  if (gyroOn) { disableGyro(); return; }
  if (typeof DeviceOrientationEvent !== 'undefined' &&
      typeof DeviceOrientationEvent.requestPermission === 'function') {
    try { if (await DeviceOrientationEvent.requestPermission() !== 'granted') return; }
    catch { return; }
  }
  window.addEventListener('deviceorientation', onDeviceOrientation);
  gyroOn = true; gyroBtn.classList.add('active'); gyroBtn.textContent = '◎';
  flash('Move your phone to look around');
}

function disableGyro() {
  window.removeEventListener('deviceorientation', onDeviceOrientation);
  gyroOn = false; hasGyro = false;
  gyroBtn.classList.remove('active'); gyroBtn.textContent = '◉';
}

function recenterView() {
  if (gyroOn && hasGyro) {
    // Store inverse of current gyro quaternion as offset
    gyroOffset.copy(gQ).invert();
    flash('View recentered');
  } else {
    rotH = 0; rotV = 0;
  }
}

// ── Controls overlay ─────────────────────────────────────────────────────────
const HIDE_MS = 3500;
function showControls() {
  if (!videoEl) return;
  ctrlOverlay.classList.add('show'); ctrlShown = true;
  clearTimeout(ctrlTimer);
  if (isPlaying) ctrlTimer = setTimeout(hideControls, HIDE_MS);
}
function hideControls() { ctrlOverlay.classList.remove('show'); ctrlShown = false; }
function toggleControls() { ctrlShown ? hideControls() : showControls(); }

// ── Playback ─────────────────────────────────────────────────────────────────
function togglePlay() {
  if (!videoEl) return;
  if (isPlaying) { videoEl.pause(); isPlaying = false; }
  else           { videoEl.play();  isPlaying = true;  }
  syncPlayBtn(); showControls();
}

function syncPlayBtn() {
  const icon = isPlaying ? '⏸' : '▶';
  playPauseBtn.textContent = icon; ppSmall.textContent = icon;
  clearTimeout(ctrlTimer);
  if (isPlaying) ctrlTimer = setTimeout(hideControls, HIDE_MS);
}

function seekBy(s) {
  if (!videoEl) return;
  videoEl.currentTime = clamp(videoEl.currentTime + s, 0, videoEl.duration || 0);
  showControls();
}

function updateProgress() {
  if (!videoEl?.duration) return;
  const p = (videoEl.currentTime / videoEl.duration) * 100;
  progressFill.style.width  = p + '%';
  progressDot.style.left    = p + '%';
  timeLabel.textContent     = fmt(videoEl.currentTime) + ' / ' + fmt(videoEl.duration);
}

// Progress scrub
let scrubbing = false;
function startScrub(e) {
  scrubbing = true; doScrub(e);
}
function doScrub(e) {
  if (!scrubbing || !videoEl?.duration) return;
  const rect = $('progress-wrap').getBoundingClientRect();
  const cx = e.touches ? e.touches[0].clientX : e.clientX;
  videoEl.currentTime = clamp(((cx - rect.left) / rect.width) * videoEl.duration, 0, videoEl.duration);
}
$('progress-wrap').addEventListener('mousedown', startScrub);
$('progress-wrap').addEventListener('touchstart', e => { scrubbing = true; doScrub(e); }, { passive: true });
window.addEventListener('mousemove', doScrub);
window.addEventListener('touchmove', e => doScrub(e), { passive: true });
window.addEventListener('mouseup', () => { scrubbing = false; });
window.addEventListener('touchend', () => { scrubbing = false; });

volSlider.addEventListener('input', () => { if (videoEl) videoEl.volume = parseFloat(volSlider.value); });

// ── VR mode ───────────────────────────────────────────────────────────────────
function toggleVR() {
  isVR = !isVR;
  vrLine.style.display = isVR ? 'block' : 'none';
  vrBtn.classList.toggle('active', isVR);
  if (isVR) {
    screen.orientation?.lock('landscape').catch(() => {});
    flash(distortOn ? 'VR + Cardboard mode' : 'VR mode — split screen');
  }
}

function toggleFullscreen() {
  if (!document.fullscreenElement) viewPlayer.requestFullscreen().catch(() => {});
  else document.exitFullscreen();
}

// ── Button bindings ───────────────────────────────────────────────────────────
$('back-btn').addEventListener('click', showLibrary);
$('err-back-btn').addEventListener('click', showLibrary);
$('play-pause-btn').addEventListener('click', togglePlay);
$('pp-small-btn').addEventListener('click', togglePlay);
$('seek-back-btn').addEventListener('click', () => seekBy(-10));
$('seek-fwd-btn').addEventListener('click', () => seekBy(10));
$('gyro-btn').addEventListener('click', toggleGyro);
$('recenter-btn').addEventListener('click', recenterView);
$('vr-btn').addEventListener('click', toggleVR);
$('fs-btn').addEventListener('click', toggleFullscreen);

// ── Keyboard shortcuts ────────────────────────────────────────────────────────
document.addEventListener('keydown', e => {
  if (!viewPlayer.classList.contains('active')) return;
  if (e.code === 'Space')        { e.preventDefault(); togglePlay(); }
  if (e.code === 'ArrowLeft')    seekBy(-10);
  if (e.code === 'ArrowRight')   seekBy(10);
  if (e.code === 'ArrowUp')      fovAdjust(-5);
  if (e.code === 'ArrowDown')    fovAdjust(5);
  if (e.code === 'KeyV')         toggleVR();
  if (e.code === 'KeyG')         toggleGyro();
  if (e.code === 'KeyR')         recenterView();
  if (e.code === 'Escape')       showLibrary();
});

function fovAdjust(d) {
  fov = clamp(fov + d, 30, 120);
  if (camera) { camera.fov = fov; camera.updateProjectionMatrix(); }
  fovSlider.value = fov; fovVal.textContent = Math.round(fov) + '°';
}

// ── Toast ─────────────────────────────────────────────────────────────────────
let toastT = null;
function flash(msg) {
  toast.textContent = msg; toast.classList.add('show');
  clearTimeout(toastT); toastT = setTimeout(() => toast.classList.remove('show'), 2400);
}

// ── Utils ─────────────────────────────────────────────────────────────────────
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function fmt(s) {
  if (!isFinite(s)) return '0:00';
  return `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,'0')}`;
}

// ── Tab bar ────────────────────────────────────────────────────────────────────
const viewBrowse = $('view-browse');

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  viewLib.classList.toggle('active', name === 'library');
  viewBrowse.classList.toggle('active', name === 'browse');
  if (name === 'browse') $('url-field').focus();
}

// ── Browse / iframe ─────────────────────────────────────────────────────────
const VIDEO_EXTS = /\.(mp4|webm|mkv|m4v|mov|avi|ogv|ts|m3u8|mpd)(\?.*)?$/i;

const frame          = $('browser-frame');
const urlField       = $('url-field');
const urlScheme      = $('url-scheme');
const navBack        = $('nav-back');
const navFwd         = $('nav-fwd');
const navRefresh     = $('nav-refresh');
const browseStart    = $('browse-start');
const playIntercept  = $('play-intercept');
const interceptLabel = $('intercept-label');
let pendingVideoUrl  = '';
let pendingVideoTitle = '';
// Simple history stack (iframe doesn't give us clean access to its history length)
let iframeHistory = [];
let historyPos = -1;

function navigateTo(url) {
  url = normaliseUrl(url);
  if (!url) return;
  browseStart.classList.add('hidden');
  frame.src = url;
  pushHistory(url);
  updateUrlBar(url);
}

function normaliseUrl(raw) {
  raw = raw.trim();
  if (!raw) return '';
  if (/^https?:\/\//i.test(raw)) return raw;
  return 'http://' + raw;
}

function pushHistory(url) {
  // Truncate forward history
  iframeHistory = iframeHistory.slice(0, historyPos + 1);
  iframeHistory.push(url);
  historyPos = iframeHistory.length - 1;
  updateNavBtns();
}

function updateNavBtns() {
  navBack.disabled = historyPos <= 0;
  navFwd.disabled  = historyPos >= iframeHistory.length - 1;
}

function updateUrlBar(url) {
  try {
    const u = new URL(url);
    urlScheme.textContent = u.protocol + '//';
    urlField.value = u.host + u.pathname + u.search;
  } catch {
    urlField.value = url;
    urlScheme.textContent = '';
  }
}

// Go button / Enter
function goToUrl() {
  const raw = (urlScheme.textContent + urlField.value).trim() || urlField.value.trim();
  navigateTo(raw);
}
$('go-btn').addEventListener('click', goToUrl);
urlField.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); goToUrl(); } });
urlField.addEventListener('focus', () => {
  // Show full URL in field on focus
  urlScheme.textContent = '';
});
urlField.addEventListener('blur', () => {
  // Restore scheme display
  try {
    const full = normaliseUrl(urlField.value);
    updateUrlBar(full);
  } catch {}
});

navBack.addEventListener('click', () => {
  if (historyPos > 0) {
    historyPos--;
    frame.src = iframeHistory[historyPos];
    updateUrlBar(iframeHistory[historyPos]);
    updateNavBtns();
  }
});

navFwd.addEventListener('click', () => {
  if (historyPos < iframeHistory.length - 1) {
    historyPos++;
    frame.src = iframeHistory[historyPos];
    updateUrlBar(iframeHistory[historyPos]);
    updateNavBtns();
  }
});

navRefresh.addEventListener('click', () => {
  try { frame.contentWindow.location.reload(); }
  catch { frame.src = frame.src; }
});

// iframe load handler
frame.addEventListener('load', () => {
  let currentUrl = '';
  try {
    currentUrl = frame.contentWindow.location.href;
    if (currentUrl === 'about:blank') return;
    updateUrlBar(currentUrl);
    // Only push if URL actually changed (not a reload)
    if (currentUrl !== iframeHistory[historyPos]) {
      pushHistory(currentUrl);
    }
  } catch {
    // Cross-origin: best-guess from frame.src
    currentUrl = frame.src;
  }

  hideIntercept();

  // Check if the current URL is itself a video
  if (VIDEO_EXTS.test(currentUrl)) {
    showIntercept(currentUrl, deriveTitle(currentUrl));
    return;
  }

  // Try to inject into same-origin page
  injectVideoLinks();
});

function injectVideoLinks() {
  try {
    const doc = frame.contentDocument;
    if (!doc || !doc.body) return;

    // Inject highlight styles
    if (!doc.getElementById('vr-injected')) {
      const s = doc.createElement('style');
      s.id = 'vr-injected';
      s.textContent = `
        a.__vr-video {
          outline: 2px solid #00d4ff !important;
          outline-offset: 2px;
          border-radius: 3px;
          position: relative;
        }
        a.__vr-video::after {
          content: ' ▶ VR';
          color: #00d4ff;
          font-size: .75em;
          font-weight: 700;
          letter-spacing: .04em;
        }
      `;
      doc.head.appendChild(s);
    }

    // Mark and intercept video links
    doc.querySelectorAll('a[href]').forEach(a => {
      const href = a.href;
      if (!href || a.__vrBound) return;
      if (VIDEO_EXTS.test(href)) {
        a.classList.add('__vr-video');
        a.__vrBound = true;
        a.addEventListener('click', e => {
          e.preventDefault();
          e.stopPropagation();
          showIntercept(href, a.textContent.trim() || deriveTitle(href));
        });
      }
    });
  } catch {
    // Cross-origin — silently skip injection
  }
}

function showIntercept(url, title) {
  pendingVideoUrl   = url;
  pendingVideoTitle = title || deriveTitle(url);
  interceptLabel.textContent = pendingVideoTitle;
  playIntercept.classList.add('show');
}

function hideIntercept() {
  playIntercept.classList.remove('show');
  pendingVideoUrl = '';
}

$('intercept-play-btn').addEventListener('click', () => {
  if (!pendingVideoUrl) return;
  const vid = {
    id: Date.now(),
    url: pendingVideoUrl,
    title: pendingVideoTitle,
    thumb: '',
    stereoMode: 'mono',
  };
  // Add to library if not already there
  if (!library.find(v => v.url === vid.url)) {
    library.unshift(vid); saveLib(); renderLibrary();
  }
  hideIntercept();
  openPlayer(vid);
});

$('intercept-dismiss').addEventListener('click', hideIntercept);

// ── Boot ──────────────────────────────────────────────────────────────────────
renderLibrary();
