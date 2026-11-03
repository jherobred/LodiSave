// The gallery window: photos, GIFs, videos and sounds in the folders the user added, organized
// without File Explorer. The app checks every path sent back against those folders.
const { invoke, convertFileSrc } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const paths = window.__TAURI__.path;
const win = window.__TAURI__.window.getCurrentWindow();

const $ = (id) => document.getElementById(id);
const el = (tag, className = '', props = {}) => Object.assign(document.createElement(tag), className ? { className } : {}, props);
document.documentElement.classList.add('js');
const icon = (name) => {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'i');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = `<use href="#i-${name}"/>`;
  return svg;
};
const pref = (k, d) => { try { return localStorage.getItem(k) ?? d } catch { return d } };
const keep = (k, v) => { try { localStorage.setItem(k, typeof v === 'string' ? v : JSON.stringify(v)) } catch {} };
const list = (k) => { try { return JSON.parse(pref(k, '[]')) } catch { return [] } };
const low = (p) => p.toLowerCase();
const base = (p) => p.split(/[\\/]/).pop();
const size = (b) => (b >= 1073741824 ? `${(b / 1073741824).toFixed(1)} GB` : b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${b ? Math.max(1, Math.round(b / 1024)) : 0} KB`);
const clock = (s) => (Number.isFinite(s) ? `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}` : '');
const day = (ms) => new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
const under = (dir, root) => low(dir) === low(root) || low(dir).startsWith(low(root).replace(/\\$/, '') + '\\');
const OOPS = { blocked: 'Windows blocked that.', taken: 'That name is taken.', name: "Windows can't use that name.", gone: 'That file is gone.', outside: "That's outside your gallery folders.", restore: "Couldn't bring it back. Check the Recycle Bin.", root: "A whole drive can't be added. Pick a folder on it.", system: 'Windows and apps keep that folder for themselves, so it stays out.', nodrive: "Google Drive isn't set up in this build.", login: 'Google Drive needs you to connect again.', offline: "Couldn't reach Google Drive.", denied: 'Google Drive was not connected.', timeout: 'Sign-in took too long. Try again.', busy: 'A backup is already running.', code: "That code doesn't match. Check that the clocks on your phone and PC are right.", used: 'That code was just used. Wait for the next one.', wait: 'Too many wrong codes. Try again in a minute.', expired: 'Setup timed out. Start again.', off: 'No authenticator is linked.', into: "A folder can't go inside itself.", drive: 'Folders move only within the same drive.', added: 'Added folders, and folders holding one, stay where they are.' };

let lib = { roots: [], items: [], folders: [], saves: '', backed: [] };
const ui = {
  view: 'all', folder: null, tag: null, kind: 'all', query: '', sort: pref('g-sort', 'new'), deep: pref('g-deep', '1') === '1',
  zoom: +pref('g-zoom', '180'), hover: pref('g-hover', 'play'), months: pref('g-months', 'on'), pinMode: pref('g-pinmode', 'all'),
  pins: list('g-pins'), unpins: list('g-unpins'), open: new Set(list('g-open')),
  shown: [], drawn: 0, sel: new Set(), anchor: -1, dups: null, backed: new Set(),
  layout: pref('g-layout', 'grid'), sideShut: pref('g-side', 'open') === 'shut', peekShut: pref('g-peek', 'open') === 'shut',
  // Width over height of each picture seen, for the Pinterest layout.
  ratios: new Map(),
  quality: pref('g-quality', 'sharp'), hd: pref('g-hd', 'on') === 'on',
};
// Motion and theme follow the panel's own settings, which share this storage.
const still = () => pref('motion', 'on') === 'off';
document.documentElement.classList.toggle('still', still());
const paintTheme = () => (document.documentElement.dataset.theme = pref('theme', 'system'));
paintTheme();
addEventListener('storage', (e) => { if (e.key === 'theme') paintTheme() });

// Changes what the window shows inside a View Transition, or at once with motion off.
function swap(update) {
  if (still() || !document.startViewTransition) return Promise.resolve(update());
  const t = document.startViewTransition(update);
  t.ready.catch(() => {}); // a newer transition skips this one
  return t.finished.catch(() => {});
}

// Data --------------------------------------------------------------------------------------------

// Lookups the window makes many times per paint, built once per load.
let byPath = new Map(), kidsOf = new Map(), lockedIn = new Set();
function index() {
  byPath = new Map(lib.items.map((i) => [i.path, i]));
  kidsOf = new Map();
  for (const f of lib.folders) if (f.parent) kidsOf.set(f.parent, [...(kidsOf.get(f.parent) || []), f]);
  // Folders with a locked file anywhere under them.
  lockedIn = new Set();
  const dirs = new Map(lib.folders.map((f) => [low(f.path), f]));
  for (const i of lib.items) {
    if (i.kind !== 'locked') continue;
    for (let d = dirs.get(low(i.dir)); d && !lockedIn.has(d.path); d = d.parent && dirs.get(low(d.parent))) lockedIn.add(d.path);
  }
}

// Whether a reload found what the window already shows, so it can skip repainting.
const same = (a, b) => JSON.stringify([a.roots, a.folders, a.backed]) === JSON.stringify([b.roots, b.folders, b.backed])
  && a.items.length === b.items.length
  && a.items.every((x, n) => { const y = b.items[n]; return x.path === y.path && x.modified === y.modified && x.bytes === y.bytes && x.fav === y.fav && x.blur === y.blur && x.tags.join() === y.tags.join() });

let loading = null;
async function load() {
  let changed = false;
  loading ??= invoke('gallery').then((l) => {
    if (same(lib, l)) return;
    changed = true;
    lib = l;
    index();
    ui.backed = new Set(l.backed);
    for (const p of [...ui.sel]) if (!byPath.has(p)) ui.sel.delete(p);
    if (ui.view === 'folder' && !lib.folders.some((f) => f.path === ui.folder)) ui.view = 'all';
  }).finally(() => (loading = null));
  await loading;
  // A reload after a change keeps the grid where it was scrolled.
  if (changed || !booted) paint(booted);
  booted = true;
}
let booted = false;

const isRoot = (p) => lib.roots.some((r) => low(r) === low(p));
const pinned = (p) => (ui.pinMode === 'all' && isRoot(p) ? !ui.unpins.includes(low(p)) : ui.pins.includes(low(p)));
function togglePin(p) {
  const k = low(p);
  if (ui.pinMode === 'all' && isRoot(p)) ui.unpins = ui.unpins.includes(k) ? ui.unpins.filter((x) => x !== k) : [...ui.unpins, k];
  else ui.pins = ui.pins.includes(k) ? ui.pins.filter((x) => x !== k) : [...ui.pins, k];
  keep('g-pins', ui.pins);
  keep('g-unpins', ui.unpins);
  paintSide();
}

function scope() {
  let items = lib.items;
  if (ui.view === 'fav') items = items.filter((i) => i.fav);
  else if (ui.view === 'recent') items = items.filter((i) => i.modified >= Date.now() - 30 * 864e5);
  else if (ui.view === 'folder') items = items.filter((i) => (ui.deep ? under(i.dir, ui.folder) : low(i.dir) === low(ui.folder)));
  else if (ui.view === 'tag') items = items.filter((i) => i.tags.includes(ui.tag));
  else if (ui.view === 'from') items = items.filter((i) => sourceOf(i.name)?.app === ui.from);
  else if (ui.view === 'dups') {
    const order = new Map((ui.dups || []).flat().map((p, n) => [p, n]));
    items = items.filter((i) => order.has(i.path)).sort((a, b) => order.get(a.path) - order.get(b.path));
  }
  if (ui.kind !== 'all') items = items.filter((i) => i.kind === ui.kind);
  const words = ui.query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length) items = items.filter((i) => words.every((w) => low(i.name).includes(w) || i.tags.some((t) => low(t).includes(w)) || low(base(i.dir)).includes(w)));
  if (ui.view === 'dups') return items;
  const by = { new: (a, b) => b.modified - a.modified, old: (a, b) => a.modified - b.modified, name: (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }), size: (a, b) => b.bytes - a.bytes };
  return [...items].sort(by[ui.sort]);
}

// Sidebar -----------------------------------------------------------------------------------------

function folderRow(f, depth, tree) {
  const kids = tree ? kidsOf.get(f.path) || [] : [];
  const row = el('button', `row${ui.view === 'folder' && ui.folder === f.path ? ' on' : ''}`, { type: 'button', title: f.path });
  row.style.setProperty('--depth', depth);
  const twist = el('span', `twist${kids.length ? (ui.open.has(f.path) ? '' : ' shut') : ' none'}`);
  twist.append(icon('down'));
  twist.onclick = (e) => {
    e.stopPropagation();
    ui.open.has(f.path) ? ui.open.delete(f.path) : ui.open.add(f.path);
    keep('g-open', [...ui.open]);
    paintSide();
  };
  const pin = el('span', `pin mini${pinned(f.path) ? ' on' : ''}`, { title: pinned(f.path) ? 'Unpin' : 'Pin to the top' });
  pin.append(icon('pin'));
  pin.onclick = (e) => { e.stopPropagation(); togglePin(f.path) };
  const marks = el('span', 'marks');
  if (f.blur) marks.append(icon('eye-off'));
  if (lockedIn.has(f.path)) marks.append(icon('lock'));
  row.append(...(tree ? [twist] : []), icon('folder'), el('span', 'name', { textContent: isRoot(f.path) ? base(f.path) || f.path : f.name }), marks, el('em', '', { textContent: f.total || '' }), pin);
  row.onclick = () => go({ view: 'folder', folder: f.path });
  row.oncontextmenu = (e) => { e.preventDefault(); folderMenu(f, e.clientX, e.clientY) };
  // Tiles dropped on a folder move there, or copy with Ctrl held. A folder dragged onto another
  // moves into it; added folders stay put.
  row.draggable = !isRoot(f.path);
  row.ondragstart = (e) => { e.dataTransfer.setData('text/lodisave-dir', f.path); e.dataTransfer.effectAllowed = 'move' };
  row.ondragover = (e) => {
    const files = e.dataTransfer.types.includes('text/lodisave'), dir = e.dataTransfer.types.includes('text/lodisave-dir');
    if (!files && !dir) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = files && e.ctrlKey ? 'copy' : 'move';
    row.classList.add('drop');
  };
  row.ondragleave = () => row.classList.remove('drop');
  row.ondrop = (e) => {
    e.preventDefault();
    row.classList.remove('drop');
    const dir = e.dataTransfer.getData('text/lodisave-dir');
    if (dir) moveFolder(dir, f.path);
    else place(JSON.parse(e.dataTransfer.getData('text/lodisave')), f.path, e.ctrlKey);
  };
  const out = [row];
  if (tree && ui.open.has(f.path)) for (const k of kids.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))) out.push(...folderRow(k, depth + 1, true));
  return out;
}

function paintSide() {
  for (const b of $('nav').querySelectorAll('[data-view]')) b.classList.toggle('on', ui.view === b.dataset.view);
  $('lock-now').hidden = !unlocked();
  const pins = lib.folders.filter((f) => pinned(f.path));
  $('pinned-head').hidden = !pins.length;
  $('pinned').replaceChildren(...pins.flatMap((f) => folderRow(f, 0, false)));
  // Every added folder is a top row, also one added inside another.
  $('tree').replaceChildren(...lib.roots.map((r) => lib.folders.find((f) => low(f.path) === low(r))).filter(Boolean).flatMap((f) => folderRow(f, 0, true)));
  const tags = new Map();
  for (const i of lib.items) for (const t of i.tags) tags.set(t, (tags.get(t) || 0) + 1);
  $('tags-head').hidden = !tags.size;
  $('tags').replaceChildren(...[...tags].sort((a, b) => a[0].localeCompare(b[0])).map(([t, n]) => {
    const b = el('button', ui.view === 'tag' && ui.tag === t ? 'on' : '', { type: 'button' });
    b.append(t, el('em', '', { textContent: n }));
    b.onclick = () => go({ view: 'tag', tag: t });
    return b;
  }));
  // Saved from: the apps LodiSave's own saves came from, named in their files.
  const apps = new Map();
  for (const i of lib.items) { const f = sourceOf(i.name); if (f) apps.set(f.app, (apps.get(f.app) || 0) + 1) }
  $('from-head').hidden = !apps.size;
  $('from').replaceChildren(...[...apps].sort((a, b) => b[1] - a[1]).map(([app, n]) => {
    const r = el('button', `row${ui.view === 'from' && ui.from === app ? ' on' : ''}`, { type: 'button', title: `Saved from ${app}` });
    r.append(el('span', 'app-mark', { textContent: app.slice(0, 2) }), el('span', 'name', { textContent: app }), el('em', '', { textContent: n }));
    r.onclick = () => go({ view: 'from', from: app });
    return r;
  }));
}

// Grid --------------------------------------------------------------------------------------------

const src = (p) => convertFileSrc(p);
// Windows' own thumbnail of a file, sized for the tiles on this screen. Tiles fall back to the file
// when Windows has none. The size is the long side, and a tile crops to a square that can stretch
// to almost twice the slider's width, so Sharp asks for enough to cover that crop at screen pixels.
const thumbSize = () => {
  const need = ui.zoom * devicePixelRatio * (ui.quality === 'fast' ? 1 : 2.2);
  return [256, 512, 768, 1024].find((s) => s >= need) || 1024;
};
const thumb = (r) => `${convertFileSrc(r.path, 'thumb')}?s=${thumbSize()}&m=${r.modified}`;
const CHUNK = 160;
// Videos get their file only once they scroll near, so a folder of clips doesn't load them all.
const near = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    near.unobserve(e.target);
    e.target.src = e.target.dataset.src;
  }
}, { root: $('scroll'), rootMargin: '600px' });
const more = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) drawMore() }, { root: $('scroll'), rootMargin: '1200px' });
more.observe($('more'));

// A tile's height in the Pinterest layout: as many 2 px rows as its picture is tall at the column
// width, plus the 6 px gap. Pictures not seen yet are square.
function fit(t, width = t.clientWidth) {
  if (ui.layout !== 'masonry') return;
  const r = ui.ratios.get(ui.shown[t.dataset.n]?.path) || 1;
  t.style.setProperty('--rows', Math.ceil((width / r + 6) / 2));
}
// Every tile in a box is as wide as the first: one read per box, then only writes.
function fitAll(from = 0) {
  if (ui.layout !== 'masonry') return;
  for (const box of $('grid').querySelectorAll('.tiles')) {
    const w = box.firstElementChild?.clientWidth || 0;
    for (const t of box.children) if (+t.dataset.n >= from) fit(t, w);
  }
}
const learn = (t, path, w, h) => {
  if (!w || !h) return;
  if (ui.ratios.has(path)) return fit(t);
  // Very tall or wide pictures are cut to 1:2.5 or 2.5:1, so one can't fill the screen.
  ui.ratios.set(path, Math.min(2.5, Math.max(0.4, w / h)));
  fit(t);
};

function tile(r, n) {
  const t = el('div', `tile${ui.sel.has(r.path) ? ' sel' : ''}${r.blur ? ' veil' : ''}`, { draggable: true, title: `${r.name}\n${size(r.bytes)}, ${day(r.modified)}` });
  t.dataset.n = n;
  t.style.setProperty('--k', Math.min(n - ui.drawn, 24));
  const badges = el('div', 'badges');
  if (r.kind === 'image' || r.kind === 'gif' || r.kind === 'video') {
    const img = el('img', '', { src: thumb(r), loading: 'lazy', decoding: 'async', alt: '' });
    img.onload = () => { img.classList.add('ready'); learn(t, r.path, img.naturalWidth, img.naturalHeight) };
    img.onerror = () => {
      if (r.kind !== 'video') { if (img.src !== src(r.path)) img.src = src(r.path); return }
      // No Windows thumbnail for this video (a codec Windows lacks): a still from the file itself.
      const v = el('video', '', { muted: true, preload: 'metadata', playsInline: true });
      v.dataset.src = `${src(r.path)}#t=0.5`;
      v.onloadedmetadata = () => learn(t, r.path, v.videoWidth, v.videoHeight);
      near.observe(v);
      img.replaceWith(v);
    };
    t.append(img);
    if (r.kind === 'gif') badges.append(el('span', '', { textContent: 'GIF' }));
    if (r.kind === 'video') { const len = el('span'); len.append(icon('play')); badges.append(len) }
    // Hovered, a video plays and a GIF moves, from the file; only that one tile pays for it.
    let live = null;
    t.onmouseenter = () => {
      if (ui.hover !== 'play' || r.blur || live || r.kind === 'image') return;
      const video = r.kind === 'video';
      live = el(video ? 'video' : 'img', 'live', video ? { src: src(r.path), muted: true, loop: true, autoplay: true, playsInline: true } : { src: src(r.path), alt: '' });
      const shown = live;
      shown.onloadeddata = shown.onload = () => shown.classList.add('ready');
      if (video) shown.onloadedmetadata = () => { const len = badges.firstChild; if (len && len.childNodes.length < 2) len.append(clock(shown.duration)) };
      t.insertBefore(shown, badges);
    };
    t.onmouseleave = () => {
      if (!live) return;
      live.removeAttribute('src');
      if (live.load) live.load(); // lets go of the file and the decoder
      live.remove();
      live = null;
    };
  } else if (r.kind === 'locked') {
    const g = el('div', 'glyph');
    g.append(icon('lock'));
    t.append(g);
    const tag = el('span');
    tag.append(icon('lock'), 'Locked');
    badges.append(tag);
    // Unlocked this session: the picture shows, from memory only. Big videos stay a lock till opened.
    if (unlocked() && r.bytes < 64 << 20) openLocked(r).then((o) => {
      if (!o) return;
      if (ui.sel.has(r.path)) { peekFor = null; paintPeek() }
      t.title = `${o.name}\n${t.title.split('\n')[1]}`;
      tag.lastChild.textContent = 'Unlocked';
      if (o.kind === 'image' || o.kind === 'gif') {
        const img = el('img', 'ready', { src: o.url, alt: '' });
        img.onload = () => learn(t, r.path, img.naturalWidth, img.naturalHeight);
        g.replaceWith(img);
      } else if (o.kind === 'video') g.replaceWith(el('video', '', { src: `${o.url}#t=0.5`, muted: true, preload: 'metadata' }));
    });
  } else {
    const g = el('div', 'glyph');
    g.append(icon('music'));
    t.append(g);
    badges.append(el('span', '', { textContent: r.name.split('.').pop().toUpperCase() }));
  }
  if (ui.backed.has(low(r.path))) { const c = el('span'); c.append(icon('cloud')); badges.append(c) }
  t.append(badges);
  if (r.fav) { const h = el('span', 'heart'); h.append(icon('heart')); t.append(h) }
  const tick = el('span', 'tick');
  tick.append(icon('check'));
  tick.onclick = (e) => { e.stopPropagation(); toggle(n, e.shiftKey) };
  t.append(tick);
  // A click picks and previews, as in File Explorer; Ctrl adds, Shift picks a range, and a double
  // click opens the viewer.
  t.onclick = (e) => {
    e.stopPropagation();
    if (e.ctrlKey || e.metaKey) toggle(n);
    else if (e.shiftKey) toggle(n, true);
    else only(n);
  };
  t.ondblclick = () => openViewer(n);
  t.oncontextmenu = (e) => {
    e.preventDefault();
    if (!ui.sel.has(r.path)) { ui.sel = new Set([r.path]); ui.anchor = n; paintSel() }
    tileMenu(r, e.clientX, e.clientY);
  };
  t.ondragstart = (e) => {
    const moving = ui.sel.has(r.path) ? [...ui.sel] : [r.path];
    e.dataTransfer.setData('text/lodisave', JSON.stringify(moving));
    e.dataTransfer.effectAllowed = 'copyMove';
  };
  return t;
}

