// Everything found in the active tab, as a masonry grid. Click a tile to select it, or use its
// Save pill. The big button saves the selection, or everything in the current filter.
const COPY = {
  en: {
    looking: 'Looking…', found: (n) => `Found ${n}!`, on: (h) => `on ${h}`,
    none: 'Nothing yet', noneSub: 'Play the video or scroll the page, then open LodiSave again.',
    yt: 'Not on YouTube', ytSub: 'The LodiSave app saves YouTube links.',
    blocked: 'Can\'t look here', blockedSub: 'Browser pages and stores are off-limits.',
    all: 'All', image: 'Images', video: 'Videos',
    save: 'Save', saving: 'Saving…', saved: 'Saved', failed: 'Failed', part: 'Part',
    saveAll: (n) => `Save all ${n}`, saveSome: (n) => `Save ${n}`, progress: (i, n) => `Saving ${i} of ${n}…`, show: 'Show in folder',
    done: (n) => `Saved ${n}!`, where: 'in Downloads › LodiSave', bad: (n) => `${n} couldn't be saved.`,
    hint: 'Page blocks right-click? Hold Shift while you click.',
    small: (n) => `Show ${n} small`, hideSmall: 'Hide small',
    settings: 'Settings', language: 'Language', motion: 'Motion', motionOn: 'On', motionOff: 'Off', close: 'Done',
    hover: 'Checkbox on hover', hoverHint: 'Hover a photo or video on any site, tick its circle, then save everything you ticked from the bar.',
  },
  tl: {
    looking: 'Naghahanap…', found: (n) => `May ${n}!`, on: (h) => `sa ${h}`,
    none: 'Wala pa', noneSub: 'I-play o i-scroll muna, tapos buksan ulit ang LodiSave.',
    yt: 'Bawal sa YouTube', ytSub: 'Sa LodiSave app i-save ang YouTube links.',
    blocked: 'Bawal dito', blockedSub: 'Hindi pwede sa mga page ng browser at store.',
    all: 'Lahat', image: 'Larawan', video: 'Video',
    save: 'I-save', saving: 'Sine-save…', saved: 'Na-save', failed: 'Pumalya', part: 'Bahagi',
    saveAll: (n) => `I-save lahat (${n})`, saveSome: (n) => `I-save ang ${n}`, progress: (i, n) => `Sine-save ${i} sa ${n}…`, show: 'Buksan ang folder',
    done: (n) => `Na-save ang ${n}!`, where: 'nasa Downloads › LodiSave', bad: (n) => `${n} ang hindi na-save.`,
    hint: 'Ayaw ng right-click? Pindutin ang Shift habang nagki-click.',
    small: (n) => `Ipakita ang ${n} maliit`, hideSmall: 'Itago ang maliliit',
    settings: 'Settings', language: 'Wika', motion: 'Galaw', motionOn: 'On', motionOff: 'Off', close: 'Sige',
    hover: 'Checkbox pag naka-hover', hoverHint: 'I-hover ang photo o video sa kahit anong site, i-tick ang bilog, tapos i-save lahat ng na-tick mula sa bar.',
  },
};
const KIND = { video: 'Video', audio: 'Audio', stream: 'Stream', hls: 'HLS', dash: 'DASH' };
const SMALL = 64; // px; smaller images (icons, spacers) hide behind "Show N small"
const ICON = {
  lock: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>',
  play: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M8 5.5v13l10.5-6.5Z"/></svg>',
  image: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3.5" y="4.5" width="17" height="15" rx="3"/><path d="M4 16l5-5 4 4 2-2 5 5"/></svg>',
  check: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
};

const $ = (id) => document.getElementById(id);
const make = (tag, cls, text) => Object.assign(document.createElement(tag), cls ? { className: cls } : {}, text ? { textContent: text } : {});
const pref = (k, d) => { try { return localStorage.getItem(k) || d } catch { return d } };
const keep = (k, v) => { try { localStorage.setItem(k, v) } catch {} };

const ui = { lang: pref('lang', 'en'), motion: pref('motion', 'on'), hover: pref('hover', 'on'), phase: 'looking', host: '', filter: 'all', small: false, progress: null, ok: 0, bad: 0, lastId: null, note: null };
const tiles = [];
let tabId;
const say = (key, ...args) => { const v = COPY[ui.lang][key]; return typeof v === 'function' ? v(...args) : v };

function pose(p) {
  const lodi = $('lodi'), hop = lodi.querySelector('.hop');
  if (lodi.dataset.pose === p) return;
  lodi.dataset.pose = p;
  hop.classList.remove('go');
  void hop.getBoundingClientRect(); // restarts the hop animation
  hop.classList.add('go');
}

