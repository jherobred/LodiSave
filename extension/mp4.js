// Fragmented MP4 (CMAF) surgery: what DASH, HLS fMP4 and MediaSource players stream.
// merge(video, audio) joins a video-only and an audio-only stream into one file with both tracks.
// clean(bytes) keeps one continuous run of a single-track stream, dropping repeated init segments.
// repair(bytes) fixes a file an older LodiSave joined: looped fragments, video starting after audio.
// flatten(bytes) turns fragmented MP4 into a plain MP4, which is what merge, clean and repair give:
// one index up front with each track's real length. Windows' own player takes a fragmented file's
// length from the site's stream header and plays such files badly; Chromium players don't.
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

  function joinFragments(video, audio) {
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

  function cleanFragments(bytes) {
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
  // again. Any fragmented MP4 comes back as a plain one, which Windows' own player times right.
  // Null when it isn't fragmented MP4 or nothing changes.
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
    const ok = streams.length && !streams.some((s) => !s);
    const video = ok && streams.find((s) => s.kind === 'vide'), audio = ok && streams.find((s) => s.kind === 'soun');
    const joined = !ok ? null : streams.length === 1 ? cleanFragments(streams[0].bytes) : streams.length === 2 && video && audio ? joinFragments(video.bytes, audio.bytes) : null;
    const out = flatten(joined || b) || joined;
    if (!out || out.length === b.length && out.every((v, i) => v === b[i])) return null;
    return out;
  }

  // ---- Plain MP4 ----
  // Each track's samples are gathered from its fragments in file order. A fragment's decode time
  // closes a gap or overlap by stretching or shortening the sample before it, so the tracks stay in
  // step. When each track starts goes into its composition offsets, with no edit list. Samples go
  // into the mdat in half-second chunks, the tracks interleaved by time, and the moov goes first so
  // a player can start at once.
  const u24 = (b, o) => (b[o] << 16) | (b[o + 1] << 8) | b[o + 2];
  const words = (list) => { const o = new Uint8Array(list.length * 4); list.forEach((v, i) => put32(o, i * 4, v)); return o };
  const full = (type, version, parts) => make(type, [Uint8Array.of(version, 0, 0, 0), ...parts]);
  const text = (b, o) => String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);
  const ascii = (str) => Uint8Array.from(str, (c) => c.charCodeAt(0));

  function flatten(b) {
    const top = boxes(b), moov = top.find((x) => x.type === 'moov'), mvex = moov && child(b, moov, 'mvex');
    if (!mvex || child(b, moov, 'pssh')) return null;
    const mvhd = child(b, moov, 'mvhd'), trexes = kids(b, mvex).filter((k) => k.type === 'trex');
    if (!mvhd) return null;
    const tracks = [];
    for (const trak of kids(b, moov).filter((k) => k.type === 'trak')) {
      const tkhd = child(b, trak, 'tkhd'), mdia = child(b, trak, 'mdia'), mdhd = child(b, mdia, 'mdhd'), hdlr = child(b, mdia, 'hdlr');
      const stbl = child(b, child(b, mdia, 'minf'), 'stbl'), stsd = child(b, stbl, 'stsd');
      if (!tkhd || !mdhd || !stbl || !stsd) return null;
      const id = u32(b, body(tkhd) + (b[body(tkhd)] === 1 ? 20 : 12)), trex = trexes.find((t) => u32(b, body(t) + 4) === id);
      const entry = text(b, body(stsd) + 12);
      if (!trex || entry === 'encv' || entry === 'enca') return null;
      // The media time of the first edit that isn't empty: an audio encoder's delay.
      let m = 0;
      const elst = child(b, child(b, trak, 'edts'), 'elst');
      if (elst) {
        const v1 = b[body(elst)] === 1, n = u32(b, body(elst) + 4);
        for (let i = 0, q = body(elst) + 8; i < n && q < elst.end; i++, q += v1 ? 20 : 12) {
          const t = v1 ? (u32(b, q + 8) >= 0x80000000 ? -1 : u64(b, q + 8)) : u32(b, q + 4) | 0;
          if (t >= 0) { m = t; break }
        }
      }
      tracks.push({
        trak, stbl, stsd, id, entry, m, scale: timescale(b, mdhd), samples: [], next: 0,
        sound: !!hdlr && text(b, body(hdlr) + 8) === 'soun',
        def: { sdi: u32(b, body(trex) + 8), dur: u32(b, body(trex) + 12), size: u32(b, body(trex) + 16), flags: u32(b, body(trex) + 20) },
      });
    }
    for (const moof of top.filter((x) => x.type === 'moof')) {
      let prevEnd = null;
      for (const traf of kids(b, moof).filter((k) => k.type === 'traf')) {
        const tfhd = child(b, traf, 'tfhd');
        if (!tfhd) continue;
        const p = body(tfhd), fl = u24(b, p + 1), tr = tracks.find((t) => t.id === u32(b, p + 4));
        if (!tr) continue;
        let o = p + 8, base = moof.start, { sdi, dur: dd, size: ds, flags: df } = tr.def;
        if (fl & 1) { base = u64(b, o); o += 8 } else if (!(fl & 0x20000) && prevEnd !== null) base = prevEnd;
        if (fl & 2) { sdi = u32(b, o); o += 4 }
        if (fl & 8) { dd = u32(b, o); o += 4 }
        if (fl & 0x10) { ds = u32(b, o); o += 4 }
        if (fl & 0x20) df = u32(b, o);
        const tfdt = child(b, traf, 'tfdt'), last = tr.samples[tr.samples.length - 1];
        let t = tfdt ? (b[body(tfdt)] === 1 ? u64(b, body(tfdt) + 4) : u32(b, body(tfdt) + 4)) : tr.next;
        if (last && t > last.dts) last.dur = t - last.dts;
        else if (last) t = last.dts + last.dur;
        let pos = base;
        for (const trun of kids(b, traf).filter((k) => k.type === 'trun')) {
          const q = body(trun), v = b[q], rf = u24(b, q + 1), n = u32(b, q + 4);
          let r = q + 8, first = null;
          if (rf & 1) { pos = base + (u32(b, r) | 0); r += 4 }
          if (rf & 4) { first = u32(b, r); r += 4 }
          for (let i = 0; i < n; i++) {
            let dur = dd, size = ds, flags = i === 0 && first !== null ? first : df, cto = 0;
            if (rf & 0x100) { dur = u32(b, r); r += 4 }
            if (rf & 0x200) { size = u32(b, r); r += 4 }
            if (rf & 0x400) { flags = u32(b, r); r += 4 }
            if (rf & 0x800) { cto = v ? u32(b, r) | 0 : u32(b, r); r += 4 }
            if (r > trun.end || pos < 0 || pos + size > b.length) break; // a cut-off file ends here
            tr.samples.push({ dts: t, dur, size, pos, sync: !(flags & 0x10000), cto, sdi });
            t += dur;
            pos += size;
          }
        }
        tr.next = t;
        prevEnd = pos;
      }
    }
    const live = tracks.filter((t) => t.samples.length);
    if (!live.length) return null;

    // Times go into the composition offsets alone, and none is negative, so every player reads them
    // the same way (dts + cto) without an edit list. Windows' player ignores empty edits and edit
    // media times in a plain file, ffmpeg (so Chromium) drops a gap at the start of an edit and
    // guesses a B-frame delay without one. A track keeps when it starts (its first decode time) less
    // an audio encoder's delay (its edit list's media time), and the whole movie moves later by the
    // least that keeps every offset at 0 or more: the encoder delay, or a B-frame delay.
    const M = timescale(b, mvhd);
    if (!M || live.some((t) => !t.scale)) return null;
    for (const tr of live) {
      tr.media = tr.samples.reduce((n, s) => n + s.dur, 0);
      tr.m = Math.min(tr.m, tr.samples[0].dts + tr.media);
      tr.need = tr.m - tr.samples[0].dts - tr.samples.reduce((n, s) => Math.min(n, s.cto), Infinity);
    }
    const most = live.reduce((a, t) => (t.need * a.scale > a.need * t.scale ? t : a));
    for (const tr of live) {
      const late = most.need <= 0 ? 0 : tr === most ? most.need : Math.floor((most.need * tr.scale + most.scale - 1) / most.scale);
      const shift = tr.samples[0].dts - tr.m + late;
      let t = 0, end = 0;
      for (const s of tr.samples) { s.cto += shift; end = Math.max(end, t + s.cto + s.dur); t += s.dur }
      tr.length = Math.floor((end * M + Math.floor(tr.scale / 2)) / tr.scale); // in the movie's timescale
    }
    // Half-second chunks, interleaved by decode time; the earlier track wins a tie.
    const chunks = [];
    live.forEach((tr, k) => {
      tr.chunks = [];
      let c = null;
      for (const s of tr.samples) {
        if (!c || 2 * (s.dts - c.t) >= tr.scale || s.sdi !== c.sdi) tr.chunks.push(c = { k, tr, t: s.dts, sdi: s.sdi, samples: [], bytes: 0, at: 0 });
        c.samples.push(s);
        c.bytes += s.size;
      }
      chunks.push(...tr.chunks);
    });
    chunks.sort((x, y) => x.t * y.tr.scale - y.t * x.tr.scale || x.k - y.k);
    const payload = chunks.reduce((n, c) => n + c.bytes, 0);
    let big = false; // past 4 GB the mdat needs a 64-bit size and co64 offsets

    const rle = (list) => {
      const out = [];
      for (const v of list) out.length && out[out.length - 1][1] === v ? out[out.length - 1][0]++ : out.push([1, v]);
      return out;
    };
    // A copy of a full box with a length field set: at32 in version 0, at64 in version 1.
    const withLength = (x, at32, at64, v) => {
      const out = b.slice(x.start, x.end), p = x.head;
      if (out[p] === 1) put64(out, p + at64, v);
      else put32(out, p + at32, Math.min(v, 0xffffffff));
      return out;
    };
    const stbl = (tr) => {
      const s = tr.samples, durs = rle(s.map((x) => x.dur)), parts = [b.slice(tr.stsd.start, tr.stsd.end)];
      parts.push(full('stts', 0, [words([durs.length, ...durs.flat()])]));
      if (s.some((x) => x.cto)) {
        const ctos = rle(s.map((x) => x.cto));
        parts.push(full('ctts', s.some((x) => x.cto < 0) ? 1 : 0, [words([ctos.length, ...ctos.flatMap(([n, v]) => [n, v >>> 0])])]));
      }
      if (!tr.sound && s.some((x) => !x.sync)) {
        const keys = s.flatMap((x, i) => (x.sync ? [i + 1] : []));
        parts.push(full('stss', 0, [words([keys.length, ...keys])]));
      }
      const size = s[0].size;
      parts.push(full('stsz', 0, [words(s.every((x) => x.size === size) ? [size, s.length] : [0, s.length, ...s.map((x) => x.size)])]));
      const stsc = [];
      tr.chunks.forEach((c, i) => {
        const prev = stsc[stsc.length - 1];
        if (!prev || prev[1] !== c.samples.length || prev[2] !== c.sdi) stsc.push([i + 1, c.samples.length, c.sdi]);
      });
      parts.push(full('stsc', 0, [words([stsc.length, ...stsc.flat()])]));
      if (big) {
        const o = new Uint8Array(4 + tr.chunks.length * 8);
        put32(o, 0, tr.chunks.length);
        tr.chunks.forEach((c, i) => put64(o, 4 + i * 8, c.at));
        parts.push(full('co64', 0, [o]));
      } else parts.push(full('stco', 0, [words([tr.chunks.length, ...tr.chunks.map((c) => c.at)])]));
      for (const k of kids(b, tr.stbl)) if (!/^(stsd|stts|ctts|stss|stsz|stz2|stsc|stco|co64|sbgp)$/.test(k.type)) parts.push(b.slice(k.start, k.end));
      return make('stbl', parts);
    };
    const trak = (tr) => make('trak', kids(b, tr.trak).flatMap((k) => {
      if (k.type === 'tkhd') return [withLength(k, 20, 28, tr.length)];
      if (k.type === 'edts') return [];
      if (k.type !== 'mdia') return [b.slice(k.start, k.end)];
      return [make('mdia', kids(b, k).map((x) => x.type === 'mdhd' ? withLength(x, 16, 24, tr.media)
        : x.type === 'minf' ? make('minf', kids(b, x).map((y) => (y.type === 'stbl' ? stbl(tr) : b.slice(y.start, y.end))))
          : b.slice(x.start, x.end)))];
    }));
    const movie = () => make('moov', kids(b, moov).flatMap((k) => {
      if (k.type === 'mvhd') return [withLength(k, 16, 24, Math.max(...live.map((t) => t.length)))];
      if (k.type === 'trak') { const tr = live.find((t) => t.trak.start === k.start); return tr ? [trak(tr)] : [] }
      return k.type === 'mvex' || k.type === 'pssh' ? [] : [b.slice(k.start, k.end)];
    }));
    const brands = ['isom', 'iso2', ...(live.some((t) => t.entry === 'avc1') ? ['avc1'] : []), 'mp41'];
    const ftyp = make('ftyp', [ascii('isom'), words([512]), ...brands.map(ascii)]);
    big = ftyp.length + movie().length + 8 + payload > 0xffffffff;
    const head = big ? 16 : 8;
    let at = ftyp.length + movie().length + head; // the moov's size doesn't depend on the offsets
    for (const c of chunks) { c.at = at; at += c.bytes }
    const moovOut = movie(), out = new Uint8Array(at);
    out.set(ftyp, 0);
    out.set(moovOut, ftyp.length);
    let o = ftyp.length + moovOut.length;
    if (big) { put32(out, o, 1); out.set(ascii('mdat'), o + 4); put64(out, o + 8, payload + 16) } else { put32(out, o, payload + 8); out.set(ascii('mdat'), o + 4) }
    o += head;
    for (const c of chunks) for (const s of c.samples) { out.set(b.subarray(s.pos, s.pos + s.size), o); o += s.size }
    return out;
  }

  // A plain MP4 when one can be made, else the fragmented file.
  const plain = (x) => x && (flatten(x) || x);
  const merge = (video, audio) => plain(joinFragments(video, audio));
  const clean = (bytes) => plain(cleanFragments(bytes));

  return { merge, clean, repair, fragmented, flatten, joinFragments };
})();
if (typeof module !== 'undefined') module.exports = LodiMP4;
