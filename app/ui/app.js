// The tray panel. The card at the top follows the clipboard: a copied link or image is one Save
// away. Below it are the latest files in Downloads\LodiSave and the screen capture button.
const { invoke, convertFileSrc } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const COPY = {
  en: {
    idle: 'Copy a link to save it', idleSub: 'Instagram, TikTok, X, Facebook, Reddit, YouTube and more.', paste: 'Paste a link',
    link: 'Link copied!', image: 'Image copied!', imageSub: 'Saves as a PNG.',
    save: 'Save', later: 'Later', open: 'Open', show: 'Show in folder', again: 'Try again',
    helper: 'Getting the video helper…', helperSub: 'One-time download of yt-dlp, about 18 MB.',
    reading: 'Reading the link…', saving: (p) => (p == null ? 'Saving…' : `Saving ${Math.floor(p)}%`), joining: 'Joining video and audio…',
    saved: 'Saved!', captured: 'Captured!', savedMany: (n) => `Saved ${n} files!`,
    login: 'This one needs a login', loginSub: 'Open it in your browser and save it with the LodiSave extension.',
    nothing: 'Nothing to save here', nothingSub: 'LodiSave found no video or image at this link.',
    offline: 'Can\'t reach the site', offlineSub: 'Check your connection and try again.',
    refused: 'The site said no', refusedSub: 'It blocks some networks and regions. Try again later or on another network.',
    gone: 'This post is gone', goneSub: 'It was removed, made private, or the link is wrong.',
    broken: 'LodiSave can\'t read this site right now', brokenSub: 'The site changed. LodiSave updates its reader every week, so try again later.',
    helperFail: 'Couldn\'t get the video helper', helperFailSub: 'LodiSave downloads yt-dlp the first time. Check your connection.',
    noimage: 'The image is gone', noimageSub: 'Copy it again, then save.',
    blocked: 'Windows blocked the save', blockedSub: 'Controlled folder access protects Downloads. Allow LodiSave under Ransomware protection in Windows Security.', security: 'Open Windows Security',
    failed: 'That didn\'t work',
    recent: 'Recent', folder: 'Open folder', none: 'Your saves show up here.', capture: 'Capture area',
    settings: 'Settings', close: 'Close', language: 'Language', motion: 'Motion', autostart: 'Start with Windows', on: 'On', off: 'Off',
    extension: 'Browser extension', setup: 'Set up', done: 'Done',
    extensionHint: () => (ui.ext ? `${ui.ext.name} is open. Four steps, this list stays here:` : 'Opens Chrome, Edge or Brave and walks you through loading the extension.'),
    extStep1: "In the new window's address bar, press Ctrl+V, then Enter.", extStep2: 'Turn on Developer mode.', extStep3: 'Click Load unpacked.', extStep4: 'Copy the folder, then press Ctrl+V and Select Folder.',
    copyPage: 'Copy address', copyDir: 'Copy folder', copied: 'Copied',
    ago: (s) => (s < 60 ? 'now' : s < 3600 ? `${Math.floor(s / 60)}m` : s < 86400 ? `${Math.floor(s / 3600)}h` : `${Math.floor(s / 86400)}d`),
  },
  tl: {
    idle: 'Mag-copy ng link para i-save', idleSub: 'Instagram, TikTok, X, Facebook, Reddit, YouTube at iba pa.', paste: 'I-paste ang link',
    link: 'May na-copy kang link!', image: 'May na-copy kang image!', imageSub: 'Ise-save bilang PNG.',
    save: 'I-save', later: 'Mamaya na', open: 'Buksan', show: 'Ipakita sa folder', again: 'Subukan ulit',
    helper: 'Kinukuha ang video helper…', helperSub: 'Isang beses lang i-download ang yt-dlp, mga 18 MB.',
    reading: 'Binabasa ang link…', saving: (p) => (p == null ? 'Sine-save…' : `Sine-save ${Math.floor(p)}%`), joining: 'Pinagsasama ang video at audio…',
    saved: 'Na-save!', captured: 'Nakuha!', savedMany: (n) => `Na-save ang ${n} files!`,
    login: 'Kailangan mag-login dito', loginSub: 'Buksan sa browser at i-save gamit ang LodiSave extension.',
    nothing: 'Walang ma-save dito', nothingSub: 'Walang nakitang video o image ang LodiSave sa link na ito.',
    offline: 'Hindi ma-reach ang site', offlineSub: 'I-check ang internet mo at subukan ulit.',
    refused: 'Ayaw ibigay ng site', refusedSub: 'May mga network at lugar na hinaharang nito. Subukan ulit mamaya o sa ibang network.',
    gone: 'Wala na ang post na ito', goneSub: 'Tinanggal, ginawang private, o mali ang link.',
    broken: 'Hindi pa mabasa ng LodiSave ang site na ito', brokenSub: 'Nagbago ang site. Linggo-linggo nag-a-update ang LodiSave, kaya subukan ulit mamaya.',
    helperFail: 'Hindi makuha ang video helper', helperFailSub: 'Dina-download ng LodiSave ang yt-dlp sa unang gamit. I-check ang internet mo.',
    noimage: 'Wala na ang image', noimageSub: 'I-copy ulit, tapos i-save.',
    blocked: 'Hinarang ng Windows ang pag-save', blockedSub: 'Pinoprotektahan ng Controlled folder access ang Downloads. I-allow ang LodiSave sa Ransomware protection ng Windows Security.', security: 'Buksan ang Windows Security',
    failed: 'Hindi gumana',
    recent: 'Mga na-save kanina', folder: 'Buksan ang folder', none: 'Dito lalabas ang mga na-save mo.', capture: 'Kunan ang screen',
    settings: 'Settings', close: 'Isara', language: 'Wika', motion: 'Galaw', autostart: 'Buksan pag-start ng Windows', on: 'On', off: 'Off',
    extension: 'Browser extension', setup: 'I-set up', done: 'Sige',
    extensionHint: () => (ui.ext ? `Bukas na ang ${ui.ext.name}. Apat na hakbang, nandito lang ang listahan:` : 'Bubuksan ang Chrome, Edge o Brave at gagabayan ka sa pag-load ng extension.'),
    extStep1: 'Sa address bar ng bagong window, pindutin ang Ctrl+V, tapos Enter.', extStep2: 'I-on ang Developer mode.', extStep3: 'I-click ang Load unpacked.', extStep4: 'I-copy ang folder, tapos pindutin ang Ctrl+V at Select Folder.',
    copyPage: 'I-copy ang address', copyDir: 'I-copy ang folder', copied: 'Na-copy',
    ago: (s) => (s < 60 ? 'ngayon' : s < 3600 ? `${Math.floor(s / 60)}m` : s < 86400 ? `${Math.floor(s / 3600)}h` : `${Math.floor(s / 86400)}d`),
  },
};
const ERRORS = { login: 'login', nothing: 'nothing', offline: 'offline', refused: 'refused', gone: 'gone', broken: 'broken', helper: 'helperFail', noimage: 'noimage', blocked: 'blocked' };

