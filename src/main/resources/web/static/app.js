import * as THREE from "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js";
import { OrbitControls } from "https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/controls/OrbitControls.js";

const canvas = document.getElementById("c");
const worldSelect = document.getElementById("worldSelect");
const playerSelect = document.getElementById("playerSelect");
const elClock = document.getElementById("clock");
const elWeather = document.getElementById("weather");
const elDayNight = document.getElementById("dayNight");
const elCursor = document.getElementById("cursorPos");

const REQUEST_INTERVAL_MS = 10_000;
const STILL_AFTER_MS = 450;
const MAX_INFLIGHT = 6;
const MAX_TORCH_LIGHTS_PER_CHUNK = 18;
const TORCH_DISTANCE = 14;
const markerSelect = document.getElementById("markerSelect");

const state = {
  world: null,
  viewDistance: 6,
  chunkMeshes: new Map(),
  chunkLights: new Map(),
  requested: new Set(),
  inflight: 0,
  requestQueue: [],
  atlasTex: null,
  mat: null,

  // overlays
  markerGroup: new THREE.Group(),
  playerGroup: new THREE.Group(),
  markerObjs: new Map(), // name -> object3d
  playerObjs: new Map(), // uuid -> sprite
};
// --- WASD fly controls (camera + target together) ---
let typingInUI = false;
const keys = {
  w:false, a:false, s:false, d:false,
  up:false, down:false,
  ctrl:false, alt:false
};

function isTypingTarget(el) {
  if (!el) return false;
  const tag = el.tagName?.toLowerCase();
  return tag === "input" || tag === "textarea" || tag === "select" || el.isContentEditable;
}

window.addEventListener("keydown", (e) => {
  if (isTypingTarget(document.activeElement)) return;

  switch (e.code) {
    case "KeyW": keys.w = true; e.preventDefault(); break;
    case "KeyA": keys.a = true; e.preventDefault(); break;
    case "KeyS": keys.s = true; e.preventDefault(); break;
    case "KeyD": keys.d = true; e.preventDefault(); break;

    case "Space": keys.up = true; e.preventDefault(); break;
    case "ShiftLeft":
    case "ShiftRight": keys.down = true; e.preventDefault(); break;

    case "ControlLeft":
    case "ControlRight": keys.ctrl = true; break;

    case "AltLeft":
    case "AltRight": keys.alt = true; break;
  }
}, { passive: false });

window.addEventListener("keyup", (e) => {
  switch (e.code) {
    case "KeyW": keys.w = false; break;
    case "KeyA": keys.a = false; break;
    case "KeyS": keys.s = false; break;
    case "KeyD": keys.d = false; break;

    case "Space": keys.up = false; break;
    case "ShiftLeft":
    case "ShiftRight": keys.down = false; break;

    case "ControlLeft":
    case "ControlRight": keys.ctrl = false; break;

    case "AltLeft":
    case "AltRight": keys.alt = false; break;
  }
});
function api(path) { return fetch(path, { cache: "no-store" }).then(r => r.json()); }
function keyChunk(cx, cz) { return `${cx},${cz}`; }

function fmtTime(ticks) {
  const t = (ticks % 24000 + 24000) % 24000;
  const totalMinutes = Math.floor(((t + 6000) % 24000) * 60 / 1000);
  const hh = Math.floor(totalMinutes / 60), mm = totalMinutes % 60;
  const ampm = hh >= 12 ? "PM" : "AM";
  const h12 = ((hh + 11) % 12) + 1;
  return `${h12}:${String(mm).padStart(2, "0")} ${ampm}`;
}
function weatherEmoji(w) { if (w === "THUNDER") return "⛈️"; if (w === "RAIN") return "🌧️"; return "—"; }
function dayNightEmoji(isDay) { return isDay ? "☀️" : "🌙"; }

// --- Three setup ---
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(window.innerWidth, window.innerHeight, false);
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
scene.add(state.markerGroup);
scene.add(state.playerGroup);

const fog = new THREE.Fog(0x87ceff, 450, 2400);
scene.fog = fog;

