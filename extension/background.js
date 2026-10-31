importScripts('shared.js');
const { isYouTube, filenameFor, extFor, b64 } = LodiShared;
const YT_MSG = 'YouTube is not supported in the extension. Use the LodiSave app.';
const NO_APP = 'Other folders need the LodiSave app for Windows, so these went to Downloads › LodiSave.';
const FOLDER_ERRORS = {
  folder: 'That folder is gone. Pick another one.',
  blocked: 'Windows blocked saving there. Allow LodiSave under Ransomware protection in Windows Security.',
  nothing: 'That link is a web page, not the file. Play the video, then save it.',
};

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: 'lodisave', title: 'Save with LodiSave', contexts: ['all'] });
});

async function scanTab(tabId) {
  const tab = await chrome.tabs.get(tabId);
  if (isYouTube(tab.url)) return { error: YT_MSG, youtube: true };
  let results;
  try {
    results = await chrome.scripting.executeScript({
      target: { tabId, allFrames: true },
      func: () => {
        try { return globalThis.__lodiScan ? { items: globalThis.__lodiScan() } : null } catch (e) { return { error: String(e?.stack || e) } }
      },
    });
  } catch (e) {
    return { error: `Can't read this page: ${e.message}` };
  }
  const items = [], errors = [], seen = new Set();
  for (const r of results) {
    if (r.result?.error) errors.push(r.result.error);
    for (const it of r.result?.items || []) {
      // mse:, blob: and canvas: ids are only unique within a frame.
      const key = /^(https?|data):/.test(it.url) ? it.url : `${r.frameId} ${it.url}`;
      if (seen.has(key)) continue;
      seen.add(key);
      items.push({ ...it, frameId: r.frameId });
    }
  }
  return { items, errors };
}

function finished(id) {
  return new Promise((resolve) => {
    const check = async () => {
      const [it] = await chrome.downloads.search({ id });
      if (it && it.state !== 'in_progress') {
        chrome.downloads.onChanged.removeListener(onChange);
        resolve(it);
      }
    };
    const onChange = (d) => d.id === id && d.state && check();
    chrome.downloads.onChanged.addListener(onChange);
    check();
  });
}

// Names LodiSave asked for, by download URL. Chrome drops a requested name once an extension
// listens to onDeterminingFilename, so the listener hands it back, with the extension Chrome found
// from the file's type when the URL had none (Instagram and Facebook video URLs have none).
const wanted = new Map();
chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  const want = item.byExtensionId === chrome.runtime.id && wanted.get(item.url);
  if (!want) return suggest();
  wanted.delete(item.url);
  const ext = /\.[a-z0-9]{2,5}$/i, found = ext.exec(item.filename)?.[0];
  suggest({ filename: ext.test(want) || !found ? want : want + found, conflictAction: 'uniquify' });
});

async function saveUrl(url, filename) {
  try {
    wanted.set(url, 'LodiSave/' + filename);
    const id = await chrome.downloads.download({ url, filename: 'LodiSave/' + filename, conflictAction: 'uniquify', saveAs: false });
    const it = await finished(id);
    wanted.delete(url);
    // LodiSave only saves media. A web page here is a site's player page or a login wall.
    if (it.state === 'complete' && /^text\/html/.test(it.mime || '')) {
      await chrome.downloads.removeFile(id).catch(() => {});
      chrome.downloads.erase({ id });
      return { error: 'That link is a web page, not the file. Play the video, then save it.' };
    }
    return it.state === 'complete' ? { ok: true, id, filename: it.filename, bytes: it.fileSize } : { error: `Download failed: ${it.error}`, code: it.error, id };
  } catch (e) {
    return { error: e.message || String(e) };
  }
}

async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({ url: 'offscreen.html', reasons: ['BLOBS'], justification: 'Turn fetched bytes into a file for chrome.downloads' })
    .catch((e) => { if (!/single offscreen/i.test(e.message)) throw e });
}

// chrome.downloads sends no Referer, refuses one in `headers`, and session rules don't reach it, so CDNs
// that check Referer (TikTok, many image hosts) answer 403. The extension's own fetches get a session
// rule that adds the page's origin as Referer, what the browser itself sent.
let ruleId = 0;
async function refererRule(url, page) {
  const id = ++ruleId;
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [id],
    addRules: [{
      id, priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: [{ header: 'referer', operation: 'set', value: new URL(page).origin + '/' }] },
      condition: { requestDomains: [new URL(url).hostname], tabIds: [chrome.tabs.TAB_ID_NONE] },
    }],
  });
  return id;
}
const dropRule = (id) => chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id] });

