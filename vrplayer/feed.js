// feed.js — parse a DeoVR / SLR-style "deeplink" scene JSON into a normalized
// descriptor the player understands.
//
// The deeplink format (used by SLR, DeoVR, and compatible sites) looks like:
//   {
//     "title": "...", "videoLength": 1234,
//     "is3d": true, "screenType": "dome", "stereoMode": "sbs",
//     "videoThumbnail": "https://.../thumb.jpg",
//     "encodings": [
//       { "name": "h264", "videoSources": [
//           { "resolution": 1920, "url": "https://.../1080.mp4" },
//           { "resolution": 2880, "url": "https://.../2880.mp4" } ] },
//       { "name": "h265", "videoSources": [ ... ] }
//     ]
//   }
//
// We map screenType -> projection, stereoMode -> stereo layout, and flatten the
// encodings into a quality list (browser-playable codecs first).

const DOME_HINTS = ['dome', '180', 'mkx', 'rf52', 'fisheye', 'vrca', '220', '190', '200'];

function pickProjection(screenType) {
  const s = String(screenType || '').toLowerCase();
  if (s.includes('sphere') || s === '360') return '360';
  if (DOME_HINTS.some((h) => s.includes(h))) return '180';
  // "flat" and unknowns fall back to 360 (the player has no flat-plane mode yet)
  return '360';
}

function pickLayout(stereoMode, is3d) {
  const m = String(stereoMode || '').toLowerCase();
  if (is3d === false || m === 'off' || m === 'mono' || m === '') return 'mono';
  if (m === 'sbs' || m === 'lr') return 'sbs';
  if (m === 'tb' || m === 'ou' || m === 'topbottom') return 'ou';
  return is3d ? 'sbs' : 'mono';
}

// Lower rank = preferred. Browsers reliably decode H.264/AVC in MP4; HEVC/H.265
// is hit-or-miss, so it sorts after.
function codecRank(name) {
  const n = String(name || '').toLowerCase();
  if (n.includes('264') || n.includes('avc')) return 0;
  if (n.includes('vp9') || n.includes('webm')) return 1;
  if (n.includes('265') || n.includes('hevc')) return 3;
  return 2;
}

function label(height, codecName) {
  const h = height ? `${height}p` : 'source';
  const c = codecName && codecRank(codecName) !== 0 ? ` ${String(codecName).toUpperCase()}` : '';
  return h + c;
}

export function parseDeoVR(data) {
  if (!data || typeof data !== 'object') throw new Error('not a deeplink object');

  const is3d = data.is3d;
  const projection = pickProjection(data.screenType);
  const layout = pickLayout(data.stereoMode, is3d);

  const sources = [];
  const push = (url, height, codec) => {
    if (url) sources.push({ url, height: Number(height) || 0, codec: codec || '', label: label(Number(height) || 0, codec) });
  };

  if (Array.isArray(data.encodings)) {
    for (const enc of data.encodings) {
      for (const s of enc.videoSources || []) push(s.url, s.resolution ?? s.height, enc.name);
    }
  }
  // Older / flatter variants
  if (!sources.length && Array.isArray(data.videoSources)) {
    for (const s of data.videoSources) push(s.url, s.resolution ?? s.height, s.codec);
  }

  sources.sort((a, b) => codecRank(a.codec) - codecRank(b.codec) || b.height - a.height);

  if (!sources.length) throw new Error('deeplink has no playable video sources');

  return {
    title: data.title || data.name || 'Untitled scene',
    thumbnail: data.videoThumbnail || data.thumbnailUrl || data.thumbnail || '',
    duration: Number(data.videoLength || data.duration || 0),
    projection,
    layout,
    sources,
  };
}

// Looks like a deeplink JSON URL? (used to decide fetch-as-feed vs direct video)
export function looksLikeFeed(url) {
  return /\.json(\?|#|$)/i.test(url) || /deovr|deeplink|trailer|scene.*\.json/i.test(url);
}

export async function loadFeed(url) {
  const res = await fetch(url, { credentials: 'omit' });
  if (!res.ok) throw new Error(`feed fetch failed: ${res.status}`);
  return parseDeoVR(await res.json());
}