const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 7000);
camera.position.set(30, 40, 30);

const controls = new OrbitControls(camera, canvas);
controls.mouseButtons = { LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.ROTATE };
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.maxDistance = 2600;
controls.minDistance = 4;
controls.target.set(0, 64, 0);

const grid = new THREE.GridHelper(1024, 256);
grid.material.opacity = 0.10;
grid.material.transparent = true;
scene.add(grid);

// --- Lighting ---
const ambient = new THREE.AmbientLight(0xffffff, 0.42);
scene.add(ambient);

const hemi = new THREE.HemisphereLight(0xbad7ff, 0x1a1a2a, 0.60);
scene.add(hemi);

const sun = new THREE.DirectionalLight(0xffffff, 1.25);
scene.add(sun);

const moon = new THREE.DirectionalLight(0xbad7ff, 0.30);
scene.add(moon);

function makeDiscSprite(color) {
  const c = document.createElement("canvas");
  c.width = 128; c.height = 128;
  const ctx = c.getContext("2d");
  const grd = ctx.createRadialGradient(64, 64, 10, 64, 64, 60);
  grd.addColorStop(0, "rgba(255,255,255,1)");
  grd.addColorStop(0.5, color);
  grd.addColorStop(1, "rgba(0,0,0,0)");
  ctx.fillStyle = grd;
  ctx.beginPath();
  ctx.arc(64, 64, 60, 0, Math.PI * 2);
  ctx.fill();
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  const spr = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
  spr.scale.set(220, 220, 1);
  return spr;
}

const sunSprite = makeDiscSprite("rgba(255,215,120,0.92)");
const moonSprite = makeDiscSprite("rgba(140,180,255,0.72)");
scene.add(sunSprite);
scene.add(moonSprite);

function clamp01(x) { return Math.max(0, Math.min(1, x)); }
function lerp(a, b, t) { return a + (b - a) * t; }
function lerpColor(c1, c2, t) {
  return new THREE.Color(
      lerp(c1.r, c2.r, t),
      lerp(c1.g, c2.g, t),
      lerp(c1.b, c2.b, t),
  );
}

// Overhead arc instead of orbiting around horizon ring
function applySkyAndLights(ticks, weather) {
  const t = ((ticks % 24000) + 24000) % 24000;

  // 0..23999 to angle: sunrise ~0, noon ~6000, sunset ~12000, midnight ~18000
  const angle = (t / 24000) * Math.PI * 2; // 0..2pi

  // Sun height: +1 at noon, -1 at midnight
  const sunHeight = Math.cos(angle - Math.PI / 2);
  const dayFactor = clamp01((sunHeight + 1) / 2); // 0..1 (true bright day)

  const weatherDim = (weather === "THUNDER") ? 0.55 : (weather === "RAIN" ? 0.78 : 1.0);

  const daySky = new THREE.Color(0x87ceff);
  const nightSky = new THREE.Color(0x071426);
  const sky = lerpColor(nightSky, daySky, dayFactor);

  scene.background = sky;
  fog.color = sky;

  ambient.intensity = lerp(0.20, 0.55, dayFactor) * weatherDim;
  hemi.intensity = lerp(0.70, 0.55, dayFactor) * weatherDim;

  sun.intensity = lerp(0.0, 1.35, dayFactor) * weatherDim;
  moon.intensity = lerp(0.35, 0.0, dayFactor);

  // Arc across sky directly "over top"
  const radius = 1200;

  // East->West along X axis, overhead on Y, constant Z so it doesn't orbit around camera
  const sx = Math.sin(angle) * radius;
  const sy = Math.cos(angle) * radius; // up at noon
  const sz = -radius * 0.35;

  sun.position.set(sx, Math.max(-200, sy), sz);
  moon.position.set(-sx, Math.max(-200, -sy), -sz);

  // Place sprites on skydome-ish positions
  const sunPos = sun.position.clone().normalize().multiplyScalar(1700);
  const moonPos = moon.position.clone().normalize().multiplyScalar(1700);
  sunSprite.position.copy(sunPos);
  moonSprite.position.copy(moonPos);

  sunSprite.visible = dayFactor > 0.12;
  moonSprite.visible = dayFactor < 0.35;
}

