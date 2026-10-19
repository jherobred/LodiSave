// Runs in the page's own JS world at document_start, before any page script. Records what DOM
// scanning can't see: bytes fed to MediaSource, blob: media and canvas sources. Manifests are found
// from the isolated world through PerformanceObserver, so fetch and XHR stay untouched.
// Page code can read and spoof all of this, so everything it reports is treated as untrusted.
(() => {
  if (window.__lodisaveHooks) return;
  Object.defineProperty(window, '__lodisaveHooks', { value: true });

  const { parse, stringify } = JSON;
  const CE = CustomEvent;
  const create = URL.createObjectURL, revoke = URL.revokeObjectURL;
  let nextId = 0, held = 0, cap = 0;
  const blobs = new Map(), canvasSrc = new Set(), sources = [], streams = [];
  const msByObj = new WeakMap(), msByUrl = new Map(), sbRec = new WeakMap(), drmEls = new WeakSet();

  // Per-frame cap on held bytes. Fixtures lower it with <html data-lodisave-cap>, read on first use
  // because the document element doesn't exist yet at document_start.
  const limit = () => cap || (cap = +document.documentElement?.dataset.lodisaveCap || 512 * 1024 * 1024);

  const drop = (s) => { held -= s.bytes; s.chunks = []; s.bytes = 0 };
  // Frees finished streams, oldest first, until `need` more bytes fit. Streams still playing stay.
  const evict = (need) => {
    for (const r of streams.filter((x) => x.closed).sort((x, y) => x.closed - y.closed)) {
      if (held + need <= limit()) return true;
      for (const s of r.sbs) if (s.bytes) { drop(s); s.evicted = true }
    }
    return held + need <= limit();
  };

  const MS = window.MediaSource;
  const msRec = (ms) => {
    let r = msByObj.get(ms);
    if (!r) {
      r = { id: ++nextId, url: null, sbs: [], closed: 0 };
      msByObj.set(ms, r);
      streams.push(r);
      try { ms.addEventListener('sourceclose', () => (r.closed = Date.now())) } catch {}
    }
    return r;
  };
  const markDrm = (r) => {
    for (const s of r.sbs) if (!s.drm) { s.drm = true; drop(s) }
  };

  if (MS) {
    const add0 = MS.prototype.addSourceBuffer;
    MS.prototype.addSourceBuffer = function (mime) {
      const sb = add0.apply(this, arguments);
      try {
        const s = { id: ++nextId, mime: String(mime), chunks: [], bytes: 0, drm: false, evicted: false, truncated: false, ms: msRec(this) };
        s.ms.sbs.push(s);
        sbRec.set(sb, s);
        sources.push(s);
      } catch {}
      return sb;
    };
    const append0 = SourceBuffer.prototype.appendBuffer;
    SourceBuffer.prototype.appendBuffer = function (data) {
      try {
        const s = sbRec.get(this);
        if (s && !s.drm && !s.truncated) {
          const u8 = ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data);
          if (evict(u8.byteLength)) {
            s.chunks.push(u8.slice());
            s.bytes += u8.byteLength;
            held += u8.byteLength;
          } else s.truncated = true;
        }
      } catch {}
      return append0.apply(this, arguments);
    };
  }

  URL.createObjectURL = function (obj) {
    const url = create.apply(this, arguments);
    try {
      if (MS && obj instanceof MS) {
        const r = msRec(obj);
        r.url = url;
        msByUrl.set(url, r);
      } else if (obj instanceof Blob && (!obj.type || /^(image|video|audio)\//.test(obj.type)) && evict(obj.size)) {
        blobs.set(url, { id: ++nextId, blob: obj });
        held += obj.size;
      }
    } catch {}
    return url;
  };

  const C2D = window.CanvasRenderingContext2D;
  if (C2D) {
    const draw0 = C2D.prototype.drawImage;
    C2D.prototype.drawImage = function (img) {
      try {
        const u = img instanceof HTMLImageElement && (img.currentSrc || img.src);
        if (u && !u.startsWith('data:') && canvasSrc.size < 500) canvasSrc.add(u);
      } catch {}
      return draw0.apply(this, arguments);
    };
  }

  // DRM: once keys attach or encrypted init data shows up, drop that stream's bytes and stop capturing.
  const drm = (el) => {
    drmEls.add(el);
    const r = msByUrl.get(el.src);
    if (r) markDrm(r);
  };
  const setKeys0 = HTMLMediaElement.prototype.setMediaKeys;
  if (setKeys0) {
    HTMLMediaElement.prototype.setMediaKeys = function (keys) {
      if (keys) drm(this);
      return setKeys0.apply(this, arguments);
    };
  }
  document.addEventListener('encrypted', (e) => e.target instanceof HTMLMediaElement && drm(e.target), true);

  const state = () => {
    for (const el of document.querySelectorAll('video,audio')) {
      const r = msByUrl.get(el.src);
      if (r && (el.mediaKeys || drmEls.has(el))) markDrm(r);
    }
    return {
      mse: sources.map((s) => ({
        id: s.id, ms: s.ms.id, mime: s.mime, bytes: s.bytes, drm: s.drm, evicted: s.evicted, truncated: s.truncated, msUrl: s.ms.url,
      })),
      blobs: [...blobs].map(([url, b]) => ({ id: b.id, url, type: b.blob.type, size: b.blob.size })),
      canvas: [...canvasSrc],
    };
  };

  // A fresh blob: URL holding everything captured for one source buffer or blob.
  const urlFor = (id) => {
    const s = sources.find((x) => x.id === id);
    if (s) return s.drm || !s.bytes ? null : create.call(URL, new Blob(s.chunks, { type: s.mime.split(';')[0].trim() }));
    for (const b of blobs.values()) if (b.id === id) return create.call(URL, b.blob);
    return null;
  };

  // Synchronous request/response with the isolated-world content script over DOM events.
  document.addEventListener('lodisave:req', (e) => {
    let q;
    try { q = parse(e.detail) } catch { return }
    let res;
    if (q.op === 'state') res = state();
    else if (q.op === 'url') res = urlFor(q.id);
    else if (q.op === 'revoke') revoke.call(URL, q.url);
    document.dispatchEvent(new CE('lodisave:res', { detail: stringify({ rid: q.rid, res }) }));
  });
})();
