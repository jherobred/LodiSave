// Fragmented MP4 (CMAF) surgery: what DASH, HLS fMP4 and MediaSource players stream.
// merge(video, audio) joins a video-only and an audio-only stream into one file with both tracks.
// clean(bytes) keeps one continuous run of a single-track stream, dropping repeated init segments.
// repair(bytes) fixes a file an older LodiSave joined: looped fragments, video starting after audio.
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
        const t = !tfdt ? null : b[body(tfdt)] === 1 ? u64(b, body(tfdt) + 4) : u32(b, body(tfdt) + 4);
        run.fragments.push({ moof: box, mdat: top[i + 1], t });
        run.bytes += top[i + 1].end - top[i + 1].start;
      }
    }
    runs.sort((x, y) => y.bytes - x.bytes);
    const run = runs[0];
    if (!run || !run.fragments.length || !child(b, run.moov, 'mvex')) return null;
    const traks = kids(b, run.moov).filter((k) => k.type === 'trak');
    if (traks.length !== 1 || child(b, run.moov, 'pssh')) return null; // one clear track only
    const mdhd = child(b, child(b, traks[0], 'mdia'), 'mdhd'), scale = timescale(b, mdhd);
    const trex = child(b, child(b, run.moov, 'mvex'), 'trex');
    if (!trex) return null;
    const def = { dur: u32(b, body(trex) + 12), size: u32(b, body(trex) + 16) };
    const s = { b, ...run, trak: traks[0], mvhd: child(b, run.moov, 'mvhd'), timescale: scale, def };
    // Without decode times there is no timeline to rebuild: the biggest run plays as it came.
    if (run.fragments.some((f) => f.t === null)) return { ...s, fragments: run.fragments.map((f) => ({ ...f, t: 0, d: 0 })) };

    // The rest of the timeline comes from runs that play with the same init: the same quality again,
    // or another quality of VP8/VP9/AV1, whose frames carry their own size.
    const entry = sampleEntry(b, traks[0]), defaults = b.subarray(body(trex) + 8, trex.end);
    const fits = (r) => {
      if (same(b.subarray(r.moov.start, r.moov.end), b.subarray(run.moov.start, run.moov.end))) return true;
      const tr = kids(b, r.moov).filter((k) => k.type === 'trak'), rx = child(b, child(b, r.moov, 'mvex'), 'trex');
      return /^(vp08|vp09|av01)$/.test(entry) && tr.length === 1 && !!rx && !child(b, r.moov, 'pssh') && sampleEntry(b, tr[0]) === entry
        && timescale(b, child(b, child(b, tr[0], 'mdia'), 'mdhd')) === scale && same(b.subarray(body(rx) + 8, rx.end), defaults);
    };
    // Fragments that replay a time already kept (a looping player feeds them again) are dropped, so
    // the track plays once, in order. The biggest run wins where runs overlap.
    const kept = [];
    for (const r of runs.filter(fits)) {
      for (const f of r.fragments) {
        f.d = durations(b, f.moof, def).reduce((x, y) => x + y, 0);
        const overlap = (k) => Math.min(k.t + k.d, f.t + f.d) - Math.max(k.t, f.t);
        if (!kept.some((k) => k.t === f.t || overlap(k) > Math.min(k.d, f.d) * 0.05)) kept.push(f);
      }
    }
    return { ...s, fragments: kept.sort((x, y) => x.t - y.t) };
  }

  // Sample durations and sizes of one trun, filled in from the tfhd and trex defaults.
  function samples(b, tfhd, trun, def) {
    const tf = u32(b, body(tfhd)) & 0xffffff;
    let o = body(tfhd) + 8 + (tf & 1 ? 8 : 0) + (tf & 2 ? 4 : 0);
    const dur = tf & 8 ? u32(b, o) : def.dur;
    if (tf & 8) o += 4;
    const size = tf & 0x10 ? u32(b, o) : def.size;
    const f = u32(b, body(trun)) & 0xffffff, rec = 4 * [0x100, 0x200, 0x400, 0x800].filter((x) => f & x).length;
    const first = body(trun) + 8 + (f & 1 ? 4 : 0) + (f & 4 ? 4 : 0);
    const n = Math.min(u32(b, body(trun) + 4), rec ? Math.floor((trun.end - first) / rec) : 1e6);
    const durs = [], sizes = [];
    for (let i = 0, p = first; i < n; i++, p += rec) {
      durs.push(f & 0x100 ? u32(b, p) : dur);
      sizes.push(f & 0x200 ? u32(b, p + (f & 0x100 ? 4 : 0)) : size);
    }
    return { f, rec, first, durs, sizes };
  }
  // Every sample duration of a moof's first track fragment.
  const durations = (b, moof, def) => {
    const traf = child(b, moof, 'traf'), tfhd = child(b, traf, 'tfhd');
    return tfhd ? kids(b, traf).filter((k) => k.type === 'trun').flatMap((tr) => samples(b, tfhd, tr, def).durs) : [];
  };
  const sampleEntry = (b, trak) => {
    const stsd = child(b, child(b, child(b, child(b, trak, 'mdia'), 'minf'), 'stbl'), 'stsd');
    return stsd ? String.fromCharCode(...b.subarray(body(stsd) + 12, body(stsd) + 16)) : '';
  };
  const same = (x, y) => x.length === y.length && x.every((v, i) => v === y[i]);

  // A fragment without its first k samples, or null when its layout is more than one plain trun.
  // The dropped samples' bytes stay in the mdat; the data offset just skips them.
  function cut(out, k, def) {
    const moof = boxes(out)[0], traf = child(out, moof, 'traf'), parts = traf ? kids(out, traf) : [];
    const tfhd = child(out, traf, 'tfhd'), trun = child(out, traf, 'trun'), tfdt = child(out, traf, 'tfdt');
    if (!tfhd || !trun || !tfdt || kids(out, moof).filter((x) => x.type === 'traf').length !== 1 || parts.filter((p) => p.type === 'trun').length !== 1
      || parts.some((p) => !/^(tfhd|tfdt|trun)$/.test(p.type)) || moof.head !== 8 || traf.head !== 8 || trun.head !== 8) return null;
    const s = samples(out, tfhd, trun, def);
    if (!(s.f & 1) || k >= s.durs.length) return null;
    const removed = k * s.rec, skipped = s.sizes.slice(0, k).reduce((x, y) => x + y, 0), ticks = s.durs.slice(0, k).reduce((x, y) => x + y, 0);
    const res = concat([out.subarray(0, s.first), out.subarray(s.first + removed)]);
    for (const box of [moof, traf, trun]) put32(res, box.start, u32(res, box.start) - removed);
    put32(res, body(trun) + 4, s.durs.length - k);
    put32(res, body(trun) + 8, ((u32(res, body(trun) + 8) | 0) + skipped - removed) >>> 0);
    if (res[body(tfdt)] === 1) put64(res, body(tfdt) + 4, u64(res, body(tfdt) + 4) + ticks);
    else put32(res, body(tfdt) + 4, u32(res, body(tfdt) + 4) + ticks);
    return res;
  }

  // The fragments from `start` (in the track's ticks) on: earlier ones dropped, the one that
  // straddles it cut to its first sample at or after it, or dropped when it can't be cut.
  function after(s, start) {
    const out = [];
    for (const f of s.fragments) {
      if (f.t >= start) out.push(f);
      else if (f.t + f.d > start) {
        const ds = durations(s.b, f.moof, s.def);
        let k = 0;
        for (let t = f.t; k < ds.length && t < start; t += ds[k++]);
        const bytes = k < ds.length && cut(s.b.slice(f.moof.start, f.mdat.end), k, s.def);
        if (bytes) out.push({ ...f, bytes });
      }
    }
    return out;
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

  // Copies one moof+mdat pair: new track and sequence numbers, base offset moved if it is absolute,
  // decode time moved back by `shift` ticks.
  function fragment(s, f, id, seq, at, shift = 0) {
    const out = f.bytes || s.b.slice(f.moof.start, f.mdat.end), moof = boxes(out)[0];
    put32(out, body(child(out, moof, 'mfhd')) + 4, seq);
    for (const traf of kids(out, moof).filter((k) => k.type === 'traf')) {
      const p = body(child(out, traf, 'tfhd')), tfdt = child(out, traf, 'tfdt');
      put32(out, p + 4, id);
      if (out[p + 3] & 1) put64(out, p + 8, u64(out, p + 8) - f.moof.start + at);
      if (tfdt && shift) {
        if (out[body(tfdt)] === 1) put64(out, body(tfdt) + 4, u64(out, body(tfdt) + 4) - shift);
        else put32(out, body(tfdt) + 4, u32(out, body(tfdt) + 4) - shift);
      }
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

    // Both tracks start at 0, where the video's first frame is: a quality switch can leave the video
    // starting seconds after the audio, and players that ignore start times then play the two out of
    // step. Audio from before that frame is cut off.
    const vStart = v.fragments[0].t, aStart = Math.round((vStart * a.timescale) / v.timescale);
    const all = [
      ...v.fragments.map((f) => ({ s: v, f, id: 1, at: f.t / v.timescale, shift: vStart })),
      ...(aStart ? after(a, aStart) : a.fragments).map((f) => ({ s: a, f, id: 2, at: f.t / a.timescale, shift: aStart })),
    ].sort((x, y) => x.at - y.at || x.id - y.id);
    const parts = [ftyp, moov];
    let offset = ftyp.length + moov.length;
    all.forEach((x, i) => {
      const out = fragment(x.s, x.f, x.id, i + 1, offset, x.shift);
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
      const out = fragment(s, f, id, i + 1, offset, s.fragments[0].t);
      parts.push(out);
      offset += out.length;
    });
    return concat(parts);
  }

  // Whether the start of a file (its first few hundred KB is enough) is fragmented MP4, the only kind
  // LodiSave's joiner wrote and repair() can fix.
  function fragmented(head) {
    const moov = boxes(head).find((x) => x.type === 'moov');
    return !!moov && !!child(head, moov, 'mvex');
  }

  // A file saved by an older LodiSave, whose joiner kept looped fragments and let the video start
  // seconds after the audio: its tracks are split back into the streams the player fed and joined
  // again. Null when it isn't fragmented MP4 or nothing changes.
  function repair(b) {
    const top = boxes(b), ftyp = top.find((x) => x.type === 'ftyp'), moov = top.find((x) => x.type === 'moov');
    const mvex = moov && child(b, moov, 'mvex');
    if (!mvex) return null;
    const keep = (x) => b.subarray(x.start, x.end);
    const traks = kids(b, moov).filter((k) => k.type === 'trak'), trexes = kids(b, mvex).filter((k) => k.type === 'trex');
    const mehd = child(b, mvex, 'mehd'), extra = kids(b, moov).filter((k) => !['mvhd', 'trak', 'mvex'].includes(k.type)).map(keep);
    const idOf = (trak) => { const t = child(b, trak, 'tkhd'); return u32(b, body(t) + (b[body(t)] === 1 ? 20 : 12)) };
    const handler = (trak) => { const h = child(b, child(b, trak, 'mdia'), 'hdlr'); return h ? String.fromCharCode(...b.subarray(body(h) + 8, body(h) + 12)) : '' };
    const streams = traks.map((trak) => {
      const id = idOf(trak), trex = trexes.find((t) => u32(b, body(t) + 4) === id);
      if (!trex) return null;
      const parts = [ftyp ? keep(ftyp) : new Uint8Array(0), make('moov', [keep(child(b, moov, 'mvhd')), keep(trak), make('mvex', [...(mehd ? [keep(mehd)] : []), keep(trex)]), ...extra])];
      let at = parts[0].length + parts[1].length, n = 0;
      top.forEach((x, i) => {
        const traf = x.type === 'moof' && top[i + 1]?.type === 'mdat' && child(b, x, 'traf'), tfhd = traf && child(b, traf, 'tfhd');
        if (!tfhd || u32(b, body(tfhd) + 4) !== id) return;
        const out = b.slice(x.start, top[i + 1].end), p = body(tfhd) - x.start;
        if (out[p + 3] & 1) put64(out, p + 8, u64(out, p + 8) - x.start + at);
        parts.push(out);
        at += out.length;
        n++;
      });
      return n ? { bytes: concat(parts), kind: handler(trak) } : null;
    });
    if (!streams.length || streams.some((s) => !s)) return null;
    const video = streams.find((s) => s.kind === 'vide'), audio = streams.find((s) => s.kind === 'soun');
    const out = streams.length === 1 ? clean(streams[0].bytes) : streams.length === 2 && video && audio ? merge(video.bytes, audio.bytes) : null;
    if (!out || out.length === b.length && out.every((v, i) => v === b[i])) return null;
    return out;
  }

  return { merge, clean, repair, fragmented };
})();
if (typeof module !== 'undefined') module.exports = LodiMP4;