async function loadAtlas() {
  return new Promise((resolve, reject) => {
    const loader = new THREE.TextureLoader();
    loader.load(
        "/api/atlas.png",
        (tex) => {
          tex.flipY = false;      // IMPORTANT
          tex.needsUpdate = true; // IMPORTANT

          tex.magFilter = THREE.NearestFilter;
          tex.minFilter = THREE.NearestFilter;
          tex.wrapS = THREE.ClampToEdgeWrapping;
          tex.wrapT = THREE.ClampToEdgeWrapping;
          tex.colorSpace = THREE.SRGBColorSpace;

          state.atlasTex = tex;

          // KEY FIXES:
          // - alphaTest makes leaves cut out correctly
          // - vertexColors allows our leaf tint multiplier
          state.mat = new THREE.MeshStandardMaterial({
            map: tex,
            vertexColors: true,
            transparent: true,
            alphaTest: 0.5,
          });

          resolve();
        },
        undefined,
        reject
    );
  });
}

// cursor raycast
const raycaster = new THREE.Raycaster();
const mouse = new THREE.Vector2();
let lastCursorPoint = null;


function disposeChunk(key) {
  const mesh = state.chunkMeshes.get(key);
  if (mesh) {
    mesh.geometry.dispose();
    scene.remove(mesh);
  }
  state.chunkMeshes.delete(key);

  const lights = state.chunkLights.get(key);
  if (lights) for (const l of lights) scene.remove(l);
  state.chunkLights.delete(key);
}

function enqueueChunk(world, cx, cz) {
  const k = keyChunk(cx, cz);
  if (state.chunkMeshes.has(k) || state.requested.has(k)) return;
  state.requested.add(k);
  state.requestQueue.push({ world, cx, cz, k });
}

async function pumpQueue() {
  while (state.inflight < MAX_INFLIGHT && state.requestQueue.length > 0) {
    const job = state.requestQueue.shift();
    state.inflight++;
    loadChunkMesh(job.world, job.cx, job.cz, job.k)
        .catch(() => {})
        .finally(() => state.inflight--);
  }
}

async function loadChunkMesh(world, cx, cz, k) {
  try {
    const res = await fetch(`/api/chunk?world=${encodeURIComponent(world)}&cx=${cx}&cz=${cz}`, { cache: "no-store" });
    if (!res.ok) return;
    const data = await res.json();
    if (!data || !data.vertices || data.vertices.length === 0) return;

    const geom = new THREE.BufferGeometry();
    geom.setAttribute("position", new THREE.BufferAttribute(new Float32Array(data.vertices), 3));
    geom.setAttribute("normal", new THREE.BufferAttribute(new Float32Array(data.normals), 3));
    geom.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(data.uvs), 2));

    // NEW: colors
    if (data.colors && data.colors.length) {
      geom.setAttribute("color", new THREE.BufferAttribute(new Float32Array(data.colors), 3));
    }

    geom.setIndex(new THREE.BufferAttribute(new Uint32Array(data.indices), 1));
    geom.computeBoundingSphere();

    const mesh = new THREE.Mesh(geom, state.mat);
    mesh.userData = { cx, cz };
    scene.add(mesh);
    state.chunkMeshes.set(k, mesh);

    // Emitters -> point lights
    if (Array.isArray(data.emitters) && data.emitters.length >= 4) {
      const lights = [];
      const count = Math.min(MAX_TORCH_LIGHTS_PER_CHUNK, Math.floor(data.emitters.length / 4));
      for (let i = 0; i < count; i++) {
        const x = data.emitters[i * 4 + 0];
        const y = data.emitters[i * 4 + 1];
        const z = data.emitters[i * 4 + 2];
        const intensity = data.emitters[i * 4 + 3];

        const l = new THREE.PointLight(0xffb35a, 0.9 * intensity, TORCH_DISTANCE, 2.0);
        l.position.set(x, y, z);
        lights.push(l);
        scene.add(l);
      }
      state.chunkLights.set(k, lights);
    }
  } finally {
    state.requested.delete(k);
  }
}

