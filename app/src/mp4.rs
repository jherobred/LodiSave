//! Joins a video-only and an audio-only fragmented MP4 (what DASH serves) into one plain MP4 with
//! both tracks, so HD downloads need no FFmpeg and Windows' own player times them right. A port of
//! extension/mp4.js (merge and flatten) that copies the media data straight from disk, so memory
//! use stays small for long videos. Malformed input never panics: out-of-range reads give 0 and the
//! result is just a broken file.

use std::fs::File;
use std::io::{self, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::Path;

#[derive(Clone, Copy)]
struct Bx {
    kind: [u8; 4],
    start: usize,
    end: usize,
    head: usize,
}

fn byte(b: &[u8], o: usize) -> u8 {
    b.get(o).copied().unwrap_or(0)
}
fn u32_at(b: &[u8], o: usize) -> u32 {
    b.get(o..o + 4).map_or(0, |s| u32::from_be_bytes([s[0], s[1], s[2], s[3]]))
}
fn u64_at(b: &[u8], o: usize) -> u64 {
    (u32_at(b, o) as u64) << 32 | u32_at(b, o + 4) as u64
}
fn put32(b: &mut [u8], o: usize, v: u32) {
    if let Some(s) = b.get_mut(o..o + 4) {
        s.copy_from_slice(&v.to_be_bytes());
    }
}
fn put64(b: &mut [u8], o: usize, v: u64) {
    if let Some(s) = b.get_mut(o..o + 8) {
        s.copy_from_slice(&v.to_be_bytes());
    }
}

/// The boxes in b[start..end]. A truncated tail ends the list.
fn boxes(b: &[u8], start: usize, end: usize) -> Vec<Bx> {
    let mut out = Vec::new();
    let mut o = start;
    while o + 8 <= end {
        let (mut size, mut head) = (u32_at(b, o) as u64, 8);
        if size == 1 {
            size = u64_at(b, o + 8);
            head = 16;
        } else if size == 0 {
            size = (end - o) as u64;
        }
        if size < head as u64 || size > (end - o) as u64 {
            break;
        }
        out.push(Bx { kind: [b[o + 4], b[o + 5], b[o + 6], b[o + 7]], start: o, end: o + size as usize, head });
        o += size as usize;
    }
    out
}
fn kids(b: &[u8], x: &Bx) -> Vec<Bx> {
    boxes(b, x.start + x.head, x.end)
}
fn child(b: &[u8], x: Option<Bx>, kind: &[u8; 4]) -> Option<Bx> {
    kids(b, &x?).into_iter().find(|c| &c.kind == kind)
}
fn body(x: &Bx) -> usize {
    x.start + x.head
}
/// The box that fills a buffer read from disk.
fn whole(b: &[u8]) -> Option<Bx> {
    boxes(b, 0, b.len()).first().copied()
}

fn make(kind: &[u8; 4], parts: &[&[u8]]) -> Vec<u8> {
    let size = 8 + parts.iter().map(|p| p.len()).sum::<usize>();
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&(size as u32).to_be_bytes());
    out.extend_from_slice(kind);
    for p in parts {
        out.extend_from_slice(p);
    }
    out
}

/// mvhd and mdhd keep the timescale at the same place.
fn timescale(b: &[u8], x: &Bx) -> u32 {
    u32_at(b, body(x) + if byte(b, body(x)) == 1 { 20 } else { 12 })
}

struct Top {
    kind: [u8; 4],
    start: u64,
    end: u64,
}

/// Top-level box headers, read with seeks so mdat payloads stay on disk.
fn top(f: &mut File) -> io::Result<Vec<Top>> {
    let len = f.metadata()?.len();
    let (mut out, mut o, mut h) = (Vec::new(), 0u64, [0u8; 16]);
    while o + 8 <= len {
        f.seek(SeekFrom::Start(o))?;
        f.read_exact(&mut h[..8])?;
        let mut size = u32_at(&h, 0) as u64;
        let head = if size == 1 {
            if o + 16 > len {
                break;
            }
            f.read_exact(&mut h[8..16])?;
            size = u64_at(&h, 8);
            16
        } else {
            if size == 0 {
                size = len - o;
            }
            8
        };
        if size < head || size > len - o {
            break;
        }
        out.push(Top { kind: [h[4], h[5], h[6], h[7]], start: o, end: o + size });
        o += size;
    }
    Ok(out)
}

/// Init and fragment headers are small. Anything bigger isn't a stream this code understands.
const SMALL: u64 = 64 << 20;