const isVideo = (it) => it.kind !== 'image';
// A link's or meta tag's box says nothing about the media's size.
const sizeOf = (it) => Math.max(it.w || 0, it.h || 0) || (/^(link|meta|network)$/.test(it.via) ? 0 : Math.max(it.rw || 0, it.rh || 0));
const mb = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`);
const typeOf = (it) => (/^data:image\/([a-z+]+)/.exec(it.url)?.[1] || /\.([a-z0-9]{3,4})(?:$|[?#])/i.exec(it.url)?.[1] || '').replace('svg+xml', 'svg').toUpperCase();
const badgeText = (it) => (isVideo(it) ? KIND[it.kind] : it.w && it.h ? `${it.w}×${it.h}` : typeOf(it));
// Videos and streams first, then the biggest images; items LodiSave can't save go last.
const rank = (it) => (it.unsupported ? 0 : isVideo(it) ? 2e9 + (it.bytes || 0) : (it.w * it.h || it.rw * it.rh || 1));

function placeholder(it) {
  const ph = make('span', 'ph');
  ph.innerHTML = it.unsupported ? ICON.lock : isVideo(it) ? ICON.play : ICON.image;
  return ph;
}

// The media itself when the popup can load it, else a placeholder.
function preview(t) {
  const { it } = t, src = it.alts?.[0] || it.url;
  const fit = (w, h) => {
    if (!w || !h) return;
    t.fig.style.setProperty('--ar', Math.min(1.8, Math.max(0.6, w / h)).toFixed(3));
    if (!isVideo(it) && Math.max(w, h) < SMALL && !t.small) { t.small = true; paint() }
  };
  fit(it.w || it.rw, it.h || it.rh);
  let media = null;
  if (!it.unsupported && /^(https?|data):/.test(src) && it.kind === 'image') {
    media = make('img');
    media.onload = () => fit(media.naturalWidth, media.naturalHeight);
    Object.assign(media, { alt: '', loading: 'lazy', decoding: 'async', src });
  } else if (!it.unsupported && /^https?:/.test(src) && it.kind === 'video') {
    media = make('video');
    media.onloadedmetadata = () => fit(media.videoWidth, media.videoHeight);
    Object.assign(media, { muted: true, preload: 'metadata', playsInline: true, src });
  }
  if (!media) return placeholder(it);
  media.onerror = () => media.replaceWith(placeholder(it));
  return media;
}

function addTile(it, i) {
  const t = { it, fig: make('figure', 'tile'), pick: make('button', 'pick'), save: make('button', 'save'), sel: false, done: false };
  t.small = !isVideo(it) && sizeOf(it) > 0 && sizeOf(it) < SMALL;
  const { fig, pick, save } = t;
  fig.style.setProperty('--i', i);
  pick.type = save.type = 'button';
  pick.append(preview(t));
  const label = `${it.kind} · ${it.via}${it.w && it.h ? ` · ${it.w}×${it.h}` : ''}${it.bytes ? ` · ${mb(it.bytes)}` : ''}`;
  pick.title = it.url.startsWith('data:') ? label : it.url.slice(0, 300);
  fig.append(pick, make('figcaption', 'sr', label));
  const text = badgeText(it);
  if (text) fig.append(make('span', 'badge', text));
  if (it.partial) fig.append(Object.assign(make('span', 'badge warn'), { title: it.partial }));
  fig.append(Object.assign(make('span', 'mark'), { innerHTML: ICON.check }));
  if (it.unsupported) {
    fig.classList.add('off');
    pick.disabled = true;
    fig.append(make('p', 'why', it.unsupported));
  } else {
    save.dataset.s = 'save';
    fig.append(save);
    pick.onclick = () => { t.sel = !t.sel; paint() };
    save.onclick = async () => {
      pose('full');
      const r = await saveTile(t);
      pose(r.ok ? 'yay' : 'oops');
      paint();
    };
  }
  tiles.push(t);
  $('grid').append(fig);
}

async function saveTile(t) {
  t.save.disabled = true;
  t.save.dataset.s = 'saving';
  paintTile(t);
  const r = await chrome.runtime.sendMessage({ type: 'save', tabId, item: t.it }).catch((e) => ({ error: e.message }));
  t.save.dataset.s = r.ok ? 'saved' : 'failed';
  t.save.disabled = !!r.ok;
  t.save.title = r.error || r.filename || '';
  t.done = !!r.ok;
  t.sel = false;
  if (r.ok) ui.lastId = r.id;
  if (r.warning || r.error) ui.note = { text: r.warning || r.error, cls: r.ok ? 'warn' : 'bad' };
  return r;
}

const shown = (t) => (ui.filter === 'all' || (ui.filter === 'video') === isVideo(t.it)) && (ui.small || !t.small);
const saveable = (t) => !t.it.unsupported && !t.done;

async function saveMany() {
  const picked = tiles.filter((t) => t.sel && saveable(t));
  const list = picked.length ? picked : tiles.filter((t) => shown(t) && saveable(t));
  if (!list.length) return;
  ui.phase = 'saving';
  ui.ok = ui.bad = 0;
  pose('full');
  for (const [i, t] of list.entries()) {
    ui.progress = [i + 1, list.length];
    paint();
    (await saveTile(t)).ok ? ui.ok++ : ui.bad++;
  }
  ui.progress = null;
  ui.phase = 'saved';
  pose(ui.ok ? 'yay' : 'oops');
  paint();
}

function paintTile(t) {
  t.fig.hidden = !shown(t);
  t.fig.classList.toggle('sel', t.sel);
  t.fig.classList.toggle('done', t.done);
  t.pick.setAttribute('aria-pressed', t.sel);
  const warn = t.fig.querySelector('.badge.warn');
  if (warn) warn.textContent = say('part');
  if (t.save.dataset.s) t.save.textContent = say(t.save.dataset.s);
}

function paint() {
  document.documentElement.lang = ui.lang === 'tl' ? 'fil' : 'en';
  document.documentElement.classList.toggle('still', ui.motion === 'off');
  const visible = tiles.filter((t) => ui.small || !t.small);
  const counts = { all: visible.length, image: visible.filter((t) => !isVideo(t.it)).length, video: visible.filter((t) => isVideo(t.it)).length };
  const empty = /^(none|yt|blocked)$/.test(ui.phase);
  document.body.classList.toggle('empty', empty);

  $('title').textContent = empty || ui.phase === 'looking' ? say(ui.phase) : ui.phase === 'saved' ? say('done', ui.ok) : say('found', counts.all);
  $('sub').textContent = empty ? say(`${ui.phase}Sub`) : ui.phase === 'saved' ? say('where') : ui.host ? say('on', ui.host) : '';

  for (const b of $('tabs').children) {
    const f = b.dataset.f;
    b.textContent = ui.phase === 'looking' ? say(f) : `${say(f)} ${counts[f]}`;
    b.setAttribute('aria-selected', ui.filter === f);
    b.disabled = f !== 'all' && !counts[f];
  }
  $('open-prefs').setAttribute('aria-label', say('settings'));
  $('open-prefs').title = say('settings');
  for (const t of tiles) paintTile(t);

  const more = $('more'), small = tiles.filter((t) => t.small).length;
  if (more) {
    more.hidden = !small;
    more.textContent = ui.small ? say('hideSmall') : say('small', small);
  }

  const picked = tiles.filter((t) => t.sel && saveable(t)).length;
  const left = tiles.filter((t) => shown(t) && saveable(t)).length;
  const folder = !picked && !left && !!ui.lastId;
  const primary = $('primary');
  primary.dataset.folder = folder;
  primary.disabled = ui.phase === 'looking' || ui.phase === 'saving' || (!picked && !left && !folder);
  $('primary-text').textContent = ui.progress ? say('progress', ...ui.progress) : picked ? say('saveSome', picked) : folder ? say('show') : say('saveAll', left);

  const note = (ui.phase === 'saved' && ui.bad ? { text: say('bad', ui.bad), cls: 'bad' } : null) || ui.note || { text: say('hint'), cls: '' };
  $('note').textContent = note.text;
  $('note').className = `note ${note.cls}`;

  for (const el of document.querySelectorAll('[data-t]')) el.textContent = say(el.dataset.t);
  for (const seg of document.querySelectorAll('[data-pref]')) {
    for (const b of seg.children) b.setAttribute('aria-pressed', ui[seg.dataset.pref] === b.dataset.v);
  }
}

$('tabs').onclick = (e) => {
  const f = e.target.closest('[data-f]')?.dataset.f;
  if (f) { ui.filter = f; paint() }
};
$('primary').onclick = () => ($('primary').dataset.folder === 'true' ? chrome.downloads.show(ui.lastId) : saveMany());
$('open-prefs').onclick = () => $('prefs').showModal();
$('close-prefs').onclick = () => $('prefs').close();
$('prefs').onclick = (e) => {
  const b = e.target.closest('[data-v]'), key = b?.parentElement.dataset.pref;
  if (!key) return;
  ui[key] = b.dataset.v;
  keep(key, b.dataset.v);
  // The hover picker on pages reads these too.
  chrome.storage.local.set({ [key]: key === 'hover' ? b.dataset.v === 'on' : b.dataset.v });
  paint();
};

(async () => {
  paint();
  for (const ar of [0.8, 1.2, 0.7, 1, 1.3, 0.9]) $('grid').append(Object.assign(make('div', 'skel'), { style: `--ar:${ar}` }));
  tabId = +new URLSearchParams(location.search).get('tab') || (await chrome.tabs.query({ active: true, currentWindow: true }))[0].id;
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  try { ui.host = new URL(tab.url).hostname.replace(/^www\./, '') } catch {}
  const res = await chrome.runtime.sendMessage({ type: 'scan', tabId }).catch((e) => ({ error: e.message }));
  $('grid').textContent = '';
  if (res.error || !res.items.length) {
    ui.phase = res.error ? (res.youtube ? 'yt' : 'blocked') : 'none';
    if (res.error) $('sub').title = res.error;
    pose('oops');
    return paint();
  }
  res.items.sort((a, b) => rank(b) - rank(a)).forEach(addTile);
  const more = Object.assign(make('button', 'more'), { id: 'more', type: 'button' });
  more.onclick = () => { ui.small = !ui.small; paint() };
  $('grid').append(more);
  ui.phase = 'found';
  pose('grab');
  paint();
})();
