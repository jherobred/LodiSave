// The tray panel. Home: the card follows the clipboard, so a copied link or image is one Save
// away, with the latest saves below. The library organizes everything in Downloads\LodiSave,
// with a viewer and quick edits. Views change with View Transitions and springs, which stop
// when Motion is off or a game runs.
const { invoke, convertFileSrc } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const ERRORS = { login: 'login', nothing: 'nothing', offline: 'offline', refused: 'refused', gone: 'gone', broken: 'broken', helper: 'helperFail', noimage: 'noimage', blocked: 'blocked' };
const OOPS = { blocked: 'errBlocked', taken: 'errTaken', name: 'errName', gone: 'errGone', outside: 'errGone', kept: 'errKept', restore: 'cantUndo', ffmpeg: 'ffmpegFail', noffmpeg: 'noFfmpeg' };

const $ = (id) => document.getElementById(id);
const el = (tag, className = '', props = {}) => Object.assign(document.createElement(tag), className ? { className } : {}, props);
const icon = (name, cls = '') => {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', `i ${cls}`);
  svg.innerHTML = `<use href="#i-${name}"/>`;
  return svg;
};
const pref = (k, d) => { try { return localStorage.getItem(k) || d } catch { return d } };
const keep = (k, v) => { try { localStorage.setItem(k, v) } catch {} };
const ui = {
  lang: pref('lang', 'en'), motion: pref('motion', 'on'), autostart: 'off', recent: pref('recent', 'show'), gaming: pref('gaming', 'auto'), playing: false,
  state: 'idle', link: '', job: null, files: [], error: null, captured: false, account: null,
  view: 'home', album: null, filter: 'all', sort: pref('sort', 'new'), query: '', picking: false, picked: new Set(),
  list: [], at: -1, from: 'home', step: 0,
};
let lib = { items: [], albums: [], ffmpeg: false };
const say = (key, ...a) => { const v = COPY[ui.lang][key]; return typeof v === 'function' ? v(...a) : v };
const dismissed = new Set();
const still = () => ui.motion === 'off' || ui.playing;
const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const short = (url) => url.replace(/^https?:\/\/(www\.)?/, '');
const base = (p) => p.split(/[\\/]/).pop();
const size = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`);
const clock = (s) => (Number.isFinite(s) ? `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}` : '');
const ext = (name) => (name.includes('.') ? name.split('.').pop().toUpperCase() : '');
const byNewest = (a, b) => b.modified - a.modified;

// Motion ----------------------------------------------------------------------------------------

// Changes what the panel shows inside a View Transition. `kind` (forward, back, zoom, fade) picks
// the CSS animation. Resolves when it has played, or at once with motion off.
let turn = 0;
function swap(update, kind = 'fade') {
  if (still() || !document.startViewTransition) return Promise.resolve(update());
  const mine = ++turn, root = document.documentElement;
  root.dataset.vt = kind;
  const t = document.startViewTransition(update);
  return t.finished.catch(() => {}).finally(() => { if (mine === turn) delete root.dataset.vt });
}

// Lodi changes pose with a little hop.
function pose(p, lodi = $('lodi')) {
  const hop = lodi.querySelector('.hop');
  lodi.dataset.pose = p;
  hop.classList.remove('go');
  void hop.getBoundingClientRect(); // restarts the hop animation
  hop.classList.add('go');
}

let toastTimer = 0, undoing = null;
function toast(text, { mood = 'yay', undo = null } = {}) {
  const box = $('toast');
  if (!$('toast-lodi').firstChild) {
    const mini = $('lodi').cloneNode(true);
    mini.removeAttribute('id');
    $('toast-lodi').append(mini);
  }
  $('toast-text').textContent = text;
  $('toast-undo').hidden = !undo;
  undoing = undo;
  box.hidden = false;
  box.classList.remove('in', 'out');
  void box.offsetWidth;
  box.classList.add('in');
  pose(mood, $('toast-lodi').firstChild);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    box.classList.add('out');
    toastTimer = setTimeout(() => { box.hidden = true; undoing = null }, still() ? 0 : 200);
  }, undo ? 6000 : 2600);
}
const oops = (e) => toast(say(OOPS[String(e)] || 'failed'), { mood: 'oops' });
$('toast-undo').onclick = () => {
  const undo = undoing;
  undoing = null;
  $('toast').hidden = true;
  undo?.();
};

// Home ------------------------------------------------------------------------------------------

function setState(state) {
  if (ui.state !== state) {
    const card = $('card');
    card.classList.remove('swap');
    void card.offsetWidth;
    card.classList.add('swap');
  }
  ui.state = state;
  pose({ idle: 'idle', link: 'grab', image: 'grab', working: 'full', done: 'yay', error: 'oops' }[state]);
  paint();
}

function paint() {
  document.documentElement.lang = ui.lang === 'tl' ? 'fil' : 'en';
  document.documentElement.classList.toggle('still', still());
  const card = $('card'), go = $('go'), later = $('later'), meter = $('meter');
  card.dataset.state = ui.state;
  let title = '', sub = '';
  go.hidden = later.hidden = false;
  go.disabled = false;
  if (ui.state === 'idle') {
    title = say('idle');
    sub = say('idleSub');
    go.textContent = say('save');
    go.disabled = !/^https?:\/\/\S+$/.test($('link').value.trim());
    later.hidden = true;
  } else if (ui.state === 'link' || ui.state === 'image') {
    title = say(ui.state);
    sub = ui.state === 'link' ? short(ui.link) : say('imageSub');
    go.textContent = say('save');
    later.textContent = say('later');
  } else if (ui.state === 'working') {
    const { phase, percent } = ui.job || {};
    title = phase === 'saving' ? say('saving', percent) : say(phase || 'reading');
    sub = phase === 'helper' ? say('helperSub') : short(ui.link);
    meter.classList.toggle('wait', percent == null);
    $('meter-fill').style.width = `${percent ?? 0}%`;
  } else if (ui.state === 'done') {
    title = ui.captured ? say('captured') : ui.files.length > 1 ? say('savedMany', ui.files.length) : say('saved');
    sub = ui.files.map(base).join(', ');
    go.textContent = say('open');
    later.textContent = say('show');
  } else if (ui.state === 'error') {
    const key = ERRORS[ui.error];
    title = key ? say(key) : say('failed');
    sub = key ? say(`${key}Sub`) : ui.error;
    go.textContent = ui.error === 'blocked' ? say('security') : ui.error === 'login' && ui.account ? say('signInTo', ui.account.name) : say('again');
    later.textContent = say('later');
    go.hidden = ['noimage', 'gone'].includes(ui.error) || (ui.error === 'login' && !ui.account);
  }
  $('card-title').textContent = title;
  $('card-sub').textContent = sub;
  fitRecent();
  $('card-sub').title = ui.state === 'link' ? ui.link : sub;
  $('link').placeholder = say('paste');
  $('query').placeholder = say('search');
  $('gaming').hidden = !ui.playing;
  const hiding = ui.recent === 'hide';
  $('eye').firstChild.innerHTML = `<use href="#i-${hiding ? 'eye-off' : 'eye'}"/>`;
  $('eye').dataset.label = hiding ? 'showRecent' : 'hideRecent';
  for (const e of document.querySelectorAll('[data-t]')) e.textContent = say(e.dataset.t);
  for (const e of document.querySelectorAll('[data-label]')) e.setAttribute('aria-label', say(e.dataset.label)), (e.title = say(e.dataset.label));
  for (const e of document.querySelectorAll('[data-tip]')) e.title = say(e.dataset.tip);
  for (const seg of document.querySelectorAll('[data-pref]')) {
    for (const b of seg.children) b.setAttribute('aria-pressed', ui[seg.dataset.pref] === b.dataset.v);
  }
  paintLibrary();
  paintViewer();
}

// What the card shows when the panel opens. A save in progress or a fresh result stays put.
async function readClipboard() {
  if (ui.state === 'working' || ui.state === 'done' || ui.state === 'error') return;
  const clip = await invoke('clipboard').catch(() => ({}));
  if (clip.link && !dismissed.has(clip.link)) {
    ui.link = clip.link;
    setState('link');
  } else if (clip.image && !dismissed.has('image')) {
    setState('image');
  } else {
    setState('idle');
  }
}

// Thumbnails load when they scroll into view: one watcher for Recent, one for the library grid.
const loader = (root) => new IntersectionObserver((entries, watcher) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    e.target.src = e.target.dataset.src;
    watcher.unobserve(e.target);
  }
}, { root, rootMargin: '240px' });
const homeLoader = loader(null);
const gridLoader = loader($('grid'));

function thumb(r, watcher) {
  const box = el('span', `thumb ${r.kind}`);
  const later = (media, src) => { media.dataset.src = src; watcher.observe(media); return media };
  const src = convertFileSrc(r.path);
  if (r.kind === 'image') {
    box.append(later(el('img', '', { alt: '', decoding: 'async', draggable: false }), src));
  } else if (r.kind === 'gif') {
    // The first frame, drawn as it loads. The GIF itself plays on hover.
    const still = el('canvas'), img = el('img', '', { alt: '', draggable: false });
    img.onload = () => {
      const s = Math.min(1, 320 / Math.max(img.naturalWidth, img.naturalHeight, 1));
      still.width = Math.max(1, Math.round(img.naturalWidth * s));
      still.height = Math.max(1, Math.round(img.naturalHeight * s));
      still.getContext('2d').drawImage(img, 0, 0, still.width, still.height);
    };
    box.append(still, later(img, src), el('span', 'tag', { textContent: 'GIF' }));
  } else if (r.kind === 'video') {
    const video = el('video', '', { muted: true, preload: 'metadata', disablePictureInPicture: true });
    const time = el('span', 'tag time', { hidden: true });
    video.onloadedmetadata = () => { time.textContent = clock(video.duration); time.hidden = !time.textContent };
    box.append(later(video, `${src}#t=0.5`), time);
  } else {
    box.append(icon('music', 'big'));
  }
  return box;
}