fn read(f: &mut File, t: &Top) -> io::Result<Option<Vec<u8>>> {
    if t.end - t.start > SMALL {
        return Ok(None);
    }
    let mut buf = vec![0; (t.end - t.start) as usize];
    f.seek(SeekFrom::Start(t.start))?;
    f.read_exact(&mut buf)?;
    Ok(Some(buf))
}

struct Fragment {
    moof: Vec<u8>,
    at: u64,
    mdat: (u64, u64),
    t: u64,
}

struct Stream {
    file: File,
    ftyp: Vec<u8>,
    moov: Vec<u8>,
    fragments: Vec<Fragment>,
    timescale: u32,
}

/// Splits a stream at each init segment and keeps the run with the most media, so a seek or a
/// quality switch doesn't mix sample descriptions. None unless it is one clear fragmented track.
fn parse(path: &Path) -> io::Result<Option<Stream>> {
    let mut file = File::open(path)?;
    let top = top(&mut file)?;
    struct Run {
        ftyp: Option<usize>,
        moov: usize,
        frags: Vec<usize>,
        bytes: u64,
    }
    let (mut runs, mut ftyp) = (Vec::<Run>::new(), None);
    for (i, x) in top.iter().enumerate() {
        match &x.kind {
            b"ftyp" => ftyp = Some(i),
            b"moov" => runs.push(Run { ftyp, moov: i, frags: Vec::new(), bytes: 0 }),
            b"moof" if top.get(i + 1).is_some_and(|n| &n.kind == b"mdat") => {
                if let Some(run) = runs.last_mut() {
                    run.frags.push(i);
                    run.bytes += top[i + 1].end - top[i + 1].start;
                }
            }
            _ => {}
        }
    }
    // rev(): the first of equally big runs wins, as in the JS version.
    let Some(run) = runs.into_iter().rev().max_by_key(|r| r.bytes) else { return Ok(None) };
    let Some(moov) = read(&mut file, &top[run.moov])? else { return Ok(None) };
    let m = whole(&moov);
    let traks: Vec<Bx> = m.map(|m| kids(&moov, &m)).unwrap_or_default().into_iter().filter(|k| &k.kind == b"trak").collect();
    if run.frags.is_empty() || child(&moov, m, b"mvex").is_none() || traks.len() != 1 || child(&moov, m, b"pssh").is_some() {
        return Ok(None);
    }
    let Some(mdhd) = child(&moov, child(&moov, Some(traks[0]), b"mdia"), b"mdhd") else { return Ok(None) };
    let ftyp = match run.ftyp {
        Some(i) => read(&mut file, &top[i])?.unwrap_or_default(),
        None => Vec::new(),
    };
    let mut fragments = Vec::with_capacity(run.frags.len());
    for i in run.frags {
        let Some(moof) = read(&mut file, &top[i])? else { return Ok(None) };
        let tfdt = child(&moof, child(&moof, whole(&moof), b"traf"), b"tfdt");
        let t = tfdt.map_or(0, |x| if byte(&moof, body(&x)) == 1 { u64_at(&moof, body(&x) + 4) } else { u32_at(&moof, body(&x) + 4) as u64 });
        fragments.push(Fragment { moof, at: top[i].start, mdat: (top[i + 1].start, top[i + 1].end), t });
    }
    let timescale = timescale(&moov, &mdhd);
    Ok(Some(Stream { file, ftyp, moov, fragments, timescale }))
}

/// A copy of the trak with a new track_ID, its durations moved to another movie timescale.
fn retrack(moov: &[u8], trak: Bx, id: u32, scale: f64) -> Vec<u8> {
    let mut out = moov[trak.start..trak.end].to_vec();
    let at = |x: &Bx| body(x) - trak.start;
    let Some(tkhd) = child(moov, Some(trak), b"tkhd") else { return out };
    let (p, v1) = (at(&tkhd), byte(moov, body(&tkhd)) == 1);
    put32(&mut out, p + if v1 { 20 } else { 12 }, id);
    if scale != 1.0 {
        let d = p + if v1 { 28 } else { 20 };
        if v1 {
            let v = u64_at(&out, d);
            put64(&mut out, d, (v as f64 * scale).round() as u64);
        } else if u32_at(&out, d) != u32::MAX {
            let v = u32_at(&out, d);
            put32(&mut out, d, (v as f64 * scale).round() as u32);
        }
        if let Some(elst) = child(moov, child(moov, Some(trak), b"edts"), b"elst") {
            let mut q = at(&elst);
            let (ev1, n) = (byte(&out, q) == 1, u32_at(&out, q + 4));
            q += 8;
            for _ in 0..n.min(4096) {
                if ev1 {
                    let v = u64_at(&out, q);
                    put64(&mut out, q, (v as f64 * scale).round() as u64);
                } else {
                    let v = u32_at(&out, q);
                    put32(&mut out, q, (v as f64 * scale).round() as u32);
                }
                q += if ev1 { 20 } else { 12 };
            }
        }
    }
    out
}

