// Fragmented MP4 (CMAF) surgery: what DASH, HLS fMP4 and MediaSource players stream.
// merge(video, audio) joins a video-only and an audio-only stream into one file with both tracks.
// clean(bytes) keeps one continuous run of a single-track stream, dropping repeated init segments.
// Pure byte work with no dependencies, so the extension and the Android WebView can both run it.
// Both return null when the input isn't fragmented MP4.
var LodiMP4 = (() => {
  const u32 = (b, o) => ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0;
  const u64 = (b, o) => u32(b, o) * 2 ** 32 + u32(b, o + 4);
  const put32 = (b, o, v) => {
    b[o] = v >>> 24; b[o + 1] = (v >>> 16) & 255; b[o + 2] = (v >>> 8) & 255; b[o + 3] = v & 255;
  };
  const put64 = (b, o, v) => { put32(b, o, Math.floor(v / 2 ** 32)); put32(b, o + 4, v >>> 0) };

  function boxes(b, start = 0, end = b.length) {
    const out = [];
    for (let o = start; o + 8 <= end;) {
      let size = u32(b, o), head = 8;
      if (size === 1) { size = u64(b, o + 8); head = 16 } else if (size === 0) size = end - o;
      if (size < head || o + size > end) break; // a truncated tail ends the list
      out.push({ type: String.fromCharCode(b[o + 4], b[o + 5], b[o + 6], b[o + 7]), start: o, end: o + size, head });
      o += size;
    }
    return out;
  }
  const kids = (b, box) => boxes(b, box.start + box.head, box.end);
  const child = (b, box, type) => box && kids(b, box).find((c) => c.type === type);
  const body = (box) => box.start + box.head;

  function make(type, parts) {
    const size = 8 + parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(size);
    put32(out, 0, size);
    for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
    let o = 8;
    for (const p of parts) { out.set(p, o); o += p.length }
    return out;
  }

  const concat = (parts) => {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length }
    return out;
  };

  // mvhd and mdhd keep the timescale at the same place.
  const timescale = (b, box) => (b[body(box)] === 1 ? u32(b, body(box) + 20) : u32(b, body(box) + 12));

  // Splits a stream at each init segment and keeps the run with the most media, so a seek or a
  // quality switch doesn't mix sample descriptions.
  function parse(b) {
    const runs = [];
    let ftyp = null;
    const top = boxes(b);
    for (let i = 0; i < top.length; i++) {
      const box = top[i];
      if (box.type === 'ftyp') ftyp = box;
      else if (box.type === 'moov') runs.push({ ftyp, moov: box, fragments: [], bytes: 0 });
      else if (box.type === 'moof' && runs.length && top[i + 1]?.type === 'mdat') {
        const run = runs[runs.length - 1], traf = child(b, box, 'traf'), tfdt = child(b, traf, 'tfdt');
        const t = !tfdt ? 0 : b[body(tfdt)] === 1 ? u64(b, body(tfdt) + 4) : u32(b, body(tfdt) + 4);
        run.fragments.push({ moof: box, mdat: top[i + 1], t });
        run.bytes += top[i + 1].end - top[i + 1].start;
      }
    }
    const run = runs.sort((x, y) => y.bytes - x.bytes)[0];
    if (!run || !run.fragments.length || !child(b, run.moov, 'mvex')) return null;
    const traks = kids(b, run.moov).filter((k) => k.type === 'trak');
    if (traks.length !== 1 || child(b, run.moov, 'pssh')) return null; // one clear track only
    const mdhd = child(b, child(b, traks[0], 'mdia'), 'mdhd');
    return { b, ...run, trak: traks[0], mvhd: child(b, run.moov, 'mvhd'), timescale: timescale(b, mdhd) };
  }

  // A copy of the trak with a new track_ID, its durations moved to another movie timescale.
  function retrack(s, id, scale) {
    const { b, trak } = s, out = b.slice(trak.start, trak.end), at = (box) => body(box) - trak.start;
    const tkhd = child(b, trak, 'tkhd'), p = at(tkhd), v1 = out[p] === 1;
    put32(out, p + (v1 ? 20 : 12), id);
    if (scale !== 1) {
      const d = p + (v1 ? 28 : 20);
      if (v1) put64(out, d, Math.round(u64(out, d) * scale));
      else if (u32(out, d) !== 0xffffffff) put32(out, d, Math.round(u32(out, d) * scale));
      const elst = child(b, child(b, trak, 'edts'), 'elst');
      if (elst) {
        let q = at(elst);
        const ev1 = out[q] === 1, n = u32(out, q + 4);
        q += 8;
        for (let i = 0; i < n; i++, q += ev1 ? 20 : 12) {
          if (ev1) put64(out, q, Math.round(u64(out, q) * scale));
          else put32(out, q, Math.round(u32(out, q) * scale));
        }
      }
    }
    return out;
  }

  function trex(s, id) {
    const t = child(s.b, child(s.b, s.moov, 'mvex'), 'trex');
    const out = s.b.slice(t.start, t.end);
    put32(out, t.head + 4, id);
    return out;
  }

  // Copies one moof+mdat pair: new track and sequence numbers, base offset moved if it is absolute.
  function fragment(s, f, id, seq, at) {
    const { b } = s, out = b.slice(f.moof.start, f.mdat.end), base = f.moof.start;
    const mfhd = child(b, f.moof, 'mfhd');
    put32(out, body(mfhd) - base + 4, seq);
    for (const traf of kids(b, f.moof).filter((k) => k.type === 'traf')) {
      const tfhd = child(b, traf, 'tfhd'), p = body(tfhd) - base;
      put32(out, p + 4, id);
      if (out[p + 3] & 1) put64(out, p + 8, u64(out, p + 8) - base + at);
    }
    return out;
  }

  function merge(video, audio) {
    const v = parse(video), a = parse(audio);
    if (!v || !a) return null;
    const movieScale = timescale(v.b, v.mvhd);
    const mvhd = v.b.slice(v.mvhd.start, v.mvhd.end);
    put32(mvhd, mvhd.length - 4, 3); // next_track_ID
    const mehd = child(v.b, child(v.b, v.moov, 'mvex'), 'mehd');
    const mvex = make('mvex', [mehd ? v.b.slice(mehd.start, mehd.end) : new Uint8Array(0), trex(v, 1), trex(a, 2)]);
    const extra = kids(v.b, v.moov).filter((k) => !['mvhd', 'trak', 'mvex'].includes(k.type)).map((k) => v.b.slice(k.start, k.end));
    const moov = make('moov', [mvhd, retrack(v, 1, 1), retrack(a, 2, movieScale / timescale(a.b, a.mvhd)), mvex, ...extra]);
    const ftyp = v.ftyp ? v.b.slice(v.ftyp.start, v.ftyp.end) : new Uint8Array(0);

    const all = [
      ...v.fragments.map((f) => ({ s: v, f, id: 1, at: f.t / v.timescale })),
      ...a.fragments.map((f) => ({ s: a, f, id: 2, at: f.t / a.timescale })),
    ].sort((x, y) => x.at - y.at || x.id - y.id);
    const parts = [ftyp, moov];
    let offset = ftyp.length + moov.length;
    all.forEach((x, i) => {
      const out = fragment(x.s, x.f, x.id, i + 1, offset);
      parts.push(out);
      offset += out.length;
    });
    return concat(parts);
  }

  function clean(bytes) {
    const s = parse(bytes);
    if (!s) return null;
    const parts = [s.ftyp ? s.b.slice(s.ftyp.start, s.ftyp.end) : new Uint8Array(0), s.b.slice(s.moov.start, s.moov.end)];
    let offset = parts[0].length + parts[1].length;
    const id = u32(s.b, body(child(s.b, s.trak, 'tkhd')) + (s.b[body(child(s.b, s.trak, 'tkhd'))] === 1 ? 20 : 12));
    s.fragments.forEach((f, i) => {
      const out = fragment(s, f, id, i + 1, offset);
      parts.push(out);
      offset += out.length;
    });
    return concat(parts);
  }

  return { merge, clean };
})();
if (typeof module !== 'undefined') module.exports = LodiMP4;
