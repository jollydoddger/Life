// app.js — UI glue: media loading, look controls, transport, view modes.
import { Player } from './player.js';
import { VRButton } from 'three/addons/webxr/VRButton.js';
import { buildTestPattern } from './testpattern.js';
import { looksLikeFeed, loadFeed } from './feed.js';
import { attachMedia, isHlsUrl } from './media.js';
import * as library from './library.js';

const $ = (sel) => document.querySelector(sel);

// Register the service worker so the app installs to the home screen and runs
// offline. Scope is this directory (works under any GitHub Pages sub-path).
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
}

const canvas = $('#stage');
const video = $('#media');
const player = new Player(canvas, video);

// expose for the automated test harness / console tinkering
window.__player = player;

// ---------------------------------------------------------------- look control
let dragging = false, lastX = 0, lastY = 0;
canvas.addEventListener('pointerdown', (e) => {
  if (player.orientation) return;          // gyro owns the view
  dragging = true; lastX = e.clientX; lastY = e.clientY;
  canvas.setPointerCapture(e.pointerId);
});
canvas.addEventListener('pointermove', (e) => {
  if (!dragging) return;
  player.look((e.clientX - lastX) * -0.12, (e.clientY - lastY) * 0.12);
  lastX = e.clientX; lastY = e.clientY;
});
const endDrag = () => { dragging = false; };
canvas.addEventListener('pointerup', endDrag);
canvas.addEventListener('pointercancel', endDrag);

// ---------------------------------------------------------------- view modes
// WebXR button (real headset). Hidden gracefully if unsupported.
$('#vrslot').appendChild(VRButton.createButton(player.renderer));

// Gyroscope look (phones). iOS needs an explicit permission gesture, which a
// button tap provides. Returns whether motion tracking is now active.
async function enableGyro() {
  if (player.orientation) return true;
  try {
    const DOE = window.DeviceOrientationEvent;
    if (DOE && typeof DOE.requestPermission === 'function') {
      if (await DOE.requestPermission() !== 'granted') return false;
    }
  } catch (err) { console.warn('gyro permission failed', err); return false; }
  window.addEventListener('deviceorientation', onOrient);
  $('#gyro').classList.add('active');
  return true;
}
function disableGyro() {
  window.removeEventListener('deviceorientation', onOrient);
  player.setOrientation(null);
  $('#gyro').classList.remove('active');
}
function onOrient(e) {
  const screenAngle = window.screen?.orientation?.angle ?? window.orientation ?? 0;
  player.setOrientation({ alpha: e.alpha, beta: e.beta, gamma: e.gamma, screen: screenAngle });
}
$('#gyro').addEventListener('click', () => {
  player.orientation ? disableGyro() : enableGyro();
});

// Cardboard = the phone-in-headset flow: one tap enters split-screen stereo,
// turns on head tracking, and goes fullscreen so it fills the lenses.
$('#cardboard').addEventListener('click', async () => {
  const on = player.mode !== 'cardboard';
  player.setMode(on ? 'cardboard' : 'flat');
  $('#cardboard').classList.toggle('active', on);
  if (on) {
    await enableGyro();
    try { await document.documentElement.requestFullscreen?.(); } catch { /* denied */ }
    screen.orientation?.lock?.('landscape').catch(() => {});
  } else {
    disableGyro();
    if (document.fullscreenElement) document.exitFullscreen?.();
  }
});

// ---------------------------------------------------------------- format toggles
// layout is a segmented control; projection is a dropdown (it has fisheye FOVs).
document.querySelectorAll('.seg').forEach((seg) => {
  seg.addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn) return;
    seg.querySelectorAll('button').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    player.setFormat({ [seg.dataset.group]: btn.dataset.value });
  });
});

// Map the projection dropdown's value <-> {projection, fov}.
const PROJ = {
  '360': { projection: '360' }, '180': { projection: '180' },
  fish180: { projection: 'fisheye', fov: 180 }, fish190: { projection: 'fisheye', fov: 190 },
  fish200: { projection: 'fisheye', fov: 200 }, fish220: { projection: 'fisheye', fov: 220 },
};
const projKey = (projection, fov) => (projection === 'fisheye' ? 'fish' + (fov || 180) : projection);
$('#projection').addEventListener('change', (e) => player.setFormat(PROJ[e.target.value]));

// Reflect a format chosen programmatically (e.g. from a scene feed) in the UI.
function reflectFormat({ projection, layout, fov }) {
  if (projection) $('#projection').value = projKey(projection, fov);
  if (layout) {
    document.querySelector('.seg[data-group="layout"]')?.querySelectorAll('button')
      .forEach((b) => b.classList.toggle('active', b.dataset.value === layout));
  }
}

