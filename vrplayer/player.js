// player.js — stereoscopic 180°/360° video engine built on three.js.
//
// Design notes
// ------------
// * The media is painted on the inside of a sphere (360°) or a forward
//   hemisphere (180°). We use geometry.scale(-1, 1, 1) so we view the inner
//   surface with correct (non-mirrored) orientation — the same trick the
//   stock three.js panorama example uses.
// * Stereo is done with render layers, never layer 0:
//       left-eye media  -> layer 1
//       right-eye media -> layer 2
//   three.js' StereoCamera and WebXRManager enable layer 1 on the left eye
//   camera and layer 2 on the right. The flat (desktop) preview camera is
//   pinned to layer 1 so you always see the left eye.
// * For per-eye cropping (SBS / over-under) each eye gets its own VideoTexture
//   wrapping the *same* <video> element, with different offset/repeat.

import * as THREE from 'three';
import { StereoEffect } from 'three/addons/effects/StereoEffect.js';

const SPHERE_RADIUS = 500;

// Equidistant fisheye dome (the format SLR/DeoVR call mkx200, mkx220, rf52,
// fisheye190, fisheye). The lens maps angle-from-forward linearly to image
// radius, so a ray at angle θ samples radius (θ / (fov/2)) * 0.5 of the circle,
// which is inscribed in the eye's image square. Forward = +X, up = +Y,
// right = +Z; per-eye SBS/OU cropping is still handled by texture offset/repeat.
function fisheyeGeometry(radius, fovDeg) {
  const half = THREE.MathUtils.degToRad(fovDeg / 2);
  const W = 128, H = 96;
  const pos = [], uv = [], idx = [];
  for (let j = 0; j <= H; j++) {
    const t = j / H;
    const theta = half * t;
    const rN = 0.5 * t;                       // image radius, 0..0.5 (equidistant)
    const sinT = Math.sin(theta), cosT = Math.cos(theta);
    for (let i = 0; i <= W; i++) {
      const phi = 2 * Math.PI * (i / W);
      const cphi = Math.cos(phi), sphi = Math.sin(phi);
      pos.push(cosT * radius, sinT * cphi * radius, -sinT * sphi * radius);
      uv.push(0.5 - rN * sphi, 0.5 + rN * cphi); // un-mirror horizontally; +v = up
    }
  }
  for (let j = 0; j < H; j++) {
    for (let i = 0; i < W; i++) {
      const a = j * (W + 1) + i, b = a + 1, c = a + (W + 1), d = c + 1;
      idx.push(a, c, b, b, c, d);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

export class Player {
  constructor(canvas, source) {
    this.canvas = canvas;
    this.source = source; // <video> or <canvas>

    this.projection = '360';   // '360' | '180' | 'fisheye'
    this.fov = 180;            // fisheye field of view in degrees (180/190/200/220)
    this.layout = 'mono';      // 'mono' | 'sbs' | 'ou'
    this.mode = 'flat';        // 'flat' | 'cardboard' | 'xr'

    // --- renderer ---
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.xr.enabled = true;

    // --- scene & camera (camera sits at the centre of the sphere) ---
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(72, 1, 0.1, 1100);
    this.camera.layers.set(1); // flat preview = left eye
    this.scene.add(this.camera);

    // Cardboard split-screen effect (used when WebXR is unavailable).
    this.stereoEffect = new StereoEffect(this.renderer);
    this.stereoEffect.setEyeSeparation(0.064);

    // Look state for pointer / gyro control.
    this.lon = 0;       // yaw, degrees
    this.lat = 0;       // pitch, degrees
    this.yawOffset = 0; // recentre offset (radians), applied in gyro mode
    this.orientation = null; // {alpha,beta,gamma,screen} when gyro active

    this.meshes = [];
    this._buildScreen();

    this.onResize();
    window.addEventListener('resize', () => this.onResize());

    this.renderer.setAnimationLoop((t, frame) => this._render(t, frame));
  }

  // ---- media surface -------------------------------------------------------

  setSource(source) {
    this.source = source;
    this._buildScreen();
  }

  _eyeTexture(eye) {
    const isVideo = this.source.tagName === 'VIDEO';
    const tex = isVideo
      ? new THREE.VideoTexture(this.source)
      : new THREE.CanvasTexture(this.source);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.minFilter = THREE.LinearFilter;
    tex.magFilter = THREE.LinearFilter;
    tex.generateMipmaps = false;
    if (this.layout === 'sbs') {
      tex.repeat.x = 0.5;
      tex.offset.x = eye === 'left' ? 0.0 : 0.5;
    } else if (this.layout === 'ou') {
      tex.repeat.y = 0.5;
      // Top half is the left eye by convention; v=0 is the bottom.
      tex.offset.y = eye === 'left' ? 0.5 : 0.0;
    }
    return tex;
  }

  _geometry() {
    if (this.projection === 'fisheye') {
      return fisheyeGeometry(SPHERE_RADIUS, this.fov);
    }
    let geo;
    if (this.projection === '180') {
      // Forward-facing hemisphere centred on -Z.
      geo = new THREE.SphereGeometry(
        SPHERE_RADIUS, 64, 40,
        Math.PI / 2, Math.PI,   // phiStart, phiLength
        0, Math.PI              // thetaStart, thetaLength
      );
    } else {
      geo = new THREE.SphereGeometry(SPHERE_RADIUS, 64, 40);
    }
    geo.scale(-1, 1, 1);   // view from the inside, un-mirrored
    geo.rotateY(Math.PI);  // equirect centre (forward of capture) faces the start view
    return geo;
  }

  _buildScreen() {
    // Tear down any previous surface.
    for (const m of this.meshes) {
      this.scene.remove(m);
      m.geometry.dispose();
      m.material.map?.dispose();
      m.material.dispose();
    }
    this.meshes = [];

    const eyes = this.layout === 'mono'
      ? [{ eye: 'left', layers: [1, 2] }]                 // same surface both eyes
      : [{ eye: 'left', layers: [1] }, { eye: 'right', layers: [2] }];

    // Fisheye dome is viewed from the centre; render both faces so winding can't
    // hide it. Equirect uses scale(-1,1,1) + FrontSide.
    const side = this.projection === 'fisheye' ? THREE.DoubleSide : THREE.FrontSide;
    for (const { eye, layers } of eyes) {
      const material = new THREE.MeshBasicMaterial({ map: this._eyeTexture(eye), side });
      const mesh = new THREE.Mesh(this._geometry(), material);
      mesh.layers.disableAll();
      for (const l of layers) mesh.layers.enable(l);
      this.scene.add(mesh);
      this.meshes.push(mesh);
    }
  }

  setFormat({ projection, layout, fov }) {
    if (projection) this.projection = projection;
    if (layout) this.layout = layout;
    if (fov) this.fov = fov;
    this._buildScreen();
  }

  // ---- view modes ----------------------------------------------------------

  setMode(mode) {
    this.mode = mode;
  }

  setOrientation(o) { this.orientation = o; }

  // Pointer drag updates yaw/pitch (degrees). Ignored while gyro/XR drive view.
  look(dLon, dLat) {
    this.lon += dLon;
    this.lat = Math.max(-85, Math.min(85, this.lat + dLat));
  }

  // Make whatever is currently in front the new centre of the video.
  recenter() {
    if (this.orientation) {
      const q = new THREE.Quaternion();
      this._deviceQuaternion(this.orientation, q);
      const f = new THREE.Vector3(0, 0, -1).applyQuaternion(q);
      this.yawOffset = Math.atan2(f.z, f.x); // rotate current heading onto +X (content centre)
    } else {
      this.lon = 0; this.lat = 0;
    }
  }

  // ---- render loop ---------------------------------------------------------

  _applyPointerView() {
    const phi = THREE.MathUtils.degToRad(90 - this.lat);
    const theta = THREE.MathUtils.degToRad(this.lon);
    const target = new THREE.Vector3(
      Math.sin(phi) * Math.cos(theta),
      Math.cos(phi),
      Math.sin(phi) * Math.sin(theta)
    );
    this.camera.lookAt(target);
  }

  // Standard W3C deviceorientation -> quaternion conversion (writes into `out`).
  _deviceQuaternion(o, out) {
    const zee = new THREE.Vector3(0, 0, 1);
    const q0 = new THREE.Quaternion();
    const q1 = new THREE.Quaternion(-Math.sqrt(0.5), 0, 0, Math.sqrt(0.5)); // -PI/2 about X
    const deg = THREE.MathUtils.degToRad;
    out.setFromEuler(new THREE.Euler(deg(o.beta || 0), deg(o.alpha || 0), -deg(o.gamma || 0), 'YXZ'));
    out.multiply(q1);
    out.multiply(q0.setFromAxisAngle(zee, -deg(o.screen || 0)));
    return out;
  }

  _applyDeviceOrientation(o) {
    this._deviceQuaternion(o, this.camera.quaternion);
    // Recentre offset: rotate the whole view about world-up so the chosen
    // heading reads as forward (centre of the video).
    if (this.yawOffset) {
      this.camera.quaternion.premultiply(
        new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), this.yawOffset));
    }
  }

  _render(time, frame) {
    if (this.renderer.xr.isPresenting) {
      this.renderer.render(this.scene, this.camera);
      return;
    }
    if (this.orientation) {
      this._applyDeviceOrientation(this.orientation);
    } else {
      this._applyPointerView();
    }
    if (this.mode === 'cardboard') {
      this.stereoEffect.render(this.scene, this.camera);
    } else {
      this.renderer.render(this.scene, this.camera);
    }
  }

  onResize() {
    const w = this.canvas.clientWidth || window.innerWidth;
    const h = this.canvas.clientHeight || window.innerHeight;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h, false);
    this.stereoEffect.setSize(w, h);
  }
}