fn trex(moov: &[u8], id: u32) -> Option<Vec<u8>> {
    let t = child(moov, child(moov, whole(moov), b"mvex"), b"trex")?;
    let mut out = moov[t.start..t.end].to_vec();
    put32(&mut out, t.head + 4, id);
    Some(out)
}

/// New track and sequence numbers for one moof, and its base offset moved if it is absolute.
fn patch(moof: &mut [u8], id: u32, seq: u32, base: u64, at: u64) {
    let m = whole(moof);
    if let Some(mfhd) = child(moof, m, b"mfhd") {
        put32(moof, body(&mfhd) + 4, seq);
    }
    let trafs: Vec<Bx> = m.map(|m| kids(moof, &m)).unwrap_or_default().into_iter().filter(|k| &k.kind == b"traf").collect();
    for traf in trafs {
        if let Some(tfhd) = child(moof, Some(traf), b"tfhd") {
            let p = body(&tfhd);
            put32(moof, p + 4, id);
            if byte(moof, p + 3) & 1 == 1 {
                let v = u64_at(moof, p + 8);
                put64(moof, p + 8, v.wrapping_sub(base).wrapping_add(at));
            }
        }
    }
}

/// Writes `out` as one plain MP4 with the video and audio tracks. Ok(false) when either input isn't
/// a single-track fragmented MP4 (WebM, progressive MP4), and nothing is written.
pub fn merge(video: &Path, audio: &Path, out: &Path) -> io::Result<bool> {
    let frag = out.with_extension("joining");
    let done = join(video, audio, &frag).and_then(|joined| if joined { flatten(&frag, out).map(|flat| (true, flat)) } else { Ok((false, false)) });
    match done {
        // A fragmented file is still a good file when it can't be made plain.
        Ok((true, false)) => std::fs::rename(&frag, out).map(|_| true),
        Ok((joined, _)) => {
            let _ = std::fs::remove_file(&frag);
            Ok(joined)
        }
        Err(e) => {
            let _ = std::fs::remove_file(&frag);
            Err(e)
        }
    }
}

/// Writes `out` as fragmented MP4 with the video and audio tracks interleaved by time.
fn join(video: &Path, audio: &Path, out: &Path) -> io::Result<bool> {
    let (Some(mut v), Some(mut a)) = (parse(video)?, parse(audio)?) else { return Ok(false) };
    let (vm, am) = (whole(&v.moov), whole(&a.moov));
    let (Some(vmvhd), Some(amvhd)) = (child(&v.moov, vm, b"mvhd"), child(&a.moov, am, b"mvhd")) else { return Ok(false) };
    let (Some(t1), Some(t2)) = (trex(&v.moov, 1), trex(&a.moov, 2)) else { return Ok(false) };
    let (movie_scale, audio_movie_scale) = (timescale(&v.moov, &vmvhd), timescale(&a.moov, &amvhd));
    if movie_scale == 0 || audio_movie_scale == 0 || v.timescale == 0 || a.timescale == 0 {
        return Ok(false);
    }
    let mut mvhd = v.moov[vmvhd.start..vmvhd.end].to_vec();
    let n = mvhd.len();
    put32(&mut mvhd, n - 4, 3); // next_track_ID
    let mehd = child(&v.moov, child(&v.moov, vm, b"mvex"), b"mehd").map(|x| v.moov[x.start..x.end].to_vec()).unwrap_or_default();
    let mvex = make(b"mvex", &[&mehd, &t1, &t2]);
    let rest = |m: &[u8], kind: &[u8; 4]| kids(m, &whole(m).unwrap()).into_iter().find(|k| &k.kind == kind).unwrap();
    let extra: Vec<u8> = kids(&v.moov, &vm.unwrap())
        .into_iter()
        .filter(|k| !matches!(&k.kind, b"mvhd" | b"trak" | b"mvex"))
        .flat_map(|k| v.moov[k.start..k.end].to_vec())
        .collect();
    let vtrak = retrack(&v.moov, rest(&v.moov, b"trak"), 1, 1.0);
    let atrak = retrack(&a.moov, rest(&a.moov, b"trak"), 2, movie_scale as f64 / audio_movie_scale as f64);
    let moov = make(b"moov", &[&mvhd, &vtrak, &atrak, &mvex, &extra]);

    let mut all: Vec<(f64, u32, usize)> = (v.fragments.iter().enumerate().map(|(i, f)| (f.t as f64 / v.timescale as f64, 1, i)))
        .chain(a.fragments.iter().enumerate().map(|(i, f)| (f.t as f64 / a.timescale as f64, 2, i)))
        .collect();
    all.sort_by(|x, y| x.0.total_cmp(&y.0).then(x.1.cmp(&y.1)));

    let mut w = BufWriter::with_capacity(1 << 20, File::create(out)?);
    w.write_all(&v.ftyp)?;
    w.write_all(&moov)?;
    let mut offset = (v.ftyp.len() + moov.len()) as u64;
    for (seq, (_, id, i)) in all.into_iter().enumerate() {
        let s = if id == 1 { &mut v } else { &mut a };
        let f = &s.fragments[i];
        let mut moof = f.moof.clone();
        patch(&mut moof, id, seq as u32 + 1, f.at, offset);
        w.write_all(&moof)?;
        s.file.seek(SeekFrom::Start(f.mdat.0))?;
        let copied = io::copy(&mut (&mut s.file).take(f.mdat.1 - f.mdat.0), &mut w)?;
        offset += moof.len() as u64 + copied;
    }
    w.flush()?;
    Ok(true)
}