const monthOf = (ms) => new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'long' });
let lastGroup = null;
function drawMore() {
  if (ui.drawn >= ui.shown.length) return;
  const grid = $('grid'), end = Math.min(ui.shown.length, ui.drawn + CHUNK);
  const byMonth = ui.months === 'on' && (ui.sort === 'new' || ui.sort === 'old') && ui.view !== 'dups';
  let box = grid.lastElementChild?.classList.contains('tiles') ? grid.lastElementChild : null;
  for (let n = ui.drawn; n < end; n++) {
    const r = ui.shown[n];
    const group = ui.view === 'dups' ? dupGroup(r.path) : byMonth ? monthOf(r.modified) : '';
    if (!box || group !== lastGroup) {
      if (group) grid.append(groupHead(group, n));
      box = el('div', `tiles${ui.layout === 'masonry' ? ' masonry' : ''}`);
      grid.append(box);
      lastGroup = group;
    }
    box.append(tile(r, n));
  }
  const from = ui.drawn;
  ui.drawn = end;
  fitAll(from);
}
// The column width changes with the window, the panels and the tile size.
let refit = 0;
new ResizeObserver(() => { cancelAnimationFrame(refit); refit = requestAnimationFrame(() => fitAll()) }).observe($('grid'));

function groupHead(label, from) {
  const h = el('div', 'group', { textContent: label });
  const pick = el('button', '', { type: 'button', textContent: ui.view === 'dups' ? 'Select extra copies' : 'Select' });
  pick.onclick = () => {
    const same = ui.shown.filter((r, n) => n >= from && (ui.view === 'dups' ? dupGroup(r.path) : monthOf(r.modified)) === label);
    // Of duplicates, the oldest copy stays unpicked.
    const picks = ui.view === 'dups' ? [...same].sort((a, b) => a.modified - b.modified).slice(1) : same;
    for (const r of picks) ui.sel.add(r.path);
    paintSel();
  };
  h.append(pick);
  return h;
}

const dupGroup = (p) => {
  const n = ui.dups.findIndex((g) => g.includes(p));
  const r = lib.items.find((i) => i.path === p);
  return `${ui.dups[n].length} copies, ${size(r?.bytes || 0)} each`;
};

function paintGrid(keep = false) {
  const at = keep ? $('scroll').scrollTop : 0;
  ui.shown = scope();
  ui.drawn = 0;
  lastGroup = null;
  $('grid').classList.toggle('calm', keep);
  $('grid').replaceChildren();
  $('scroll').scrollTop = 0;
  const empty = $('empty');
  empty.hidden = ui.shown.length > 0 && lib.roots.length > 0;
  if (!lib.roots.length) emptyStart(empty);
  else if (!ui.shown.length) empty.replaceChildren(el('h2', '', { textContent: ui.view === 'dups' ? (ui.dups ? 'No duplicates here' : 'Looking for duplicates…') : 'Nothing here' }), el('p', '', { textContent: ui.query ? 'Nothing matches your search.' : 'Photos and videos in this view show up here.' }));
  drawMore();
  if (keep) {
    // Drawn down to where the grid was, so it comes back to the same place without rising in again.
    while (ui.drawn < ui.shown.length && $('grid').scrollHeight < at + $('scroll').clientHeight) drawMore();
    $('scroll').scrollTop = at;
  }
}

function paint(keep = false) {
  const titles = { all: 'All media', fav: 'Favorites', recent: 'Last 30 days', dups: 'Duplicates' };
  const title = ui.view === 'folder' ? base(ui.folder) || ui.folder : ui.view === 'tag' ? `#${ui.tag}` : ui.view === 'from' ? `Saved from ${ui.from}` : titles[ui.view];
  if ($('title').textContent !== title) {
    $('title').textContent = title;
    $('title').classList.remove('swap');
    void $('title').offsetWidth; // restarts the animation
    $('title').classList.add('swap');
  }
  $('deep-wrap').hidden = ui.view !== 'folder';
  paintSide();
  paintGrid(keep);
  const total = ui.shown.reduce((n, r) => n + r.bytes, 0);
  $('sub').replaceChildren(
    ...(ui.view === 'folder' ? [el('span', '', { textContent: ui.folder, title: ui.folder })] : []),
    el('span', '', { textContent: `${ui.shown.length} ${ui.shown.length === 1 ? 'file' : 'files'}` }),
    el('span', '', { textContent: size(total) }),
  );
  paintSel();
}

function go(to) {
  Object.assign(ui, { tag: null, folder: null, from: null }, to);
  ui.sel.clear();
  if (ui.view === 'dups' && !ui.dups) findDups();
  swap(paint);
}

async function emptyStart(box) {
  const btn = (label, path) => {
    const b = el('button', `pill${path ? '' : ' primary'}`, { type: 'button', textContent: label });
    b.onclick = () => addFolder(path);
    return b;
  };
  const row = el('div', 'row-btns');
  row.append(btn('Pick a folder…'));
  for (const [label, path] of await suggestions()) row.append(btn(label, path));
  box.replaceChildren(
    el('h2', '', { textContent: 'Pick the folders the gallery can see' }),
    el('p', '', { textContent: 'LodiSave only reads folders you add here. Windows, Program Files, AppData and whole drives stay out, and deletes go to the Recycle Bin.' }),
    row,
  );
}

async function suggestions() {
  const out = [];
  const add = async (label, get) => { try { const p = await get(); if (p && !lib.roots.some((r) => under(p, r))) out.push([label, p]) } catch {} };
  await add('Pictures', paths.pictureDir);
  await add('Videos', paths.videoDir);
  await add('LodiSave saves', async () => lib.saves);
  await add('Desktop', paths.desktopDir);
  return out;
}

async function addFolder(path = null) {
  try {
    const root = await invoke('gallery_add', { path });
    if (!root) return;
    ui.open.add(root);
    keep('g-open', [...ui.open]);
    await load();
    toast(`Added ${base(root) || root}`);
  } catch (e) { oops(e) }
}

// Selection ---------------------------------------------------------------------------------------

function only(n) {
  ui.sel = new Set([ui.shown[n].path]);
  ui.anchor = n;
  paintSel();
}

function toggle(n, range = false) {
  const r = ui.shown[n];
  if (range && ui.anchor >= 0) {
    const [a, b] = [Math.min(ui.anchor, n), Math.max(ui.anchor, n)];
    for (let i = a; i <= b; i++) ui.sel.add(ui.shown[i].path);
  } else {
    ui.sel.has(r.path) ? ui.sel.delete(r.path) : ui.sel.add(r.path);
    ui.anchor = n;
  }
  paintSel();
}

function paintSel() {
  for (const t of $('grid').querySelectorAll('.tile')) t.classList.toggle('sel', ui.sel.has(ui.shown[t.dataset.n]?.path));
  // The preview panel holds the tools; the floating bar stands in while it is shut.
  const bar = ui.sel.size > 0 && peekShut();
  $('selbar').hidden = !bar;
  document.body.classList.toggle('has-selbar', bar);
  $('app').classList.toggle('picking', ui.sel.size > 1);
  $('sel-count').textContent = `${ui.sel.size} selected`;
  paintPeek();
}

const picked = () => [...ui.sel];
const item = (p) => byPath.get(p);

// Actions -----------------------------------------------------------------------------------------

let undoing = null;
function toast(text, undo = null) {
  $('toast-text').textContent = text;
  $('toast-undo').hidden = !undo;
  undoing = undo;
  $('toast').hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => ($('toast').hidden = true), undo ? 7000 : 3000);
}
$('toast-undo').onclick = async () => { $('toast').hidden = true; const u = undoing; undoing = null; try { await u?.() } catch (e) { oops(e) } await load() };
const oops = (e) => toast(OOPS[String(e)] || `Something went wrong: ${e}`);

async function fav(ps, on) {
  try { await invoke('g_mark', { paths: ps, what: 'fav', on }); await load() } catch (e) { oops(e) }
}

async function blur(ps, on) {
  try { await invoke('g_mark', { paths: ps, what: 'blur', on }); await load() } catch (e) { oops(e) }
}

async function trash(ps) {
  if (!ps.length) return;
  try {
    await invoke('g_trash', { paths: ps });
    ui.sel.clear();
    if (!$('viewer').hidden) dropFromViewer(ps);
    await load();
    toast(`${ps.length === 1 ? base(ps[0]) : `${ps.length} files`} went to the Recycle Bin`, () => invoke('g_restore', { paths: ps }));
  } catch (e) { oops(e) }
}