function renderRecent() {
  const box = $('recent');
  box.textContent = '';
  if (ui.recent === 'hide') {
    const line = el('p', 'calm empty');
    const show = el('button', 'link', { type: 'button', textContent: say('showIt') });
    show.onclick = () => setRecent('show');
    line.append(icon('eye-off'), el('span', '', { textContent: say('recentHidden') }), show);
    box.append(line);
    return;
  }
  const list = lib.items.filter((i) => !i.hidden).sort(byNewest).slice(0, 6);
  if (!list.length) {
    box.append(el('p', 'empty', { textContent: say('none') }));
    return;
  }
  list.forEach((r, i) => {
    const item = el('button', 'item', { type: 'button', title: r.name });
    item.style.setProperty('--i', i);
    item.dataset.key = r.path;
    item.append(thumb(r, homeLoader), el('span', 'name', { textContent: r.name }),
      el('span', 'meta', { textContent: `${say('ago', Math.max(0, (Date.now() - r.modified) / 1000))} · ${size(r.bytes)}` }));
    item.onclick = () => openViewer(list, i, item, 'home');
    box.append(item);
  });
  fitRecent();
}

// Recent shows the rows that fit whole: the card is taller while it holds the paste box.
function fitRecent() {
  const box = $('recent');
  for (const item of box.children) item.style.visibility = '';
  requestAnimationFrame(() => {
    for (const item of box.children) if (item.offsetTop + item.offsetHeight > box.clientHeight + 2) item.style.visibility = 'hidden';
  });
}

function setRecent(v) {
  ui.recent = v;
  keep('recent', v);
  swap(renderRecent);
  paint();
}

async function refresh() {
  lib = await invoke('library').catch(() => lib);
  const live = new Set(lib.items.map((i) => i.path));
  for (const key of tiles.keys()) if (!live.has(key)) tiles.delete(key);
  renderRecent();
  if (ui.view === 'library') renderGrid('flip');
}

async function saveLink(url) {
  ui.link = url;
  ui.job = { phase: 'reading', percent: null };
  ui.captured = false;
  ui.account = null;
  setState('working');
  try {
    ui.files = await invoke('save_link', { url });
    dismissed.add(url);
    $('link').value = '';
    setState('done');
  } catch (e) {
    ui.error = String(e);
    if (ui.error === 'login') ui.account = await invoke('account_for', { url }).catch(() => null);
    setState('error');
  }
  refresh();
}

async function saveImage() {
  try {
    ui.files = [await invoke('save_clipboard_image')];
    ui.captured = false;
    dismissed.add('image');
    setState('done');
  } catch (e) {
    ui.error = String(e);
    setState('error');
  }
  refresh();
}

$('go').onclick = () => {
  if (ui.state === 'idle') saveLink($('link').value.trim());
  else if (ui.state === 'link') saveLink(ui.link);
  else if (ui.state === 'image') saveImage();
  else if (ui.state === 'done') invoke('open_path', { path: ui.files[0] });
  else if (ui.state === 'error' && ui.error === 'blocked') invoke('open_security');
  else if (ui.state === 'error' && ui.error === 'login' && ui.account) invoke('sign_in', { site: ui.account.id, lang: ui.lang }).catch(oops);
  else if (ui.state === 'error') ui.link && !ui.captured ? saveLink(ui.link) : readClipboard();
};
$('later').onclick = () => {
  if (ui.state === 'done') return invoke('reveal', { path: ui.files[0] });
  if (ui.state === 'link') dismissed.add(ui.link);
  if (ui.state === 'image') dismissed.add('image');
  ui.error = null;
  setState('idle');
};
$('paste').onsubmit = (e) => {
  e.preventDefault();
  if (!$('go').disabled) saveLink($('link').value.trim());
};
$('link').oninput = paint;
$('close').onclick = () => invoke('hide');
$('folder').onclick = () => invoke('open_folder');
$('capture').onclick = () => invoke('capture');
$('eye').onclick = () => setRecent(ui.recent === 'hide' ? 'show' : 'hide');
$('see-all').onclick = () => go('library');

