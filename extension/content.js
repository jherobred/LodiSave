// Isolated-world glue: bridges hooks.js, runs the finder, and builds the files only the page holds
// (MediaSource captures, blobs, HLS and DASH streams).
(() => {
  if (globalThis.__lodiScan) return;
  const { isYouTube } = LodiShared;

  // Shift+right-click skips the page's handlers, so a blocked context menu still opens.
  // Registered at document_start, so it runs before any page listener.
  let point = null;
  const onButton = (e) => {
    if (e.button !== 2) return;
    if (e.type === 'contextmenu') point = { x: e.clientX, y: e.clientY };
    if (e.shiftKey) e.stopImmediatePropagation();
  };
  for (const t of ['contextmenu', 'mousedown', 'mouseup', 'pointerdown', 'pointerup', 'auxclick']) window.addEventListener(t, onButton, true);

  // Every resource the page loads, including ones it later clears from the timing buffer. This is
  // how stream manifests are found without patching fetch or XHR.
  const loaded = new Set();
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (loaded.size < 5000) loaded.add(e.name);
    }).observe({ type: 'resource', buffered: true });
  } catch {}

  let rid = 0, answer;
  document.addEventListener('lodisave:res', (e) => {
    try {
      const m = JSON.parse(e.detail);
      if (m.rid === rid) answer = m.res;
    } catch {}
  });
  const ask = (op, extra) => {
    answer = undefined;
    document.dispatchEvent(new CustomEvent('lodisave:req', { detail: JSON.stringify({ rid: ++rid, op, ...extra }) }));
    return answer;
  };

  const clock = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
  // How much of the player's video the capture covers, for streams still loading.
  function coverage(msUrl) {
    const el = [...document.querySelectorAll('video,audio')].find((v) => v.src === msUrl || v.currentSrc === msUrl);
    if (!el || !Number.isFinite(el.duration) || !el.buffered.length) return null;
    const end = el.buffered.end(el.buffered.length - 1);
    return end < el.duration - 1 ? `Captured ${clock(end)} of ${clock(el.duration)}. Play it to the end to save the whole video.` : null;
  }

  function streamItems(mse) {
    const out = [];
    const groups = new Map();
    for (const m of mse) groups.set(m.ms, [...(groups.get(m.ms) || []), m]);
    for (const list of groups.values()) {
      const live = list.filter((m) => m.bytes);
      const video = live.find((m) => /^video\/mp4/.test(m.mime)), audio = live.find((m) => /^audio\/mp4/.test(m.mime));
      const note = (m) => (m.evicted ? 'Freed to save memory. Play it again to capture it.' : m.drm ? 'DRM-protected stream. LodiSave does not decrypt.' : undefined);
      const partial = (m) => coverage(m.msUrl) || (m.truncated ? 'Too long to keep in memory. Saves what fit.' : undefined);
      if (video && audio && live.length === 2) {
        out.push({ url: `mse:${video.ms}:av`, ids: [video.id, audio.id], kind: 'stream', via: 'mse', label: 'video + audio', mime: 'video/mp4', bytes: video.bytes + audio.bytes, msUrl: video.msUrl, partial: partial(video) });
        continue;
      }
      for (const m of list) {
        if (!m.bytes && !m.drm && !m.evicted) continue;
        out.push({ url: `mse:${m.id}`, id: m.id, kind: 'stream', via: 'mse', mime: m.mime, bytes: m.bytes, drm: m.drm, msUrl: m.msUrl, unsupported: note(m), partial: partial(m) });
      }
    }
    return out;
  }

  function pageItems() {
    const s = ask('state') || {}, out = streamItems(s.mse || []);
    for (const b of s.blobs || []) {
      out.push({ url: b.url, id: b.id, kind: /^video/.test(b.type) ? 'video' : /^audio/.test(b.type) ? 'audio' : 'image', via: 'blob', mime: b.type, bytes: b.size });
    }
    for (const u of s.canvas || []) out.push({ url: u, kind: 'image', via: 'canvas-src' });
    return out;
  }

  const PLAY = 'Play the video first so LodiSave can capture it.';

  // Swaps blob: URLs from the DOM for what the hooks captured behind them, and asks known CDNs for
  // their full-size originals, keeping the shown URL as the fallback.
  function merge(found, withPage) {
    const page = pageItems(), blobs = new Map(page.filter((h) => h.via === 'blob').map((h) => [h.url, h]));
    const out = [], seen = new Set();
    const push = (it) => {
      const full = /^https?:/.test(it.url) && LodiRules.upgrade(it.url);
      if (full) it = { ...it, url: full, alts: [it.url], full: true };
      if (seen.has(it.url) || isYouTube(it.url)) return;
      seen.add(it.url);
      out.push({ ...it, page: location.href });
    };
    for (const it of found) {
      if (it.url.startsWith('blob:')) {
        const streams = page.filter((h) => h.msUrl === it.url);
        if (streams.length) { for (const s of streams) push({ ...s, rw: it.rw, rh: it.rh }); continue }
        const b = blobs.get(it.url);
        if (b) { push({ ...it, id: b.id, via: 'blob', mime: b.mime, bytes: b.bytes }); continue }
        // A player's blob: URL with nothing captured behind it yet.
        if (it.kind !== 'image') { push({ ...it, unsupported: PLAY }); continue }
      }
      push(it);
    }
    if (withPage) {
      for (const h of page) push(h);
      for (const u of loaded) {
        const kind = LodiFinder.kindOf(u);
        if (kind) push({ url: u.split('#')[0], kind, via: 'network' });
      }
    }
    return out;
  }

  globalThis.__lodiScan = () => merge(LodiFinder.scan(), true);

  const usable = (it) => !it.unsupported && !(it.w && it.h && (it.w < 8 || it.h < 8));
  // On a player, the video beats its poster or thumbnail overlay. A srcset's biggest candidate beats
  // the one on screen. Otherwise the topmost media wins.
  const rank = (it) => (/^(video|stream|hls|dash)$/.test(it.kind) ? 0 : /^(srcset|picture)$/.test(it.via) ? 1 : 2);

  function best(items) {
    // A poster is only a still of its video: a video that can't be saved yet says so instead.
    const poster = items.find((it) => it.via === 'poster');
    let top = items.filter((it) => usable(it) && it.via !== 'poster').sort((a, b) => rank(a) - rank(b))[0];
    if (!top && poster) return items.find((it) => it.kind === 'video' && it.unsupported) || { ...poster, kind: 'video', unsupported: PLAY };
    if (top?.via === 'mse') {
      // A buffered capture can be partial. One known manifest in the frame is the whole stream, as
      // is a master playlist the others are named after (Pinterest's x.m3u8 and x_720w.m3u8).
      const manifests = [...loaded].filter((u) => /^(hls|dash)$/.test(LodiFinder.kindOf(u) || ''));
      const stem = (u) => u.split(/[?#]/)[0].replace(/\.(m3u8|mpd)$/i, '');
      const whole = manifests.find((m) => manifests.every((o) => stem(o).startsWith(stem(m))));
      if (whole) top = { url: whole, kind: LodiFinder.kindOf(whole), via: 'network', page: location.href };
    }
    return top;
  }

  function pick() {
    if (!point) return { error: 'No right-click position recorded. Reload the page and try again.' };
    const items = merge(LodiFinder.at(point.x, point.y), false), top = best(items);
    if (top) return { item: top, count: items.length };
    return { error: items[0]?.unsupported || 'No media under the cursor' };
  }

  // For picker.js: what saving this element would save, or why it can't be saved.
  globalThis.__lodiItemFor = (el) => {
    const items = merge(LodiFinder.of(el), false);
    return best(items) || items[0] || null;
  };

  const get = async (u, range) => {
    const r = await fetch(u, range ? { headers: { Range: 'bytes=' + range } } : {});
    if (!r.ok) throw new Error(`HTTP ${r.status} on ${u}`);
    return r;
  };
  const bytes = async (u, range) => new Uint8Array(await (await get(u, range)).arrayBuffer());
  const join = (parts) => {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length }
    return out;
  };

  // Separate video and audio become one MP4 when both are fragmented MP4. Otherwise both files save.
  function tracks(video, audio) {
    if (!audio) return [{ blob: new Blob([video.data]), ext: video.ext }];
    if (!video) return [{ blob: new Blob([audio.data]), ext: audio.ext }];
    const merged = video.fmp4 && audio.fmp4 && LodiMP4.merge(video.data, audio.data);
    if (merged) return [{ blob: new Blob([merged], { type: 'video/mp4' }), ext: 'mp4' }];
    return [{ blob: new Blob([video.data]), ext: video.ext }, { blob: new Blob([audio.data]), ext: audio.ext, suffix: 'audio' }];
  }

  const playlist = async (url) => {
    const r = await get(url);
    return LodiHLS.parse(await r.text(), r.url || url);
  };
  // ponytail: segments load one at a time and stay in memory, parallelize and stream for long VODs
  async function hlsTrack(pl) {
    if (pl.encrypted) throw new Error(`Encrypted HLS (${pl.encrypted}) is not supported`);
    if (!pl.segments.length) throw new Error('The playlist has no segments');
    const parts = [];
    if (pl.map) parts.push(await bytes(pl.map.url, pl.map.range));
    for (const s of pl.segments) parts.push(await bytes(s.url, s.range));
    const ext = pl.map ? 'mp4' : (/\.(ts|aac|mp3|mp4|m4s)$/i.exec(pl.segments[0].url.split(/[?#]/)[0])?.[1] || 'ts').replace(/m4s/i, 'mp4');
    return { data: join(parts), ext, fmp4: !!pl.map };
  }
  async function hls(url) {
    let pl = await playlist(url), audio = null;
    if (pl.variants.length) {
      const v = LodiHLS.best(pl.variants), a = LodiHLS.audioFor(pl, v);
      if (a) audio = await hlsTrack(await playlist(a.url));
      pl = await playlist(v.url);
    }
    return tracks(await hlsTrack(pl), audio);
  }

  async function dash(url) {
    const r = await get(url), m = LodiDASH.parse(await r.text(), r.url || url);
    if (m.drm) throw new Error('DRM-protected stream. LodiSave does not decrypt.');
    if (m.live) throw new Error('Live DASH streams are not supported yet');
    if (!m.video && !m.audio) throw new Error('The manifest has no video or audio');
    const load = async (t) => {
      if (!t) return null;
      const parts = [];
      if (t.init) parts.push(await bytes(t.init));
      for (const s of t.segments) parts.push(await bytes(s));
      const webm = /webm/.test(t.mime);
      return { data: join(parts), ext: webm ? 'webm' : 'mp4', fmp4: !webm };
    };
    return tracks(await load(m.video), await load(m.audio));
  }

  // A captured source buffer as a clean file: one continuous fragmented-MP4 run when it is one.
  async function capture(id) {
    const url = ask('url', { id });
    if (!url) throw new Error('That capture is no longer available. Reload and play it again.');
    const data = new Uint8Array(await (await fetch(url)).arrayBuffer());
    ask('revoke', { url });
    return data;
  }

  // Returns blob: URLs the service worker can hand to chrome.downloads, one per file.
  async function localUrls(it) {
    const files = (list) => list.map((f) => ({ url: URL.createObjectURL(f.blob), ext: f.ext, suffix: f.suffix }));
    if (it.via === 'mse' && it.ids) {
      const [v, a] = await Promise.all(it.ids.map(capture));
      return { files: files(tracks({ data: v, ext: 'mp4', fmp4: true }, { data: a, ext: 'm4a', fmp4: true })) };
    }
    if (it.via === 'mse') {
      const data = await capture(it.id), mp4 = /mp4/.test(it.mime) && LodiMP4.clean(data);
      return { files: files([{ blob: new Blob([mp4 || data], { type: it.mime.split(';')[0] }) }]) };
    }
    if (it.via === 'blob' && it.id) {
      const url = ask('url', { id: it.id });
      if (!url) throw new Error('That capture is no longer available. Reload and try again.');
      return { files: [{ url }], main: true };
    }
    if (it.kind === 'hls') return { files: files(await hls(it.url)) };
    if (it.kind === 'dash') return { files: files(await dash(it.url)) };
    if (it.url.startsWith('blob:')) return { files: [{ url: it.url }], keep: true };
    throw new Error('Nothing to build for this item');
  }

  chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
    if (msg.type === 'pick') reply(pick());
    else if (msg.type === 'local-url') {
      localUrls(msg.item).then(reply, (e) => reply({ error: e.message || String(e) }));
      return true;
    } else if (msg.type === 'revoke') {
      for (const url of msg.urls) msg.main ? ask('revoke', { url }) : URL.revokeObjectURL(url);
    }
  });
})();
