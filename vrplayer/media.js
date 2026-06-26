// media.js — attach a video URL to a <video> element, transparently handling
// HLS (.m3u8) via hls.js, native HLS (Safari), and progressive files.
//
// Returns a small controller so the UI can list adaptive quality levels and
// switch between them (and tear the previous stream down before loading a new
// one). For progressive sources `levels` is empty and quality is handled by the
// caller swapping the URL.

import Hls from 'hls';

export function isHlsUrl(url) {
  return /\.m3u8(\?|#|$)/i.test(url || '');
}

export function hlsSupported() {
  return Hls.isSupported();
}

// Attach `url` to `video`. `onLevels(levels)` is called once the HLS manifest is
// parsed, with `[{ index, label }]` (index -1 means "Auto").
export function attachMedia(video, url, { onLevels } = {}) {
  // Tear down a previous hls.js instance if one is bound to this element.
  if (video._hls) { video._hls.destroy(); video._hls = null; }
  video.removeAttribute('src');

  if (isHlsUrl(url)) {
    if (Hls.isSupported()) {
      const hls = new Hls({ capLevelToPlayerSize: true, enableWorker: true });
      video._hls = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        const levels = [{ index: -1, label: 'Auto' }].concat(
          hls.levels
            .map((l, i) => ({
              index: i,
              h: l.height || 0,
              label: l.height ? `${l.height}p` : `${Math.round((l.bitrate || 0) / 1000)}k`,
            }))
            .sort((a, b) => b.h - a.h)
        );
        onLevels?.(levels);
      });
      hls.loadSource(url);
      hls.attachMedia(video);
      return {
        type: 'hls',
        setLevel: (i) => { hls.currentLevel = i; }, // -1 = auto/ABR
        destroy: () => { hls.destroy(); video._hls = null; },
      };
    }
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = url; // Safari decodes HLS natively
      return { type: 'native-hls', setLevel: () => {}, destroy: () => {} };
    }
    throw new Error('HLS is not supported in this browser');
  }

  video.src = url;
  return { type: 'progressive', setLevel: () => {}, destroy: () => {} };
}