// Views -----------------------------------------------------------------------------------------

const VIEWS = ['home', 'library', 'accounts'];
function go(view) {
  if (view === ui.view) return;
  const kind = view === 'home' ? 'back' : 'forward';
  if (ui.picking) setPicking(false);
  swap(() => {
    ui.view = view;
    for (const v of VIEWS) $(v).hidden = v !== view;
    if (view === 'library') renderGrid('enter');
    if (view === 'accounts') loadAccounts();
    paint();
  }, kind);
}
$('lib-back').onclick = () => go('home');
$('acc-back').onclick = () => go('home');

// Library ---------------------------------------------------------------------------------------

const tiles = new Map();
const item = (path) => lib.items.find((i) => i.path === path);
const inScope = (i) => ui.album == null || i.album === ui.album;
const SORTS = {
  new: byNewest,
  old: (a, b) => a.modified - b.modified,
  name: (a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }),
  size: (a, b) => b.bytes - a.bytes,
};

function shown() {
  const q = ui.query.trim().toLowerCase();
  return lib.items
    .filter((i) => inScope(i) && (!q || i.name.toLowerCase().includes(q)) && (ui.filter === 'all' || (ui.filter === 'fav' ? i.fav : i.kind === ui.filter)))
    .sort(SORTS[ui.sort]);
}

function tileFor(r) {
  let t = tiles.get(r.path);
  if (!t) {
    t = el('button', 'tile', { type: 'button' });
    t.dataset.key = r.path;
    const tick = el('span', 'tick');
    tick.append(icon('check'));
    const marks = el('span', 'marks');
    marks.append(icon('heart', 'heart'), icon('eye-off', 'shut'));
    const th = thumb(r, gridLoader);
    th.append(marks);
    t.append(th, tick, el('span', 'tname'));
    t.onclick = (e) => tap(r.path, e);
    tiles.set(r.path, t);
  }
  t.title = r.name;
  t.querySelector('.tname').textContent = r.name;
  t.classList.toggle('fav', r.fav);
  t.classList.toggle('hid', r.hidden);
  t.classList.toggle('on', ui.picked.has(r.path));
  return t;
}

// Shows the grid: 'enter' staggers tiles in, 'flip' slides tiles that stayed to their new places
// (FLIP) and fades new ones in, 'none' just updates.
function renderGrid(mode = 'none') {
  const grid = $('grid');
  const before = new Map();
  if (mode === 'flip' && !still()) for (const t of grid.children) before.set(t.dataset.key, t.getBoundingClientRect());
  ui.listed = shown();
  grid.replaceChildren(...ui.listed.map(tileFor));
  if (mode === 'enter') {
    grid.scrollTop = 0;
    [...grid.children].forEach((t, i) => { t.classList.remove('enter'); t.style.setProperty('--i', i) });
    void grid.offsetWidth;
    for (const t of grid.children) t.classList.add('enter');
  } else if (before.size) {
    flip(grid, before);
  }
  const none = !ui.listed.length, nothing = !lib.items.some(inScope);
  $('blank').hidden = !none;
  $('blank-title').textContent = say(nothing ? 'empty' : 'noMatch');
  $('blank-sub').textContent = say(nothing ? (ui.album ? 'emptyAlbum' : 'emptySub') : 'noMatchSub');
  paintLibrary();
}

function flip(grid, before) {
  const ease = cssVar('--drift'), view = grid.getBoundingClientRect();
  [...grid.children].forEach((t, i) => {
    t.classList.remove('enter');
    const b = t.getBoundingClientRect();
    if (b.bottom < view.top - 60 || b.top > view.bottom + 60) return;
    const a = before.get(t.dataset.key);
    if (!a) {
      t.animate([{ opacity: 0, transform: 'scale(.9)' }, { opacity: 1, transform: 'none' }], { duration: 420, easing: ease, delay: Math.min(i, 12) * 16, fill: 'backwards' });
    } else if (a.left !== b.left || a.top !== b.top) {
      t.animate([{ transform: `translate(${a.left - b.left}px, ${a.top - b.top}px)` }, { transform: 'none' }], { duration: 560, easing: ease });
    }
  });
}

function paintLibrary() {
  $('album-name').textContent = ui.album == null ? say('allSaves') : ui.album === '' ? say('loose') : ui.album;
  $('picked').textContent = say('picked', ui.picked.size);
  $('library').classList.toggle('picking', ui.picking);
  const listed = ui.listed || [];
  $('select-all').textContent = listed.length && listed.every((i) => ui.picked.has(i.path)) ? say('selectNone') : say('selectAll');
  for (const b of $('selbar').children) b.disabled = !ui.picked.size;
  const q = ui.query.trim().toLowerCase(), scope = lib.items.filter((i) => inScope(i) && (!q || i.name.toLowerCase().includes(q)));
  for (const b of $('filters').children) {
    const f = b.dataset.f;
    b.setAttribute('aria-pressed', ui.filter === f);
    b.querySelector('b').textContent = scope.filter((i) => f === 'all' || (f === 'fav' ? i.fav : i.kind === f)).length;
  }
  $('sort-name').textContent = say({ new: 'newest', old: 'oldest', name: 'byName', size: 'bySize' }[ui.sort]);
  for (const b of $('sort-menu').children) b.setAttribute('aria-checked', b.dataset.sort === ui.sort);
}

function tap(path, e) {
  if (ui.picking || e.ctrlKey || e.shiftKey) return pick(path);
  openViewer(ui.listed, ui.listed.findIndex((i) => i.path === path), tiles.get(path), 'library');
}

function pick(path) {
  if (!ui.picking) setPicking(true);
  ui.picked.has(path) ? ui.picked.delete(path) : ui.picked.add(path);
  tiles.get(path)?.classList.toggle('on', ui.picked.has(path));
  paintLibrary();
}

function setPicking(on) {
  ui.picking = on;
  if (!on) {
    ui.picked.clear();
    for (const t of tiles.values()) t.classList.remove('on');
  }
  paintLibrary();
}

