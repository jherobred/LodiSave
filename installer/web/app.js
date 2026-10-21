"use strict";

// Filled in by the installer. The fallback lets the page be previewed in a browser.
const L = window.__LODI__ || {
  mode: new URLSearchParams(location.search).get("mode") || "install",
  version: "0.1.0",
  dir: "C:\\Users\\you\\AppData\\Local\\Programs\\LodiSave",
  existing: null,
  sizeMb: 12,
};

const $ = (s) => document.querySelector(s);
const post = (cmd, data = {}) => window.ipc?.postMessage(JSON.stringify({ cmd, ...data }));
const installing = L.mode === "install";
const updating = installing && L.existing;

const state = {
  dir: L.dir,
  desktopShortcut: true,
  startWithWindows: true,
  removeData: false,
  busy: false,
};

// ---- Screens -----------------------------------------------------------------------

let current = null;

function show(id) {
  const next = document.getElementById(id);
  if (next === current) return;
  if (current) {
    const old = current;
    old.classList.remove("active");
    old.classList.add("leaving");
    setTimeout(() => old.classList.remove("leaving"), 650);
  }
  [...next.children].forEach((child, i) => child.style.setProperty("--i", i));
  next.classList.remove("leaving");
  next.classList.add("active");
  current = next;
  const primary = next.querySelector("[data-primary]");
  if (primary) setTimeout(() => primary.focus({ preventScroll: true }), 400);
}

function setBusy(busy) {
  state.busy = busy;
  document.body.classList.toggle("busy", busy);
}

// ---- Sheets ------------------------------------------------------------------------

let sheet = null;

function openSheet(id) {
  sheet = document.getElementById(id);
  sheet.classList.add("open");
  $("#backdrop").classList.add("open");
}

function closeSheet() {
  if (!sheet) return;
  sheet.classList.remove("open");
  $("#backdrop").classList.remove("open");
  sheet = null;
}

// ---- Lodi ----------------------------------------------------------------------------

const worker = $("#work-lodi");

function pose(p) {
  const hop = worker.querySelector(".hop");
  worker.dataset.pose = p;
  hop.classList.remove("go");
  void hop.getBoundingClientRect(); // restarts the hop animation
  hop.classList.add("go");
}

// His cheeks fill up with the files, from normal to stuffed.
function cheeks(p) {
  const s = `scale(${(1 + 0.48 * p).toFixed(3)})`;
  $("#cheek-l").style.transform = s;
  $("#cheek-r").style.transform = s;
}

const COLORS = ["#FF8A3D", "#E8B37A", "#FBEBD6", "#FF9A7E"];

function burst() {
  const host = $("#burst");
  host.replaceChildren();
  for (let i = 0; i < 18; i++) {
    const leaf = document.createElement("i");
    leaf.style.setProperty("--a", `${(360 / 18) * i + Math.random() * 12}deg`);
    leaf.style.setProperty("--r", `${110 + Math.random() * 50}px`);
    leaf.style.setProperty("--c", COLORS[i % COLORS.length]);
    leaf.style.animationDelay = `${Math.random() * 90}ms`;
    host.appendChild(leaf);
  }
}

// ---- Progress ----------------------------------------------------------------------

let target = 0;
let shown = 0;
let startedAt = 0;
let lastFrame = 0;
let outcome;
let notes = [];
let frame = 0;

function startProgress() {
  target = 0;
  shown = 0;
  outcome = undefined;
  startedAt = lastFrame = performance.now();
  $("#progress-title").textContent = installing ? (updating ? "Updating LodiSave" : "Installing LodiSave") : "Removing LodiSave";
  $("#progress-file").textContent = "Getting ready";
  pose("full");
  cheeks(0);
  show("progress");
  setBusy(true);
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(tick);
}

// The work often takes a second or two, so the bar eases toward the real progress instead of
// jumping, and stays up long enough to be read.
function tick(now) {
  const dt = Math.min(0.1, (now - lastFrame) / 1000);
  lastFrame = now;
  const gap = target - shown;
  if (gap > 0) shown += Math.max(gap * (1 - Math.exp(-dt / 0.22)), Math.min(gap, 0.15 * dt));
  $("#progress-bar").style.transform = `scaleX(${shown})`;
  $("#progress-pct").textContent = `${Math.round(shown * 100)}%`;
  cheeks(installing ? shown : 1 - shown);
  if (outcome !== undefined) {
    if (outcome) return fail(outcome);
    if (shown > 0.999 && now - startedAt > 1800) return complete();
  }
  frame = requestAnimationFrame(tick);
}

function showNotes(id) {
  const note = $(id);
  note.textContent = notes.join(" ");
  note.hidden = notes.length === 0;
}

