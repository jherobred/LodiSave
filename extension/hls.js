// Minimal HLS playlist reader. Encrypted playlists are reported, never decrypted.
var LodiHLS = (() => {
  const attrs = (s) => {
    const o = {};
    for (const m of s.matchAll(/([A-Z0-9-]+)=("[^"]*"|[^,]*)/g)) o[m[1]] = m[2].replace(/^"|"$/g, '');
    return o;
  };

  function parse(text, base) {
    const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    if (lines[0] !== '#EXTM3U') throw new Error('Not an HLS playlist');
    const pl = { variants: [], media: [], segments: [], map: null, encrypted: null, live: !lines.includes('#EXT-X-ENDLIST') };
    const ends = {};
    // "len@offset" -> "start-end" for a Range header. A missing offset continues the previous range.
    const range = (spec, url) => {
      const [len, off] = spec.split('@').map(Number);
      const start = Number.isFinite(off) ? off : ends[url] || 0;
      ends[url] = start + len;
      return `${start}-${start + len - 1}`;
    };
    let pending = null;
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      if (l.startsWith('#EXT-X-STREAM-INF:')) {
        const a = attrs(l.slice(18));
        if (lines[i + 1] && !lines[i + 1].startsWith('#')) {
          pl.variants.push({ url: new URL(lines[++i], base).href, bandwidth: +a.BANDWIDTH || 0, resolution: a.RESOLUTION || '', audio: a.AUDIO || null });
        }
      } else if (l.startsWith('#EXT-X-MEDIA:')) {
        const a = attrs(l.slice(13));
        if (a.URI) pl.media.push({ type: a.TYPE, group: a['GROUP-ID'], isDefault: a.DEFAULT === 'YES', url: new URL(a.URI, base).href });
      } else if (l.startsWith('#EXT-X-KEY:')) {
        const method = attrs(l.slice(11)).METHOD;
        if (method && method !== 'NONE') pl.encrypted = method;
      } else if (l.startsWith('#EXT-X-MAP:')) {
        const a = attrs(l.slice(11)), url = new URL(a.URI, base).href;
        pl.map = { url, range: a.BYTERANGE ? range(a.BYTERANGE, url) : null };
      } else if (l.startsWith('#EXT-X-BYTERANGE:')) {
        pending = l.slice(17);
      } else if (!l.startsWith('#')) {
        const url = new URL(l, base).href;
        pl.segments.push({ url, range: pending ? range(pending, url) : null });
        pending = null;
      }
    }
    return pl;
  }

  const best = (variants) => variants.reduce((a, v) => (!a || v.bandwidth > a.bandwidth ? v : a), null);

  // The separate audio playlist a variant plays with (X and Reddit stream audio this way), or null.
  const audioFor = (pl, variant) => {
    const group = pl.media.filter((m) => m.type === 'AUDIO' && m.group === variant.audio);
    return group.find((m) => m.isDefault) || group[0] || null;
  };

  return { parse, best, audioFor };
})();
if (typeof module !== 'undefined') module.exports = LodiHLS;
