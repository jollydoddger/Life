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

export class Player {
  constructor(canvas, source) {
    this.canvas = canvas;
    this.source = source; // <video> or <canvas>

    this.projection = '360';   // '360' | '180'
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

    for (const { eye, layers } of eyes) {
      const material = new THREE.MeshBasicMaterial({ map: this._eyeTexture(eye) });
      const mesh = new THREE.Mesh(this._geometry(), material);
      mesh.layers.disableAll();
      for (const l of layers) mesh.layers.enable(l);
      this.scene.add(mesh);
      this.meshes.push(mesh);
    }
  }

  setFormat({ projection, layout }) {
    if (projection) this.projection = projection;
    if (layout) this.layout = layout;
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

  _applyDeviceOrientation(o) {
    // Standard W3C deviceorientation -> quaternion conversion.
    const zee = new THREE.Vector3(0, 0, 1);
    const euler = new THREE.Euler();
    const q0 = new THREE.Quaternion();
    const q1 = new THREE.Quaternion(-Math.sqrt(0.5), 0, 0, Math.sqrt(0.5)); // -PI/2 about X
    const deg = THREE.MathUtils.degToRad;
    const alpha = deg(o.alpha || 0);
    const beta = deg(o.beta || 0);
    const gamma = deg(o.gamma || 0);
    const screen = deg(o.screen || 0);
    euler.set(beta, alpha, -gamma, 'YXZ');
    this.camera.quaternion.setFromEuler(euler);
    this.camera.quaternion.multiply(q1);
    this.camera.quaternion.multiply(q0.setFromAxisAngle(zee, -screen));
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