const $ = (id) => document.getElementById(id);
const pref = (k, d) => { try { return localStorage.getItem(k) || d } catch { return d } };
const keep = (k, v) => { try { localStorage.setItem(k, v) } catch {} };
const ui = { lang: pref('lang', 'en'), motion: pref('motion', 'on'), autostart: 'off', state: 'idle', link: '', job: null, files: [], error: null, captured: false };
const say = (key, ...a) => { const v = COPY[ui.lang][key]; return typeof v === 'function' ? v(...a) : v };
const dismissed = new Set();

function pose(p) {
  const lodi = $('lodi'), hop = lodi.querySelector('.hop');
  if (lodi.dataset.pose === p) return;
  lodi.dataset.pose = p;
  hop.classList.remove('go');
  void hop.getBoundingClientRect(); // restarts the hop animation
  hop.classList.add('go');
}

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

const short = (url) => url.replace(/^https?:\/\/(www\.)?/, '');
const base = (p) => p.split(/[\\/]/).pop();

function paint() {
  document.documentElement.lang = ui.lang === 'tl' ? 'fil' : 'en';
  document.documentElement.classList.toggle('still', ui.motion === 'off');
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
    go.textContent = ui.error === 'blocked' ? say('security') : say('again');
    later.textContent = say('later');
    go.hidden = ['login', 'noimage', 'gone'].includes(ui.error);
  }
  $('card-title').textContent = title;
  $('card-sub').textContent = sub;
  $('card-sub').title = ui.state === 'link' ? ui.link : sub;
  $('link').placeholder = say('paste');
  for (const el of document.querySelectorAll('[data-t]')) el.textContent = say(el.dataset.t);
  for (const el of document.querySelectorAll('[data-label]')) el.setAttribute('aria-label', say(el.dataset.label));
  for (const seg of document.querySelectorAll('[data-pref]')) {
    for (const b of seg.children) b.setAttribute('aria-pressed', ui[seg.dataset.pref] === b.dataset.v);
  }
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

