// LodiSave media finder. DOM-only and dependency-free so the Android WebView can inject it as-is.
// LodiFinder.scan() lists every media URL in the page. LodiFinder.at(x, y) lists the media under a
// point, including media hidden under overlays or behind pointer-events: none. LodiFinder.of(el)
// lists one element's media.
var LodiFinder = (() => {
  const SVG = 'http://www.w3.org/2000/svg';
  const KINDS = [
    [/\.m3u8$/i, 'hls'],
    [/\.mpd$/i, 'dash'],
    [/\.(mp4|webm|mov|m4v|mkv|ogv)$/i, 'video'],
    [/\.(mp3|m4a|aac|oga|ogg|opus|wav|flac)$/i, 'audio'],
    [/\.(jpe?g|jfif|png|gif|webp|avif|bmp|svg|heic)$/i, 'image'],
  ];
  const NO_CSS = new Set(['head', 'meta', 'link', 'script', 'style', 'title', 'noscript', 'template', 'base', 'source', 'track', 'br', 'wbr', 'option']);
  const OG_IMAGE = /^(og:image(:secure_url|:url)?|twitter:image(:src)?)$/i;
  const OG_VIDEO = /^(og:video(:secure_url|:url)?|twitter:player:stream)$/i;
  const RESOURCE_KIND = { img: 'image', image: 'image', video: 'video', audio: 'audio' };

  function kindOf(url) {
    if (url.startsWith('data:')) return /^data:(image|video|audio)\//.exec(url)?.[1] || null;
    const path = url.split(/[?#]/)[0];
    for (const [re, kind] of KINDS) if (re.test(path)) return kind;
    return null;
  }

  // srcset per the HTML spec: URLs may contain commas, descriptors are "<n>w" or "<n>x".
  function parseSrcset(s) {
    const out = [];
    let i = 0;
    while (i < s.length) {
      while (i < s.length && /[\s,]/.test(s[i])) i++;
      let j = i;
      while (j < s.length && !/\s/.test(s[j])) j++;
      let url = s.slice(i, j), desc = '';
      i = j;
      if (url.endsWith(',')) url = url.replace(/,+$/, '');
      else {
        let depth = 0;
        while (i < s.length && (s[i] !== ',' || depth)) {
          if (s[i] === '(') depth++;
          else if (s[i] === ')') depth--;
          desc += s[i++];
        }
      }
      if (!url) continue;
      const w = /(\d+(?:\.\d+)?)w\b/i.exec(desc), x = /(\d+(?:\.\d+)?)x\b/i.exec(desc);
      out.push({ url, w: w ? +w[1] : 0, x: x ? +x[1] : w ? 0 : 1 });
    }
    return out;
  }

  function largest(srcset) {
    let top = null;
    for (const c of parseSrcset(srcset || '')) if (!top || c.w > top.w || (c.w === top.w && c.x > top.x)) top = c;
    return top && top.url;
  }

  function cssUrls(value) {
    const out = [];
    if (value && value !== 'none') for (const m of value.matchAll(/url\(\s*(['"]?)(.*?)\1\s*\)/g)) out.push(m[2]);
    return out;
  }

  function collector() {
    const out = [], seen = new Set();
    const add = (url, kind, via, base, extra) => {
      if (!url) return;
      try { url = new URL(url, base).href } catch { return }
      if (/^https?:/.test(url)) url = url.split('#')[0];
      else if (!/^(data|blob|canvas):/.test(url)) return;
      if (seen.has(url)) return;
      seen.add(url);
      out.push({ url, kind: kind || kindOf(url) || 'video', via, ...extra });
    };
    return { out, add };
  }

  let tainted = 0;
  function canvas(el, put) {
    const px = el.width * el.height;
    if (!px || px > 16777216) return; // ponytail: skips canvases over 16 MP, add tiled export if a site needs it
    try {
      put(el.toDataURL('image/png'), 'image', 'canvas', { w: el.width, h: el.height });
    } catch {
      put(`canvas:tainted/${++tainted}`, 'image', 'canvas', {
        w: el.width, h: el.height,
        unsupported: 'This canvas mixes in another site\'s image, so the browser blocks reading it. Save the image it was drawn from instead.',
      });
    }
  }

  function fromElement(el, add) {
    const tag = el.localName, doc = el.ownerDocument, base = doc.baseURI;
    if (tag === 'meta') {
      const key = el.getAttribute('property') || el.getAttribute('name') || '';
      if (OG_IMAGE.test(key)) add(el.content, 'image', 'meta', base);
      else if (OG_VIDEO.test(key)) add(el.content, null, 'meta', base);
      return;
    }
    if (NO_CSS.has(tag)) return;
    const r = el.getBoundingClientRect(), box = { rw: Math.round(r.width), rh: Math.round(r.height) };
    const put = (url, kind, via, extra) => add(url, kind, via, base, { ...box, ...extra });

    if (tag === 'img') {
      put(el.currentSrc || el.src, 'image', 'img', { w: el.naturalWidth, h: el.naturalHeight });
      put(largest(el.getAttribute('srcset')), 'image', 'srcset');
      if (el.parentElement?.localName === 'picture') {
        for (const s of el.parentElement.querySelectorAll('source')) put(largest(s.getAttribute('srcset')), 'image', 'picture');
      }
    } else if (tag === 'video' || tag === 'audio') {
      put(el.currentSrc || el.getAttribute('src'), tag, tag, tag === 'video' ? { w: el.videoWidth, h: el.videoHeight } : {});
      for (const s of el.querySelectorAll('source')) put(s.src, tag, 'source');
      if (el.poster) put(el.poster, 'image', 'poster');
    } else if (tag === 'canvas') {
      canvas(el, put);
    } else if (tag === 'svg' && el.namespaceURI === SVG && !el.parentElement?.closest('svg') && r.width >= 16 && r.height >= 16) {
      const copy = el.cloneNode(true);
      copy.setAttribute('xmlns', SVG);
      const xml = new XMLSerializer().serializeToString(copy);
      if (xml.length < 2e6) put('data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml), 'image', 'svg', { w: box.rw, h: box.rh });
    } else if (tag === 'image' && el.namespaceURI === SVG) {
      put(el.href?.baseVal || el.getAttribute('href') || el.getAttribute('xlink:href'), 'image', 'svg-image');
    } else if (tag === 'a' && el.href) {
      const kind = kindOf(el.href);
      if (kind) put(el.href, kind, 'link');
    }

    // ponytail: three getComputedStyle calls per element, fine on demand, cache if scans get slow on huge DOMs
    const win = doc.defaultView;
    if (!win) return;
    for (const u of cssUrls(win.getComputedStyle(el).backgroundImage)) put(u, 'image', 'css');
    for (const pseudo of ['::before', '::after']) {
      const s = win.getComputedStyle(el, pseudo);
      if (s.content === 'none' || s.content === 'normal') continue;
      for (const u of [...cssUrls(s.backgroundImage), ...cssUrls(s.content)]) put(u, 'image', 'css' + pseudo);
    }
  }

  function walk(root, add) {
    const tw = (root.ownerDocument || root).createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    for (let el = tw.nextNode(); el; el = tw.nextNode()) {
      fromElement(el, add);
      if (el.shadowRoot) walk(el.shadowRoot, add);
      if (el.localName === 'iframe' || el.localName === 'frame') {
        let d = null;
        try { d = el.contentDocument } catch {}
        if (d) scanDoc(d, add);
      }
    }
  }

  function scanDoc(doc, add) {
    walk(doc, add);
    // Catches media loaded by scripts that never touches the DOM (new Image(), fetch, CSS).
    for (const e of doc.defaultView?.performance.getEntriesByType('resource') || []) {
      const kind = kindOf(e.name) || RESOURCE_KIND[e.initiatorType];
      if (kind) add(e.name, kind, 'network', doc.baseURI);
    }
  }

  function scan(doc = document) {
    const { out, add } = collector();
    scanDoc(doc, add);
    return out;
  }

  function stack(x, y, doc, add) {
    // Make every element hit-testable for one query, so pointer-events: none can't hide media.
    const style = doc.createElement('style');
    style.textContent = '*{pointer-events:auto!important}';
    (doc.head || doc.documentElement).append(style);
    let els;
    try { els = doc.elementsFromPoint(x, y) } finally { style.remove() }
    const seenRoots = new Set();
    const inner = (root) => {
      if (seenRoots.has(root)) return;
      seenRoots.add(root);
      for (const el of root.elementsFromPoint(x, y)) {
        fromElement(el, add);
        if (el.shadowRoot) inner(el.shadowRoot);
      }
    };
    for (const el of els) {
      fromElement(el, add);
      if (el.shadowRoot) inner(el.shadowRoot);
      if (el.localName === 'iframe') {
        let d = null;
        try { d = el.contentDocument } catch {}
        if (d) {
          const r = el.getBoundingClientRect();
          stack(x - r.left - el.clientLeft, y - r.top - el.clientTop, d, add);
        }
      }
    }
  }

  function at(x, y, doc = document) {
    const { out, add } = collector();
    stack(x, y, doc, add);
    return out;
  }

  // The media of one element, for the hover picker.
  function of(el) {
    const { out, add } = collector();
    fromElement(el, add);
    return out;
  }

  return { scan, at, of, kindOf, largest };
})();
if (typeof module !== 'undefined') module.exports = LodiFinder;