$('select').onclick = () => setPicking(!ui.picking);
$('select-all').onclick = () => {
  const all = ui.listed.every((i) => ui.picked.has(i.path));
  for (const i of ui.listed) all ? ui.picked.delete(i.path) : ui.picked.add(i.path);
  renderGrid();
};
$('query').oninput = (e) => { ui.query = e.target.value; renderGrid('flip') };
$('filters').onclick = (e) => {
  const f = e.target.closest('[data-f]')?.dataset.f;
  if (!f || f === ui.filter) return;
  ui.filter = f;
  renderGrid('flip');
};
$('sort-menu').onclick = (e) => {
  const s = e.target.closest('[data-sort]')?.dataset.sort;
  if (!s) return;
  ui.sort = s;
  keep('sort', s);
  $('sort-menu').hidePopover();
  renderGrid('flip');
};
$('album-pick').onclick = () => albumSheet('pick');

async function mark(paths, what, on) {
  try {
    await invoke('mark', { paths, what, on });
  } catch (e) {
    return oops(e);
  }
  for (const i of lib.items) if (paths.includes(i.path)) i[what] = on;
  renderRecent();
  if (ui.view === 'library') renderGrid('flip');
  paintViewer();
  toast(what === 'fav' ? (on ? say('faved', paths.length) : say('unfaved')) : say(on ? 'hiddenHome' : 'shownHome'));
}

// Deleting goes to the Recycle Bin; Undo brings the files back from it.
async function remove(paths) {
  try {
    await invoke('trash', { paths });
  } catch (e) {
    return oops(e);
  }
  await refresh();
  relist();
  toast(say('trashed', paths.length), {
    undo: async () => {
      try {
        await invoke('restore', { paths });
        toast(say('restored'));
      } catch (e) {
        oops('restore');
      }
      await refresh();
      relist();
    },
  });
}

async function copyFiles(paths) {
  try {
    await invoke('copy_files', { paths });
    toast(say('filesCopied', paths.length));
  } catch (e) {
    oops(e);
  }
}

$('selbar').onclick = (e) => {
  const act = e.target.closest('[data-do]')?.dataset.do, paths = [...ui.picked];
  if (!act || !paths.length) return;
  if (act === 'move') return albumSheet('move', paths);
  if (act === 'fav') mark(paths, 'fav', !paths.every((p) => item(p)?.fav));
  if (act === 'hide') mark(paths, 'hidden', !paths.every((p) => item(p)?.hidden));
  if (act === 'copy') copyFiles(paths);
  if (act === 'delete') setPicking(false), remove(paths);
};

// Albums: the subfolders of the save folder. The same sheet picks what the library shows, and
// where files move to.
function albumSheet(mode, paths = []) {
  const d = $('albums'), list = $('album-list');
  $('albums-title').textContent = say(mode === 'move' ? 'moveTo' : 'albums');
  $('album-form').hidden = true;
  const count = (a) => lib.items.filter((i) => a == null || i.album === a).length;
  const rows = mode === 'move' ? [['', say('mainFolder')], ...lib.albums.map((a) => [a, a])] : [[null, say('allSaves')], ['', say('loose')], ...lib.albums.map((a) => [a, a])];
  const choose = async (a) => {
    d.close();
    if (mode === 'pick') {
      ui.album = a;
      return renderGrid('enter');
    }
    try {
      await invoke('move_to', { paths, album: a });
      setPicking(false);
      toast(say('movedTo', a || say('mainFolder')));
    } catch (e) {
      oops(e);
    }
    await refresh();
    relist();
  };
  list.replaceChildren(...rows.map(([a, label]) => {
    const li = el('li', mode === 'pick' && ui.album === a ? 'on' : '');
    const b = el('button', 'pick-row', { type: 'button' });
    b.append(icon(a == null ? 'grid' : 'folder'), el('span', '', { textContent: label }), el('b', '', { textContent: count(a) }));
    b.onclick = () => choose(a);
    li.append(b);
    if (mode === 'pick' && a) {
      const rename = el('button', 'tiny', { type: 'button', title: say('renameAlbum') });
      rename.append(icon('edit'));
      rename.onclick = () => renameAlbum(a);
      const del = el('button', 'tiny', { type: 'button', title: say('deleteAlbum') });
      del.append(icon('trash'));
      del.onclick = () => deleteAlbum(a);
      li.append(rename, del);
    }
    return li;
  }));
  const add = el('li');
  const b = el('button', 'pick-row', { type: 'button' });
  b.append(icon('plus'), el('span', '', { textContent: say('newAlbum') }));
  b.onclick = () => {
    $('album-form').hidden = false;
    $('album-input').value = '';
    $('album-input').placeholder = say('albumName');
    $('album-input').focus();
  };
  add.append(b);
  list.append(add);
  $('album-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const name = await invoke('create_album', { name: $('album-input').value });
      lib.albums.push(name);
      choose(name);
    } catch (err) {
      oops(err);
    }
  };
  d.showModal();
}
$('albums-close').onclick = () => $('albums').close();

async function renameAlbum(a) {
  $('albums').close();
  const name = await ask({ title: say('renameAlbum'), value: a });
  if (name == null || name === a) return;
  try {
    const now = await invoke('rename_album', { old: a, new: name });
    if (ui.album === a) ui.album = now;
    toast(say('renamed'));
  } catch (e) {
    oops(e);
  }
  refresh();
}

async function deleteAlbum(a) {
  $('albums').close();
  if (!(await ask({ title: `${say('deleteAlbum')}: ${a}`, sub: say('deleteAlbumSub', a), ok: say('delete'), danger: true }))) return;
  try {
    await invoke('delete_album', { name: a });
    if (ui.album === a) ui.album = null;
  } catch (e) {
    oops(e);
  }
  refresh();
}

// A small sheet that asks for a name (when `value` is given) or a yes.
function ask({ title, sub = '', value = null, ok = say('ok'), danger = false }) {
  return new Promise((done) => {
    const d = $('ask'), input = $('ask-input');
    $('ask-title').textContent = title;
    $('ask-sub').textContent = sub;
    $('ask-sub').hidden = !sub;
    $('ask-form').hidden = value == null;
    $('ask-yes').textContent = ok;
    $('ask-yes').classList.toggle('danger', danger);
    input.value = value ?? '';
    const finish = (v) => { d.onclose = null; d.close(); done(v) };
    $('ask-yes').onclick = () => finish(value == null ? true : input.value);
    $('ask-form').onsubmit = (e) => { e.preventDefault(); finish(input.value) };
    $('ask-no').onclick = () => finish(null);
    d.onclose = () => done(null);
    d.showModal();
    if (value != null) {
      input.focus();
      input.setSelectionRange(0, value.length);
    }
  });
}

// Viewer ----------------------------------------------------------------------------------------

const viewer = $('viewer'), stage = $('stage');
let about = false;

