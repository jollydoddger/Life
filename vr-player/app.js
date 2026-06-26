import * as THREE from 'three';

// ── Constants ──────────────────────────────────────────────────────────────
const STORAGE_KEY = 'vr-player-v1';
const CONTROLS_HIDE_MS = 3500;

// ── Library (localStorage) ─────────────────────────────────────────────────
function loadLibrary() {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY)) || []; }
  catch { return []; }
}

function saveLibrary(list) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
}

let library = loadLibrary();

// ── DOM refs ───────────────────────────────────────────────────────────────
const viewLibrary  = document.getElementById('view-library');
const viewPlayer   = document.getElementById('view-player');
const videoGrid    = document.getElementById('video-grid');
const emptyState   = document.getElementById('empty-state');
const addModal     = document.getElementById('add-modal');
const inputUrl     = document.getElementById('input-url');
const inputTitle   = document.getElementById('input-title');
const inputThumb   = document.getElementById('input-thumb');
const canvasWrap   = document.getElementById('player-canvas-wrap');
const vrLine       = document.getElementById('vr-line');
const buffering    = document.getElementById('buffering');
const playerError  = document.getElementById('player-error');
const ctrlOverlay  = document.getElementById('player-controls');
const ctrlTitle    = document.getElementById('ctrl-title');
const playPauseBtn = document.getElementById('play-pause-btn');
const ppSmall      = document.getElementById('pp-small-btn');
const progressFill = document.getElementById('progress-fill');
const progressDot  = document.getElementById('progress-dot');
const timeLabel    = document.getElementById('time-label');
const volSlider    = document.getElementById('vol-slider');
const gyroBtn      = document.getElementById('gyro-btn');
const vrBtn        = document.getElementById('vr-btn');
const gyroToast    = document.getElementById('gyro-toast');

// ── Player state ───────────────────────────────────────────────────────────
let scene, camera, renderer, sphere, videoTex;
let videoEl = null;
let isVR = false;
let gyroOn = false;
let hasGyro = false;
let isPlaying = false;
let controlsTimer = null;
let controlsShown = false;
let animFrameId = null;

// Rotation from touch/mouse drag
let dragActive = false;
let dragX = 0, dragY = 0;
let rotH = 0, rotV = 0;   // yaw, pitch (radians)

// Gyro quaternion helpers
const zee    = new THREE.Vector3(0, 0, 1);
const gEuler = new THREE.Euler();
const gQ0    = new THREE.Quaternion();
const gQ1    = new THREE.Quaternion(-Math.sqrt(0.5), 0, 0, Math.sqrt(0.5));
const gQuat  = new THREE.Quaternion();

// Manual quaternion
const mEuler = new THREE.Euler(0, 0, 0, 'YXZ');
const mQuat  = new THREE.Quaternion();

// ── Library UI ─────────────────────────────────────────────────────────────
function renderLibrary() {
  videoGrid.innerHTML = '';
  const empty = library.length === 0;
  emptyState.classList.toggle('show', empty);
  videoGrid.style.display = empty ? 'none' : 'grid';

  library.forEach((vid, i) => {
    const card = document.createElement('div');
    card.className = 'video-card';
    card.innerHTML = `
      <div class="card-thumb">
        ${vid.thumb ? `<img src="${escHtml(vid.thumb)}" loading="lazy" onerror="this.style.display='none'">` : '🎬'}
        <div class="card-play"><div class="card-play-icon">▶</div></div>
      </div>
      <div class="card-info">
        <div class="card-title">${escHtml(vid.title || 'Untitled')}</div>
        <div class="card-meta">360° · tap to play</div>
      </div>
      <button class="card-delete" data-i="${i}" title="Remove">✕</button>
    `;
    card.addEventListener('click', (e) => {
      if (e.target.closest('.card-delete')) return;
      openPlayer(vid);
    });
    card.querySelector('.card-delete').addEventListener('click', (e) => {
      e.stopPropagation();
      library.splice(i, 1);
      saveLibrary(library);
      renderLibrary();
    });
    videoGrid.appendChild(card);
  });
}