// ---- Plain MP4: a port of flatten() in extension/mp4.js, with the same integer math ----

struct Sample {
    dts: u64,
    dur: u64,
    size: u32,
    pos: u64,
    sync: bool,
    cto: i64,
    sdi: u32,
}

struct Chunk {
    k: usize,
    t: u64,
    sdi: u32,
    first: usize,
    count: usize,
    bytes: u64,
    at: u64,
}

struct Track {
    trak: Bx,
    stbl: Bx,
    stsd: Bx,
    id: u32,
    entry: [u8; 4],
    m: u64,
    scale: u32,
    sound: bool,
    def: [u32; 4], // sample description index, duration, size, flags
    samples: Vec<Sample>,
    next: u64,
    media: u64,
    need: i128,
    length: u64,
    chunks: Vec<usize>,
}

fn u24_at(b: &[u8], o: usize) -> u32 {
    (byte(b, o) as u32) << 16 | (byte(b, o + 1) as u32) << 8 | byte(b, o + 2) as u32
}
fn words(list: &[u32]) -> Vec<u8> {
    list.iter().flat_map(|v| v.to_be_bytes()).collect()
}
fn full(kind: &[u8; 4], version: u8, body: &[u8]) -> Vec<u8> {
    make(kind, &[&[version, 0, 0, 0], body])
}
fn rle<T: PartialEq + Copy>(list: impl Iterator<Item = T>) -> Vec<(u32, T)> {
    let mut out: Vec<(u32, T)> = Vec::new();
    for v in list {
        match out.last_mut() {
            Some(last) if last.1 == v => last.0 += 1,
            _ => out.push((1, v)),
        }
    }
    out
}
/// A copy of a full box with a length field set: at32 in version 0, at64 in version 1.
fn with_length(b: &[u8], x: &Bx, at32: usize, at64: usize, v: u64) -> Vec<u8> {
    let mut out = b[x.start..x.end].to_vec();
    let p = x.head;
    if byte(&out, p) == 1 {
        put64(&mut out, p + at64, v);
    } else {
        put32(&mut out, p + at32, v.min(u32::MAX as u64) as u32);
    }
    out
}