async function place(ps, dest, copy) {
  try {
    const was = ps.map((p) => item(p)?.dir);
    const done = await invoke('g_place', { paths: ps, dest, copy });
    ui.sel.clear();
    await load();
    const undo = copy ? () => invoke('g_trash', { paths: done }) : async () => {
      // Each file goes back to the folder it came from.
      const back = new Map();
      done.forEach((p, n) => was[n] && back.set(was[n], [...(back.get(was[n]) || []), p]));
      for (const [dir, files] of back) await invoke('g_place', { paths: files, dest: dir, copy: false });
    };
    toast(`${copy ? 'Copied' : 'Moved'} ${done.length} to ${base(dest)}`, undo);
  } catch (e) { oops(e) }
}

// Moves a folder into another; Undo moves it back to where it was.
async function moveFolder(from, dest) {
  const parent = lib.folders.find((f) => f.path === from)?.parent;
  try {
    const to = await invoke('g_move_dir', { path: from, dest });
    if (to === from) return;
    // Open, pinned and current folders follow it.
    const swapIn = (p) => (low(p) === low(from) || low(p).startsWith(low(from) + '\\') ? to + p.slice(from.length) : p);
    ui.open = new Set([...ui.open].map(swapIn));
    ui.open.add(dest);
    keep('g-open', [...ui.open]);
    ui.pins = ui.pins.map((p) => low(swapIn(p)));
    keep('g-pins', ui.pins);
    if (ui.folder) ui.folder = swapIn(ui.folder);
    await load();
    toast(`Moved ${base(from)} into ${base(dest) || dest}`, parent ? () => invoke('g_move_dir', { path: to, dest: parent }) : null);
  } catch (e) { oops(e) }
}

async function clip(ps) {
  try { await invoke('copy_files', { paths: ps }); toast(ps.length === 1 ? 'Copied. Paste it anywhere.' : `Copied ${ps.length} files. Paste them anywhere.`) } catch (e) { oops(e) }
}

async function backup(ps) {
  try {
    toast('Backing up to Google Drive…');
    const n = await invoke('drive_backup', { paths: ps });
    await load();
    toast(`Backed up ${n} to Google Drive`);
  } catch (e) { e === 'nodrive' || e === 'login' ? driveSheet() : oops(e) }
}

function act(what, ps = picked()) {
  const one = ps.length === 1 ? item(ps[0]) : null;
  ({
    fav: () => fav(ps, !ps.every((p) => item(p)?.fav)),
    blur: () => blur(ps, !ps.every((p) => item(p)?.blur)),
    tag: () => tagSheet(ps),
    move: () => folderSheet(ps, false),
    newwith: () => newFolderWith(ps),
    copyto: () => folderSheet(ps, true),
    rename: () => (one ? renameSheet(one) : batchSheet(ps)),
    drive: () => (driveReady ? backup(ps) : driveLater()),
    clip: () => clip(ps),
    trash: () => trash(ps),
    all: () => { for (const r of ui.shown) ui.sel.add(r.path); paintSel() },
    view: () => openViewer(ui.shown.findIndex((r) => r.path === ps[0])),
    none: () => { ui.sel.clear(); paintSel() },
    open: () => invoke('open_path', { path: ps[0] }).catch(oops),
    reveal: () => invoke('reveal', { path: ps[0] }).catch(oops),
    path: () => invoke('copy_text', { text: ps.join('\r\n') }).then(() => toast('Path copied'), oops),
    wallpaper: () => invoke('g_wallpaper', { path: ps[0] }).then(() => toast('Set as your desktop background'), oops),
    lock: () => lockSheet(ps.map(item).filter((r) => r && r.kind !== 'locked'), ps.length === 1 ? item(ps[0])?.name : `${ps.length} files`),
    unlock: () => unlockSheet(ps.map(item).filter((r) => r?.kind === 'locked')),
    unlockgood: () => unlockForGood(ps.map(item).filter((r) => r?.kind === 'locked')),
    newpass: () => newPasswordSheet(ps.map(item).filter((r) => r?.kind === 'locked')),
  })[what]?.();
}
for (const b of $('selbar').querySelectorAll('[data-act]')) b.onclick = () => act(b.dataset.act);

// Menus -------------------------------------------------------------------------------------------

function menu(x, y, entries) {
  const m = $('menu');
  m.replaceChildren(...entries.filter(Boolean).map((e) => {
    if (e === '-') return el('hr');
    const b = el('button', `${e.bad ? 'bad' : ''}${e.ghost ? ' ghost' : ''}`, { type: 'button' });
    b.append(icon(e.icon), e.label);
    if (e.key) b.append(el('kbd', '', { textContent: e.key }));
    b.onclick = () => { m.hidden = true; e.run() };
    return b;
  }));
  m.hidden = false;
  const r = m.getBoundingClientRect();
  m.style.left = `${Math.min(x, innerWidth - r.width - 8)}px`;
  m.style.top = `${Math.min(y, innerHeight - r.height - 8)}px`;
}
addEventListener('pointerdown', (e) => { if (!$('menu').contains(e.target)) $('menu').hidden = true }, true);

const inFolder = (f) => lib.items.filter((i) => under(i.dir, f.path));

function tileMenu(r, x, y) {
  const ps = picked(), one = ps.length === 1, still = r.kind === 'image';
  const locked = ps.filter((p) => item(p)?.kind === 'locked'), plain = ps.length - locked.length;
  menu(x, y, [
    plain && { icon: 'lock', label: 'Lock with a password…', key: 'L', run: () => act('lock', ps) },
    locked.length && !locked.every((p) => opened.has(p)) && { icon: 'unlock', label: 'Unlock to view…', run: () => act('unlock', ps) },
    locked.length && { icon: 'unlock', label: 'Remove the password…', run: () => act('unlockgood', ps) },
    locked.length && { icon: 'lock', label: 'Set a new password…', run: () => act('newpass', ps) },
    '-',
    one && { icon: 'image', label: 'View', run: () => openViewer(ui.shown.indexOf(r)) },
    one && { icon: 'open', label: 'Open with its app', run: () => act('open', ps) },
    one && { icon: 'folder', label: 'Show in File Explorer', run: () => act('reveal', ps) },
    '-',
    { icon: 'heart', label: ps.every((p) => item(p)?.fav) ? 'Remove from favorites' : 'Favorite', key: 'F', run: () => act('fav', ps) },
    { icon: 'eye-off', label: ps.every((p) => item(p)?.blur) ? 'Unblur' : 'Blur', key: 'B', run: () => act('blur', ps) },
    { icon: 'tag', label: 'Tags…', key: 'T', run: () => act('tag', ps) },
    '-',
    { icon: 'edit', label: one ? 'Rename…' : 'Rename all…', key: 'F2', run: () => act('rename', ps) },
    { icon: 'move', label: 'Move to…', key: 'M', run: () => act('move', ps) },
    { icon: 'folder-plus', label: one ? 'Move to a new folder…' : `New folder with these ${ps.length}…`, key: 'N', run: () => act('newwith', ps) },
    { icon: 'folder', label: 'Copy to…', run: () => act('copyto', ps) },
    { icon: 'copy', label: 'Copy', key: 'Ctrl+C', run: () => act('clip', ps) },
    { icon: 'copy', label: 'Copy path', run: () => act('path', ps) },
    one && still && { icon: 'image', label: 'Set as desktop background', run: () => act('wallpaper', ps) },
    { icon: 'cloud', label: driveReady ? 'Back up to Google Drive' : 'Google Drive: later version', ghost: !driveReady, run: () => act('drive', ps) },
    '-',
    { icon: 'trash', label: 'Delete', key: 'Del', bad: true, run: () => act('trash', ps) },
  ]);
}

function folderMenu(f, x, y) {
  const root = isRoot(f.path);
  menu(x, y, [
    { icon: 'pin', label: pinned(f.path) ? 'Unpin' : 'Pin to the top', run: () => togglePin(f.path) },
    { icon: 'plus', label: 'New folder inside…', run: () => newFolderSheet(f.path) },
    !root && { icon: 'edit', label: 'Rename…', run: () => renameFolderSheet(f) },
    { icon: 'folder', label: 'Open in File Explorer', run: () => invoke('open_path', { path: f.path }).catch(oops) },
    '-',
    { icon: 'eye-off', label: f.blur ? 'Unblur folder' : 'Blur folder', run: () => blur([f.path], !f.blur) },
    { icon: 'lock', label: 'Lock folder with a password…', run: () => lockSheet(inFolder(f).filter((r) => r.kind !== 'locked'), base(f.path) || f.path) },
    inFolder(f).some((r) => r.kind === 'locked') && { icon: 'unlock', label: 'Unlock folder to view…', run: () => unlockSheet(inFolder(f).filter((r) => r.kind === 'locked')) },
    inFolder(f).some((r) => r.kind === 'locked') && { icon: 'unlock', label: 'Remove passwords in folder…', run: () => act('unlockgood', inFolder(f).filter((r) => r.kind === 'locked').map((r) => r.path)) },
    root && '-',
    root && { icon: 'close', label: 'Remove from gallery', bad: true, run: () => removeRoot(f.path) },
  ]);
}

async function removeRoot(p) {
  await invoke('gallery_remove', { path: p });
  await load();
  toast(`Removed ${base(p) || p} from the gallery. Its files stay where they are.`, () => invoke('gallery_add', { path: p }));
}

// Sheets ------------------------------------------------------------------------------------------

function sheet(...nodes) {
  $('sheet-body').replaceChildren(...nodes);
  if (!$('sheet').open) $('sheet').showModal();
  return $('sheet');
}
const close = () => $('sheet').close();
const button = (label, cls, run) => {
  const b = el('button', `pill ${cls}`, { type: 'button', textContent: label });
  b.onclick = run;
  return b;
};
const buttons = (...b) => { const d = el('div', 'btns'); d.append(...b); return d };
function seg(options, value, onPick) {
  const s = el('div', 'seg');
  for (const [v, label] of options) {
    const b = el('button', v === value ? 'on' : '', { type: 'button', textContent: label });
    b.onclick = () => { for (const o of s.children) o.classList.toggle('on', o === b); onPick(v) };
    s.append(b);
  }
  return s;
}

function renameSheet(r) {
  const stem = r.name.includes('.') ? r.name.slice(0, r.name.lastIndexOf('.')) : r.name;
  const input = el('input', '', { type: 'text', value: stem });
  const ok = async () => {
    try { await invoke('g_rename', { path: r.path, name: input.value }); close(); await load() } catch (e) { oops(e) }
  };
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(el('h2', '', { textContent: 'Rename' }), input, buttons(button('Cancel', '', close), button('Rename', 'primary', ok)));
  input.select();
}

// Several files at once, from a pattern: {name} the old name, {n} the count, {date} the file date.
function batchSheet(ps) {
  const items = ps.map(item).filter(Boolean).sort((a, b) => a.modified - b.modified);
  const input = el('input', '', { type: 'text', value: '{name}' });
  const start = el('input', '', { type: 'number', value: 1, min: 0 });
  const preview = el('div', 'preview');
  const name = (r, n) => {
    const stem = r.name.includes('.') ? r.name.slice(0, r.name.lastIndexOf('.')) : r.name;
    const d = new Date(r.modified), pad = (x) => String(x).padStart(2, '0');
    const width = String(items.length + +start.value).length;
    return input.value.replaceAll('{name}', stem).replaceAll('{n}', String(+start.value + n).padStart(width, '0')).replaceAll('{date}', `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`);
  };
  const show = () => (preview.textContent = items.slice(0, 8).map((r, n) => `${r.name}  →  ${name(r, n)}${r.name.includes('.') ? r.name.slice(r.name.lastIndexOf('.')) : ''}`).join('\n') + (items.length > 8 ? `\n… and ${items.length - 8} more` : ''));
  input.oninput = start.oninput = show;
  show();
  const ok = async () => {
    let failed = 0;
    for (const [n, r] of items.entries()) {
      try { await invoke('g_rename', { path: r.path, name: name(r, n) }) } catch { failed++ }
    }
    close();
    await load();
    toast(failed ? `Renamed ${items.length - failed}, ${failed} couldn't be` : `Renamed ${items.length} files`);
  };
  sheet(
    el('h2', '', { textContent: `Rename ${items.length} files` }),
    el('p', '', { textContent: 'Use {name} for the old name, {n} for a number and {date} for the file date. Oldest first.' }),
    input, el('h4', '', { textContent: 'Numbers start at' }), start, preview,
    buttons(button('Cancel', '', close), button('Rename all', 'primary', ok)),
  );
  input.select();
}

function tagSheet(ps) {
  const items = ps.map(item).filter(Boolean);
  const all = [...new Set(lib.items.flatMap((i) => i.tags))].sort();
  const have = [...new Set(items.flatMap((i) => i.tags))].sort();
  const input = el('input', '', { type: 'text', placeholder: 'New tags, comma between' });
  const current = el('div', 'chiprow');
  for (const t of have) {
    const b = el('button', '', { type: 'button', title: 'Remove' });
    b.append(t, icon('close'));
    b.onclick = async () => { await invoke('g_tag', { paths: ps, add: [], drop: [t] }); b.remove(); await load() };
    current.append(b);
  }
  const known = el('div', 'chiprow');
  for (const t of all.filter((t) => !have.includes(t))) {
    const b = el('button', '', { type: 'button', textContent: `+ ${t}` });
    b.onclick = async () => { await invoke('g_tag', { paths: ps, add: [t], drop: [] }); close(); await load() };
    known.append(b);
  }
  const ok = async () => {
    const add = input.value.split(',').map((t) => t.trim()).filter(Boolean);
    try { if (add.length) await invoke('g_tag', { paths: ps, add, drop: [] }); close(); await load() } catch (e) { oops(e) }
  };
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(
    el('h2', '', { textContent: ps.length === 1 ? 'Tags' : `Tags for ${ps.length} files` }),
    el('p', '', { textContent: 'Tags group files across folders without moving them.' }),
    ...(have.length ? [current] : []), input, ...(known.childElementCount ? [el('h4', '', { textContent: 'Your tags' }), known] : []),
    buttons(button('Done', 'primary', ok)),
  );
  input.focus();
}

