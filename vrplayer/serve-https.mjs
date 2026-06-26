// serve-https.mjs — serve the player over HTTPS on your LAN so a phone can load
// it in a Cardboard headset. WebXR and device-motion sensors require a secure
// context, so plain http://192.168.x.x won't expose the gyro — this does.
//
//   node serve-https.mjs              # or: npm run serve:phone
//
// On first run it self-signs a certificate with openssl (preinstalled on macOS
// and most Linux; on Windows use Git Bash). Your phone will show a one-time
// "not private" warning for the self-signed cert — tap Advanced → proceed.

import https from 'node:https';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 8443;
const CERT_DIR = path.join(ROOT, '.devcert');
const KEY = path.join(CERT_DIR, 'key.pem');
const CRT = path.join(CERT_DIR, 'cert.pem');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.mp4': 'video/mp4',
  '.webm': 'video/webm', '.m3u8': 'application/vnd.apple.mpegurl', '.ts': 'video/mp2t',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
};

function lanIP() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) if (i.family === 'IPv4' && !i.internal) return i.address;
  }
  return '127.0.0.1';
}
const ip = lanIP();

if (!fs.existsSync(KEY) || !fs.existsSync(CRT)) {
  fs.mkdirSync(CERT_DIR, { recursive: true });
  console.log('Generating a self-signed certificate (one-time)…');
  try {
    execFileSync('openssl', [
      'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
      '-keyout', KEY, '-out', CRT, '-days', '365',
      '-subj', '/CN=orbit-dev',
      '-addext', `subjectAltName=IP:${ip},IP:127.0.0.1,DNS:localhost`,
    ], { stdio: 'inherit' });
  } catch {
    console.error('\nCouldn\'t run openssl. Install it, or use `npm start` (HTTP, desktop only).');
    process.exit(1);
  }
}

https.createServer({ key: fs.readFileSync(KEY), cert: fs.readFileSync(CRT) }, (req, res) => {
  let rel = decodeURIComponent(req.url.split('?')[0]);
  if (rel === '/') rel = '/index.html';
  const file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404); return res.end('not found');
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}).listen(PORT, '0.0.0.0', () => {
  console.log(`\n  Orbit is serving over HTTPS:\n`);
  console.log(`    this computer : https://localhost:${PORT}/`);
  console.log(`    your phone    : https://${ip}:${PORT}/   ← open this on the phone\n`);
  console.log('  Make sure the phone is on the same Wi-Fi. First load shows a');
  console.log('  certificate warning (self-signed) — tap Advanced → Proceed.\n');
  console.log('  Then: load a video → tap Cardboard → grant motion access → drop in headset.\n');
});
