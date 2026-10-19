//! Joins a video-only and an audio-only fragmented MP4 (what DASH serves) into one file with both
//! tracks, so HD downloads need no FFmpeg. A port of extension/mp4.js that copies the media data
//! straight from disk, so memory use stays small for long videos. Malformed input never panics:
//! out-of-range reads give 0 and the result is just a broken file.

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

/// Writes `out` with the video and audio tracks interleaved by time. Ok(false) when either input
/// isn't a single-track fragmented MP4 (WebM, progressive MP4), and nothing is written.
pub fn merge(video: &Path, audio: &Path, out: &Path) -> io::Result<bool> {
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