// The gallery's own folders as a tree to pick one from. `on` is the picked path.
function folderTree(pick, on, onPick, onGo) {
  pick.replaceChildren(...(function rows(parent, depth) {
    return lib.folders.filter((f) => f.parent === parent).sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true })).flatMap((f) => {
      const b = el('button', `row${f.path === on ? ' on' : ''}`, { type: 'button', title: f.path });
      b.style.setProperty('--depth', depth);
      b.append(icon('folder'), f.parent ? f.name : f.path);
      b.onclick = () => onPick(f.path);
      b.ondblclick = onGo;
      return [b, ...rows(f.path, depth + 1)];
    });
  })(null, 0));
}

// A folder picker over the gallery's own folders, for Move and Copy.
function folderSheet(ps, copy) {
  let dest = ui.view === 'folder' ? ui.folder : null;
  const pick = el('div', 'picker');
  const paintPick = () => folderTree(pick, dest, (p) => { dest = p; paintPick() }, () => go2());
  paintPick();
  const name = el('input', '', { type: 'text', placeholder: 'New folder in the one picked' });
  const go2 = async () => {
    if (!dest) return toast('Pick a folder first');
    let to = dest;
    if (name.value.trim()) {
      try { to = await invoke('g_mkdir', { parent: dest, name: name.value }) } catch (e) { return oops(e) }
    }
    close();
    place(ps, to, copy);
  };
  sheet(
    el('h2', '', { textContent: `${copy ? 'Copy' : 'Move'} ${ps.length === 1 ? base(ps[0]) : `${ps.length} files`} to` }),
    pick, name,
    el('p', 'note', { textContent: 'Tip: drag files onto a folder on the left to move them, or hold Ctrl to copy.' }),
    buttons(button('Cancel', '', close), button(copy ? 'Copy here' : 'Move here', 'primary', go2)),
  );
}

// Type a name for a new folder, or pick a folder already there, as in LodiSave's save bar: the
// picked files move into it. The new folder goes where the files are, or in the folder picked below.
function newFolderWith(ps) {
  if (!ps.length) return;
  const dirs = [...new Set(ps.map((p) => item(p)?.dir).filter(Boolean))];
  const known = (d) => lib.folders.find((f) => low(f.path) === low(d))?.path;
  let parent = (dirs.length === 1 && known(dirs[0])) || (ui.view === 'folder' && ui.folder) || known(lib.roots[0] || '');
  const input = el('input', '', { type: 'text', placeholder: 'Type a new folder name, or find one' });
  const choices = el('div', 'picker choices');
  const where = el('div', 'picker', { hidden: true });
  const change = el('button', 'textlink', { type: 'button' });
  const make = async () => {
    const name = input.value.trim();
    if (!name) return input.focus();
    if (!parent) return toast('Pick where the folder goes');
    let to;
    try { to = await invoke('g_mkdir', { parent, name }) } catch (e) { return oops(e) }
    ui.open.add(parent);
    keep('g-open', [...ui.open]);
    close();
    place(ps, to, false);
  };
  const into = (dest) => { close(); place(ps, dest, false) };
  const paint2 = () => {
    const q = low(input.value.trim());
    change.textContent = `Makes it in ${parent ? base(parent) || parent : '…'}. Change`;
    const rows = [];
    if (q) {
      const b = el('button', 'row on', { type: 'button' });
      b.append(icon('folder-plus'), `New folder “${input.value.trim()}”`);
      b.onclick = make;
      rows.push(b);
    }
    // Folders already there whose name holds what was typed, the closest first.
    const found = lib.folders.filter((f) => !q || low(f.name).includes(q)).sort((a, b) => low(a.name).indexOf(q) - low(b.name).indexOf(q) || a.name.length - b.name.length).slice(0, 8);
    for (const f of found) {
      const b = el('button', 'row', { type: 'button', title: f.path });
      b.append(icon('folder'), el('span', 'name', { textContent: f.parent ? f.name : f.path }), el('em', '', { textContent: f.parent ? base(f.parent) : '' }));
      b.onclick = () => into(f.path);
      rows.push(b);
    }
    choices.replaceChildren(...rows);
    folderTree(where, parent, (p) => { parent = p; paint2() }, make);
  };
  input.oninput = paint2;
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); make() } };
  change.onclick = () => { where.hidden = !where.hidden };
  paint2();
  sheet(
    el('h2', '', { textContent: ps.length === 1 ? `Move ${base(ps[0])} to a new folder` : `New folder with ${ps.length} files` }),
    input, change, where, choices,
    buttons(button('Cancel', '', close), button('Make and move', 'primary', make)),
  );
  input.focus();
}

function newFolderSheet(parent) {
  const input = el('input', '', { type: 'text', placeholder: 'Folder name' });
  const ok = async () => {
    try { const d = await invoke('g_mkdir', { parent, name: input.value }); ui.open.add(parent); keep('g-open', [...ui.open]); close(); await load(); toast(`Made ${base(d)}. It shows here once it has photos or videos.`) } catch (e) { oops(e) }
  };
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(el('h2', '', { textContent: `New folder in ${base(parent) || parent}` }), input, buttons(button('Cancel', '', close), button('Make', 'primary', ok)));
  input.focus();
}

function renameFolderSheet(f) {
  const input = el('input', '', { type: 'text', value: f.name });
  const ok = async () => {
    try {
      const to = await invoke('g_rename', { path: f.path, name: input.value });
      const k = low(f.path);
      ui.pins = ui.pins.map((p) => (p === k ? low(to) : p));
      keep('g-pins', ui.pins);
      if (ui.folder === f.path) ui.folder = to;
      close();
      await load();
    } catch (e) { oops(e) }
  };
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(el('h2', '', { textContent: 'Rename folder' }), input, buttons(button('Cancel', '', close), button('Rename', 'primary', ok)));
  input.select();
}

// Settings: sections in a list on the left, and on the right each setting as a row with its name
// on the left and its control on the right. The search box filters rows across every section.
const PAGES = [
  ['general', 'General', 'prefs'],
  ['folders', 'Folders', 'folder'],
  ['recovery', 'Password recovery', 'lock'],
  ['windows', 'Windows permissions', 'shield'],
  ['repair', 'Repair videos', 'refresh'],
];
let prefsPage = 'general';

const prow = (label, note, ...controls) => {
  const r = el('div', 'prow');
  const t = el('div', 'ptext');
  t.append(el('span', '', { textContent: label }), ...(note ? [el('small', '', { textContent: note })] : []));
  r.append(t, ...controls);
  return r;
};
const psec = (title, ...rows) => { const s = el('section', 'psec'); s.append(el('h3', '', { textContent: title }), ...rows); return s };
function switcher(on, onChange) {
  const b = el('button', `switch${on ? ' on' : ''}`, { type: 'button' });
  b.setAttribute('role', 'switch');
  b.setAttribute('aria-checked', String(on));
  b.onclick = () => { on = !on; b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)); onChange(on) };
  return b;
}
function choose(options, value, onChange) {
  const w = el('label', 'choose');
  const sel = el('select');
  for (const [v, label] of options) sel.append(el('option', '', { value: v, textContent: label }));
  sel.value = value;
  sel.onchange = () => onChange(sel.value);
  w.append(sel, icon('down'));
  return w;
}

async function prefsSections(page) {
  if (page === 'general') return [
    psec('Appearance',
      prow('Theme', 'Shared with the tray panel.', choose([['system', 'Same as Windows'], ['light', 'Light'], ['dark', 'Dark']], pref('theme', 'system'), (v) => { keep('theme', v); paintTheme() })),
      prow('Motion', 'Shared with the tray panel.', switcher(pref('motion', 'on') !== 'off', (on) => { keep('motion', on ? 'on' : 'off'); document.documentElement.classList.toggle('still', !on) })),
    ),
    psec('Grid',
      prow('Videos and GIFs', 'What a tile does under the pointer.', choose([['play', 'Play on hover'], ['still', 'Stay still']], ui.hover, (v) => { ui.hover = v; keep('g-hover', v) })),
      prow('Group by month', 'With newest or oldest first.', switcher(ui.months === 'on', (on) => { ui.months = on ? 'on' : 'off'; keep('g-months', ui.months); paintGrid() })),
      prow('Pinned folders', 'Pinned folders sit at the top of the sidebar.', choose([['all', 'Every added folder'], ['pick', 'Only the ones I pin']], ui.pinMode, (v) => { ui.pinMode = v; keep('g-pinmode', v); paintSide() })),
      prow('Tile sharpness', 'Sharp asks Windows for bigger thumbnails, so tiles look like the saved file. Fast loads big folders quicker.', choose([['sharp', 'Sharp'], ['fast', 'Fast']], ui.quality, (v) => { ui.quality = v; keep('g-quality', v); paintGrid(true) })),
    ),
    psec('Viewer',
      prow('Enhance small photos', 'Photos shown bigger than they were saved are redrawn at screen size and sharpened. The HD button in the viewer does it for one photo. It sharpens edges; it can\'t add detail the file never had.', switcher(ui.hd, (on) => { ui.hd = on; keep('g-hd', on ? 'on' : 'off') })),
    ),
  ];
  if (page === 'folders') {
    const rows = lib.roots.map((r) => {
      const rm = el('button', 'pill small', { type: 'button', textContent: 'Remove' });
      rm.onclick = async () => { await removeRoot(r); settingsSheet() };
      return prow(base(r) || r, r, rm);
    });
    const add = el('button', 'pill small primary', { type: 'button', textContent: 'Add…' });
    add.onclick = async () => { await addFolder(); settingsSheet() };
    const quick = el('div', 'chiprow');
    for (const [label, p] of await suggestions()) {
      const b = el('button', '', { type: 'button', textContent: `+ ${label}`, title: p });
      b.onclick = async () => { await addFolder(p); settingsSheet() };
      quick.append(b);
    }
    return [psec('Folders the gallery can see',
      prow('Add a folder', 'Add as many as you like, also one inside another. Each shows as its own folder in the sidebar.', add),
      ...(quick.childElementCount ? [prow('Suggested', '', quick)] : []),
      ...rows,
      prow('What stays out', "Whole drives, Windows, Program Files, ProgramData and AppData can't be added, links aren't followed, files only in OneDrive's cloud aren't downloaded, and deletes go to the Recycle Bin."),
    )];
  }
  if (page === 'recovery') return [psec('Password recovery', prow('Authenticator app', 'A 6-digit code opens a locked file when its password is forgotten.'), wide(await recoveryBox()))];
  if (page === 'windows') return [psec('Windows permissions', wide(await permissionsBox()))];
  const status = el('small', 'note');
  const go2 = el('button', 'pill small primary', { type: 'button', textContent: 'Repair' });
  go2.onclick = async () => {
    go2.disabled = true;
    const { fixed, failed, checked } = await repairVideos((t) => (status.textContent = t));
    status.textContent = !checked ? 'No MP4 videos in your folders.' : `Checked ${checked}. ${fixed ? `Repaired ${fixed}.` : 'Nothing needed fixing.'}${failed ? ` ${failed} couldn't be (Windows blocked it, or the file is in use).` : ''}`;
    go2.disabled = false;
    if (fixed) load();
  };
  const r = prow('Repair videos saved by older versions', 'Fixes sound that runs ahead of or behind the picture, and clips that repeat, in videos LodiSave saved from streaming sites before. Each original goes to the Recycle Bin.', go2);
  r.querySelector('.ptext').append(status);
  return [psec('Repair videos', r)];
}
const wide = (node) => { const r = el('div', 'prow wide'); r.append(node); return r };

async function settingsSheet(page = prefsPage) {
  if ($('sheet').open) close();
  prefsPage = page;
  const q = $('prefs-q').value.trim().toLowerCase();
  $('prefs-nav-list').replaceChildren(...PAGES.map(([id, label, ic]) => {
    const b = el('button', !q && id === prefsPage ? 'on' : '', { type: 'button' });
    b.append(icon(ic), label);
    b.onclick = () => { $('prefs-q').value = ''; settingsSheet(id) };
    return b;
  }));
  let sections;
  if (q) {
    sections = (await Promise.all(PAGES.map(([id]) => prefsSections(id)))).flat();
    for (const sec of sections) {
      const title = sec.querySelector('h3').textContent.toLowerCase();
      for (const r of sec.querySelectorAll('.prow')) r.hidden = !title.includes(q) && !r.textContent.toLowerCase().includes(q);
      sec.hidden = ![...sec.querySelectorAll('.prow')].some((r) => !r.hidden);
    }
    if (sections.every((x) => x.hidden)) sections.push(el('p', 'note', { textContent: 'No settings match.' }));
  } else sections = await prefsSections(prefsPage);
  $('prefs-page').replaceChildren(...sections);
  if (!$('prefs').open) $('prefs').showModal();
}
$('prefs-close').onclick = () => $('prefs').close();
let prefsTyping;
$('prefs-q').oninput = () => { clearTimeout(prefsTyping); prefsTyping = setTimeout(() => settingsSheet(), 120) };
// A click on the dim area around the dialog closes it.
$('prefs').onclick = (e) => { if (e.target === $('prefs')) $('prefs').close() };

// Videos the extension joined before its timeline fix (0.3.2) can have sound out of step with the
// picture or repeated clips. Each fragmented MP4 in the folders is joined again with the current
// joiner; files it doesn't change are left alone.
async function repairVideos(say) {
  ui.sel.clear();
  paintSel(); // the preview lets go of a video it was playing
  const vids = lib.items.filter((i) => i.kind === 'video' && /\.(mp4|m4v)$/i.test(i.name) && i.bytes <= MAX_LOCK);
  let fixed = 0, failed = 0;
  for (const [n, r] of vids.entries()) {
    say(`Checking ${n + 1} of ${vids.length}…`);
    try {
      // Its first 256 KB tell whether it is fragmented MP4, the only kind LodiSave joined.
      const head = new Uint8Array(await (await fetch(src(r.path), { headers: { Range: 'bytes=0-262143' } })).arrayBuffer());
      if (!LodiMP4.fragmented(head)) continue;
      // Windows' asset server may send the whole file instead of the range asked for.
      const out = LodiMP4.repair(head.length === r.bytes ? head : await readFile(r.path));
      if (!out) continue;
      await invoke('g_replace', out, { headers: { path: ascii(r.path) } });
      fixed++;
    } catch { failed++ }
  }
  return { fixed, failed, checked: vids.length };
}