// ---------------------------------------------------------------- transport
const seek = $('#seek');
const timeEl = $('#time');
const fmt = (s) => {
  if (!isFinite(s)) return '0:00';
  const m = Math.floor(s / 60), ss = String(Math.floor(s % 60)).padStart(2, '0');
  return `${m}:${ss}`;
};
$('#playPause').addEventListener('click', () => {
  if (video.paused) video.play(); else video.pause();
});
video.addEventListener('play', () => ($('#playPause').textContent = '❚❚'));
video.addEventListener('pause', () => ($('#playPause').textContent = '▶'));
video.addEventListener('timeupdate', () => {
  if (video.duration) seek.value = String((video.currentTime / video.duration) * 1000);
  timeEl.textContent = `${fmt(video.currentTime)} / ${fmt(video.duration)}`;
});
seek.addEventListener('input', () => {
  if (video.duration) video.currentTime = (seek.value / 1000) * video.duration;
});
$('#mute').addEventListener('click', () => {
  video.muted = !video.muted;
  $('#mute').textContent = video.muted ? '🔇' : '🔊';
});

// ---------------------------------------------------------------- media loading
function showPlayer() {
  $('#loader').classList.add('hidden');
  $('#ui').classList.remove('hidden');
  setTimeout(() => $('#hint').classList.add('gone'), 2500);
}
const quality = $('#quality');
let controller = null;        // current media controller (from attachMedia)

function fillQuality(options, mode) {
  quality.innerHTML = '';
  options.forEach((o, i) => {
    const opt = document.createElement('option');
    opt.value = String(o.value ?? i);
    opt.textContent = o.label;
    quality.appendChild(opt);
  });
  quality._mode = mode;
  quality.classList.toggle('hidden', options.length < 2);
}

// Attach a source URL (HLS or progressive) to the <video>, optionally keeping
// the current playhead/play-state (used when swapping progressive qualities).
function playUrl(url, { keepTime = false, onLevels } = {}) {
  const t = keepTime ? video.currentTime : 0;
  const wasPlaying = keepTime ? !video.paused : true;
  controller = attachMedia(video, url, { onLevels });
  video.addEventListener('loadedmetadata', () => {
    if (t) video.currentTime = t;
    if (wasPlaying) video.play().catch(() => {});
  }, { once: true });
}

// The thing currently on screen, in a form the library can persist.
let currentItem = null;
function setCurrent(item) { currentItem = item; updateSaveState(); }
function titleFromUrl(url) {
  try { return decodeURIComponent(url.split('/').pop().split('?')[0]) || url; }
  catch { return url; }
}

function loadVideo(src) {
  player.setSource(video);
  video.muted = false; $('#mute').textContent = '🔊';
  quality.classList.add('hidden');
  // A bare HLS URL can still expose adaptive qualities.
  playUrl(src, { onLevels: (levels) => fillQuality(levels.map((l) => ({ value: l.index, label: l.label })), 'hls') });
  setCurrent(src.startsWith('blob:') ? null : {
    title: titleFromUrl(src), url: src, thumbnail: '', kind: 'video',
    projection: player.projection, layout: player.layout, fov: player.fov,
  });
  showPlayer();
}

// Apply a parsed scene feed: configure projection/stereo, list qualities, play.
function applyScene(scene, feedUrl) {
  player.setFormat({ projection: scene.projection, layout: scene.layout, fov: scene.fov });
  reflectFormat(scene);
  player.setSource(video);
  video.muted = false; $('#mute').textContent = '🔊';

  const first = scene.sources[0];
  if (isHlsUrl(first.url)) {
    // Adaptive HLS scene: quality comes from the manifest's levels.
    quality.classList.add('hidden');
    playUrl(first.url, { onLevels: (levels) => fillQuality(levels.map((l) => ({ value: l.index, label: l.label })), 'hls') });
  } else {
    // Progressive scene: quality is the list of distinct source files.
    quality._scene = scene;
    fillQuality(scene.sources.map((s, i) => ({ value: i, label: s.label })), 'mp4');
    playUrl(first.url);
  }
  $('#hint').textContent = scene.title;
  $('#hint').classList.remove('gone');
  setCurrent(feedUrl ? {
    title: scene.title, url: feedUrl, thumbnail: scene.thumbnail, kind: 'feed',
    projection: scene.projection, layout: scene.layout, fov: scene.fov,
  } : null);
  showPlayer();
}