const size = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`);
async function loadRecent() {
  const list = await invoke('recent').catch(() => []);
  const box = $('recent');
  box.textContent = '';
  if (!list.length) {
    box.append(Object.assign(document.createElement('p'), { className: 'empty', textContent: say('none') }));
    return;
  }
  list.forEach((r, i) => {
    const item = Object.assign(document.createElement('button'), { type: 'button', className: 'item', title: r.name });
    item.style.setProperty('--i', i);
    const thumb = Object.assign(document.createElement('span'), { className: 'thumb' });
    const src = convertFileSrc(r.path);
    if (r.kind === 'image') thumb.append(Object.assign(document.createElement('img'), { src, alt: '', loading: 'lazy', decoding: 'async' }));
    else if (r.kind === 'video') thumb.append(Object.assign(document.createElement('video'), { src: `${src}#t=0.5`, muted: true, preload: 'metadata' }));
    else thumb.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/></svg>';
    item.append(thumb,
      Object.assign(document.createElement('span'), { className: 'name', textContent: r.name }),
      Object.assign(document.createElement('span'), { className: 'meta', textContent: `${say('ago', r.age)} · ${size(r.bytes)}` }));
    item.onclick = () => invoke('open_path', { path: r.path });
    box.append(item);
  });
}

async function saveLink(url) {
  ui.link = url;
  ui.job = { phase: 'reading', percent: null };
  ui.captured = false;
  setState('working');
  try {
    ui.files = await invoke('save_link', { url });
    dismissed.add(url);
    $('link').value = '';
    setState('done');
  } catch (e) {
    ui.error = String(e);
    setState('error');
  }
  loadRecent();
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
  loadRecent();
}

$('go').onclick = () => {
  if (ui.state === 'idle') saveLink($('link').value.trim());
  else if (ui.state === 'link') saveLink(ui.link);
  else if (ui.state === 'image') saveImage();
  else if (ui.state === 'done') invoke('open_path', { path: ui.files[0] });
  else if (ui.state === 'error' && ui.error === 'blocked') invoke('open_security');
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
$('extension').onclick = () => invoke('open_extension');
$('repo').onclick = () => invoke('open_repo');
$('open-prefs').onclick = () => $('prefs').showModal();
$('close-prefs').onclick = () => $('prefs').close();
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
$('prefs').onclick = (e) => {
  const b = e.target.closest('[data-v]'), key = b?.parentElement.dataset.pref;
  if (!key) return;
  ui[key] = b.dataset.v;
  if (key === 'autostart') invoke('set_autostart', { on: b.dataset.v === 'on' });
  else keep(key, b.dataset.v);
  paint();
};
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('prefs').open) invoke('hide');
});

// A peek reports a background result without taking focus, then tucks itself away.
let peekTimer = 0;
const panel = $('panel');
panel.onmouseenter = () => clearTimeout(peekTimer);

listen('panel://shown', ({ payload }) => {
  panel.classList.remove('in');
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
  loadRecent();
});
// Set up opened the browser. The panel stays up as the checklist until Done.
listen('extension://opened', ({ payload }) => {
  clearTimeout(peekTimer);
  ui.ext = payload;
  $('steps').hidden = false;
  if (!$('prefs').open) $('prefs').showModal();
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
  loadRecent();
});

(async () => {
  const info = await invoke('info').catch(() => ({}));
  ui.autostart = info.autostart ? 'on' : 'off';
  $('version').textContent = `LodiSave ${info.version || ''}`;
  $('keys').hidden = !info.shortcut;
  paint();
  readClipboard();
  loadRecent();
})();