// A build without Google's sign-in client for Drive shows Drive faded, as coming in a later version.
let driveReady = true;
invoke('drive_status').then((s) => {
  driveReady = s.ready;
  $('open-drive').classList.toggle('ghost', !driveReady);
  $('open-drive').querySelector('.later').hidden = driveReady;
  for (const b of document.querySelectorAll('[data-act="drive"]')) b.classList.toggle('ghost', !driveReady);
  peekFor = null;
  paintPeek();
}).catch(() => {});
const driveLater = () => toast('Google Drive backup on PC comes in a later version. It works in LodiSave for Android now.');

async function driveSheet() {
  if (!driveReady) return driveLater();
  let s;
  try { s = await invoke('drive_status') } catch (e) { return oops(e) }
  const rules = { ...s.rules };
  const save = () => invoke('drive_rules', { rules });
  const head = el('p', s.connected ? 'ok' : '', { textContent: !s.ready ? "Google Drive isn't set up in this build of LodiSave." : s.connected ? `Connected. ${s.backed} backed up${s.waiting ? `, ${s.waiting} waiting` : ''}.` : 'Not connected' });
  const meter = el('div', 'meter', { hidden: !s.busy });
  meter.append(el('span'));
  const connect = button(s.connected ? 'Disconnect' : 'Connect Google Drive', s.connected ? '' : 'primary', async () => {
    if (s.connected) { await invoke('drive_disconnect'); return driveSheet() }
    head.textContent = 'Finish signing in in your browser…';
    try { await invoke('drive_connect'); driveSheet() } catch (e) { oops(e); driveSheet() }
  });
  connect.disabled = !s.ready;
  const days = el('input', '', { type: 'number', min: 1, max: 3650, value: rules.days });
  days.onchange = () => { rules.days = Math.max(1, +days.value || 30); save() };
  const date = el('input', '', { type: 'date', value: new Date((rules.before || Date.now() / 1000) * 1000).toISOString().slice(0, 10) });
  date.onchange = () => { rules.before = Math.floor(new Date(date.value).getTime() / 1000) || 0; save() };
  const free = el('input', '', { type: 'number', min: 0, max: 3650, value: Math.max(rules.free, 0) || 30 });
  free.onchange = () => { rules.free = Math.max(0, +free.value || 0); save() };
  const ruleBox = el('div');
  const paintRule = () => ruleBox.replaceChildren(...(rules.rule === 'days' ? [el('p', '', { textContent: 'Back up photos and videos older than this many days:' }), days] : rules.rule === 'date' ? [el('p', '', { textContent: 'Back up photos and videos from before:' }), date] : []));
  paintRule();
  const freeBox = el('div', '', { hidden: rules.free < 0 });
  freeBox.append(el('p', '', { textContent: 'Days after backup when a file leaves this PC:' }), free);
  const now = button('Back up now', 'primary', async () => {
    meter.hidden = false;
    try { const n = await invoke('drive_backup', { paths: [] }); toast(`Backed up ${n} to Google Drive`); await load() } catch (e) { oops(e) }
    driveSheet();
  });
  now.disabled = !s.connected || s.busy;
  sheet(
    el('h2', '', { textContent: 'Google Drive backup' }),
    el('p', '', { textContent: 'Backs up into a LodiSave folder in your Drive. LodiSave can only see files it put there.' }),
    head, meter, buttons(connect),
    el('h4', '', { textContent: 'Back up on its own' }),
    seg([['off', 'Off'], ['days', 'By age'], ['date', 'Before a date']], rules.rule, (v) => { rules.rule = v; paintRule(); save() }),
    ruleBox,
    el('h4', '', { textContent: 'Free up space' }),
    seg([['off', 'Off'], ['on', 'On']], rules.free < 0 ? 'off' : 'on', (v) => { rules.free = v === 'on' ? Math.max(0, +free.value || 30) : -1; freeBox.hidden = v !== 'on'; save() }),
    freeBox,
    el('p', 'note', { textContent: 'A backed-up file goes to the Recycle Bin only if it is still the same size Drive got. Select files and press Back up to send them now.' }),
    buttons(button('Close', '', close), now),
  );
}
listen('drive://progress', (e) => {
  const m = $('sheet-body').querySelector('.meter');
  if (!m) return;
  m.hidden = !!e.payload.end;
  m.firstChild.style.transform = `scaleX(${e.payload.total ? e.payload.done / e.payload.total : 0.05})`;
});

async function findDups() {
  ui.dups = null;
  try { ui.dups = await invoke('g_duplicates', { paths: lib.items.map((i) => i.path) }) } catch (e) { ui.dups = []; oops(e) }
  if (ui.view === 'dups') paint();
}

// Locks -------------------------------------------------------------------------------------------
// A locked file is encrypted here. Each lock run makes a random 256-bit file key; the file's name
// and bytes are sealed with it (AES-GCM), and the key itself is sealed twice: with the password
// (PBKDF2-SHA256 at 600,000 rounds, then AES-GCM) and, when an authenticator is linked, with the
// recovery public key (RSA-OAEP). The file is "LODILOCK", version 2, a flags byte (1: the recovery
// key can open it), the salt, the password's IV and sealed key, the recovery key's length and sealed
// key, the data IV and the sealed name and bytes. Passwords and the recovery key opened this session
// stay in memory until Lock again or the window closes; nothing unlocked touches disk unless the
// password is removed for good.

const MAGIC = 'LODILOCK', ROUNDS = 600000;
// ponytail: whole files go through memory, so files over 1 GB aren't locked; chunked sealing lifts that.
const MAX_LOCK = 1 << 30;
const RSA = { name: 'RSA-OAEP', hash: 'SHA-256' };
const sessions = [];
let recoveryKey = null;
const unlocked = () => sessions.length > 0 || !!recoveryKey;
const keys = new Map();
const opened = new Map();
const MIME = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', avif: 'image/avif', bmp: 'image/bmp', svg: 'image/svg+xml', mp4: 'video/mp4', m4v: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm', mkv: 'video/x-matroska', mp3: 'audio/mpeg', m4a: 'audio/mp4', aac: 'audio/aac', ogg: 'audio/ogg', opus: 'audio/ogg', wav: 'audio/wav', flac: 'audio/flac', weba: 'audio/webm' };
const hex = (b) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
// Headers of raw-body commands must be ASCII; JSON's \u escapes keep any name.
const ascii = (s) => JSON.stringify(s).replace(/[\u007f-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
const readFile = async (p) => new Uint8Array(await (await fetch(src(p))).arrayBuffer());
const aes = (op, key, iv, data) => crypto.subtle[op]({ name: 'AES-GCM', iv }, key, data).then((b) => new Uint8Array(b));
const fileKey = (raw) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
const random = (n) => crypto.getRandomValues(new Uint8Array(n));

function keyFor(password, salt) {
  const k = `${hex(salt)}\0${password}`;
  if (!keys.has(k)) {
    keys.set(k, crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey'])
      .then((raw) => crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations: ROUNDS, hash: 'SHA-256' }, raw, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])));
  }
  return keys.get(k);
}

// The recovery public key new locks also seal their key with, or null when no authenticator is linked.
async function recoveryPublic() {
  const b = await invoke('recovery_public').catch(() => null);
  return b ? crypto.subtle.importKey('spki', new Uint8Array(b), RSA, false, ['encrypt']) : null;
}

function pack({ salt, ivP, wP, wR, ivD, data }) {
  const n = wR.length, out = new Uint8Array(100 + n + data.length);
  out.set(new TextEncoder().encode(MAGIC));
  out[8] = 2;
  out[9] = n ? 1 : 0;
  out.set(salt, 10);
  out.set(ivP, 26);
  out.set(wP, 38);
  new DataView(out.buffer).setUint16(86, n);
  out.set(wR, 88);
  out.set(ivD, 88 + n);
  out.set(data, 100 + n);
  return out;
}

function parse(b) {
  if (b.length < 100 || new TextDecoder().decode(b.subarray(0, 8)) !== MAGIC || b[8] !== 2) return null;
  const n = new DataView(b.buffer, b.byteOffset).getUint16(86);
  return { salt: b.slice(10, 26), ivP: b.slice(26, 38), wP: b.slice(38, 86), wR: b.slice(88, 88 + n), ivD: b.slice(88 + n, 100 + n), data: b.subarray(100 + n) };
}

async function seal(bytes, name, password, salt, pub) {
  const k = random(32), title = new TextEncoder().encode(name);
  const plain = new Uint8Array(4 + title.length + bytes.length);
  new DataView(plain.buffer).setUint32(0, title.length);
  plain.set(title, 4);
  plain.set(bytes, 4 + title.length);
  const ivD = random(12), ivP = random(12);
  return pack({
    salt, ivP, ivD,
    data: await aes('encrypt', await fileKey(k), ivD, plain),
    wP: await aes('encrypt', await keyFor(password, salt), ivP, k),
    wR: pub ? new Uint8Array(await crypto.subtle.encrypt(RSA, pub, k)) : new Uint8Array(0),
  });
}

// What a locked file holds, opened with one of `passwords` or the recovery key, or null.
async function unseal(b, passwords, recovery = null) {
  const p = parse(b);
  if (!p) return null;
  let k = null, password = null;
  for (const pw of passwords) {
    try { k = await aes('decrypt', await keyFor(pw, p.salt), p.ivP, p.wP); password = pw; break } catch {}
  }
  if (!k && recovery && p.wR.length) {
    try { k = new Uint8Array(await crypto.subtle.decrypt(RSA, recovery, p.wR)) } catch {}
  }
  if (!k) return null;
  const plain = await aes('decrypt', await fileKey(k), p.ivD, p.data);
  const n = new DataView(plain.buffer).getUint32(0);
  return { name: new TextDecoder().decode(plain.subarray(4, 4 + n)), bytes: plain.subarray(4 + n), k, parts: p, password };
}

// A locked file opened with this session's passwords or recovery key, as a blob: URL in memory.
const opening = new Map();
function openLocked(r) {
  if (opened.has(r.path)) return Promise.resolve(opened.get(r.path));
  if (!unlocked()) return Promise.resolve(null);
  if (!opening.has(r.path)) {
    opening.set(r.path, readFile(r.path).then((b) => unseal(b, sessions, recoveryKey)).then((got) => {
      if (!got) return null;
      const ext = got.name.split('.').pop().toLowerCase(), type = MIME[ext] || '';
      const o = { url: URL.createObjectURL(new Blob([got.bytes], { type })), name: got.name, kind: ext === 'gif' ? 'gif' : type.split('/')[0] || 'image', bytes: got.bytes, k: got.k, parts: got.parts, byRecovery: !got.password };
      opened.set(r.path, o);
      return o;
    }).catch(() => null).finally(() => opening.delete(r.path)));
  }
  return opening.get(r.path);
}

// Forgets this session's passwords and recovery key: unlocked files are locked again.
function lockAgain() {
  sessions.length = 0;
  recoveryKey = null;
  keys.clear();
  for (const o of opened.values()) URL.revokeObjectURL(o.url);
  opened.clear();
  if (!$('viewer').hidden) closeViewer();
  peekFor = null;
  paint();
  toast('Locked again');
}

async function lockFiles(rs, password, say) {
  const salt = random(16), pub = await recoveryPublic();
  let done = 0, skipped = 0;
  for (const r of rs) {
    if (r.kind === 'locked' || r.bytes > MAX_LOCK) { skipped++; continue }
    say(`Locking ${done + skipped + 1} of ${rs.length}…`);
    let copy = null;
    try {
      const sealed = await seal(await readFile(r.path), r.name, password, salt, pub);
      copy = await invoke('g_lock_write', sealed, { headers: { path: ascii(r.path) } });
      // The original is deleted only once its locked copy reads back whole with the password.
      const check = await unseal(await readFile(copy), [password]);
      if (!check || check.bytes.length !== r.bytes) throw new Error('check');
      await invoke('g_lock_drop', { original: r.path, copy });
      done++;
    } catch {
      if (copy) await invoke('g_trash', { paths: [copy] }).catch(() => {});
      skipped++;
    }
  }
  if (!sessions.includes(password)) sessions.push(password);
  return { done, skipped };
}

function lockSheet(rs, label) {
  if (!rs.length) return toast('Nothing here to lock');
  const pass = el('input', '', { type: 'password', placeholder: 'Password', autocomplete: 'new-password' });
  const again = el('input', '', { type: 'password', placeholder: 'Type it again', autocomplete: 'new-password' });
  const status = el('p', 'note');
  const backup = el('p', 'note', { textContent: 'Checking for an authenticator…' });
  invoke('recovery_status').then((on) => {
    backup.className = on ? 'note ok' : 'warn';
    backup.textContent = on ? 'Your authenticator app can open these too, on this PC, if you forget the password.' : "If you forget the password, the files can't be opened. Link an authenticator in Settings › Password recovery first, and a code from your phone can open them instead.";
  });
  const ok = async () => {
    if (pass.value.length < 4) return (status.textContent = 'Use at least 4 characters.');
    if (pass.value !== again.value) return (status.textContent = "The passwords don't match.");
    for (const b of $('sheet-body').querySelectorAll('button')) b.disabled = true;
    const { done, skipped } = await lockFiles(rs, pass.value, (t) => (status.textContent = t));
    close();
    ui.sel.clear();
    await load();
    toast(skipped ? `Locked ${done}. ${skipped} couldn't be (over 1 GB, or Windows blocked it).` : `Locked ${done} ${done === 1 ? 'file' : 'files'}`);
  };
  again.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(
    el('h2', '', { textContent: `Lock ${label}` }),
    el('p', '', { textContent: `${rs.length === 1 ? 'The file is' : `All ${rs.length} files are`} encrypted and the unlocked ${rs.length === 1 ? 'original is' : 'originals are'} deleted. Other apps see only a scrambled .lodilock file.` }),
    backup, pass, again, status,
    buttons(button('Cancel', '', close), button('Lock', 'primary', ok)),
  );
  pass.focus();
}