function pruneChunksAround(centerCx, centerCz) {
  const r = state.viewDistance + 2;
  for (const [k, mesh] of state.chunkMeshes.entries()) {
    const dx = mesh.userData.cx - centerCx;
    const dz = mesh.userData.cz - centerCz;
    if (Math.abs(dx) > r || Math.abs(dz) > r) disposeChunk(k);
  }
}

function requestChunksAround(x, z) {
  if (!state.world) return;
  const cx = Math.floor(x / 16), cz = Math.floor(z / 16);

  pruneChunksAround(cx, cz);

  const r = state.viewDistance;
  const list = [];
  for (let dz = -r; dz <= r; dz++) {
    for (let dx = -r; dx <= r; dx++) {
      list.push([cx + dx, cz + dz, dx * dx + dz * dz]);
    }
  }
  list.sort((a, b) => a[2] - b[2]);
  for (const [ccx, ccz] of list) enqueueChunk(state.world, ccx, ccz);
}

function clearChunks() {
  for (const k of state.chunkMeshes.keys()) disposeChunk(k);
  state.chunkMeshes.clear();
  state.requested.clear();
  state.requestQueue.length = 0;
  state.chunkLights.clear();
}

async function refreshWorlds() {
  const data = await api("/api/worlds");
  state.viewDistance = data.defaultViewDistanceChunks || 10;
  const worlds = data.worlds || [];
  worldSelect.innerHTML = "";

  for (const w of worlds) {
    const opt = document.createElement("option");
    opt.value = w.name;
    opt.textContent = `${w.icon ? w.icon + " " : ""}${w.displayName || w.name}`;
    worldSelect.appendChild(opt);
  }

  if (!state.world && worlds.length) {
    setWorld(worlds[0].name);
    worldSelect.value = worlds[0].name;
  }
}

async function refreshStatus() {
  if (!state.world) return;
  const st = await api(`/api/status?world=${encodeURIComponent(state.world)}`);

  elClock.textContent = st.showClock ? fmtTime(st.timeTicks) : "";
  elWeather.textContent = st.showWeather ? weatherEmoji(st.weather) : "";
  elDayNight.textContent = st.showDayNight ? dayNightEmoji(st.isDay) : "";

  applySkyAndLights(st.timeTicks ?? 0, st.weather ?? "CLEAR");
}

function makePin(label) {
  const g = new THREE.Group();

  const pole = new THREE.Mesh(
      new THREE.CylinderGeometry(0.08, 0.08, 1.4, 10),
      new THREE.MeshStandardMaterial({ color: 0xffffff})
  );
  pole.position.y = 0.7;
  g.add(pole);

  const head = new THREE.Mesh(
      new THREE.ConeGeometry(0.22, 0.55, 14),
      new THREE.MeshStandardMaterial({ color: 0xff2bd6})
  );
  head.position.y = 1.55;
  g.add(head);

  // label sprite
  const c = document.createElement("canvas");
  c.width = 512; c.height = 128;
  const ctx = c.getContext("2d");
  ctx.fillStyle = "rgba(20,20,26,0.75)";
  ctx.fillRect(0, 0, c.width, c.height);
  ctx.fillStyle = "rgba(255,255,255,0.95)";
  ctx.font = "48px system-ui, -apple-system, Segoe UI, Roboto, Arial";
  ctx.textBaseline = "middle";
  ctx.fillText(label, 18, 64);

  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;

  const spr = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
  spr.scale.set(6.5, 1.6, 1);
  spr.position.set(0, 2.7, 0);
  g.add(spr);

  return g;
}
markerSelect.addEventListener("change", async () => {
  const name = markerSelect.value;
  if (!name) return;

  const data = await api(`/api/markers?world=${encodeURIComponent(state.world)}`);
  const m = (data.markers || []).find(x => x.name === name);
  if (!m) return;

  controls.target.set(m.x, m.y, m.z);
  camera.position.set(m.x + 25, m.y + 30, m.z + 25);

  lastRequestAt = 0;
  requestChunksAround(m.x, m.z);
});
async function refreshMarkers() {
  if (!state.world) return;
  const data = await api(`/api/markers?world=${encodeURIComponent(state.world)}`);
  const markers = data.markers || [];

  // rebuild for simplicity
  state.markerGroup.clear();
  state.markerObjs.clear();

  for (const m of markers) {
    const pin = makePin(m.label || m.name);
    pin.position.set(m.x + 0.5, m.y, m.z + 0.5);
    state.markerGroup.add(pin);
    state.markerObjs.set(m.name, pin);
  }
  markerSelect.innerHTML = `<option value="">Markers</option>`;
  for (const m of markers) {
    const opt = document.createElement("option");
    opt.value = m.name;
    opt.textContent = `${m.label || m.name} (${Math.floor(m.x)}, ${Math.floor(m.y)}, ${Math.floor(m.z)})`;
    markerSelect.appendChild(opt);
  }
}