/// Writes `out` as a plain MP4 made from the fragmented MP4 at `src`: one index up front with each
/// track's real length. Ok(false) when `src` isn't fragmented MP4 or can't be made plain, and
/// nothing is written.
pub fn flatten(src: &Path, out: &Path) -> io::Result<bool> {
    let mut file = File::open(src)?;
    let len = file.metadata()?.len();
    let top = top(&mut file)?;
    let Some(t) = top.iter().find(|x| &x.kind == b"moov") else { return Ok(false) };
    let Some(b) = read(&mut file, t)? else { return Ok(false) };
    let moov = whole(&b);
    let mvex = child(&b, moov, b"mvex");
    if mvex.is_none() || child(&b, moov, b"pssh").is_some() {
        return Ok(false);
    }
    let Some(mvhd) = child(&b, moov, b"mvhd") else { return Ok(false) };
    let trexes: Vec<Bx> = kids(&b, &mvex.unwrap()).into_iter().filter(|k| &k.kind == b"trex").collect();
    let mut tracks = Vec::new();
    for trak in kids(&b, &moov.unwrap()).into_iter().filter(|k| &k.kind == b"trak") {
        let (tkhd, mdia) = (child(&b, Some(trak), b"tkhd"), child(&b, Some(trak), b"mdia"));
        let (mdhd, hdlr) = (child(&b, mdia, b"mdhd"), child(&b, mdia, b"hdlr"));
        let stbl = child(&b, child(&b, mdia, b"minf"), b"stbl");
        let stsd = child(&b, stbl, b"stsd");
        let (Some(tkhd), Some(mdhd), Some(stbl), Some(stsd)) = (tkhd, mdhd, stbl, stsd) else { return Ok(false) };
        let id = u32_at(&b, body(&tkhd) + if byte(&b, body(&tkhd)) == 1 { 20 } else { 12 });
        let trex = trexes.iter().find(|t| u32_at(&b, body(t) + 4) == id);
        let p = body(&stsd) + 12;
        let entry = [byte(&b, p), byte(&b, p + 1), byte(&b, p + 2), byte(&b, p + 3)];
        let Some(trex) = trex else { return Ok(false) };
        if &entry == b"encv" || &entry == b"enca" {
            return Ok(false);
        }
        // The media time of the first edit that isn't empty: an audio encoder's delay.
        let mut m = 0;
        if let Some(elst) = child(&b, child(&b, Some(trak), b"edts"), b"elst") {
            let (v1, n) = (byte(&b, body(&elst)) == 1, u32_at(&b, body(&elst) + 4));
            let (mut i, mut q) = (0, body(&elst) + 8);
            while i < n && q < elst.end {
                let t = if v1 {
                    if u32_at(&b, q + 8) >= 0x8000_0000 { -1 } else { u64_at(&b, q + 8) as i128 }
                } else {
                    u32_at(&b, q + 4) as i32 as i128
                };
                if t >= 0 {
                    m = t as u64;
                    break;
                }
                i += 1;
                q += if v1 { 20 } else { 12 };
            }
        }
        let sound = hdlr.is_some_and(|h| b.get(body(&h) + 8..body(&h) + 12) == Some(b"soun"));
        let def = [8, 12, 16, 20].map(|o| u32_at(&b, body(trex) + o));
        let scale = timescale(&b, &mdhd);
        tracks.push(Track { trak, stbl, stsd, id, entry, m, scale, sound, def, samples: Vec::new(), next: 0, media: 0, need: 0, length: 0, chunks: Vec::new() });
    }
    for x in top.iter().filter(|x| &x.kind == b"moof") {
        let Some(f) = read(&mut file, x)? else { return Ok(false) };
        let Some(moof) = whole(&f) else { continue };
        let mut prev_end: Option<i64> = None;
        for traf in kids(&f, &moof).into_iter().filter(|k| &k.kind == b"traf") {
            let Some(tfhd) = child(&f, Some(traf), b"tfhd") else { continue };
            let p = body(&tfhd);
            let fl = u24_at(&f, p + 1);
            let Some(tr) = tracks.iter_mut().find(|t| t.id == u32_at(&f, p + 4)) else { continue };
            let (mut o, mut base) = (p + 8, x.start as i64);
            let [mut sdi, mut dd, mut ds, mut df] = tr.def;
            if fl & 1 != 0 {
                base = u64_at(&f, o) as i64;
                o += 8;
            } else if let Some(e) = prev_end.filter(|_| fl & 0x20000 == 0) {
                base = e;
            }
            if fl & 2 != 0 {
                sdi = u32_at(&f, o);
                o += 4;
            }
            if fl & 8 != 0 {
                dd = u32_at(&f, o);
                o += 4;
            }
            if fl & 0x10 != 0 {
                ds = u32_at(&f, o);
                o += 4;
            }
            if fl & 0x20 != 0 {
                df = u32_at(&f, o);
            }
            let tfdt = child(&f, Some(traf), b"tfdt");
            let mut t = tfdt.map_or(tr.next, |x| if byte(&f, body(&x)) == 1 { u64_at(&f, body(&x) + 4) } else { u32_at(&f, body(&x) + 4) as u64 });
            if let Some(last) = tr.samples.last_mut() {
                if t > last.dts {
                    last.dur = t - last.dts;
                } else {
                    t = last.dts + last.dur;
                }
            }
            let mut pos = base;
            for trun in kids(&f, &traf).into_iter().filter(|k| &k.kind == b"trun") {
                let q = body(&trun);
                let (v, rf, n) = (byte(&f, q), u24_at(&f, q + 1), u32_at(&f, q + 4));
                let (mut r, mut first) = (q + 8, None);
                if rf & 1 != 0 {
                    pos = base + u32_at(&f, r) as i32 as i64;
                    r += 4;
                }
                if rf & 4 != 0 {
                    first = Some(u32_at(&f, r));
                    r += 4;
                }
                for i in 0..n {
                    let (mut dur, mut size, mut flags, mut cto) = (dd, ds, if i == 0 { first.unwrap_or(df) } else { df }, 0i64);
                    if rf & 0x100 != 0 {
                        dur = u32_at(&f, r);
                        r += 4;
                    }
                    if rf & 0x200 != 0 {
                        size = u32_at(&f, r);
                        r += 4;
                    }
                    if rf & 0x400 != 0 {
                        flags = u32_at(&f, r);
                        r += 4;
                    }
                    if rf & 0x800 != 0 {
                        cto = if v != 0 { u32_at(&f, r) as i32 as i64 } else { u32_at(&f, r) as i64 };
                        r += 4;
                    }
                    if r > trun.end || pos < 0 || pos as u64 + size as u64 > len {
                        break; // a cut-off file ends here
                    }
                    tr.samples.push(Sample { dts: t, dur: dur as u64, size, pos: pos as u64, sync: flags & 0x10000 == 0, cto, sdi });
                    t += dur as u64;
                    pos += size as i64;
                }
            }
            tr.next = t;
            prev_end = Some(pos);
        }
    }
    let mut live: Vec<Track> = tracks.into_iter().filter(|t| !t.samples.is_empty()).collect();
    if live.is_empty() {
        return Ok(false);
    }

    // Times go into the composition offsets alone, none negative, with no edit list: see mp4.js.
    let movie_scale = timescale(&b, &mvhd) as u128;
    if movie_scale == 0 || live.iter().any(|t| t.scale == 0) {
        return Ok(false);
    }
    for tr in &mut live {
        tr.media = tr.samples.iter().map(|s| s.dur).sum();
        tr.m = tr.m.min(tr.samples[0].dts + tr.media);
        let least = tr.samples.iter().map(|s| s.cto).min().unwrap_or(0);
        tr.need = tr.m as i128 - tr.samples[0].dts as i128 - least as i128;
    }
    let mut most = 0;
    for (i, t) in live.iter().enumerate().skip(1) {
        if t.need * live[most].scale as i128 > live[most].need * t.scale as i128 {
            most = i;
        }
    }
    let (most_need, most_scale) = (live[most].need, live[most].scale as i128);
    for (i, tr) in live.iter_mut().enumerate() {
        let late = if most_need <= 0 { 0 } else if i == most { most_need } else { (most_need * tr.scale as i128 + most_scale - 1) / most_scale };
        let shift = (tr.samples[0].dts as i128 - tr.m as i128 + late) as i64;
        let (mut t, mut end) = (0i128, 0i128);
        for s in &mut tr.samples {
            s.cto += shift;
            end = end.max(t + s.cto as i128 + s.dur as i128);
            t += s.dur as i128;
        }
        let scale = tr.scale as u128;
        tr.length = ((end as u128 * movie_scale + scale / 2) / scale) as u64; // in the movie's timescale
    }
    // Half-second chunks, interleaved by decode time; the earlier track wins a tie.
    let mut chunks: Vec<Chunk> = Vec::new();
    for (k, tr) in live.iter_mut().enumerate() {
        for (i, s) in tr.samples.iter().enumerate() {
            let open = tr.chunks.last().map(|&c| &chunks[c]).filter(|c| 2 * (s.dts - c.t) < tr.scale as u64 && s.sdi == c.sdi).is_some();
            if !open {
                tr.chunks.push(chunks.len());
                chunks.push(Chunk { k, t: s.dts, sdi: s.sdi, first: i, count: 0, bytes: 0, at: 0 });
            }
            let c = chunks.last_mut().unwrap();
            c.count += 1;
            c.bytes += s.size as u64;
        }
    }
    let mut order: Vec<usize> = (0..chunks.len()).collect();
    order.sort_by(|&x, &y| {
        let (x, y) = (&chunks[x], &chunks[y]);
        (x.t as u128 * live[y.k].scale as u128).cmp(&(y.t as u128 * live[x.k].scale as u128)).then(x.k.cmp(&y.k))
    });
    let payload: u64 = chunks.iter().map(|c| c.bytes).sum();

    let stbl = |tr: &Track, chunks: &[Chunk], big: bool| -> Vec<u8> {
        let s = &tr.samples;
        let mut parts: Vec<Vec<u8>> = vec![b[tr.stsd.start..tr.stsd.end].to_vec()];
        let durs = rle(s.iter().map(|x| x.dur));
        let mut stts = vec![durs.len() as u32];
        durs.iter().for_each(|&(n, v)| stts.extend([n, v as u32]));
        parts.push(full(b"stts", 0, &words(&stts)));
        if s.iter().any(|x| x.cto != 0) {
            let ctos = rle(s.iter().map(|x| x.cto));
            let mut ctts = vec![ctos.len() as u32];
            ctos.iter().for_each(|&(n, v)| ctts.extend([n, v as u32]));
            parts.push(full(b"ctts", if s.iter().any(|x| x.cto < 0) { 1 } else { 0 }, &words(&ctts)));
        }
        if !tr.sound && s.iter().any(|x| !x.sync) {
            let keys: Vec<u32> = s.iter().enumerate().filter(|(_, x)| x.sync).map(|(i, _)| i as u32 + 1).collect();
            parts.push(full(b"stss", 0, &words(&[&[keys.len() as u32][..], &keys].concat())));
        }
        let size = s[0].size;
        let stsz = if s.iter().all(|x| x.size == size) { vec![size, s.len() as u32] } else { [&[0, s.len() as u32][..], &s.iter().map(|x| x.size).collect::<Vec<_>>()].concat() };
        parts.push(full(b"stsz", 0, &words(&stsz)));
        let mut stsc: Vec<[u32; 3]> = Vec::new();
        for (i, &c) in tr.chunks.iter().enumerate() {
            let c = &chunks[c];
            if stsc.last().is_none_or(|p| p[1] != c.count as u32 || p[2] != c.sdi) {
                stsc.push([i as u32 + 1, c.count as u32, c.sdi]);
            }
        }
        parts.push(full(b"stsc", 0, &words(&[&[stsc.len() as u32][..], &stsc.concat()].concat())));
        if big {
            let mut o = (tr.chunks.len() as u32).to_be_bytes().to_vec();
            tr.chunks.iter().for_each(|&c| o.extend(chunks[c].at.to_be_bytes()));
            parts.push(full(b"co64", 0, &o));
        } else {
            let at: Vec<u32> = tr.chunks.iter().map(|&c| chunks[c].at as u32).collect();
            parts.push(full(b"stco", 0, &words(&[&[at.len() as u32][..], &at].concat())));
        }
        for k in kids(&b, &tr.stbl) {
            if !matches!(&k.kind, b"stsd" | b"stts" | b"ctts" | b"stss" | b"stsz" | b"stz2" | b"stsc" | b"stco" | b"co64" | b"sbgp") {
                parts.push(b[k.start..k.end].to_vec());
            }
        }
        make(b"stbl", &parts.iter().map(|p| p.as_slice()).collect::<Vec<_>>())
    };
    let copy = |x: &Bx| b[x.start..x.end].to_vec();
    let wrap = |kind: &[u8; 4], parts: Vec<Vec<u8>>| make(kind, &parts.iter().map(|p| p.as_slice()).collect::<Vec<_>>());
    let trak = |tr: &Track, chunks: &[Chunk], big: bool| -> Vec<u8> {
        let mut parts = Vec::new();
        for k in kids(&b, &tr.trak) {
            match &k.kind {
                b"tkhd" => parts.push(with_length(&b, &k, 20, 28, tr.length)),
                b"edts" => {}
                b"mdia" => parts.push(wrap(b"mdia", kids(&b, &k).iter().map(|x| match &x.kind {
                    b"mdhd" => with_length(&b, x, 16, 24, tr.media),
                    b"minf" => wrap(b"minf", kids(&b, x).iter().map(|y| if &y.kind == b"stbl" { stbl(tr, chunks, big) } else { copy(y) }).collect()),
                    _ => copy(x),
                }).collect())),
                _ => parts.push(copy(&k)),
            }
        }
        wrap(b"trak", parts)
    };
    let movie = |chunks: &[Chunk], big: bool| -> Vec<u8> {
        let mut parts = Vec::new();
        for k in kids(&b, &moov.unwrap()) {
            match &k.kind {
                b"mvhd" => parts.push(with_length(&b, &k, 16, 24, live.iter().map(|t| t.length).max().unwrap_or(0))),
                b"trak" => {
                    if let Some(tr) = live.iter().find(|t| t.trak.start == k.start) {
                        parts.push(trak(tr, chunks, big));
                    }
                }
                b"mvex" | b"pssh" => {}
                _ => parts.push(copy(&k)),
            }
        }
        wrap(b"moov", parts)
    };
    let mut brands: Vec<&[u8; 4]> = vec![b"isom", b"iso2"];
    if live.iter().any(|t| &t.entry == b"avc1") {
        brands.push(b"avc1");
    }
    brands.push(b"mp41");
    let ftyp = wrap(b"ftyp", [b"isom".to_vec(), 512u32.to_be_bytes().to_vec()].into_iter().chain(brands.iter().map(|x| x.to_vec())).collect());
    // Past 4 GB the mdat needs a 64-bit size and co64 offsets.
    let big = (ftyp.len() + movie(&chunks, false).len() + 8) as u64 + payload > u32::MAX as u64;
    let head = if big { 16 } else { 8 };
    let mut at = (ftyp.len() + movie(&chunks, big).len() + head) as u64; // the moov's size doesn't depend on the offsets
    for &c in &order {
        chunks[c].at = at;
        at += chunks[c].bytes;
    }
    let moov_out = movie(&chunks, big);

    let mut w = BufWriter::with_capacity(1 << 20, File::create(out)?);
    w.write_all(&ftyp)?;
    w.write_all(&moov_out)?;
    if big {
        w.write_all(&1u32.to_be_bytes())?;
        w.write_all(b"mdat")?;
        w.write_all(&(payload + 16).to_be_bytes())?;
    } else {
        w.write_all(&(payload as u32 + 8).to_be_bytes())?;
        w.write_all(b"mdat")?;
    }
    // Sample bytes in chunk order, each run of neighbouring samples copied in one go.
    let mut run: Option<(u64, u64)> = None;
    let mut flush = |run: (u64, u64), w: &mut BufWriter<File>| -> io::Result<()> {
        file.seek(SeekFrom::Start(run.0))?;
        let copied = io::copy(&mut (&mut file).take(run.1), w)?;
        if copied == run.1 { Ok(()) } else { Err(io::Error::new(io::ErrorKind::UnexpectedEof, "media data ended early")) }
    };
    for &c in &order {
        let c = &chunks[c];
        for s in &live[c.k].samples[c.first..c.first + c.count] {
            run = match run {
                Some((p, n)) if p + n == s.pos => Some((p, n + s.size as u64)),
                Some(r) => {
                    flush(r, &mut w)?;
                    Some((s.pos, s.size as u64))
                }
                None => Some((s.pos, s.size as u64)),
            };
        }
    }
    if let Some(r) = run {
        flush(r, &mut w)?;
    }
    w.flush()?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use std::path::Path;
    use std::process::Command;

    // Needs the fixture media from `npm test` (fixtures/make-media.mjs). The JS merge it compares
    // against is checked with ffprobe by fixtures/run.mjs.
    #[test]
    fn same_bytes_as_the_extension() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
        let av = root.join("fixtures/media/av");
        if !av.join("video.mp4").exists() {
            eprintln!("skipped: run `npm test` once to generate fixtures/media");
            return;
        }
        let out = std::env::temp_dir().join(format!("lodisave-merge-{}.mp4", std::process::id()));
        assert!(super::merge(&av.join("video.mp4"), &av.join("audio.mp4"), &out).unwrap());
        let ours = std::fs::read(&out).unwrap();
        std::fs::remove_file(&out).unwrap();
        let script = "const M=require(process.argv[1]),fs=require('fs');process.stdout.write(Buffer.from(M.merge(fs.readFileSync(process.argv[2]),fs.readFileSync(process.argv[3]))))";
        let js = Command::new("node").args(["-e", script]).arg(root.join("extension/mp4.js")).arg(av.join("video.mp4")).arg(av.join("audio.mp4")).output().unwrap();
        assert!(js.status.success());
        assert!(ours == js.stdout, "Rust and JS merges differ: {} vs {} bytes", ours.len(), js.stdout.len());
        assert!(!super::merge(&root.join("fixtures/media/clip.webm"), &av.join("audio.mp4"), &out).unwrap());
    }
}