// Asks for the password of `rs`, or a code from the authenticator, and keeps what opens them for
// this session.
function unlockSheet(rs) {
  return new Promise((done) => {
    const finish = () => { close(); peekFor = null; paint(); done(true) };
    const passStep = () => {
      const pass = el('input', '', { type: 'password', placeholder: 'Password', autocomplete: 'current-password' });
      const status = el('p', 'note');
      const ok = async () => {
        status.textContent = 'Checking…';
        const got = rs[0] && (await unseal(await readFile(rs[0].path), [pass.value]));
        if (!got) return (status.textContent = "That password doesn't open it.");
        if (!sessions.includes(pass.value)) sessions.push(pass.value);
        finish();
      };
      pass.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
      const forgot = el('button', 'textlink', { type: 'button', textContent: 'Forgot the password? Use your authenticator' });
      forgot.onclick = codeStep;
      sheet(
        el('h2', '', { textContent: rs.length === 1 ? 'Unlock to view' : `Unlock ${rs.length} files to view` }),
        el('p', '', { textContent: 'They open in LodiSave only, until you press Lock again or close the gallery. The files stay locked on disk.' }),
        pass, status, forgot,
        buttons(button('Cancel', '', () => { close(); done(false) }), button('Unlock', 'primary', ok)),
      );
      pass.focus();
    };
    const codeStep = async () => {
      if (!(await invoke('recovery_status'))) {
        return sheet(
          el('h2', '', { textContent: 'No authenticator linked' }),
          el('p', '', { textContent: 'Only the password can open this file. Link an authenticator in Settings › Password recovery, and files you lock from then on can also be opened with a code from your phone.' }),
          buttons(button('Back', '', passStep)),
        );
      }
      const input = el('input', 'code', { type: 'text', inputMode: 'numeric', autocomplete: 'one-time-code', maxLength: 7, placeholder: '123 456' });
      const status = el('p', 'note');
      const ok = async () => {
        status.textContent = 'Checking…';
        let key;
        try {
          key = await crypto.subtle.importKey('pkcs8', new Uint8Array(await invoke('recovery_unlock', { code: input.value })), RSA, false, ['decrypt']);
        } catch (e) { return (status.textContent = OOPS[String(e)] || String(e)) }
        const got = rs[0] && (await unseal(await readFile(rs[0].path), [], key));
        if (!got) return (status.textContent = 'This file was locked before your authenticator was linked, so only its password opens it.');
        recoveryKey = key;
        finish();
        toast('Opened with your authenticator. You can set a new password from the file.');
      };
      input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
      sheet(
        el('h2', '', { textContent: 'Use your authenticator' }),
        el('p', '', { textContent: 'Open your authenticator app and type the 6-digit code for LodiSave.' }),
        input, status,
        buttons(button('Back', '', passStep), button('Unlock', 'primary', ok)),
      );
      input.focus();
    };
    passStep();
  });
}

// Takes the password off for good: each file is written back as itself and its locked copy deleted.
async function unlockForGood(rs) {
  if (!rs.length) return;
  if (!(await openLocked(rs[0])) && !(await unlockSheet(rs))) return;
  let done = 0;
  for (const r of rs) {
    const o = await openLocked(r);
    if (!o) continue;
    try {
      await invoke('g_unlock_write', o.bytes, { headers: { path: ascii(r.path), name: ascii(o.name) } });
      URL.revokeObjectURL(o.url);
      opened.delete(r.path);
      done++;
    } catch (e) { oops(e) }
  }
  ui.sel.clear();
  await load();
  toast(done === rs.length ? `Removed the password from ${done} ${done === 1 ? 'file' : 'files'}` : `Removed it from ${done} of ${rs.length}. The rest need their own password.`);
}

// A new password for files open this session, typically after the authenticator opened them. Only
// the sealed key changes; the photo inside stays as it was, and the authenticator still opens it.
async function newPasswordSheet(rs) {
  if (!rs.length) return;
  if (!(await openLocked(rs[0])) && !(await unlockSheet(rs))) return;
  const pass = el('input', '', { type: 'password', placeholder: 'New password', autocomplete: 'new-password' });
  const again = el('input', '', { type: 'password', placeholder: 'Type it again', autocomplete: 'new-password' });
  const status = el('p', 'note');
  const ok = async () => {
    if (pass.value.length < 4) return (status.textContent = 'Use at least 4 characters.');
    if (pass.value !== again.value) return (status.textContent = "The passwords don't match.");
    const salt = random(16);
    let done = 0;
    for (const r of rs) {
      const o = await openLocked(r);
      if (!o) continue;
      const ivP = random(12);
      const bytes = pack({ ...o.parts, salt, ivP, wP: await aes('encrypt', await keyFor(pass.value, salt), ivP, o.k) });
      try {
        await invoke('g_relock', bytes, { headers: { path: ascii(r.path) } });
        const check = await unseal(await readFile(r.path), [pass.value]);
        if (check) { done++; o.parts = check.parts }
      } catch (e) { oops(e) }
    }
    if (!sessions.includes(pass.value)) sessions.push(pass.value);
    close();
    await load();
    toast(`New password set on ${done} ${done === 1 ? 'file' : 'files'}`);
  };
  again.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(
    el('h2', '', { textContent: rs.length === 1 ? 'Set a new password' : `Set a new password on ${rs.length} files` }),
    el('p', '', { textContent: 'The old password stops working. Your authenticator still opens these.' }),
    pass, again, status,
    buttons(button('Cancel', '', close), button('Set password', 'primary', ok)),
  );
  pass.focus();
}

// Settings › Password recovery: link an authenticator app with a QR code, or turn it off.
async function recoveryBox() {
  const box = el('div', 'recovery');
  const fill = async () => {
    const on = await invoke('recovery_status').catch(() => false);
    if (on) {
      box.replaceChildren(
        el('p', 'ok', { textContent: 'On. A code from your authenticator app opens files locked since you linked it.' }),
        el('p', 'note', { textContent: 'It works on this PC and this Windows account. It keeps locked files closed to anyone who copies them elsewhere or opens LodiSave without your phone; it can\'t stop a program running as you.' }),
        buttons(button('Turn off', '', () => offSheet().then(() => settingsSheet()))),
      );
    } else {
      box.replaceChildren(
        el('p', 'note', { textContent: 'Forget a password and a 6-digit code from Google Authenticator, or any authenticator app, opens the file instead. Files locked before you link it still need their password.' }),
        buttons(button('Link an authenticator', 'primary', setupSheet)),
      );
    }
  };
  await fill();
  return box;
}

async function setupSheet() {
  let s;
  try { s = await invoke('recovery_begin') } catch (e) { return oops(e) }
  const qr = el('div', 'qr', { innerHTML: s.svg });
  const key = el('p', 'secret', { textContent: s.secret });
  const input = el('input', 'code', { type: 'text', inputMode: 'numeric', autocomplete: 'one-time-code', maxLength: 7, placeholder: '123 456' });
  const status = el('p', 'note');
  const ok = async () => {
    status.textContent = 'Linking…';
    try {
      // The recovery key pair: the public half seals each new lock's key, the private half is
      // sealed by Windows and comes back only for a correct code.
      const pair = await crypto.subtle.generateKey({ ...RSA, modulusLength: 3072, publicExponent: new Uint8Array([1, 0, 1]) }, true, ['encrypt', 'decrypt']);
      const pub = new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey));
      const priv = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
      await invoke('recovery_finish', { code: input.value, public: [...pub], private: [...priv] });
      settingsSheet();
      toast('Authenticator linked. New locks can be opened with a code too.');
    } catch (e) { status.textContent = OOPS[String(e)] || String(e) }
  };
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
  sheet(
    el('h2', '', { textContent: 'Link an authenticator' }),
    el('p', '', { textContent: 'In Google Authenticator (or any authenticator app), add an account and scan this code.' }),
    qr,
    el('p', 'note', { textContent: "Can't scan? Choose “Enter a setup key” and type:" }), key,
    el('h4', '', { textContent: 'Then type the 6-digit code it shows' }),
    input, status,
    el('p', 'note', { textContent: 'Works on this PC and this Windows account. Files you lock from now on can be opened with your password or a code.' }),
    buttons(button('Cancel', '', () => settingsSheet()), button('Link', 'primary', ok)),
  );
  input.focus();
}

function offSheet() {
  return new Promise((done) => {
    const input = el('input', 'code', { type: 'text', inputMode: 'numeric', autocomplete: 'one-time-code', maxLength: 7, placeholder: '123 456' });
    const status = el('p', 'note');
    const ok = async () => {
      try { await invoke('recovery_off', { code: input.value }); toast('Authenticator unlinked'); done() } catch (e) { status.textContent = OOPS[String(e)] || String(e) }
    };
    input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); ok() } };
    sheet(
      el('h2', '', { textContent: 'Turn off password recovery' }),
      el('p', 'warn', { textContent: 'Locked files will open only with their passwords. Type a code from your authenticator to confirm.' }),
      input, status,
      buttons(button('Cancel', '', () => done()), button('Turn off', 'primary', ok)),
    );
    input.focus();
  });
}

// Settings › Windows permissions: what Controlled folder access lets LodiSave do, and a button that
// asks Windows directly.
async function permissionsBox() {
  const box = el('div', 'list');
  const fill = async () => {
    box.replaceChildren(el('p', 'note', { textContent: 'Checking…' }));
    let p;
    try { p = await invoke('permissions') } catch (e) { return box.replaceChildren(el('p', 'note', { textContent: String(e) })) }
    const line = (label, good, text) => { const l = el('div', 'line'); l.append(icon(good ? 'check' : 'shield'), el('span', '', { textContent: label }), el('b', good ? 'ok' : 'bad-text', { textContent: text })); return l };
    const states = { on: 'On', off: 'Off', audit: 'Audit only', unknown: 'Not Windows Security' };
    const blocked = !p.saves || !p.pictures;
    box.replaceChildren(
      line('Controlled folder access', p.protection !== 'on' || !blocked, states[p.protection]),
      line('Save into Downloads\\LodiSave', p.saves, p.saves ? 'Allowed' : 'Blocked'),
      line('Change protected folders (Pictures, Videos, Desktop)', p.pictures, p.pictures ? 'Allowed' : 'Blocked'),
    );
    if (blocked) {
      const allow = button('Allow LodiSave', 'primary', async () => {
        allow.disabled = true;
        try { await invoke('allow_protected'); toast('Windows now lets LodiSave save and organize there') } catch (e) { toast(e === 'declined' ? "Windows didn't get a yes, so nothing changed." : "Windows Security didn't take it. Allow LodiSave there instead."); if (e !== 'declined') invoke('open_security') }
        fill();
      });
      box.append(el('p', 'note', { textContent: 'Windows asks you to confirm, then adds LodiSave to the apps allowed in protected folders. Only LodiSave is added; the protection stays on.' }), buttons(button('Open Windows Security', '', () => invoke('open_security')), allow));
    }
  };
  fill();
  return box;
}

// Preview panel -----------------------------------------------------------------------------------

const facts = (rows) => {
  const dl = el('dl', 'facts');
  for (const [k, v, cls] of rows) {
    if (v == null || v === '') continue;
    const dd = el('dd', cls || '');
    dd.append(v);
    dl.append(el('dt', '', { textContent: k }), dd);
  }
  return dl;
};
const pill = (label, cls, run) => {
  const b = el('button', `pill ${cls}`, { type: 'button', textContent: label });
  b.onclick = run;
  return b;
};

// Who a save came from and on which app, from LodiSave's "@who · App · title" names.
const SOURCE = /^@([^·]+?) · ([^·]+?) · /;
const sourceOf = (name) => {
  const m = SOURCE.exec(name);
  return m ? { who: m[1], app: m[2] } : null;
};
const titleOf = (name) => name.replace(SOURCE, '');

let peekFor = null;
function paintPeek() {
  const ps = picked();
  const key = ps.join('|') + (ps.length === 1 ? JSON.stringify(item(ps[0])) : '') + ui.view + ui.shown.length + sessions.length + !!recoveryKey + ui.backed.size;
  paintRail(ps);
  if (peekShut() || key === peekFor) return;
  peekFor = key;
  const body = el('div', 'peek-body', { id: 'peek-body' });
  if (ps.length === 1 && item(ps[0])) onePeek(body, item(ps[0]));
  else if (ps.length > 1) manyPeek(body, ps.map(item).filter(Boolean));
  else nonePeek(body);
  $('peek-body').replaceWith(body);
  $('peek-label').textContent = ps.length > 1 ? `${ps.length} selected` : ps.length ? 'Preview' : 'Overview';
}

function onePeek(body, r) {
  if (r.kind === 'locked' && !opened.has(r.path)) return lockedPeek(body, r);
  const o = opened.get(r.path);
  if (o) r = { ...r, name: o.name, kind: o.kind, url: o.url };
  const at = () => ui.shown.findIndex((x) => x.path === r.path);
  const box = el('div', `pv${r.blur ? ' veil' : ''}`);
  const url = r.url || src(r.path);
  const dims = el('span');
  if (r.kind === 'video') {
    const v = el('video', '', { src: url, muted: true, autoplay: true, loop: true, playsInline: true, poster: r.url ? '' : thumb(r) });
    v.onloadedmetadata = () => (dims.textContent = `${v.videoWidth} × ${v.videoHeight}, ${clock(v.duration)}`);
    // The player's bar and buttons show only while the pointer is over the video.
    box.onmouseenter = () => (v.controls = true);
    box.onmouseleave = () => (v.controls = false);
    box.append(v);
  } else if (r.kind === 'audio') {
    const g = el('div', 'glyph');
    g.append(icon('music'), el('audio', '', { src: url, controls: true }));
    box.append(g);
  } else {
    const img = el('img', '', { alt: r.name, title: 'Open (Enter)', decoding: 'async' });
    soon(img, r, url, () => (dims.textContent = `${img.naturalWidth} × ${img.naturalHeight}`));
    img.onclick = () => openViewer(at(), img);
    box.append(img);
  }
  const from = sourceOf(r.name);
  const folder = el('button', '', { type: 'button', textContent: base(r.dir) || r.dir, title: r.dir });
  folder.onclick = () => go({ view: 'folder', folder: lib.folders.find((f) => low(f.path) === low(r.dir))?.path || r.dir });
  let saved = null;
  if (from) {
    saved = el('button', '', { type: 'button', textContent: `@${from.who} on ${from.app}` });
    saved.onclick = () => go({ view: 'from', from: from.app });
  }
  const tags = el('div', 'chiprow');
  for (const t of r.tags) { const b = el('button', '', { type: 'button', textContent: `#${t}` }); b.onclick = () => go({ view: 'tag', tag: t }); tags.append(b) }
  const add = el('button', '', { type: 'button', textContent: r.tags.length ? 'Edit tags' : 'Add a tag' });
  add.onclick = () => act('tag', [r.path]);
  tags.append(add);
  const ps = [r.path];
  const main = el('div', 'mainacts');
  main.append(pill('View', 'primary', () => openViewer(at())), pill('Open with app', '', () => act('open', ps)), pill('Show in folder', '', () => act('reveal', ps)));
  body.append(
    box,
    el('p', 'pname', { textContent: from ? titleOf(r.name) : r.name }),
    facts([
      ['Saved from', saved],
      ['Size', r.kind === 'image' || r.kind === 'gif' || r.kind === 'video' ? dims : null],
      ['File', `${size(r.bytes)}, ${r.name.split('.').pop().toUpperCase()}`],
      ['Changed', new Date(r.modified).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })],
      ['Folder', folder],
      ['Google Drive', ui.backed.has(low(r.path)) ? 'Backed up' : null, 'ok'],
    ]),
    tags, main, acts(ps, r.kind === 'image' ? [['image', 'Set as desktop background', 'wallpaper']] : []),
  );
}