function complete() {
  pose("yay");
  cheeks(0);
  burst();
  $("#progress-file").textContent = "";
  setTimeout(() => {
    setBusy(false);
    showNotes(installing ? "#done-note" : "#removed-note");
    if (installing) {
      $("#done-title").textContent = updating ? "LodiSave is up to date" : "LodiSave is ready";
      const desktop = state.desktopShortcut && !notes.some((n) => n.includes("desktop shortcut"));
      $("#done-lead").textContent = desktop
        ? "Find it on your desktop, in the Start menu, and in the tray next to the clock."
        : "Find it in the Start menu and in the tray next to the clock.";
      show("done");
    } else {
      $("#removed-lead").textContent = state.removeData
        ? "Your settings and the video helper were removed too. Thanks for giving it a try."
        : "Your settings were kept in case you come back. Thanks for giving it a try.";
      show("removed");
    }
  }, 1300);
}

function fail(message) {
  setBusy(false);
  $("#error-title").textContent = installing ? "Setup couldn't finish" : "LodiSave couldn't be removed";
  $("#error-text").textContent = message;
  show("error");
}

window.lodi = {
  progress(fraction, file) {
    target = Math.max(target, Math.min(1, fraction));
    if (file) $("#progress-file").textContent = file;
  },
  finished(error, skipped) {
    outcome = error || null;
    notes = skipped || [];
    if (!error) target = 1;
  },
  folderPicked(path) {
    state.dir = path;
    renderDir();
  },
};

// ---- Wiring ------------------------------------------------------------------------

function renderDir() {
  $("#dir-text").textContent = state.dir;
  $("#dir-text").title = state.dir;
}

function toggle(button) {
  const key = button.dataset.toggle;
  state[key] = !state[key];
  button.setAttribute("aria-checked", String(state[key]));
}

const actions = {
  minimize: () => post("minimize"),
  close: () => !state.busy && post("close"),
  openOptions: () => openSheet("options"),
  openLicense: () => openSheet("license"),
  closeSheet,
  launch: () => post("launch"),
  launchExtension: () => post("launch", { extension: true }),
  retry: () => show(installing ? "welcome" : "remove"),
};

document.addEventListener("click", (e) => {
  const link = e.target.closest("a[href]");
  if (link) {
    e.preventDefault();
    post("openUrl", { url: link.href });
    return;
  }
  const button = e.target.closest("[data-action], [data-toggle]");
  if (!button) return;
  if (button.dataset.toggle) toggle(button);
  else actions[button.dataset.action]?.();
});

// Dragging anywhere that isn't a control moves the window.
document.addEventListener("mousedown", (e) => {
  if (e.button !== 0 || e.target.closest("button, a, .sheet, .backdrop, .errbox, .license-text")) return;
  post("drag");
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (sheet) closeSheet();
    else actions.close();
  }
});

$("#install").addEventListener("click", () => {
  closeSheet();
  startProgress();
  post("install", { dir: state.dir, desktopShortcut: state.desktopShortcut, startWithWindows: state.startWithWindows });
});

$("#uninstall").addEventListener("click", () => {
  startProgress();
  post("uninstall", { removeData: state.removeData });
});

$("#change-dir").addEventListener("click", () => post("pickFolder", { dir: state.dir }));

// ---- Start -------------------------------------------------------------------------

function init() {
  if (updating) {
    const same = L.existing.version === L.version;
    $("#welcome-pill").textContent = same ? `Version ${L.version} is installed` : `Update ${L.existing.version} → ${L.version}`;
    $("#welcome-title").textContent = same ? "Reinstall LodiSave" : "Update LodiSave";
    $("#install-label").textContent = same ? "Reinstall" : "Update";
    // Updates go where the app already is.
    $("#change-dir").hidden = true;
  } else {
    $("#welcome-pill").textContent = `Version ${L.version} · Free and open source`;
  }
  $("#remove-pill").textContent = `Version ${L.version}`;
  $("#fine-print").textContent = `Just for you · No admin rights needed · ${L.sizeMb} MB`;
  renderDir();

  const leaves = $(".leaves");
  for (let i = 0; i < 14; i++) {
    const leaf = document.createElement("i");
    leaf.style.setProperty("--x", `${Math.random() * 100}%`);
    leaf.style.setProperty("--s", `${6 + Math.random() * 8}px`);
    leaf.style.setProperty("--d", `${10 + Math.random() * 9}s`);
    leaf.style.setProperty("--delay", `${-Math.random() * 19}s`);
    leaf.style.setProperty("--dx", `${(Math.random() - 0.5) * 80}px`);
    leaf.style.setProperty("--rot", `${(Math.random() - 0.5) * 540}deg`);
    leaf.style.setProperty("--c", COLORS[i % COLORS.length]);
    leaves.appendChild(leaf);
  }

  show(installing ? "welcome" : "remove");
  // Show the window once fonts are in, so it never opens half drawn.
  Promise.race([document.fonts.ready, new Promise((r) => setTimeout(r, 1500))]).then(() =>
    requestAnimationFrame(() => requestAnimationFrame(() => post("ready"))),
  );
}

init();