function headUrl(uuid) {
  // Public head renderer (fast). If you want offline/internal later, we’ll proxy it.
  return `https://mc-heads.net/avatar/${uuid}/64`;
}

const headLoader = new THREE.TextureLoader();
headLoader.crossOrigin = "anonymous";

async function refreshPlayers() {
  const data = await api("/api/players");
  const players = data.players || [];

  // update dropdown
  playerSelect.innerHTML = `<option value="">Players</option>`;
  for (const p of players) {
    const opt = document.createElement("option");
    opt.value = p.uuid;
    opt.textContent = `${p.name} — ${p.world} (${Math.floor(p.x)}, ${Math.floor(p.y)}, ${Math.floor(p.z)})`;
    playerSelect.appendChild(opt);
  }

  // rebuild sprites for current world only
  state.playerGroup.clear();
  state.playerObjs.clear();

  for (const p of players) {
    if (p.world !== state.world) continue;

    const tex = await new Promise((resolve) => {
      headLoader.load(headUrl(p.uuid), resolve, undefined, () => resolve(null));
    });

    const mat = new THREE.SpriteMaterial({
      map: tex || null,
      color: tex ? 0xffffff : 0xff2bd6,
      transparent: true,
      depthWrite: false
    });

    const spr = new THREE.Sprite(mat);
    spr.scale.set(2.2, 2.2, 1);
    spr.position.set(p.x, p.y + 2.6, p.z);
    state.playerGroup.add(spr);
    state.playerObjs.set(p.uuid, spr);
  }
}

function setWorld(worldName) {
  state.world = worldName;
  clearChunks();

  api(`/api/status?world=${encodeURIComponent(worldName)}`).then(st => {
    if (st && st.spawn) {
      controls.target.set(st.spawn.x, st.spawn.y, st.spawn.z);
      camera.position.set(st.spawn.x + 30, st.spawn.y + 35, st.spawn.z + 30);
      lastRequestAt = 0;

      // FORCE initial load immediately
      requestChunksAround(controls.target.x, controls.target.z);
    }
  }).catch(() => {});

  refreshMarkers().catch(() => {});
  refreshPlayers().catch(() => {});
}

worldSelect.addEventListener("change", () => setWorld(worldSelect.value));

playerSelect.addEventListener("change", async () => {
  const uuid = playerSelect.value;
  if (!uuid) return;

  const data = await api("/api/players");
  const p = (data.players || []).find(x => x.uuid === uuid);
  if (!p) return;

  if (p.world && p.world !== state.world) {
    worldSelect.value = p.world;
    setWorld(p.world);
  }

  controls.target.set(p.x, p.y, p.z);
  camera.position.set(p.x + 25, p.y + 30, p.z + 25);

  // FORCE load around player immediately
  lastRequestAt = 0;
  requestChunksAround(p.x, p.z);
});

