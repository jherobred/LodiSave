importScripts('shared.js');
const { isYouTube, filenameFor } = LodiShared;
const YT_MSG = 'YouTube is not supported in the extension. Use the LodiSave app.';

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

async function saveUrl(url, filename) {
  try {
    const id = await chrome.downloads.download({ url, filename: 'LodiSave/' + filename, conflictAction: 'uniquify' });
    const it = await finished(id);
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
// that check Referer (TikTok, many image hosts) answer 403. The fallback fetches from the offscreen page,
// where a session rule adds the page's origin as Referer (what the browser itself sent), then saves the bytes.
// ponytail: the fallback holds the whole file in memory, stream it if protected files get large
let ruleId = 0;
async function fetchAndSave(url, filename, page) {
  const id = ++ruleId;
  await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: [id],
    addRules: [{
      id, priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: [{ header: 'referer', operation: 'set', value: new URL(page).origin + '/' }] },
      condition: { requestDomains: [new URL(url).hostname], tabIds: [chrome.tabs.TAB_ID_NONE] },
    }],
  });
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
    chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id] });
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

async function save(tabId, it) {
  if (!it) return { error: 'Nothing to save' };
  if (it.unsupported) return { error: it.unsupported };
  if (isYouTube(it.url) || isYouTube(it.page)) return { error: YT_MSG };
  const warning = it.partial;
  if (/^(https?|data):/.test(it.url) && !/^(hls|dash)$/.test(it.kind)) {
    // A full-size upgrade can fail where the copy on the page works, so fall back to that copy.
    let res;
    for (const url of [it.url, ...(it.alts || [])]) {
      res = await download(url, filenameFor({ ...it, url }), it.page);
      if (res.ok) return { ...res, warning };
    }
    return res;
  }
  const frameId = it.frameId || 0;
  let local;
  try {
    local = await chrome.tabs.sendMessage(tabId, { type: 'local-url', item: it }, { frameId });
  } catch (e) {
    return { error: e.message };
  }
  if (!local || local.error) return { error: local?.error || 'The page did not answer' };
  const results = [];
  for (const f of local.files) results.push(await download(f.url, filenameFor(it, f.ext, f.suffix)));
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
  return res?.item ? save(tab.id, { ...res.item, frameId }) : { error: res?.error || 'Nothing found' };
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const r = await menuClick(info, tab);
  if (!tab) return;
  chrome.action.setBadgeText({ tabId: tab.id, text: r.ok ? '' : '!' });
  chrome.action.setTitle({ tabId: tab.id, title: r.ok ? 'LodiSave' : `LodiSave: ${r.error}` });
});

// The hover picker (picker.js). Each frame keeps its own picks. Counts go to the top frame, which
// shows the bar, so this worker keeps no state and survives being restarted.
async function savePicked(tabId, frames) {
  const items = [];
  for (const frameId of frames) {
    const got = await chrome.tabs.sendMessage(tabId, { type: 'take-picked' }, { frameId }).catch(() => []);
    for (const it of got || []) items.push({ ...it, frameId });
  }
  let ok = 0, bad = 0, warn = 0, last = null;
  for (const [i, it] of items.entries()) {
    chrome.tabs.sendMessage(tabId, { type: 'bar-progress', i: i + 1, n: items.length }, { frameId: 0 }).catch(() => {});
    const r = await save(tabId, it);
    if (r.ok) { ok++; last = r.id; if (r.warning) warn++ } else bad++;
  }
  chrome.tabs.sendMessage(tabId, { type: 'clear-picked' }).catch(() => {});
  return { ok, bad, warn, last };
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  const tabId = sender.tab?.id;
  if (msg.type === 'scan') scanTab(msg.tabId).then(reply);
  else if (msg.type === 'save') save(msg.tabId, msg.item).then(reply);
  else if (msg.type === 'save-picked' && tabId) savePicked(tabId, msg.frames || []).then(reply);
  else {
    if (msg.type === 'picked' && tabId) chrome.tabs.sendMessage(tabId, { type: 'frame-picked', frameId: sender.frameId, count: msg.count }, { frameId: 0 }).catch(() => {});
    else if (msg.type === 'clear-picked' && tabId) chrome.tabs.sendMessage(tabId, { type: 'clear-picked' }).catch(() => {});
    else if (msg.type === 'show-download') chrome.downloads.show(msg.id);
    return;
  }
  return true;
});

self.lodi = { scanTab, save, menuClick, savePicked }; // fixtures/run.mjs drives these directly