// Waits until new media can be drawn, so the morph lands on a real picture.
const ready = (m) => Promise.race([
  new Promise((done) => setTimeout(done, 300)),
  m.tagName === 'IMG' ? m.decode().catch(() => {}) : m.tagName === 'VIDEO' ? new Promise((done) => m.addEventListener('loadeddata', done, { once: true })) : Promise.resolve(),
]);

function media(r) {
  const src = convertFileSrc(r.path);
  if (r.kind === 'video') return el('video', 'media', { src, controls: true, autoplay: true, muted: false, disablePictureInPicture: true });
  if (r.kind === 'audio') {
    const box = el('div', 'media sound');
    box.append(icon('music'));
    return box;
  }
  return el('img', 'media', { src, alt: r.name, draggable: false });
}

async function show(r) {
  stage.querySelector('.media')?.remove();
  $('player')?.remove();
  const m = media(r);
  stage.prepend(m);
  if (r.kind === 'audio') stage.after(el('audio', 'player', { id: 'player', src: convertFileSrc(r.path), controls: true, autoplay: true }));
  paintViewer();
  if (about) facts();
  await ready(m);
}

function openViewer(list, at, from, origin) {
  if (at < 0) return;
  ui.list = list;
  ui.at = at;
  ui.from = origin;
  const hero = from?.querySelector('img:not(.gif img), video, canvas') || from?.querySelector('.thumb');
  if (hero) hero.style.viewTransitionName = 'hero';
  swap(async () => {
    if (hero) hero.style.viewTransitionName = '';
    viewer.hidden = false;
    await show(list[at]);
  }, 'zoom');
}

function closeViewer() {
  if (ui.at < 0) return;
  if (editing) endEdit();
  const r = ui.list[ui.at];
  const t = r && (ui.from === 'library' ? tiles.get(r.path) : $('recent').querySelector(`[data-key="${CSS.escape(r.path)}"]`));
  const hero = t?.isConnected ? t.querySelector('img:not(.gif img), video, canvas') || t.querySelector('.thumb') : null;
  if (hero) t.scrollIntoView({ block: 'nearest' });
  ui.at = -1;
  swap(() => {
    stage.querySelector('video')?.pause();
    $('player')?.remove();
    viewer.hidden = true;
    about = false;
    $('v-about').hidden = true;
    if (hero) hero.style.viewTransitionName = 'hero';
  }, 'zoom').then(() => { if (hero) hero.style.viewTransitionName = '' });
}

function step(by) {
  const at = ui.at + by;
  if (ui.at < 0 || editing || at < 0 || at >= ui.list.length) return;
  ui.at = at;
  swap(() => show(ui.list[at]), 'zoom');
}

// After a change, the viewer's list follows the library: the same file, or the next one.
function relist(path) {
  if (ui.at < 0) return;
  const was = ui.list[ui.at]?.path;
  ui.list = ui.from === 'library' ? ui.listed : lib.items.filter((i) => !i.hidden).sort(byNewest).slice(0, 6);
  const at = ui.list.findIndex((i) => i.path === (path || was));
  if (!ui.list.length) return closeViewer();
  ui.at = at >= 0 ? at : Math.min(ui.at, ui.list.length - 1);
  show(ui.list[ui.at]);
}

function paintViewer() {
  const r = ui.list[ui.at];
  if (!r || viewer.hidden) return;
  if (!editing) $('v-name').textContent = r.name;
  $('v-sub').textContent = `${ui.at + 1} / ${ui.list.length} · ${size(r.bytes)}`;
  $('v-fav').classList.toggle('on', r.fav);
  $('v-prev').disabled = ui.at <= 0;
  $('v-next').disabled = ui.at >= ui.list.length - 1;
  $('v-hide').textContent = say(r.hidden ? 'showHome' : 'hideHome');
  const tools = $('v-tools');
  tools.querySelector('[data-v="edit"]').hidden = r.kind === 'audio';
  tools.querySelector('[data-v="copy"]').title = say(r.kind === 'image' ? 'copyImage' : 'copy');
}