function lockedPeek(body, r) {
  const box = el('div', 'pv');
  const g = el('div', 'glyph');
  g.append(icon('lock'), el('p', 'note', { textContent: 'Encrypted. Its password opens it, or your authenticator app if it was linked when the file was locked.' }));
  box.append(g);
  const ps = [r.path];
  const main = el('div', 'mainacts');
  main.append(pill('Unlock to view', 'primary', () => act('unlock', ps)));
  body.append(
    box, el('p', 'pname', { textContent: 'Locked file' }),
    facts([['File', size(r.bytes)], ['Locked', new Date(r.modified).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })], ['Folder', base(r.dir) || r.dir]]),
    main, acts(ps, []),
  );
}

function manyPeek(body, rs) {
  const stack = el('div', 'stack');
  const turns = [[-9, -18], [6, 14], [-3, 0]];
  rs.slice(0, 3).forEach((r, i) => {
    const [deg, x] = turns[i];
    const node = r.kind === 'image' || r.kind === 'gif' || r.kind === 'video' ? el('img', '', { src: thumb(r), alt: '' }) : el('div', 'ph');
    node.style.transform = `rotate(${deg}deg) translateX(${x}px)`;
    node.style.animationDelay = `${i * 60}ms`;
    stack.append(node);
  });
  const count = (k) => rs.filter((r) => r.kind === k).length;
  const mix = [['photo', 'image'], ['video', 'video'], ['GIF', 'gif'], ['sound', 'audio'], ['locked file', 'locked']]
    .map(([word, k]) => [word, count(k)]).filter(([, n]) => n).map(([word, n]) => `${n} ${word}${n === 1 ? '' : 's'}`).join(', ');
  const ps = rs.map((r) => r.path);
  const main = el('div', 'mainacts');
  if (driveReady) main.append(pill('Back up to Google Drive', 'primary', () => act('drive', ps)), pill('Move to', '', () => act('move', ps)), pill('Tags', '', () => act('tag', ps)));
  else main.append(pill('Move to', 'primary', () => act('move', ps)), pill('Tags', '', () => act('tag', ps)), pill('Copy', '', () => act('clip', ps)));
  body.append(
    stack, el('p', 'pname', { textContent: `${rs.length} files` }), el('p', 'psub', { textContent: `${mix}, ${size(rs.reduce((n, r) => n + r.bytes, 0))} in all` }),
    main, acts(ps, [['edit', 'Rename all', 'rename']], driveReady ? ['drive', 'move', 'tag'] : ['move', 'tag', 'clip']),
  );
}

// With nothing picked: what this view holds, then what needs doing, each with its one action.
function nonePeek(body) {
  const rs = ui.shown, total = rs.reduce((n, r) => n + r.bytes, 0);
  const backed = rs.filter((r) => ui.backed.has(low(r.path))).length;
  const locked = rs.filter((r) => r.kind === 'locked').length;
  const fromApps = rs.filter((r) => sourceOf(r.name)).length;
  const todo = el('div', 'todo');
  const item2 = (title, text, label, run) => {
    const row = el('div');
    const p = el('p');
    p.append(el('b', '', { textContent: title }), text);
    row.append(p, pill(label, '', run));
    todo.append(row);
  };
  const waiting = rs.length - backed - locked;
  if (driveReady && waiting > 0) item2(`${waiting} not on Google Drive`, backed ? `${backed} already are.` : 'Nothing here is backed up yet.', 'Back up', driveSheet);
  if (locked && !unlocked()) item2(`${locked} locked ${locked === 1 ? 'file' : 'files'}`, 'Encrypted with a password.', 'Unlock', () => act('unlock', rs.filter((r) => r.kind === 'locked').map((r) => r.path)));
  if (rs.length > 1) item2('Duplicate copies', 'Find files saved more than once.', 'Check', () => go({ view: 'dups', dups: null }));
  const keys = el('details', 'keys');
  const sum = el('summary');
  sum.append(icon('down'), 'Keyboard shortcuts');
  const dl = el('dl');
  for (const [k, v] of [['Click', 'Preview'], ['Double-click, Enter', 'Open'], ['Ctrl, Shift + click', 'Pick more'], ['Arrows', 'Move'], ['F', 'Favorite'], ['T', 'Tags'], ['M', 'Move to'], ['L', 'Lock'], ['F2', 'Rename'], ['Del', 'Delete'], ['[ and ]', 'Fold the side panels'], ['Ctrl + wheel', 'Tile size']]) {
    dl.append(el('dt', '', { textContent: k }), el('dd', '', { textContent: v }));
  }
  keys.append(sum, dl);
  body.append(
    el('p', 'pname', { textContent: $('title').textContent }),
    facts([
      ['Files', `${rs.length}, ${size(total)}`],
      ['Saved by LodiSave', fromApps ? `${fromApps} from social apps` : null],
      ['Folders', lib.roots.length ? `${lib.folders.length} in ${lib.roots.length} added` : null],
    ]),
    ...(todo.childElementCount ? [el('h4', '', { textContent: 'To do' }), todo] : []),
    keys,
  );
}

// Everything a selection can do, as a plain list. `extra` adds rows for this kind of selection.
function acts(ps, extra, skip = []) {
  const list = el('div', 'acts');
  const row = (name, label, what, key = '', cls = '') => {
    const b = el('button', `act ${cls}`, { type: 'button' });
    b.append(icon(name), label);
    if (skip.includes(what)) return;
    if (key) b.append(el('kbd', '', { textContent: key }));
    b.onclick = () => act(what, ps);
    list.append(b);
  };
  const favs = ps.every((p) => item(p)?.fav), blurred = ps.every((p) => item(p)?.blur), anyPlain = ps.some((p) => item(p)?.kind !== 'locked');
  row('heart', favs ? 'Favorite' : 'Add to favorites', 'fav', 'F', favs ? 'on' : '');
  row('tag', 'Tags', 'tag', 'T');
  row('move', 'Move to', 'move', 'M');
  row('folder', 'Copy to', 'copyto');
  row('copy', 'Copy', 'clip', 'Ctrl+C');
  if (ps.length === 1) row('edit', 'Rename', 'rename', 'F2');
  for (const [name, label, what] of extra) row(name, label, what);
  row('copy', 'Copy path', 'path');
  row('eye-off', blurred ? 'Unblur' : 'Blur', 'blur', 'B');
  if (driveReady) row('cloud', 'Back up to Google Drive', 'drive');
  else row('cloud', 'Google Drive', 'drive', 'Later version', 'ghost');
  if (anyPlain) row('lock', 'Lock with a password', 'lock', 'L');
  else {
    row('lock', 'Set a new password', 'newpass');
    row('unlock', 'Remove the password', 'unlockgood');
  }
  row('trash', 'Delete', 'trash', 'Del', 'bad');
  return list;
}

// Shut, the panel keeps the tools as icons.
function paintRail(ps) {
  const b = (name, label, what, cls = '') => {
    const x = el('button', `icon ${cls}`, { type: 'button', title: label, disabled: !ps.length });
    x.setAttribute('aria-label', label);
    x.append(icon(name));
    x.onclick = () => act(what, ps);
    return x;
  };
  $('peek-rail').replaceChildren(
    b('image', 'View (Enter)', 'view'), b('heart', 'Favorite (F)', 'fav'), b('tag', 'Tags (T)', 'tag'), b('move', 'Move to (M)', 'move'),
    b('copy', 'Copy (Ctrl+C)', 'clip'), b('cloud', driveReady ? 'Back up' : 'Google Drive: later version', 'drive', driveReady ? '' : 'ghost'), el('hr'), b('trash', 'Delete (Del)', 'trash', 'bad'),
  );
}

// Viewer ------------------------------------------------------------------------------------------

const view = { list: [], at: 0, turn: 0, zoom: 1, x: 0, y: 0, show: 0 };

// The tile's picture grows into the viewer, and shrinks back into its tile on close.
const tileMedia = (path) => [...$('grid').querySelectorAll('.tile')].find((t) => ui.shown[t.dataset.n]?.path === path)?.querySelector('img,video');
const hero = (node) => node && (node.style.viewTransitionName = 'hero');
const unhero = () => document.querySelectorAll('[style*="view-transition-name"]').forEach((n) => (n.style.viewTransitionName = ''));

// A photo shows at once from its thumbnail, already in memory from the grid, and turns into the
// file itself once that is decoded, so opening never waits on a blank frame. `full` runs then.
function soon(img, r, url, full = () => {}) {
  if (r.kind !== 'image' && r.kind !== 'gif' || url !== src(r.path)) {
    img.onload = full;
    img.src = url;
    return;
  }
  img.onerror = () => { img.onerror = null; img.src = url };
  img.src = thumb(r);
  const real = new Image();
  real.src = url;
  real.decode().catch(() => {}).then(() => {
    img.onerror = null;
    img.onload = full;
    img.src = url;
  });
}

// Waits for a picture to be ready to paint, but never long: a View Transition snapshots it next.
const painted = (m) => Promise.race([m?.decode?.().catch(() => {}), new Promise((ok) => setTimeout(ok, 120))]);

// Only the picture and the viewer's backdrop move; the grid under them stays as it is.
function viewerSwap(update) {
  document.documentElement.classList.add('vt-viewer');
  return swap(update).then(() => { unhero(); document.documentElement.classList.remove('vt-viewer') });
}

function openViewer(n, from = tileMedia(ui.shown[n]?.path)) {
  if (n < 0 || !ui.shown[n]) return;
  view.list = ui.shown.map((r) => r.path);
  view.at = n;
  hero(from);
  viewerSwap(async () => {
    unhero();
    $('viewer').hidden = false;
    document.body.classList.add('viewing');
    paintViewer();
    const m = media();
    hero(m);
    await painted(m);
  });
}

function closeViewer() {
  stopShow();
  const path = view.list[view.at];
  hero(media());
  viewerSwap(() => {
    unhero();
    $('viewer').hidden = true;
    document.body.classList.remove('viewing');
    $('stage').replaceChildren();
    forgetHd();
    hero(tileMedia(path));
  });
}

// HD: a photo shown bigger than it was saved is redrawn at the screen's pixels with smooth scaling,
// then an unsharp mask. Edges get crisp; detail the file never had can't come back.
const hd = { url: '', on: false };
const forgetHd = () => { if (hd.url) URL.revokeObjectURL(hd.url); hd.url = ''; hd.on = false; $('v-hd').classList.remove('on') };
async function enhance(img) {
  const w = img.naturalWidth, h = img.naturalHeight;
  const k = Math.min(img.clientWidth / w, img.clientHeight / h) * devicePixelRatio;
  if (!(k > 1.15)) return null;
  // ponytail: one pass on this thread, capped at 8 MP so it stays under a second; a worker lifts the cap.
  const s = Math.min(k, 4, Math.sqrt(8e6 / (w * h)));
  const W = Math.round(w * s), H = Math.round(h * s);
  const bmp = await createImageBitmap(await (await fetch(img.src)).blob());
  const big = new OffscreenCanvas(W, H), g = big.getContext('2d', { willReadFrequently: true });
  g.imageSmoothingQuality = 'high';
  g.drawImage(bmp, 0, 0, W, H);
  const soft = new OffscreenCanvas(W, H), b = soft.getContext('2d', { willReadFrequently: true });
  b.filter = `blur(${Math.max(1, s * 0.6).toFixed(1)}px)`;
  b.drawImage(big, 0, 0);
  const sharp = g.getImageData(0, 0, W, H), d = sharp.data, blurred = b.getImageData(0, 0, W, H).data;
  for (let i = 0; i < d.length; i++) if ((i & 3) !== 3) d[i] += 0.7 * (d[i] - blurred[i]); // clamped to 0..255
  g.putImageData(sharp, 0, 0);
  return URL.createObjectURL(await big.convertToBlob({ type: /\.png$/i.test(img.src) ? 'image/png' : 'image/jpeg', quality: 0.95 }));
}
async function setHd(on, quiet = false) {
  const img = media(), at = view.at;
  if (!img || img.tagName !== 'IMG' || item(view.list[at])?.kind !== 'image') return quiet || toast('HD works on photos');
  if (!on) {
    if (hd.url) img.src = img.dataset.file;
    forgetHd();
    return;
  }
  if (!hd.url) {
    const url = await enhance(img).catch(() => null);
    if (view.at !== at || !img.isConnected) return url && URL.revokeObjectURL(url);
    if (!url) return quiet || toast('This photo already shows at its own size or smaller, so it is as sharp as the file');
    hd.url = url;
  }
  img.src = hd.url;
  hd.on = true;
  $('v-hd').classList.add('on');
}

