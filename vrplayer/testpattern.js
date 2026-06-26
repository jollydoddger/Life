// testpattern.js — builds a packed equirectangular calibration frame on a
// <canvas>. Doubles as the app's offline "test pattern" and the fixture used
// by the automated rendering test. Each eye sub-image carries a colour tint and
// an L/R badge so stereo packing can be verified by sampling pixels.

function drawEquirect(ctx, x, y, w, h, { tint, badge }) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(x, y, w, h);
  ctx.clip();

  // base + eye tint
  ctx.fillStyle = '#10131c';
  ctx.fillRect(x, y, w, h);
  ctx.fillStyle = tint;
  ctx.fillRect(x, y, w, h);

  // longitude/latitude grid (every 30°)
  ctx.strokeStyle = 'rgba(255,255,255,0.18)';
  ctx.lineWidth = 2;
  for (let u = 0; u <= 12; u++) {
    const px = x + (u / 12) * w;
    ctx.beginPath(); ctx.moveTo(px, y); ctx.lineTo(px, y + h); ctx.stroke();
  }
  for (let v = 0; v <= 6; v++) {
    const py = y + (v / 6) * h;
    ctx.beginPath(); ctx.moveTo(x, py); ctx.lineTo(x + w, py); ctx.stroke();
  }

  // compass labels across the horizon (u: 0=back .. .5=front .. 1=back)
  const labels = [
    [0.00, 'BACK'], [0.25, 'LEFT'], [0.50, 'FRONT'], [0.75, 'RIGHT'], [1.00, 'BACK'],
  ];
  ctx.fillStyle = '#ffffff';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = `bold ${Math.round(h * 0.06)}px sans-serif`;
  for (const [u, text] of labels) {
    ctx.fillText(text, x + u * w, y + h * 0.5);
  }

  // asymmetric mirror check: green bar left of FRONT, yellow right of it
  ctx.fillStyle = '#37d67a';
  ctx.fillRect(x + 0.44 * w, y + 0.30 * h, 0.015 * w, 0.40 * h);
  ctx.fillStyle = '#ffd23f';
  ctx.fillRect(x + 0.545 * w, y + 0.30 * h, 0.015 * w, 0.40 * h);

  // eye badge, top centre
  ctx.fillStyle = '#ffffff';
  ctx.font = `bold ${Math.round(h * 0.12)}px sans-serif`;
  ctx.fillText(badge, x + 0.5 * w, y + 0.16 * h);

  ctx.restore();
}

// Circular fisheye calibration tile: inscribed lens circle, polar grid, and
// directional markers (FRONT centre, UP green, RIGHT yellow) so orientation and
// mirroring can be verified.
function drawFisheye(ctx, x, y, size, { tint, badge }) {
  const cx = x + size / 2, cy = y + size / 2, R = size / 2;
  ctx.save();
  ctx.fillStyle = '#05070c';
  ctx.fillRect(x, y, size, size);
  ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.clip();
  ctx.fillStyle = '#10131c'; ctx.fillRect(x, y, size, size);
  ctx.fillStyle = tint; ctx.fillRect(x, y, size, size);

  ctx.strokeStyle = 'rgba(255,255,255,0.18)'; ctx.lineWidth = 2;
  for (const f of [0.33, 0.66, 1.0]) { ctx.beginPath(); ctx.arc(cx, cy, R * f, 0, Math.PI * 2); ctx.stroke(); }
  for (let a = 0; a < 360; a += 30) {
    const rad = a * Math.PI / 180;
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + R * Math.cos(rad), cy + R * Math.sin(rad)); ctx.stroke();
  }
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillStyle = '#fff'; ctx.font = `bold ${Math.round(R * 0.11)}px sans-serif`;
  ctx.fillText('FRONT', cx, cy);
  ctx.fillText('UP', cx, cy - R * 0.78);
  ctx.fillText('DOWN', cx, cy + R * 0.78);
  ctx.fillText('LEFT', cx - R * 0.78, cy);
  ctx.fillText('RIGHT', cx + R * 0.78, cy);
  ctx.fillStyle = '#37d67a'; ctx.beginPath(); ctx.arc(cx, cy - R * 0.5, R * 0.05, 0, Math.PI * 2); ctx.fill(); // UP = green
  ctx.fillStyle = '#ffd23f'; ctx.beginPath(); ctx.arc(cx + R * 0.5, cy, R * 0.05, 0, Math.PI * 2); ctx.fill();  // RIGHT = yellow
  ctx.restore();

  ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.font = `bold ${Math.round(R * 0.14)}px sans-serif`;
  ctx.fillText(badge, cx, y + size * 0.1);
}

export function buildTestPattern({ layout = 'mono', projection = '360' } = {}) {
  const blueT = 'rgba(40,90,200,0.30)', redT = 'rgba(200,60,60,0.30)';
  if (projection === 'fisheye') {
    const S = 1024;
    const canvas = document.createElement('canvas');
    const ctx = () => canvas.getContext('2d');
    if (layout === 'sbs') {
      canvas.width = S * 2; canvas.height = S;
      drawFisheye(ctx(), 0, 0, S, { tint: blueT, badge: 'L' });
      drawFisheye(ctx(), S, 0, S, { tint: redT, badge: 'R' });
    } else if (layout === 'ou') {
      canvas.width = S; canvas.height = S * 2;
      drawFisheye(ctx(), 0, 0, S, { tint: blueT, badge: 'L' });
      drawFisheye(ctx(), 0, S, S, { tint: redT, badge: 'R' });
    } else {
      canvas.width = S; canvas.height = S;
      drawFisheye(ctx(), 0, 0, S, { tint: 'rgba(80,80,90,0.18)', badge: '' });
    }
    return canvas;
  }
  return buildEquirectPattern({ layout });
}

function buildEquirectPattern({ layout = 'mono' } = {}) {
  const TILE_W = 2048, TILE_H = 1024;
  const canvas = document.createElement('canvas');
  const ctx = () => canvas.getContext('2d');

  const blue = 'rgba(40,90,200,0.30)';
  const red = 'rgba(200,60,60,0.30)';

  if (layout === 'sbs') {
    canvas.width = TILE_W * 2; canvas.height = TILE_H;
    drawEquirect(ctx(), 0, 0, TILE_W, TILE_H, { tint: blue, badge: 'L' });
    drawEquirect(ctx(), TILE_W, 0, TILE_W, TILE_H, { tint: red, badge: 'R' });
  } else if (layout === 'ou') {
    canvas.width = TILE_W; canvas.height = TILE_H * 2;
    drawEquirect(ctx(), 0, 0, TILE_W, TILE_H, { tint: blue, badge: 'L' });        // top = left
    drawEquirect(ctx(), 0, TILE_H, TILE_W, TILE_H, { tint: red, badge: 'R' });    // bottom = right
  } else {
    canvas.width = TILE_W; canvas.height = TILE_H;
    drawEquirect(ctx(), 0, 0, TILE_W, TILE_H, { tint: 'rgba(80,80,90,0.18)', badge: '' });
  }
  return canvas;
}