async function facts() {
  const r = ui.list[ui.at], m = stage.querySelector('.media');
  if (!r) return;
  const w = m?.naturalWidth || m?.videoWidth, h = m?.naturalHeight || m?.videoHeight;
  const rows = [
    [say('type'), `${say('kind')[r.kind]} · ${ext(r.name)}`],
    [say('dims'), w ? `${w} × ${h}` : say('unknown')],
    [say('size'), size(r.bytes)],
    [say('date'), new Date(r.modified).toLocaleString(ui.lang === 'tl' ? 'fil-PH' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' })],
    [say('album'), r.album || say('mainFolder')],
  ];
  const dl = $('v-facts');
  dl.replaceChildren(...rows.flatMap(([k, v]) => [el('dt', '', { textContent: k }), el('dd', '', { textContent: v })]));
  const link = await invoke('source', { path: r.path }).catch(() => null);
  if (ui.list[ui.at] !== r) return;
  const dd = el('dd', '', { textContent: link ? short(link) : say('unknown') });
  if (link) {
    const copy = el('button', 'link', { type: 'button', textContent: say('copyLink') });
    copy.onclick = () => invoke('copy_text', { text: link }).then(() => toast(say('linkCopied')));
    dd.append(copy);
  }
  dl.append(el('dt', '', { textContent: say('source') }), dd);
  if (!w && m?.tagName) m.addEventListener(m.tagName === 'IMG' ? 'load' : 'loadedmetadata', facts, { once: true });
}

async function copyImage(r) {
  try {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.src = convertFileSrc(r.path);
    await img.decode();
    const c = el('canvas', '', { width: img.naturalWidth, height: img.naturalHeight });
    c.getContext('2d').drawImage(img, 0, 0);
    const blob = await new Promise((done) => c.toBlob(done, 'image/png'));
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
    toast(say('imageCopied'));
  } catch {
    copyFiles([r.path]);
  }
}

async function renameFile(r) {
  const stem = r.name.includes('.') ? r.name.slice(0, r.name.lastIndexOf('.')) : r.name;
  const name = await ask({ title: say('rename'), value: stem });
  if (name == null || name === stem) return;
  try {
    const path = await invoke('rename', { path: r.path, name });
    toast(say('renamed'));
    await refresh();
    relist(path);
  } catch (e) {
    oops(e);
  }
}

async function act(a) {
  const r = ui.list[ui.at];
  if (!r) return;
  $('v-menu').hidePopover?.();
  if (a === 'open') invoke('open_path', { path: r.path }).catch(oops);
  else if (a === 'reveal') invoke('reveal', { path: r.path }).catch(oops);
  else if (a === 'copy') r.kind === 'image' ? copyImage(r) : copyFiles([r.path]);
  else if (a === 'edit') r.kind === 'image' ? editPhoto(r) : videoTools(r);
  else if (a === 'move') albumSheet('move', [r.path]);
  else if (a === 'delete') remove([r.path]);
  else if (a === 'rename') renameFile(r);
  else if (a === 'path') invoke('copy_text', { text: r.path }).then(() => toast(say('pathCopied')));
  else if (a === 'hide') mark([r.path], 'hidden', !r.hidden);
}
$('v-tools').onclick = (e) => { const a = e.target.closest('[data-v]')?.dataset.v; if (a && a !== 'more') act(a) };
$('v-menu').onclick = (e) => { const a = e.target.closest('[data-v]')?.dataset.v; if (a) act(a) };
$('v-back').onclick = () => (editing ? endEdit() : closeViewer());
$('v-prev').onclick = () => step(-1);
$('v-next').onclick = () => step(1);
$('v-fav').onclick = () => { const r = ui.list[ui.at]; if (r) mark([r.path], 'fav', !r.fav) };
$('v-info').onclick = () => {
  about = !about;
  $('v-about').hidden = !about;
  $('v-info').classList.toggle('on', about);
  if (about) facts();
};

// Photo editor: rotate, flip and crop on a canvas, saved as a copy beside the original.
let editing = null;

function editPhoto(r) {
  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.src = convertFileSrc(r.path);
  img.decode().then(() => {
    editing = { r, img, rot: 0, fx: 1, fy: 1, aspect: 0, fmt: ['png', 'webp'].includes(ext(r.name).toLowerCase()) ? ext(r.name).toLowerCase() : 'jpg' };
    viewer.classList.add('editing');
    $('editor').hidden = false;
    $('v-name').textContent = say('editTitle');
    stage.querySelector('.media').hidden = true;
    const crop = el('div', 'crop');
    for (const c of ['nw', 'ne', 'sw', 'se']) {
      const handle = el('i');
      handle.dataset.c = c;
      crop.append(handle);
    }
    stage.prepend(el('canvas', 'work'), crop);
    draw(true);
    paintEditor();
  }).catch(() => oops('failed'));
}

// Draws the photo turned and flipped, and resets the crop to the whole photo (or the aspect).
function draw(reset) {
  const { img, rot, fx, fy } = editing, c = stage.querySelector('.work');
  const side = rot % 180 !== 0;
  c.width = side ? img.naturalHeight : img.naturalWidth;
  c.height = side ? img.naturalWidth : img.naturalHeight;
  const g = c.getContext('2d');
  g.setTransform(1, 0, 0, 1, 0, 0);
  g.translate(c.width / 2, c.height / 2);
  g.rotate((rot * Math.PI) / 180);
  g.scale(fx, fy);
  g.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
  if (reset) fit();
  requestAnimationFrame(placeCrop);
}

function fit() {
  const c = stage.querySelector('.work'), a = editing.aspect;
  let w = c.width, h = c.height;
  if (a) (w / h > a ? (w = h * a) : (h = w / a));
  editing.crop = { x: (c.width - w) / 2, y: (c.height - h) / 2, w, h };
}

function placeCrop() {
  if (!editing) return;
  const c = stage.querySelector('.work'), box = stage.querySelector('.crop'), s = c.clientWidth / c.width, { x, y, w, h } = editing.crop;
  Object.assign(box.style, { left: `${c.offsetLeft + x * s}px`, top: `${c.offsetTop + y * s}px`, width: `${w * s}px`, height: `${h * s}px` });
}

function paintEditor() {
  for (const b of $('aspects').children) b.setAttribute('aria-pressed', Number(b.dataset.a) === editing.aspect);
  for (const b of $('formats').children) b.setAttribute('aria-pressed', b.dataset.x === editing.fmt);
}

// Dragging the crop box moves it; dragging a corner resizes it, keeping the aspect when set.
stage.addEventListener('pointerdown', (e) => {
  const box = e.target.closest('.crop');
  if (!editing || !box) return;
  const c = stage.querySelector('.work'), s = c.width / c.clientWidth, start = { ...editing.crop }, corner = e.target.dataset.c;
  const [x0, y0] = [e.clientX, e.clientY];
  box.setPointerCapture(e.pointerId);
  const move = (m) => {
    const dx = (m.clientX - x0) * s, dy = (m.clientY - y0) * s, a = editing.aspect, min = 24 * s;
    let { x, y, w, h } = start;
    if (!corner) {
      x = Math.min(Math.max(0, x + dx), c.width - w);
      y = Math.min(Math.max(0, y + dy), c.height - h);
    } else {
      const left = corner.includes('w'), top = corner.includes('n');
      const right = x + w, bottom = y + h;
      w = Math.max(min, left ? w - dx : w + dx);
      h = Math.max(min, top ? h - dy : h + dy);
      if (a) h = w / a;
      w = Math.min(w, left ? right : c.width - x);
      h = Math.min(h, top ? bottom : c.height - y);
      if (a) (w / h > a ? (w = h * a) : (h = w / a));
      if (left) x = right - w;
      if (top) y = bottom - h;
    }
    editing.crop = { x, y, w, h };
    placeCrop();
  };
  const up = () => { box.removeEventListener('pointermove', move); box.removeEventListener('pointerup', up) };
  box.addEventListener('pointermove', move);
  box.addEventListener('pointerup', up);
});

$('aspects').onclick = (e) => {
  const b = e.target.closest('[data-a]');
  if (!b || !editing) return;
  editing.aspect = Number(b.dataset.a);
  fit();
  placeCrop();
  paintEditor();
};
$('formats').onclick = (e) => {
  const b = e.target.closest('[data-x]');
  if (!b || !editing) return;
  editing.fmt = b.dataset.x;
  paintEditor();
};
$('editor').addEventListener('click', (e) => {
  const op = e.target.closest('[data-e]')?.dataset.e;
  if (!op || !editing) return;
  if (op === 'rotl' || op === 'rotr') {
    editing.rot = (editing.rot + (op === 'rotr' ? 90 : 270)) % 360;
    return draw(true);
  }
  // A flip mirrors the crop box with the photo.
  const c = stage.querySelector('.work'), k = editing.crop;
  if (op === 'fliph') (editing.rot % 180 ? (editing.fy *= -1) : (editing.fx *= -1)), (k.x = c.width - k.x - k.w);
  if (op === 'flipv') (editing.rot % 180 ? (editing.fx *= -1) : (editing.fy *= -1)), (k.y = c.height - k.y - k.h);
  draw(false);
});

function endEdit() {
  editing = null;
  viewer.classList.remove('editing');
  $('editor').hidden = $('vtools').hidden = true;
  stage.querySelector('.work')?.remove();
  stage.querySelector('.crop')?.remove();
  const m = stage.querySelector('.media');
  if (m) m.hidden = false;
  paintViewer();
}
$('e-cancel').onclick = endEdit;

// JSON with every non-ASCII character escaped, so a path fits in a request header.
const ascii = (s) => JSON.stringify(s).replace(/[\u007f-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);

$('e-save').onclick = async () => {
  const { r, crop, fmt } = editing, c = stage.querySelector('.work');
  const out = el('canvas', '', { width: Math.round(crop.w), height: Math.round(crop.h) });
  const g = out.getContext('2d');
  if (fmt === 'jpg') (g.fillStyle = '#fff'), g.fillRect(0, 0, out.width, out.height);
  g.drawImage(c, crop.x, crop.y, crop.w, crop.h, 0, 0, out.width, out.height);
  $('e-save').disabled = true;
  try {
    const blob = await new Promise((done) => out.toBlob(done, { png: 'image/png', jpg: 'image/jpeg', webp: 'image/webp' }[fmt], 0.92));
    const bytes = new Uint8Array(await blob.arrayBuffer());
    const path = await invoke('save_edit', bytes, { headers: { path: ascii(r.path), ext: fmt } });
    endEdit();
    toast(say('savedCopy'));
    await refresh();
    relist(path);
  } catch (e) {
    oops(e);
  } finally {
    $('e-save').disabled = false;
  }
};

// Video tools: trim, video to GIF and GIF to MP4, through FFmpeg when it's installed.
function videoTools(r) {
  editing = { r, video: true };
  viewer.classList.add('editing');
  $('vtools').hidden = false;
  $('v-name').textContent = say('videoTools');
  const has = lib.ffmpeg, video = stage.querySelector('video.media');
  $('vt-none').hidden = has;
  $('vt-trim').hidden = !has || r.kind !== 'video';
  $('vt-gif').hidden = !has || r.kind !== 'video';
  $('vt-go').hidden = !has;
  $('vt-go').textContent = say(r.kind === 'video' ? 'saveTrim' : 'toMp4');
  if (!has || !video) return;
  const [from, to] = [$('r-from'), $('r-to')];
  const set = () => {
    const d = video.duration || 0;
    from.max = to.max = d;
    from.value = 0;
    to.value = d;
    paintTrim();
  };
  video.readyState >= 1 ? set() : video.addEventListener('loadedmetadata', set, { once: true });
}

function paintTrim() {
  const [from, to] = [$('r-from'), $('r-to')], d = Number(from.max) || 1;
  $('range').style.setProperty('--a', `${(from.value / d) * 100}%`);
  $('range').style.setProperty('--b', `${(to.value / d) * 100}%`);
  $('t-from').textContent = `${say('trimFrom')} ${clock(Number(from.value))}`;
  $('t-to').textContent = `${say('trimTo')} ${clock(Number(to.value))}`;
}
$('range').oninput = (e) => {
  const [from, to] = [$('r-from'), $('r-to')], video = stage.querySelector('video.media');
  if (Number(from.value) > Number(to.value) - 0.2) e.target === from ? (from.value = Number(to.value) - 0.2) : (to.value = Number(from.value) + 0.2);
  if (video) video.currentTime = Number(e.target.value);
  paintTrim();
};

async function runTool(op) {
  const r = editing?.r;
  if (!r) return;
  const [from, to] = [Number($('r-from').value), Number($('r-to').value)];
  // A GIF of the whole video passes no range; a trim always does.
  const whole = op !== 'trim' && !(from > 0.05 || to < Number($('r-to').max) - 0.05);
  const buttons = [$('vt-go'), $('vt-gif')];
  const label = $('vt-go').textContent;
  for (const b of buttons) b.disabled = true;
  $('vt-go').textContent = say('working');
  pose('full', $('lodi'));
  try {
    const path = await invoke('convert', { path: r.path, op, from: whole ? 0 : from, to: whole ? 0 : to });
    endEdit();
    toast(say('savedCopy'));
    await refresh();
    relist(path);
  } catch (e) {
    oops(e);
  } finally {
    for (const b of buttons) b.disabled = false;
    $('vt-go').textContent = label;
  }
}
$('vt-go').onclick = () => runTool(editing?.r.kind === 'gif' ? 'mp4' : 'trim');
$('vt-gif').onclick = () => runTool('gif');
$('vt-cancel').onclick = endEdit;

// Accounts --------------------------------------------------------------------------------------

async function loadAccounts() {
  const list = await invoke('accounts').catch(() => []);
  $('sites').replaceChildren(...list.map((a, i) => {
    const li = el('li', a.signed ? 'yes' : '');
    li.style.setProperty('--i', i);
    const who = el('div');
    who.append(el('b', '', { textContent: a.name }), el('small', '', { textContent: say(a.signed ? 'signedIn' : 'notSigned') }));
    const b = el('button', 'ghost small', { type: 'button', textContent: say(a.signed ? 'signOut' : 'signIn') });
    b.onclick = async () => {
      if (!a.signed) return invoke('sign_in', { site: a.id, lang: ui.lang }).catch(oops);
      try {
        await invoke('sign_out', { site: a.id });
        toast(say('signedOutOf', a.name));
      } catch (e) {
        oops(e);
      }
      loadAccounts();
    };
    li.append(who, b);
    return li;
  }));
  $('safe').replaceChildren(...say('safe', say('theSite'), true).map((line) => el('li', '', { textContent: line })));
  $('safe-use').textContent = say('safeUse', say('theSite'));
}
$('open-accounts').onclick = () => { $('prefs').close(); go('accounts') };

// A sign-in finished in its window: say so, and retry the save that needed it.
listen('accounts://changed', ({ payload }) => {
  if (!payload.signed) return toast(say('signInFailed'), { mood: 'oops' });
  toast(say('signedInTo', payload.name));
  if (ui.view === 'accounts') loadAccounts();
  if (ui.state === 'error' && ui.error === 'login' && ui.account?.id === payload.site) saveLink(ui.link);
});

// Intro -----------------------------------------------------------------------------------------

const INTRO = [
  () => {
    const lodi = $('lodi').cloneNode(true);
    lodi.removeAttribute('id');
    lodi.setAttribute('width', 132);
    lodi.setAttribute('height', 132);
    setTimeout(() => pose('yay', lodi), 250);
    return [lodi, el('h1', '', { textContent: say('intro1') }), el('p', '', { textContent: say('intro1Sub') })];
  },
  () => {
    const how = el('ul', 'how');
    [['link', 'how1'], ['capture', 'how2'], ['grid', 'how3']].forEach(([i, t], n) => {
      const li = el('li');
      li.style.setProperty('--i', n);
      li.append(icon(i), el('span', '', { textContent: say(t) }));
      how.append(li);
    });
    return [el('h1', '', { textContent: say('intro2') }), how];
  },
  () => {
    const setup = el('button', 'ghost', { type: 'button', textContent: say('setupExt') });
    setup.onclick = () => { invoke('open_extension'); move(1) };
    return [el('h1', '', { textContent: say('intro3') }), el('p', '', { textContent: say('intro3Sub') }), setup, el('p', 'note', { textContent: say('introAccounts') })];
  },
  () => {
    const rows = [['language', 'lang', [['en', 'English'], ['tl', 'Taglish']]], ['motion', 'motion', [['on', say('on')], ['off', say('off')]]],
      ['autostart', 'autostart', [['on', say('on')], ['off', say('off')]]], ['gaming', 'gaming', [['auto', say('auto')], ['off', say('off')]]]];
    return [el('h1', '', { textContent: say('intro4') }), el('p', '', { textContent: say('intro4Sub') }), ...rows.map(([label, key, opts]) => {
      const row = el('p', 'row');
      const seg = el('span', 'seg');
      seg.dataset.pref = key;
      seg.append(...opts.map(([v, t]) => {
        const b = el('button', '', { type: 'button', textContent: t });
        b.dataset.v = v;
        return b;
      }));
      row.append(el('span', '', { textContent: say(label) }), seg);
      return row;
    })];
  },
];

function renderStep() {
  $('step').replaceChildren(...INTRO[ui.step]());
  $('intro-back').style.visibility = ui.step ? 'visible' : 'hidden';
  $('intro-next').textContent = say(ui.step === INTRO.length - 1 ? 'start' : 'next');
  [...$('dots').children].forEach((d, i) => d.classList.toggle('on', i === ui.step));
  paint();
}

function move(by) {
  const at = ui.step + by;
  if (at >= INTRO.length) return endIntro();
  if (at < 0) return;
  ui.step = at;
  swap(renderStep, by > 0 ? 'forward' : 'back');
}

function startIntro() {
  ui.step = 0;
  swap(() => { $('intro').hidden = false; renderStep() });
}

function endIntro() {
  keep('intro', 'done');
  swap(() => ($('intro').hidden = true));
}
$('skip').onclick = endIntro;
$('intro-next').onclick = () => move(1);
$('intro-back').onclick = () => move(-1);
$('intro-again').onclick = () => { $('prefs').close(); startIntro() };

// Settings --------------------------------------------------------------------------------------

$('open-prefs').onclick = () => $('prefs').showModal();
$('close-prefs').onclick = () => $('prefs').close();
$('extension').onclick = () => invoke('open_extension');
$('repo').onclick = () => invoke('open_repo');
$('prefs').onclose = () => {
  if (ui.ext) invoke('end_setup');
  ui.ext = null;
  $('steps').hidden = true;
  paint();
};
for (const [id, key] of [['copy-page', 'page'], ['copy-dir', 'dir']]) {
  $(id).onclick = async () => {
    await invoke('copy_text', { text: ui.ext[key] });
    $(id).textContent = say('copied');
  };
}
// Every on/off choice, in Settings and in the intro.
document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-pref] > [data-v]');
  if (!b) return;
  const key = b.parentElement.dataset.pref, v = b.dataset.v;
  ui[key] = v;
  if (key === 'autostart') invoke('set_autostart', { on: v === 'on' });
  else keep(key, v);
  if (key === 'gaming') invoke('set_gaming', { auto: v === 'auto' });
  if (key === 'recent') renderRecent();
  if (key === 'lang' && !$('intro').hidden) return renderStep();
  if (key === 'lang') renderRecent(), ui.view === 'accounts' && loadAccounts();
  paint();
});