function dropFromViewer(ps) {
  view.list = view.list.filter((p) => !ps.includes(p));
  if (!view.list.length) return closeViewer();
  view.at = Math.min(view.at, view.list.length - 1);
  paintViewer();
}

const meta = (...facts) => $('v-meta').replaceChildren(...facts.map((f) => el('span', '', { textContent: f })));

function paintViewer() {
  const r = item(view.list[view.at]);
  if (!r) return closeViewer();
  Object.assign(view, { turn: 0, zoom: 1, x: 0, y: 0 });
  const stage = $('stage');
  stage.classList.remove('zoomed');
  const o = opened.get(r.path);
  if (r.kind === 'locked' && !o) {
    const g = el('div', 'glyph');
    const b = el('button', 'pill primary', { type: 'button', textContent: 'Unlock to view' });
    b.onclick = () => unlockSheet([r]).then(paintViewer);
    g.append(icon('lock'), el('p', '', { textContent: 'This file is locked with a password.' }), b);
    stage.replaceChildren(g);
    $('v-name').textContent = 'Locked file';
    meta(size(r.bytes), base(r.dir));
    forgetHd();
    $('v-hd').hidden = true;
    return;
  }
  const kind = o ? o.kind : r.kind, url = o ? o.url : src(r.path);
  forgetHd();
  const dims = () => {
    const w = m.naturalWidth || m.videoWidth, h = m.naturalHeight || m.videoHeight;
    if (w) meta(`${w} × ${h}`, size(r.bytes), day(r.modified), base(r.dir));
  };
  let m;
  if (kind === 'video') m = el('video', '', { src: url, controls: true, autoplay: true, loop: true, poster: o ? '' : thumb(r) });
  else if (kind === 'audio') m = el('audio', '', { src: url, controls: true, autoplay: true });
  else {
    m = el('img', '', { alt: r.name, decoding: 'async' });
    m.dataset.file = url;
    soon(m, { ...r, kind }, url, () => {
      m.onload = null;
      dims();
      if (ui.hd && kind === 'image') requestAnimationFrame(() => setHd(true, true));
    });
  }
  m.onloadedmetadata = dims;
  if (kind === 'audio') {
    const g = el('div', 'glyph');
    g.append(icon('music'), m);
    stage.replaceChildren(g);
  } else stage.replaceChildren(m);
  $('v-name').textContent = o ? o.name : r.name;
  meta(size(r.bytes), day(r.modified), base(r.dir));
  $('v-fav').classList.toggle('on', r.fav);
  $('v-prev').hidden = view.at === 0;
  $('v-next').hidden = view.at >= view.list.length - 1;
  $('v-hd').hidden = kind !== 'image';
  // The photos either side load and decode ahead, so stepping doesn't wait.
  for (const p of [view.list[view.at + 1], view.list[view.at - 1]]) {
    const near = item(p);
    if (near?.kind === 'image' || near?.kind === 'gif') { const i = new Image(); i.src = src(near.path); i.decode().catch(() => {}) }
  }
}

function step(by) {
  const to = view.at + by;
  if (to < 0 || to >= view.list.length) return stopShow();
  view.at = to;
  paintViewer();
}

const media = () => $('stage').querySelector('img,video');
const place2 = () => { const m = media(); if (m) m.style.transform = `translate(${view.x}px, ${view.y}px) rotate(${view.turn}deg) scale(${view.zoom})` };

$('stage').addEventListener('wheel', (e) => {
  if (!media() || media().tagName !== 'IMG') return;
  e.preventDefault();
  view.zoom = Math.min(8, Math.max(1, view.zoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15)));
  if (view.zoom === 1) view.x = view.y = 0;
  $('stage').classList.toggle('zoomed', view.zoom > 1);
  place2();
}, { passive: false });
$('stage').addEventListener('dblclick', () => { view.zoom = view.zoom > 1 ? 1 : 2.5; view.x = view.y = 0; $('stage').classList.toggle('zoomed', view.zoom > 1); place2() });
// A click on the space around the photo or video closes the viewer, as in Google Photos. A drag
// that pans a zoomed photo doesn't count.
let downAt = [0, 0];
$('stage').addEventListener('pointerdown', (e) => (downAt = [e.clientX, e.clientY]));
$('stage').addEventListener('click', (e) => {
  if (Math.hypot(e.clientX - downAt[0], e.clientY - downAt[1]) > 6) return;
  const m = media();
  if (m ? !onPicture(m, e.clientX, e.clientY) : e.target === $('stage')) closeViewer();
});
// Whether a point is on the picture itself, not the bars object-fit leaves beside it.
function onPicture(m, x, y) {
  const w = m.naturalWidth || m.videoWidth, h = m.naturalHeight || m.videoHeight, r = m.getBoundingClientRect();
  if (!w || !h) return true;
  const s = Math.min(m.offsetWidth / w, m.offsetHeight / h) * view.zoom;
  const [pw, ph] = view.turn % 180 ? [h * s, w * s] : [w * s, h * s];
  return Math.abs(x - (r.left + r.width / 2)) <= pw / 2 && Math.abs(y - (r.top + r.height / 2)) <= ph / 2;
}
$('stage').addEventListener('pointerdown', (e) => {
  if (view.zoom <= 1 || e.target.tagName !== 'IMG') return;
  const [sx, sy, ox, oy] = [e.clientX, e.clientY, view.x, view.y];
  const move = (m) => { view.x = ox + m.clientX - sx; view.y = oy + m.clientY - sy; place2() };
  addEventListener('pointermove', move);
  addEventListener('pointerup', () => removeEventListener('pointermove', move), { once: true });
});

function startShow() {
  view.show = setInterval(() => (view.at < view.list.length - 1 ? step(1) : stopShow()), 3500);
  $('v-show').classList.add('on');
}
function stopShow() {
  clearInterval(view.show);
  view.show = 0;
  $('v-show').classList.remove('on');
}

$('v-close').onclick = closeViewer;
$('v-prev').onclick = () => step(-1);
$('v-next').onclick = () => step(1);
$('v-show').onclick = () => (view.show ? stopShow() : startShow());
$('v-rot').onclick = () => { view.turn += 90; place2() };
$('v-hd').onclick = () => setHd(!hd.on);
$('v-fav').onclick = async () => { const r = item(view.list[view.at]); await fav([r.path], !r.fav); paintViewer() };
$('v-open').onclick = () => act('open', [view.list[view.at]]);
$('v-reveal').onclick = () => act('reveal', [view.list[view.at]]);
$('v-trash').onclick = () => trash([view.list[view.at]]);

// Keys --------------------------------------------------------------------------------------------

addEventListener('keydown', (e) => {
  if ($('sheet').open || $('prefs').open) return;
  const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement?.tagName);
  if (!$('viewer').hidden) {
    const r = item(view.list[view.at]);
    const keys = {
      Escape: closeViewer, ArrowLeft: () => step(-1), ArrowRight: () => step(1), Delete: () => trash([r.path]),
      f: () => $('v-fav').click(), s: () => $('v-show').click(), r: () => $('v-rot').click(), e: () => $('v-hd').click(), b: () => blur([r.path], !r.blur).then(paintViewer),
    };
    const k = keys[e.key.length === 1 ? e.key.toLowerCase() : e.key];
    if (k) { e.preventDefault(); stopShowUnless(e.key); k() }
    return;
  }
  if (e.key === 'F5') { e.preventDefault(); load(); return }
  if (!typing && e.key === '[') return toggleSide();
  if (!typing && e.key === ']') return togglePeek();
  if (!typing && /^Arrow/.test(e.key) && ui.shown.length) { e.preventDefault(); return moveFocus(e.key, e.shiftKey) }
  if (!typing && e.key === 'Enter' && ui.anchor >= 0) return openViewer(ui.anchor);
  if ((e.key === '/' && !typing) || (e.ctrlKey && e.key.toLowerCase() === 'f')) { e.preventDefault(); $('query').focus(); return }
  if (typing) return;
  const ps = picked();
  if (e.ctrlKey && e.key.toLowerCase() === 'a') { e.preventDefault(); act('all') }
  else if (e.ctrlKey && e.key.toLowerCase() === 'c' && ps.length) act('clip')
  else if (e.key === 'Escape') act('none');
  else if (!ps.length) return;
  else if (e.key === 'Delete') act('trash');
  else if (e.key === 'F2') act('rename');
  else if (e.key.toLowerCase() === 'f') act('fav');
  else if (e.key.toLowerCase() === 'b') act('blur');
  else if (e.key.toLowerCase() === 't') act('tag');
  else if (e.key.toLowerCase() === 'm') act('move');
  else if (e.key.toLowerCase() === 'n') act('newwith');
  else if (e.key.toLowerCase() === 'l') act('lock');
});
const stopShowUnless = (key) => { if (key.toLowerCase() !== 's') stopShow() };

// Arrow keys walk the grid: left and right by one, up and down by a row of the even layout.
function moveFocus(key, extend) {
  const tiles = [...$('grid').querySelectorAll('.tile')];
  const perRow = Math.max(1, tiles.filter((t) => t.offsetTop === tiles[0]?.offsetTop).length);
  const by = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: ui.layout === 'grid' ? -perRow : -1, ArrowDown: ui.layout === 'grid' ? perRow : 1 }[key];
  const to = Math.min(ui.shown.length - 1, Math.max(0, (ui.anchor < 0 ? -by : ui.anchor) + by));
  while (to >= ui.drawn) drawMore();
  if (extend) { ui.sel.add(ui.shown[to].path); ui.anchor = to; paintSel() } else only(to);
  [...$('grid').querySelectorAll('.tile')].find((t) => +t.dataset.n === to)?.scrollIntoView({ block: 'nearest', behavior: still() ? 'auto' : 'smooth' });
}

// Wiring ------------------------------------------------------------------------------------------

for (const b of $('nav').querySelectorAll('[data-view]')) b.onclick = () => go({ view: b.dataset.view, ...(b.dataset.view === 'dups' ? { dups: null } : {}) });
$('open-drive').onclick = driveSheet;
$('lock-now').onclick = lockAgain;
$('open-settings').onclick = () => settingsSheet();
$('add-folder').onclick = () => addFolder();
$('refresh').onclick = () => { ui.dups = null; load() };
$('scroll').onclick = (e) => { if (!e.target.closest('.tile,.group') && ui.sel.size) act('none') };

// The window's own title bar buttons.
$('w-min').onclick = () => win.minimize();
$('w-max').onclick = () => win.toggleMaximize();
$('w-close').onclick = () => win.close();
const maxIcon = async () => {
  const big = await win.isMaximized().catch(() => false);
  $('w-max').firstElementChild.innerHTML = `<use href="#i-${big ? 'unmax' : 'max'}"/>`;
  $('w-max').title = big ? 'Restore' : 'Maximize';
};
win.onResized(maxIcon);
maxIcon();

// Side panels shrink to icon rails. A narrow window shuts the preview without changing the setting.
const peekShut = () => ui.peekShut || innerWidth < 1060;
const sideShut = () => ui.sideShut || innerWidth < 980;
function paintPanels() {
  $('app').classList.toggle('side-shut', sideShut());
  $('app').classList.toggle('peek-shut', peekShut());
  $('toggle-side').title = sideShut() ? 'Open the sidebar ([)' : 'Collapse the sidebar ([)';
  $('toggle-peek').title = peekShut() ? 'Open the preview (])' : 'Collapse the preview (])';
}
function toggleSide() { ui.sideShut = !sideShut(); keep('g-side', ui.sideShut ? 'shut' : 'open'); swap(paintPanels) }
function togglePeek() { ui.peekShut = !peekShut(); keep('g-peek', ui.peekShut ? 'shut' : 'open'); swap(() => { paintPanels(); paintSel() }) }
$('toggle-side').onclick = toggleSide;
$('toggle-peek').onclick = togglePeek;
addEventListener('resize', () => { paintPanels(); paintSel() });
paintPanels();
// Buttons that show only an icon are named by their tooltip.
new MutationObserver(nameIcons).observe(document.body, { childList: true, subtree: true });
function nameIcons() {
  for (const b of document.querySelectorAll('button[title]:not([aria-label])')) if (!b.textContent.trim()) b.setAttribute('aria-label', b.title);
}
nameIcons();

// Even rows or Pinterest.
const paintLayout = () => { for (const b of $('layout').children) b.classList.toggle('on', b.dataset.layout === ui.layout) };
for (const b of $('layout').children) b.onclick = () => { ui.layout = b.dataset.layout; keep('g-layout', ui.layout); paintLayout(); swap(paintGrid).then(paintSel) };
paintLayout();
for (const b of $('kinds').children) b.onclick = () => { ui.kind = b.dataset.kind; for (const o of $('kinds').children) o.classList.toggle('on', o === b); paint() };
let typingTimer;
$('query').oninput = () => { clearTimeout(typingTimer); typingTimer = setTimeout(() => { ui.query = $('query').value; paint() }, 150) };
$('sort').value = ui.sort;
$('sort').onchange = () => { ui.sort = $('sort').value; keep('g-sort', ui.sort); paint() };
$('deep').checked = ui.deep;
$('deep').onchange = () => { ui.deep = $('deep').checked; keep('g-deep', ui.deep ? '1' : '0'); paint() };
$('zoom').value = ui.zoom;
document.documentElement.style.setProperty('--tile', `${ui.zoom}px`);
$('zoom').oninput = () => { ui.zoom = +$('zoom').value; keep('g-zoom', String(ui.zoom)); document.documentElement.style.setProperty('--tile', `${ui.zoom}px`) };
// Ctrl + wheel sizes the tiles, as in File Explorer.
$('scroll').addEventListener('wheel', (e) => {
  if (!e.ctrlKey) return;
  e.preventDefault();
  $('zoom').value = +$('zoom').value + (e.deltaY < 0 ? 20 : -20);
  $('zoom').oninput();
}, { passive: false });
// Files changed in File Explorer show up when the window comes back.
let shownAt = 0;
addEventListener('focus', () => { if (Date.now() - shownAt > 4000 && $('viewer').hidden && !$('sheet').open && !$('prefs').open) load(); shownAt = Date.now() });
document.addEventListener('contextmenu', (e) => { if (!e.target.closest('.tile,.row,input')) e.preventDefault() });

load();