canvas.addEventListener("mousemove", (ev) => {
  mouse.x = (ev.clientX / window.innerWidth) * 2 - 1;
  mouse.y = -(ev.clientY / window.innerHeight) * 2 + 1;
  raycaster.setFromCamera(mouse, camera);
  const hits = raycaster.intersectObjects([...state.chunkMeshes.values()], false);
  if (hits.length) {
    lastCursorPoint = hits[0].point;
    elCursor.textContent = `X: ${Math.floor(lastCursorPoint.x)}, Y: ${Math.floor(lastCursorPoint.y)}, Z: ${Math.floor(lastCursorPoint.z)}`;
  }
}, { passive: true });

canvas.addEventListener("wheel", (ev) => {
  ev.preventDefault();
  const zoomPoint = lastCursorPoint ? lastCursorPoint.clone() : controls.target.clone();
  const dir = new THREE.Vector3().subVectors(camera.position, zoomPoint).normalize();
  const dist = camera.position.distanceTo(zoomPoint);
  const factor = Math.pow(1.0018, ev.deltaY);
  const newDist = THREE.MathUtils.clamp(dist * factor, controls.minDistance, controls.maxDistance);
  camera.position.copy(zoomPoint).addScaledVector(dir, newDist);
  controls.update();
}, { passive: false });

window.addEventListener("resize", () => {
  renderer.setSize(window.innerWidth, window.innerHeight, false);
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
});

let lastPoll = 0;
let lastMoveAt = performance.now();
let lastRequestAt = 0;

controls.addEventListener("change", () => { lastMoveAt = performance.now(); });
function applyWASD(dtSeconds) {
  if (isTypingTarget(document.activeElement)) return;

  const moving =
      keys.w || keys.a || keys.s || keys.d || keys.up || keys.down;

  if (!moving) return;

  // Speed scales with zoom distance so movement feels natural
  const dist = camera.position.distanceTo(controls.target);
  let speed = Math.max(6, dist * 0.45);

  if (keys.ctrl) speed *= 0.25;  // precision mode
  if (keys.alt)  speed *= 2.2;   // turbo mode

  const step = speed * dtSeconds;

  const forward = new THREE.Vector3();
  camera.getWorldDirection(forward);
  forward.y = 0;
  forward.normalize();

  const right = new THREE.Vector3()
      .crossVectors(forward, new THREE.Vector3(0, 1, 0))
      .normalize();

  const delta = new THREE.Vector3();

  if (keys.w) delta.add(forward);
  if (keys.s) delta.sub(forward);
  if (keys.d) delta.add(right);
  if (keys.a) delta.sub(right);

  if (keys.up)   delta.y += 1;
  if (keys.down) delta.y -= 1;

  if (delta.lengthSq() === 0) return;

  delta.normalize().multiplyScalar(step);

  camera.position.add(delta);
  controls.target.add(delta);

  lastMoveAt = performance.now();
}
let prevTime = performance.now();
function animate(t) {
  requestAnimationFrame(animate);
  const now = performance.now();
  const dt = Math.min(0.05, (now - prevTime) / 1000); // cap dt to avoid huge jumps
  prevTime = now;

  applyWASD(dt);
  controls.update();

  const still = (now - lastMoveAt) > STILL_AFTER_MS;

  if (state.world && still && (now - lastRequestAt) > REQUEST_INTERVAL_MS) {
    lastRequestAt = now;
    requestChunksAround(controls.target.x, controls.target.z);
  }

  pumpQueue().catch(() => {});

  if (t - lastPoll > 1200) {
    lastPoll = t;
    refreshStatus().catch(() => {});
    refreshMarkers().catch(() => {});
    refreshPlayers().catch(() => {});
  }

  renderer.render(scene, camera);
}

(async function boot() {
  await loadAtlas();
  await refreshWorlds();
  await refreshStatus().catch(() => {});
  await refreshMarkers().catch(() => {});
  await refreshPlayers().catch(() => {});
  animate(0);
})().catch(err => {
  console.error(err);
  alert("Failed to boot Voxmap. Check console.");
});