// The fallback fetches from the offscreen page, then saves the bytes.
// ponytail: the fallback holds the whole file in memory, stream it if protected files get large
async function fetchAndSave(url, filename, page) {
  const id = await refererRule(url, page);
  try {
    await ensureOffscreen();
    const got = await chrome.runtime.sendMessage({ type: 'offscreen-fetch', url });
    if (!got || got.error) return { error: `Download failed: ${got?.error || 'no answer'}` };
    try {
      return await saveUrl(got.url, filename);
    } finally {
      chrome.runtime.sendMessage({ type: 'offscreen-revoke', url: got.url });
    }
  } finally {
    dropRule(id);
  }
}

// Saving into a folder the user chose, through the LodiSave app (app/src/host.rs). Browsers keep an
// extension's downloads inside Downloads, and with "Ask where to save each file" on they ask once
// per file. The app writes anywhere, and picks folders with Windows' own folder picker.
const HOST = 'io.github.jherobred.lodisave';
const CHUNK = 4 << 20;
let port = null, asked = 0, idle = 0;
const waiting = new Map();
function app(msg) {
  clearTimeout(idle);
  if (!port) {
    port = chrome.runtime.connectNative(HOST);
    port.onMessage.addListener((m) => {
      const w = waiting.get(m.id);
      waiting.delete(m.id);
      if (w) m.error ? w.reject(new Error(m.error)) : w.resolve(m);
      // The app quits once nothing has needed it for a while.
      if (!waiting.size) idle = setTimeout(() => { port?.disconnect(); port = null }, 30000);
    });
    port.onDisconnect.addListener(() => {
      const e = new Error(chrome.runtime.lastError?.message || 'The LodiSave app closed');
      for (const w of waiting.values()) w.reject(e);
      waiting.clear();
      port = null;
    });
  }
  const id = ++asked;
  return new Promise((resolve, reject) => { waiting.set(id, { resolve, reject }); port.postMessage({ ...msg, id }) });
}

// The folders saved to lately, newest first, and the last one.
async function keepFolder(dir) {
  const { dirs = [] } = await chrome.storage.local.get('dirs');
  await chrome.storage.local.set({ lastDir: dir, dirs: [dir, ...dirs.filter((d) => d !== dir)].slice(0, 8) });
}

async function chooseFolder(title) {
  const { lastDir, fixedDir } = await chrome.storage.local.get(['lastDir', 'fixedDir']);
  const { dir } = await app({ op: 'folder', title: title || 'Save to', start: lastDir || fixedDir || '' });
  if (dir) await keepFolder(dir);
  return dir;
}

// Where each item goes, by the Save to setting: Downloads (null), the folder saved to last, one
// folder always, or a folder per item, from `route` (item URL to folder, picked in the bar) or
// asked one by one. { cancelled } when the user closes the folder picker.
async function foldersFor(items, route = {}) {
  const { saveTo = 'downloads', lastDir, fixedDir } = await chrome.storage.local.get(['saveTo', 'lastDir', 'fixedDir']);
  const none = { dirs: items.map(() => null) };
  if (saveTo === 'downloads' || !items.length) return none;
  if (!(await app({ op: 'ping' }).catch(() => null))) return { ...none, note: NO_APP };
  if (saveTo === 'each') {
    const dirs = [];
    for (const it of items) {
      const dir = route[it.url] || (await chooseFolder(`Save ${filenameFor(it)} to`));
      if (!dir) return { cancelled: true };
      dirs.push(dir);
    }
    return { dirs };
  }
  let dir = saveTo === 'fixed' ? fixedDir : lastDir;
  if (!dir) {
    dir = await chooseFolder();
    if (!dir) return { cancelled: true };
    if (saveTo === 'fixed') await chrome.storage.local.set({ fixedDir: dir });
  }
  return { dirs: items.map(() => dir) };
}

// The next piece of a response, as base64, or null at its end.
function webReader(res) {
  const reader = res.body.getReader();
  let done = false;
  return async () => {
    const parts = [];
    for (let size = 0; !done && size < CHUNK; ) {
      const r = await reader.read();
      if (r.done) done = true;
      else { parts.push(r.value); size += r.value.length }
    }
    return parts.length ? b64(new Uint8Array(await new Blob(parts).arrayBuffer())) : null;
  };
}

// The next piece of a file only the page can read (a blob: URL it built), as base64, or null at its end.
function pageReader({ tabId, frameId }, url) {
  let at = 0, done = false;
  return async () => {
    if (done) return null;
    const r = await chrome.tabs.sendMessage(tabId, { type: 'read-chunk', url, at, size: CHUNK }, { frameId });
    if (!r || r.error) throw new Error(r?.error || 'The page did not answer');
    at += CHUNK;
    done = r.done;
    return r.data || (done ? null : '');
  };
}