document.addEventListener('keydown', (e) => {
  if (document.querySelector('dialog[open], :popover-open')) return;
  if (!$('intro').hidden) {
    if (e.key === 'Escape') endIntro();
    return;
  }
  if (ui.at >= 0) {
    if (e.key === 'Escape') editing ? endEdit() : closeViewer();
    else if (editing || e.target.tagName === 'INPUT') return;
    else if (e.key === 'ArrowLeft') step(-1);
    else if (e.key === 'ArrowRight') step(1);
    else if (e.key === 'Delete') act('delete');
    return;
  }
  if (e.key !== 'Escape') return;
  if (ui.picking) setPicking(false);
  else if (ui.view !== 'home') go('home');
  else invoke('hide');
});

// Panel -----------------------------------------------------------------------------------------

// A peek reports a background result without taking focus, then tucks itself away.
let peekTimer = 0;
const panel = $('panel');
panel.onmouseenter = () => clearTimeout(peekTimer);

listen('panel://shown', ({ payload }) => {
  panel.classList.remove('in', 'out');
  void panel.offsetWidth;
  panel.classList.add('in');
  clearTimeout(peekTimer);
  if (payload?.peek) peekTimer = setTimeout(() => { if (!panel.matches(':hover')) invoke('hide') }, 4500);
  if (ui.state === 'done' || ui.state === 'error') {
    // An old result gives way to whatever is on the clipboard now.
    if (!payload?.peek && Date.now() - (ui.shownAt || 0) > 8000) ui.state = 'idle';
  }
  ui.shownAt = Date.now();
  readClipboard();
  refresh();
  if (!payload?.peek && pref('intro', '') !== 'done' && $('intro').hidden) startIntro();
});
listen('panel://closing', () => panel.classList.add('out'));
// Set up opened the browser. The panel stays up as the checklist until Done.
listen('extension://opened', ({ payload }) => {
  clearTimeout(peekTimer);
  ui.ext = payload;
  $('steps').hidden = false;
  if (!$('prefs').open) $('prefs').showModal();
  paint();
});
listen('gaming://changed', ({ payload }) => {
  ui.playing = payload;
  paint();
});

listen('job://progress', ({ payload }) => {
  ui.job = payload;
  if (ui.state === 'working') paint();
});
listen('capture://failed', ({ payload }) => {
  ui.error = payload;
  ui.captured = true;
  ui.shownAt = Date.now();
  setState('error');
});
listen('capture://saved', ({ payload }) => {
  ui.files = [payload];
  ui.captured = true;
  ui.shownAt = Date.now();
  setState('done');
  refresh();
});

(async () => {
  const info = await invoke('info').catch(() => ({}));
  ui.autostart = info.autostart ? 'on' : 'off';
  ui.playing = !!info.gaming;
  invoke('set_gaming', { auto: ui.gaming === 'auto' });
  $('version').textContent = `LodiSave ${info.version || ''}`;
  $('keys').hidden = !info.shortcut;
  paint();
  readClipboard();
  refresh();
  const visible = await window.__TAURI__.window.getCurrentWindow().isVisible().catch(() => false);
  if (visible && pref('intro', '') !== 'done') startIntro();
})();