quality.addEventListener('change', () => {
  if (quality._mode === 'hls') {
    controller?.setLevel(Number(quality.value)); // seamless ABR level switch
  } else {
    const s = quality._scene?.sources[Number(quality.value)];
    if (s) playUrl(s.url, { keepTime: true });
  }
});

async function loadFromInput(url) {
  if (looksLikeFeed(url)) {
    try {
      applyScene(await loadFeed(url), url);
      return;
    } catch (err) {
      console.warn('feed load failed, trying as direct video:', err);
      $('#hint').textContent = `Couldn't read scene feed (${err.message}) — loading as video`;
    }
  }
  loadVideo(url);
}

// Guess projection/stereo from a filename or URL (SLR downloads are named like
// "Title_..._MKX200_SBS.mp4"), so opening a downloaded scene auto-configures.
function guessFormatFromText(text) {
  const s = (text || '').toLowerCase();
  const g = {};
  if (/mkx220|vrca220|[_\- ]220/.test(s)) { g.projection = 'fisheye'; g.fov = 220; }
  else if (/mkx200|[_\- ]200/.test(s)) { g.projection = 'fisheye'; g.fov = 200; }
  else if (/rf52|fisheye190|[_\- ]190/.test(s)) { g.projection = 'fisheye'; g.fov = 190; }
  else if (/fisheye/.test(s)) { g.projection = 'fisheye'; g.fov = 180; }
  else if (/(^|[^\d])180([^\d]|$)/.test(s)) g.projection = '180';
  else if (/(^|[^\d])360([^\d]|$)/.test(s)) g.projection = '360';
  if (/(^|[_\-. ])(sbs|lr)([_\-. ]|$)/.test(s) || /side.?by.?side/.test(s)) g.layout = 'sbs';
  else if (/(^|[_\-. ])(tb|ou)([_\-. ]|$)/.test(s) || /over.?under|top.?bottom/.test(s)) g.layout = 'ou';
  return g;
}
function applyGuess(text) {
  const g = guessFormatFromText(text);
  if (g.projection || g.layout) { player.setFormat(g); reflectFormat({ projection: g.projection, layout: g.layout, fov: g.fov }); }
}

$('#loadUrl').addEventListener('click', () => {
  const url = $('#url').value.trim();
  if (!url) return;
  applyGuess(url);            // feeds override this with exact metadata; direct videos keep it
  loadFromInput(url);
});
$('#url').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#loadUrl').click(); });
$('#file').addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (f) { applyGuess(f.name); loadVideo(URL.createObjectURL(f)); }
});

// Built-in equirect calibration / test pattern (no network needed).
$('#demo').addEventListener('click', () => {
  const c = buildTestPattern({ layout: player.layout, projection: player.projection });
  document.body.appendChild(c); c.style.display = 'none';
  player.setSource(c);
  setCurrent(null);
  showPlayer();
});

// ---------------------------------------------------------------- library
const saveBtn = $('#save');
function updateSaveState() {
  saveBtn.disabled = !currentItem;
  saveBtn.classList.toggle('active', !!currentItem && library.has(currentItem.url));
}
saveBtn.addEventListener('click', () => {
  if (!currentItem) return;
  if (library.has(currentItem.url)) library.remove(library.load().find((i) => i.url === currentItem.url)?.id);
  else library.add(currentItem);
  updateSaveState();
  renderLibrary();
});

function renderLibrary() {
  const items = library.load();
  const tiles = $('#tiles');
  tiles.innerHTML = '';
  $('#library').classList.toggle('hidden', items.length === 0);
  for (const item of items) {
    const tile = document.createElement('div');
    tile.className = 'tile';
    tile.title = item.title;
    const initial = (item.title || '?').trim().charAt(0).toUpperCase();
    tile.innerHTML = `
      ${item.thumbnail
        ? `<img class="tile-thumb" src="${item.thumbnail}" loading="lazy"
             onerror="this.remove()" alt="" />`
        : ''}
      <div class="tile-fallback">${initial}</div>
      <span class="tile-badge">${item.kind === 'feed' ? 'SCENE' : 'VIDEO'}</span>
      <button class="tile-remove" title="Remove">✕</button>
      <div class="tile-title">${item.title}</div>`;
    tile.addEventListener('click', (e) => {
      if (e.target.closest('.tile-remove')) {
        library.remove(item.id); renderLibrary(); updateSaveState();
        return;
      }
      if (item.kind === 'video' && (item.projection || item.layout)) {
        player.setFormat({ projection: item.projection, layout: item.layout, fov: item.fov });
        reflectFormat(item);
      }
      loadFromInput(item.url);
    });
    tiles.appendChild(tile);
  }
}

renderLibrary();
updateSaveState();