// Saves a file into `dir` through the app, as `download` does into Downloads. `src` is the tab and
// frame that can read a blob: URL.
async function intoFolder(dir, src, url, name, page) {
  let file = null, rule = 0;
  try {
    let read;
    if (url.startsWith('blob:')) read = pageReader(src, url);
    else {
      if (/^https?:/.test(url) && /^https?:/.test(page || '')) rule = await refererRule(url, page);
      const res = await fetch(url, { credentials: 'include' });
      if (!res.ok) return { error: `Download failed: HTTP ${res.status}`, code: res.status };
      const type = res.headers.get('content-type') || '';
      // LodiSave only saves media. A web page here is a site's player page or a login wall.
      if (/^text\/html/.test(type)) return { error: FOLDER_ERRORS.nothing };
      if (!/\.[a-z0-9]{2,5}$/i.test(name)) name += `.${extFor(type) || 'bin'}`;
      read = webReader(res);
    }
    ({ file } = await app({ op: 'open', dir, name }));
    for (let data; (data = await read()) !== null; ) if (data) await app({ op: 'write', file, data });
    const { path } = await app({ op: 'close', file });
    file = null;
    return { ok: true, id: path, filename: path };
  } catch (e) {
    return { error: FOLDER_ERRORS[e.message] || e.message || String(e) };
  } finally {
    if (file) app({ op: 'abort', file }).catch(() => {});
    if (rule) dropRule(rule);
  }
}

async function download(url, filename, page) {
  const res = await saveUrl(url, filename);
  if (/^SERVER_(FORBIDDEN|UNAUTHORIZED)$/.test(res.code || '') && /^https?:/.test(url) && /^https?:/.test(page || '')) {
    chrome.downloads.erase({ id: res.id });
    return fetchAndSave(url, filename, page);
  }
  return res;
}

// Saves an item into Downloads › LodiSave, or into `dir` through the LodiSave app.
async function save(tabId, it, dir) {
  if (!it) return { error: 'Nothing to save' };
  if (it.unsupported) return { error: it.unsupported };
  if (isYouTube(it.url) || isYouTube(it.page)) return { error: YT_MSG };
  const warning = it.partial;
  const frameId = it.frameId || 0;
  const put = (url, name, page) => (dir ? intoFolder(dir, { tabId, frameId }, url, name, page) : download(url, name, page));
  if (/^(https?|data):/.test(it.url) && !/^(hls|dash)$/.test(it.kind)) {
    // A full-size upgrade can fail where the copy on the page works, so fall back to that copy.
    let res;
    for (const url of [it.url, ...(it.alts || [])]) {
      res = await put(url, filenameFor({ ...it, url }), it.page);
      if (res.ok) return { ...res, warning };
    }
    return res;
  }
  let local;
  try {
    local = await chrome.tabs.sendMessage(tabId, { type: 'local-url', item: it }, { frameId });
  } catch (e) {
    return { error: e.message };
  }
  if (!local || local.error) return { error: local?.error || 'The page did not answer' };
  const results = [];
  for (const f of local.files) results.push(await put(f.url, filenameFor(it, f.ext, f.suffix)));
  if (!local.keep) chrome.tabs.sendMessage(tabId, { type: 'revoke', urls: local.files.map((f) => f.url), main: local.main }, { frameId }).catch(() => {});
  return results.find((r) => !r.ok) || { ...results[0], files: results.map((r) => r.filename), warning };
}

async function menuClick(info, tab) {
  if (isYouTube(tab?.url) || isYouTube(info.frameUrl)) return { error: YT_MSG };
  const frameId = info.frameId || 0;
  let res;
  try {
    res = await chrome.tabs.sendMessage(tab.id, { type: 'pick' }, { frameId });
  } catch {
    return { error: 'LodiSave is not running in this frame yet. Reload the page.' };
  }
  if (!res?.item) return { error: res?.error || 'Nothing found' };
  const item = { ...res.item, frameId }, plan = await foldersFor([item]);
  return plan.cancelled ? { error: 'Not saved' } : save(tab.id, item, plan.dirs[0]);
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const r = await menuClick(info, tab);
  if (!tab) return;
  chrome.action.setBadgeText({ tabId: tab.id, text: r.ok ? '' : '!' });
  chrome.action.setTitle({ tabId: tab.id, title: r.ok ? 'LodiSave' : `LodiSave: ${r.error}` });
});