function escHtml(s) {
  return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// Add modal
document.getElementById('add-btn').addEventListener('click', openModal);
document.getElementById('empty-add-btn').addEventListener('click', openModal);
document.getElementById('modal-cancel').addEventListener('click', closeModal);
document.getElementById('modal-add').addEventListener('click', addAndPlay);
addModal.addEventListener('click', (e) => { if (e.target === addModal) closeModal(); });

function openModal() {
  inputUrl.value = '';
  inputTitle.value = '';
  inputThumb.value = '';
  addModal.classList.add('open');
  setTimeout(() => inputUrl.focus(), 300);
}

function closeModal() {
  addModal.classList.remove('open');
}

function addAndPlay() {
  const url = inputUrl.value.trim();
  if (!url) { inputUrl.focus(); return; }
  const vid = {
    id: Date.now(),
    url,
    title: inputTitle.value.trim() || deriveTitle(url),
    thumb: inputThumb.value.trim(),
  };
  library.unshift(vid);
  saveLibrary(library);
  renderLibrary();
  closeModal();
  openPlayer(vid);
}

function deriveTitle(url) {
  try {
    const name = new URL(url).pathname.split('/').pop().replace(/\.[^.]+$/, '');
    return decodeURIComponent(name) || 'Video';
  } catch { return 'Video'; }
}

// ── View switching ─────────────────────────────────────────────────────────
function showLibrary() {
  stopPlayer();
  viewPlayer.classList.remove('active');
  viewLibrary.classList.add('active');
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

function showPlayer() {
  viewLibrary.classList.remove('active');
  viewPlayer.classList.add('active');
}

// ── Three.js init ──────────────────────────────────────────────────────────
function initThree() {
  if (renderer) return;

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(75, 1, 0.1, 1000);

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  canvasWrap.appendChild(renderer.domElement);

  resizeRenderer();
  window.addEventListener('resize', resizeRenderer);

  setupDragControls();
}

function resizeRenderer() {
  if (!renderer) return;
  const w = canvasWrap.clientWidth;
  const h = canvasWrap.clientHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}

// ── Video + sphere ─────────────────────────────────────────────────────────
function openPlayer(vid) {
  showPlayer();
  buffering.classList.add('show');
  playerError.classList.remove('show');
  ctrlOverlay.classList.remove('show');
  controlsShown = false;

  ctrlTitle.textContent = vid.title || 'Video';

  if (videoEl) {
    videoEl.pause();
    videoEl.removeAttribute('src');
    videoEl.load();
  }

  videoEl = document.createElement('video');
  videoEl.crossOrigin = 'anonymous';
  videoEl.playsInline = true;
  videoEl.loop = true;
  videoEl.preload = 'auto';
  videoEl.src = vid.url;

  videoEl.addEventListener('canplay', onCanPlay);
  videoEl.addEventListener('waiting', () => buffering.classList.add('show'));
  videoEl.addEventListener('playing', () => buffering.classList.remove('show'));
  videoEl.addEventListener('error', onVideoError);
  videoEl.addEventListener('timeupdate', updateProgress);
  videoEl.addEventListener('ended', () => { isPlaying = false; syncPlayBtn(); });

  videoEl.load();

  initThree();
  startRenderLoop();
  scheduleShowControls();
}

function onCanPlay() {
  if (sphere) {
    scene.remove(sphere);
    sphere.geometry.dispose();
    sphere.material.map?.dispose();
    sphere.material.dispose();
  }

  videoTex = new THREE.VideoTexture(videoEl);
  videoTex.colorSpace = THREE.SRGBColorSpace;
  videoTex.minFilter = THREE.LinearFilter;
  videoTex.magFilter = THREE.LinearFilter;

  const geo = new THREE.SphereGeometry(500, 64, 32);
  geo.scale(-1, 1, 1);
  const mat = new THREE.MeshBasicMaterial({ map: videoTex });
  sphere = new THREE.Mesh(geo, mat);
  scene.add(sphere);

  buffering.classList.remove('show');

  videoEl.play().then(() => {
    isPlaying = true;
    syncPlayBtn();
    showControls();
  }).catch(() => {
    isPlaying = false;
    syncPlayBtn();
    showControls();
  });
}

function onVideoError() {
  buffering.classList.remove('show');
  playerError.classList.add('show');
}

function stopPlayer() {
  if (animFrameId) { cancelAnimationFrame(animFrameId); animFrameId = null; }
  if (videoEl) { videoEl.pause(); videoEl.src = ''; videoEl = null; }
  if (sphere) { scene?.remove(sphere); sphere = null; }
  isPlaying = false;
  isVR = false;
  vrLine.style.display = 'none';
  vrBtn.classList.remove('active');
  rotH = 0; rotV = 0;
  disableGyro();
}

// ── Render loop ────────────────────────────────────────────────────────────
function startRenderLoop() {
  if (animFrameId) cancelAnimationFrame(animFrameId);

  function loop() {
    animFrameId = requestAnimationFrame(loop);

    if (videoTex && videoEl && videoEl.readyState >= 2) {
      videoTex.needsUpdate = true;
    }

    // Camera orientation
    if (gyroOn && hasGyro) {
      camera.quaternion.slerp(gQuat, 0.18);
    } else {
      mEuler.set(rotV, rotH, 0, 'YXZ');
      mQuat.setFromEuler(mEuler);
      camera.quaternion.slerp(mQuat, 0.12);
    }

    if (isVR) {
      renderStereo();
    } else {
      const w = renderer.domElement.width;
      const h = renderer.domElement.height;
      renderer.setViewport(0, 0, w, h);
      renderer.setScissorTest(false);
      renderer.render(scene, camera);
    }
  }

  loop();
}

function renderStereo() {
  const w = renderer.domElement.width;
  const h = renderer.domElement.height;
  const hw = Math.floor(w / 2);

  const origAspect = camera.aspect;
  camera.aspect = hw / h;
  camera.updateProjectionMatrix();

  renderer.setScissorTest(true);

  renderer.setViewport(0, 0, hw, h);
  renderer.setScissor(0, 0, hw, h);
  renderer.render(scene, camera);

  renderer.setViewport(hw, 0, hw, h);
  renderer.setScissor(hw, 0, hw, h);
  renderer.render(scene, camera);

  renderer.setScissorTest(false);

  camera.aspect = origAspect;
  camera.updateProjectionMatrix();
}

// ── Drag controls ──────────────────────────────────────────────────────────
function setupDragControls() {
  const el = renderer.domElement;

  // Touch
  el.addEventListener('touchstart', (e) => {
    if (e.touches.length === 1) {
      dragActive = true;
      dragX = e.touches[0].clientX;
      dragY = e.touches[0].clientY;
      scheduleShowControls();
    }
  }, { passive: true });

  el.addEventListener('touchmove', (e) => {
    if (!dragActive || e.touches.length !== 1 || gyroOn) return;
    const dx = e.touches[0].clientX - dragX;
    const dy = e.touches[0].clientY - dragY;
    rotH -= dx * 0.006;
    rotV  = clamp(rotV - dy * 0.004, -Math.PI / 2, Math.PI / 2);
    dragX = e.touches[0].clientX;
    dragY = e.touches[0].clientY;
  }, { passive: true });

  el.addEventListener('touchend', () => { dragActive = false; });

  // Mouse
  el.addEventListener('mousedown', (e) => {
    dragActive = true;
    dragX = e.clientX;
    dragY = e.clientY;
    scheduleShowControls();
  });

  el.addEventListener('mousemove', (e) => {
    if (!dragActive) return;
    const dx = e.clientX - dragX;
    const dy = e.clientY - dragY;
    rotH -= dx * 0.005;
    rotV  = clamp(rotV - dy * 0.003, -Math.PI / 2, Math.PI / 2);
    dragX = e.clientX;
    dragY = e.clientY;
  });

  window.addEventListener('mouseup', () => { dragActive = false; });
}

// ── Gyroscope ──────────────────────────────────────────────────────────────
function handleDeviceOrientation(e) {
  if (!e.alpha && !e.beta && !e.gamma) return;
  hasGyro = true;

  const a = THREE.MathUtils.degToRad(e.alpha ?? 0);
  const b = THREE.MathUtils.degToRad(e.beta  ?? 0);
  const g = THREE.MathUtils.degToRad(e.gamma ?? 0);
  const orient = THREE.MathUtils.degToRad(screen.orientation?.angle ?? window.orientation ?? 0);

  gEuler.set(b, a, -g, 'YXZ');
  gQuat.setFromEuler(gEuler);
  gQuat.multiply(gQ1);
  gQ0.setFromAxisAngle(zee, -orient);
  gQuat.multiply(gQ0);
}

async function toggleGyro() {
  if (gyroOn) { disableGyro(); return; }

  // iOS 13+ needs explicit permission
  if (typeof DeviceOrientationEvent !== 'undefined' &&
      typeof DeviceOrientationEvent.requestPermission === 'function') {
    try {
      const perm = await DeviceOrientationEvent.requestPermission();
      if (perm !== 'granted') return;
    } catch { return; }
  }

  window.addEventListener('deviceorientation', handleDeviceOrientation);
  gyroOn = true;
  gyroBtn.classList.add('active');
  gyroBtn.textContent = '◎';

  flashToast('Move your phone to look around');
}

function disableGyro() {
  window.removeEventListener('deviceorientation', handleDeviceOrientation);
  gyroOn = false;
  hasGyro = false;
  gyroBtn.classList.remove('active');
  gyroBtn.textContent = '◉';
}

// ── Controls overlay ────────────────────────────────────────────────────────
function showControls() {
  ctrlOverlay.classList.add('show');
  controlsShown = true;
  clearTimeout(controlsTimer);
  if (isPlaying) {
    controlsTimer = setTimeout(hideControls, CONTROLS_HIDE_MS);
  }
}

function hideControls() {
  ctrlOverlay.classList.remove('show');
  controlsShown = false;
}

function scheduleShowControls() {
  showControls();
}

// Click on canvas area toggles controls
canvasWrap.addEventListener('click', () => {
  if (controlsShown) hideControls(); else showControls();
});

// ── Playback controls ───────────────────────────────────────────────────────
function togglePlay() {
  if (!videoEl) return;
  if (isPlaying) {
    videoEl.pause(); isPlaying = false;
  } else {
    videoEl.play(); isPlaying = true;
  }
  syncPlayBtn();
  showControls();
}

function syncPlayBtn() {
  const icon = isPlaying ? '⏸' : '▶';
  playPauseBtn.textContent = icon;
  ppSmall.textContent = icon;
  if (isPlaying) {
    controlsTimer = setTimeout(hideControls, CONTROLS_HIDE_MS);
  } else {
    clearTimeout(controlsTimer);
  }
}

function seekBy(sec) {
  if (!videoEl) return;
  videoEl.currentTime = clamp(videoEl.currentTime + sec, 0, videoEl.duration || 0);
  showControls();
}

function updateProgress() {
  if (!videoEl?.duration) return;
  const pct = (videoEl.currentTime / videoEl.duration) * 100;
  progressFill.style.width = pct + '%';
  progressDot.style.left = pct + '%';
  timeLabel.textContent = fmtTime(videoEl.currentTime) + ' / ' + fmtTime(videoEl.duration);
}

// Progress seek
document.getElementById('progress-wrap').addEventListener('click', (e) => {
  if (!videoEl?.duration) return;
  const rect = e.currentTarget.getBoundingClientRect();
  const ratio = (e.clientX - rect.left) / rect.width;
  videoEl.currentTime = clamp(ratio * videoEl.duration, 0, videoEl.duration);
});

volSlider.addEventListener('input', () => {
  if (videoEl) videoEl.volume = parseFloat(volSlider.value);
});

// ── VR mode ─────────────────────────────────────────────────────────────────
function toggleVR() {
  isVR = !isVR;
  vrLine.style.display = isVR ? 'block' : 'none';
  vrBtn.classList.toggle('active', isVR);
  if (isVR) {
    screen.orientation?.lock('landscape').catch(() => {});
    flashToast('VR mode — insert into Cardboard');
  }
}

// ── Fullscreen ───────────────────────────────────────────────────────────────
function toggleFullscreen() {
  if (!document.fullscreenElement) {
    viewPlayer.requestFullscreen().catch(() => {});
  } else {
    document.exitFullscreen();
  }
}

// ── Bind player buttons ──────────────────────────────────────────────────────
document.getElementById('back-btn').addEventListener('click', showLibrary);
document.getElementById('err-back-btn').addEventListener('click', showLibrary);
playPauseBtn.addEventListener('click', togglePlay);
ppSmall.addEventListener('click', togglePlay);
document.getElementById('seek-back-btn').addEventListener('click', () => seekBy(-10));
document.getElementById('seek-fwd-btn').addEventListener('click', () => seekBy(10));
gyroBtn.addEventListener('click', toggleGyro);
vrBtn.addEventListener('click', toggleVR);
document.getElementById('fs-btn').addEventListener('click', toggleFullscreen);

// ── Toast helper ─────────────────────────────────────────────────────────────
let toastTimer = null;
function flashToast(msg) {
  gyroToast.textContent = msg;
  gyroToast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => gyroToast.classList.remove('show'), 2500);
}

// ── Utils ─────────────────────────────────────────────────────────────────────
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function fmtTime(s) {
  if (!isFinite(s)) return '0:00';
  const m = Math.floor(s / 60);
  const ss = Math.floor(s % 60);
  return `${m}:${String(ss).padStart(2, '0')}`;
}

// ── Keyboard shortcuts ────────────────────────────────────────────────────────
document.addEventListener('keydown', (e) => {
  if (!viewPlayer.classList.contains('active')) return;
  if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
  if (e.code === 'ArrowLeft') seekBy(-10);
  if (e.code === 'ArrowRight') seekBy(10);
  if (e.code === 'KeyV') toggleVR();
  if (e.code === 'KeyG') toggleGyro();
  if (e.code === 'Escape') showLibrary();
});

// ── Boot ───────────────────────────────────────────────────────────────────────
renderLibrary();
