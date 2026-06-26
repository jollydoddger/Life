// app.js — UI glue: media loading, look controls, transport, view modes.
import { Player } from './player.js';
import { VRButton } from 'three/addons/webxr/VRButton.js';
import { buildTestPattern } from './testpattern.js';
import { looksLikeFeed, loadFeed } from './feed.js';

const $ = (sel) => document.querySelector(sel);

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

$('#cardboard').addEventListener('click', () => {
  const on = player.mode !== 'cardboard';
  player.setMode(on ? 'cardboard' : 'flat');
  $('#cardboard').classList.toggle('active', on);
});

// Gyroscope look (phones). iOS needs an explicit permission gesture.
$('#gyro').addEventListener('click', async () => {
  if (player.orientation) {            // turn off
    window.removeEventListener('deviceorientation', onOrient);
    player.setOrientation(null);
    $('#gyro').classList.remove('active');
    return;
  }
  try {
    const DOE = window.DeviceOrientationEvent;
    if (DOE && typeof DOE.requestPermission === 'function') {
      const res = await DOE.requestPermission();
      if (res !== 'granted') return;
    }
    window.addEventListener('deviceorientation', onOrient);
    $('#gyro').classList.add('active');
  } catch (err) {
    console.warn('gyro unavailable', err);
  }
});
function onOrient(e) {
  const screenAngle = window.screen?.orientation?.angle ?? window.orientation ?? 0;
  player.setOrientation({ alpha: e.alpha, beta: e.beta, gamma: e.gamma, screen: screenAngle });
}

// ---------------------------------------------------------------- format toggles
document.querySelectorAll('.seg').forEach((seg) => {
  seg.addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn) return;
    seg.querySelectorAll('button').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    player.setFormat({ [seg.dataset.group]: btn.dataset.value });
  });
});

// Reflect a format chosen programmatically (e.g. from a scene feed) in the UI.
function reflectFormat({ projection, layout }) {
  const set = (group, value) => {
    const seg = document.querySelector(`.seg[data-group="${group}"]`);
    seg?.querySelectorAll('button').forEach((b) =>
      b.classList.toggle('active', b.dataset.value === value));
  };
  if (projection) set('projection', projection);
  if (layout) set('layout', layout);
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
// Swap the <video> source, optionally preserving playhead + play state (used by
// the quality switcher so changing resolution doesn't restart the clip).
function setVideoSource(src, { keepTime = false } = {}) {
  const t = keepTime ? video.currentTime : 0;
  const wasPlaying = keepTime ? !video.paused : true;
  video.src = src;
  video.addEventListener('loadedmetadata', () => {
    if (t) video.currentTime = t;
    if (wasPlaying) video.play().catch(() => {});
  }, { once: true });
}

function loadVideo(src) {
  player.setSource(video);
  video.muted = false; $('#mute').textContent = '🔊';
  setVideoSource(src);
  showPlayer();
}

// Apply a parsed scene feed: configure projection/stereo, list qualities, play.
const quality = $('#quality');
function applyScene(scene) {
  player.setFormat({ projection: scene.projection, layout: scene.layout });
  reflectFormat(scene);

  quality.innerHTML = '';
  scene.sources.forEach((s, i) => {
    const opt = document.createElement('option');
    opt.value = String(i); opt.textContent = s.label;
    quality.appendChild(opt);
  });
  quality.classList.toggle('hidden', scene.sources.length < 2);
  quality._scene = scene;

  player.setSource(video);
  video.muted = false; $('#mute').textContent = '🔊';
  setVideoSource(scene.sources[0].url);
  $('#hint').textContent = scene.title;
  $('#hint').classList.remove('gone');
  showPlayer();
}
quality.addEventListener('change', () => {
  const s = quality._scene?.sources[Number(quality.value)];
  if (s) setVideoSource(s.url, { keepTime: true });
});

async function loadFromInput(url) {
  if (looksLikeFeed(url)) {
    try {
      applyScene(await loadFeed(url));
      return;
    } catch (err) {
      console.warn('feed load failed, trying as direct video:', err);
      $('#hint').textContent = `Couldn't read scene feed (${err.message}) — loading as video`;
    }
  }
  loadVideo(url);
}

$('#loadUrl').addEventListener('click', () => {
  const url = $('#url').value.trim();
  if (url) loadFromInput(url);
});
$('#url').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#loadUrl').click(); });
$('#file').addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (f) loadVideo(URL.createObjectURL(f));
});

// Built-in equirect calibration / test pattern (no network needed).
$('#demo').addEventListener('click', () => {
  const c = buildTestPattern({ layout: player.layout, projection: player.projection });
  document.body.appendChild(c); c.style.display = 'none';
  player.setSource(c);
  showPlayer();
});