// The hover picker (picker.js). Each frame keeps its own picks. Counts go to the top frame, which
// shows the bar, so this worker keeps no state and survives being restarted.
async function takePicked(tabId, frames) {
  const items = [];
  for (const frameId of frames) {
    const got = await chrome.tabs.sendMessage(tabId, { type: 'take-picked' }, { frameId }).catch(() => []);
    for (const it of got || []) items.push({ ...it, frameId });
  }
  return items;
}

// Saves several items, a few at a time, each reported to `each(i, result)` as it lands.
async function saveAll(tabId, items, dirs, each) {
  let ok = 0, bad = 0, warn = 0, last = null, error = null, next = 0;
  const results = [];
  const lane = async () => {
    while (next < items.length) {
      const i = next++;
      const { thumb, ...it } = items[i];
      const r = (results[i] = await save(tabId, it, dirs[i]));
      if (r.ok) { ok++; last = r.id; if (r.warning) warn++ } else { bad++; error ??= r.error }
      each(i, r);
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, items.length) }, lane));
  return { ok, bad, warn, last, error, results };
}

// `route` holds the folder picked for each item in the bar, for Save to: Ask for each.
async function savePicked(tabId, frames, route) {
  const items = await takePicked(tabId, frames);
  const plan = await foldersFor(items, route);
  if (plan.cancelled) return plan;
  const bar = (msg) => chrome.tabs.sendMessage(tabId, msg, { frameId: 0 }).catch(() => {});
  bar({ type: 'bar-start', thumbs: items.map((it) => (/^(https?|data|blob):/.test(it.thumb || '') ? it.thumb : '')) });
  const r = await saveAll(tabId, items, plan.dirs, (i, res) => bar({ type: 'bar-item', i, ok: !!res.ok }));
  chrome.tabs.sendMessage(tabId, { type: 'clear-picked' }).catch(() => {});
  return { ...r, note: plan.note };
}

// The popup's saves. They run here, so they finish when the popup closes, as it does when a folder
// picker opens over it. Each result goes to the popup, while it's open.
async function saveMany(tabId, items) {
  const plan = await foldersFor(items);
  if (plan.cancelled) return plan;
  const r = await saveAll(tabId, items, plan.dirs, (i, res) => chrome.runtime.sendMessage({ type: 'saved-one', tabId, i, res }).catch(() => {}));
  return { ...r, note: plan.note };
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  const tabId = sender.tab?.id;
  if (msg.type === 'scan') scanTab(msg.tabId).then(reply);
  else if (msg.type === 'save') save(msg.tabId, msg.item).then(reply);
  else if (msg.type === 'save-many') saveMany(msg.tabId, msg.items).then(reply);
  else if (msg.type === 'save-picked' && tabId) savePicked(tabId, msg.frames || [], msg.route).then(reply);
  else if (msg.type === 'list-picked' && tabId) takePicked(tabId, msg.frames || []).then((items) => reply(items.map((it) => ({ url: it.url, name: filenameFor(it), thumb: it.thumb }))));
  else if (msg.type === 'choose-folder') {
    // From the bar's folder button or the popup's settings: the folder becomes the one saved to next.
    chooseFolder(msg.title).then(async (dir) => {
      const { saveTo = 'downloads' } = await chrome.storage.local.get('saveTo');
      if (dir && msg.fixed) await chrome.storage.local.set({ saveTo: 'fixed', fixedDir: dir });
      else if (dir && saveTo === 'downloads') await chrome.storage.local.set({ saveTo: 'last' });
      else if (dir && saveTo === 'fixed') await chrome.storage.local.set({ fixedDir: dir });
      reply({ dir });
    }, (e) => reply({ error: e.message, noApp: true }));
  } else if (msg.type === 'app-ready') app({ op: 'ping' }).then(() => reply(true), () => reply(false));
  else {
    if (msg.type === 'picked' && tabId) chrome.tabs.sendMessage(tabId, { type: 'frame-picked', frameId: sender.frameId, count: msg.count }, { frameId: 0 }).catch(() => {});
    else if (msg.type === 'clear-picked' && tabId) chrome.tabs.sendMessage(tabId, { type: 'clear-picked' }).catch(() => {});
    // A download's id, or the path of a file the app saved.
    else if (msg.type === 'show-download') typeof msg.id === 'string' ? app({ op: 'show', path: msg.id }).catch(() => {}) : chrome.downloads.show(msg.id);
    return;
  }
  return true;
});

self.lodi = { scanTab, save, menuClick, savePicked, saveMany, foldersFor }; // fixtures/run.mjs drives these directly
