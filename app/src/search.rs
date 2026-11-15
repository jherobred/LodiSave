//! Finding photos and videos by what's in them: "cat" or "mountain" finds cats and mountains, whatever
//! the files are called. OpenAI's CLIP (MIT license) turns each picture, from Windows' own thumbnail of
//! it, into a list of numbers, and the words typed into another; the closer the two, the better the
//! match. It all runs on this PC: nothing about the pictures or the words leaves it. The model comes
//! from Hugging Face the first time the user turns this on, about 150 MB, each file checked against
//! the size and SHA-256 written here. The numbers for each picture are kept in search/index.bin,
//! keyed by path, and worked out again when the file's size or time changes.

use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

/// The model, pinned to one commit of Xenova's ONNX export of openai/clip-vit-base-patch32.
const BASE: &str = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/";
const FILES: [(&str, &str, u64, &str); 3] = [
    ("tokenizer.json", "tokenizer.json", 2_224_119, "f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd"),
    ("text.onnx", "onnx/text_model_quantized.onnx", 64_504_507, "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a"),
    ("vision.onnx", "onnx/vision_model_quantized.onnx", 89_117_001, "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"),
];
const DIM: usize = 512;
const SIDE: usize = 224;
/// How far a picture must be closer to the words than to "a photo" to count as a match. Tried on
/// photos of cats, dogs, mountains, beaches, food and cities: matches scored 0.023 to 0.06, others
/// at most 0.016.
pub const ENOUGH: f32 = 0.02;
/// What any photo is near, taken away from the words so only what they add counts.
const PLAIN: [&str; 5] = ["a photo", "a picture", "an image", "a photo of something", "a screenshot"];
/// Common Tagalog words, in English for the model, which knows English best.
const TAGALOG: [(&str, &str); 40] = [
    ("pusa", "cat"), ("aso", "dog"), ("bundok", "mountain"), ("dagat", "sea"), ("pagkain", "food"), ("bulaklak", "flower"), ("kotse", "car"),
    ("sasakyan", "car"), ("bahay", "house"), ("langit", "sky"), ("ulap", "clouds"), ("araw", "sun"), ("buwan", "moon"), ("puno", "tree"),
    ("ibon", "bird"), ("isda", "fish"), ("tao", "person"), ("bata", "child"), ("sanggol", "baby"), ("lalaki", "man"), ("babae", "woman"),
    ("dalampasigan", "beach"), ("baybayin", "beach"), ("ilog", "river"), ("lungsod", "city"), ("kalye", "street"), ("gabi", "night"),
    ("ulan", "rain"), ("damit", "clothes"), ("sapatos", "shoes"), ("kain", "food"), ("prutas", "fruit"), ("gulay", "vegetables"),
    ("kanin", "rice"), ("simbahan", "church"), ("paaralan", "school"), ("halaman", "plant"), ("kabayo", "horse"), ("manok", "chicken"), ("kalabaw", "water buffalo"),
];

/// A picture to work out: a file, or a file only on Google Drive by its id.
#[derive(Clone)]
pub enum Source {
    File(PathBuf),
    Cloud(String),
}

struct Want {
    key: String,
    stamp: (u64, u64),
    from: Source,
}

#[derive(Default)]
struct State {
    /// Path (or a Drive file's made-up path) to its file size, time and numbers.
    index: HashMap<String, ((u64, u64), Vec<i16>)>,
    want: Vec<Want>,
    failed: HashSet<String>,
    getting: bool,
    error: Option<String>,
    unsaved: usize,
}

pub struct Search {
    dir: PathBuf,
    state: Mutex<State>,
    wake: Condvar,
    text: Mutex<Option<(Text, Instant)>>,
    running: AtomicBool,
}

#[derive(serde::Serialize)]
pub struct Status {
    /// The model is here.
    ready: bool,
    /// Downloading it: how much is here, 0 to 1.
    getting: Option<f32>,
    error: Option<String>,
    done: usize,
    total: usize,
}

impl Search {
    pub fn open(data: &Path) -> Search {
        let dir = data.join("search");
        let index = read_index(&dir.join("index.bin")).unwrap_or_default();
        Search { dir, state: Mutex::new(State { index, ..Default::default() }), wake: Condvar::new(), text: Mutex::new(None), running: AtomicBool::new(false) }
    }

    fn model(&self, name: &str) -> PathBuf {
        self.dir.join(name)
    }

    /// Every model file is here at its full size.
    pub fn ready(&self) -> bool {
        FILES.iter().all(|(name, _, size, _)| std::fs::metadata(self.model(name)).is_ok_and(|m| m.len() == *size))
    }

    pub fn status(&self) -> Status {
        let s = self.state.lock().unwrap();
        let getting = s.getting.then(|| {
            let total: u64 = FILES.iter().map(|f| f.2).sum();
            let here: u64 = FILES
                .iter()
                .map(|(name, _, size, _)| {
                    let done = std::fs::metadata(self.model(name)).map_or(0, |m| m.len());
                    if done == *size { done } else { std::fs::metadata(self.model(&format!("{name}.part"))).map_or(0, |m| m.len()) }
                })
                .sum();
            here as f32 / total as f32
        });
        let done = s.want.iter().filter(|w| s.index.get(&w.key).is_some_and(|(st, _)| *st == w.stamp) || s.failed.contains(&w.key)).count();
        Status { ready: self.ready(), getting, error: s.error.clone(), done, total: s.want.len() }
    }

    /// The pictures the gallery shows now, to work out the ones not done yet. Pictures no longer
    /// in it leave the index.
    pub fn want(&self, items: Vec<(String, (u64, u64), Source)>) {
        let mut s = self.state.lock().unwrap();
        let keep: HashSet<&String> = items.iter().map(|(k, ..)| k).collect();
        let before = s.index.len();
        s.index.retain(|k, _| keep.contains(k));
        s.unsaved += before - s.index.len();
        s.want = items.into_iter().map(|(key, stamp, from)| Want { key, stamp, from }).collect();
        self.wake.notify_all();
    }

    /// Gets the model, then works through the pictures. Runs once; later calls only wake it.
    pub fn start(&'static self, drive: impl Fn(&str) -> Option<Vec<u8>> + Send + 'static) {
        if self.running.swap(true, Ordering::SeqCst) {
            return self.wake.notify_all();
        }
        std::thread::spawn(move || {
            let _ = unsafe { windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_APARTMENTTHREADED) };
            // Out of the way of everything else on the PC.
            let _ = unsafe { windows::Win32::System::Threading::SetThreadPriority(windows::Win32::System::Threading::GetCurrentThread(), windows::Win32::System::Threading::THREAD_PRIORITY_LOWEST) };
            if !self.ready() {
                self.state.lock().unwrap().getting = true;
                let got = self.download();
                let mut s = self.state.lock().unwrap();
                s.getting = false;
                s.error = got.err();
                if s.error.is_some() {
                    drop(s);
                    self.running.store(false, Ordering::SeqCst);
                    return;
                }
            }
            self.work(drive);
        });
    }

    fn download(&self) -> Result<(), String> {
        std::fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        for (name, path, size, sha) in FILES {
            let to = self.model(name);
            if std::fs::metadata(&to).is_ok_and(|m| m.len() == size) {
                continue;
            }
            let part = self.model(&format!("{name}.part"));
            let ok = crate::links::command("curl.exe")
                .args(["-sSfL", "--retry", "3", "--connect-timeout", "30", "--speed-time", "60", "--speed-limit", "1", "-C", "-", "-o"])
                .arg(&part)
                .arg(format!("{BASE}{path}"))
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .is_ok_and(|s| s.success());
            if !ok {
                return Err("offline".into());
            }
            let bytes = std::fs::read(&part).map_err(|e| e.to_string())?;
            if bytes.len() as u64 != size || hex(&sha256(&bytes)) != sha {
                let _ = std::fs::remove_file(&part);
                return Err("check".into());
            }
            std::fs::rename(&part, &to).map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    /// Works out the pictures not done yet, a few at a time, while no game is on, then waits for more.
    fn work(&self, drive: impl Fn(&str) -> Option<Vec<u8>>) {
        let mut vision: Option<ort::session::Session> = None;
        let scratch = self.dir.join("drive.jpg");
        loop {
            let todo: Vec<(String, (u64, u64), Source)> = {
                let s = self.state.lock().unwrap();
                let todo: Vec<_> = s
                    .want
                    .iter()
                    .filter(|w| !s.failed.contains(&w.key) && s.index.get(&w.key).is_none_or(|(st, _)| *st != w.stamp))
                    .take(4)
                    .map(|w| (w.key.clone(), w.stamp, w.from.clone()))
                    .collect();
                if todo.is_empty() || crate::game::on() {
                    // Done for now: the model's memory goes back, and the index is kept.
                    vision = None;
                    let s = if s.unsaved > 0 { self.save(s) } else { s };
                    self.idle();
                    drop(self.wake.wait_timeout(s, Duration::from_secs(30)).unwrap());
                    continue;
                }
                todo
            };
            if vision.is_none() {
                vision = session(&self.model("vision.onnx"), 2).ok();
                if vision.is_none() {
                    self.state.lock().unwrap().error = Some("model".into());
                    return self.running.store(false, Ordering::SeqCst);
                }
            }
            let mut pixels = Vec::with_capacity(todo.len() * 3 * SIDE * SIDE);
            let mut got = Vec::new();
            for (key, stamp, from) in &todo {
                let picture = match from {
                    Source::File(p) => crate::thumb::rgba(p, 448),
                    Source::Cloud(id) => drive(id).and_then(|jpg| std::fs::write(&scratch, jpg).ok()).and_then(|_| crate::thumb::rgba(&scratch, 448)),
                };
                match picture {
                    Some((w, h, px)) if w > 0 && h > 0 => {
                        pixels.extend(prepare(&px, w as usize, h as usize));
                        got.push((key.clone(), *stamp));
                    }
                    _ => {
                        self.state.lock().unwrap().failed.insert(key.clone());
                    }
                }
            }
            if got.is_empty() {
                continue;
            }
            match embed_images(vision.as_mut().unwrap(), got.len(), pixels) {
                Ok(vs) => {
                    let mut s = self.state.lock().unwrap();
                    for ((key, stamp), v) in got.into_iter().zip(vs) {
                        s.index.insert(key, (stamp, pack(&v)));
                        s.unsaved += 1;
                    }
                    if s.unsaved >= 64 {
                        drop(self.save(s));
                    }
                }
                Err(_) => {
                    let mut s = self.state.lock().unwrap();
                    got.into_iter().for_each(|(k, _)| { s.failed.insert(k); });
                }
            }
        }
    }

    fn save<'a>(&self, mut s: std::sync::MutexGuard<'a, State>) -> std::sync::MutexGuard<'a, State> {
        s.unsaved = 0;
        let mut out = Vec::with_capacity(8 + s.index.len() * (DIM * 2 + 64));
        out.extend(b"LSI1");
        out.extend((s.index.len() as u32).to_le_bytes());
        for (key, ((size, time), v)) in &s.index {
            out.extend((key.len() as u32).to_le_bytes());
            out.extend(key.as_bytes());
            out.extend(size.to_le_bytes());
            out.extend(time.to_le_bytes());
            v.iter().for_each(|x| out.extend(x.to_le_bytes()));
        }
        let part = self.dir.join("index.part");
        let _ = std::fs::create_dir_all(&self.dir);
        if std::fs::File::create(&part).and_then(|mut f| f.write_all(&out)).is_ok() {
            let _ = std::fs::rename(&part, self.dir.join("index.bin"));
        }
        s
    }

    /// The words' model goes back after ten minutes without a search.
    fn idle(&self) {
        let mut t = self.text.lock().unwrap();
        if t.as_ref().is_some_and(|(_, used)| used.elapsed() > Duration::from_secs(600)) {
            *t = None;
        }
    }

    /// The pictures that match `query`, best first, with how well. Empty while the model isn't here.
    pub fn find(&self, query: &str) -> Result<Vec<(String, f32)>, String> {
        if !self.ready() {
            return Ok(Vec::new());
        }
        let mut t = self.text.lock().unwrap();
        if t.is_none() {
            let json = std::fs::read_to_string(self.model("tokenizer.json")).map_err(|e| e.to_string())?;
            let tok = Tokenizer::load(&json).ok_or("tokenizer")?;
            let mut session = session(&self.model("text.onnx"), 1).map_err(|e| e.to_string())?;
            let mut plain = vec![0f32; DIM];
            for p in PLAIN {
                let v = embed_text(&mut session, &tok.encode(p)).map_err(|e| e.to_string())?;
                plain.iter_mut().zip(v).for_each(|(a, b)| *a += b / PLAIN.len() as f32);
            }
            *t = Some((Text { session, tok, plain }, Instant::now()));
        }
        let (text, used) = t.as_mut().unwrap();
        *used = Instant::now();
        let words = english(query);
        if words.is_empty() {
            return Ok(Vec::new());
        }
        let q = embed_text(&mut text.session, &text.tok.encode(&format!("a photo of {words}"))).map_err(|e| e.to_string())?;
        let q: Vec<f32> = q.iter().zip(&text.plain).map(|(a, b)| a - b).collect();
        drop(t);
        let s = self.state.lock().unwrap();
        let mut hits: Vec<(String, f32)> = s
            .index
            .iter()
            .map(|(k, (_, v))| (k.clone(), v.iter().zip(&q).map(|(a, b)| *a as f32 / 32767.0 * b).sum::<f32>()))
            .filter(|(_, score)| *score >= ENOUGH)
            .collect();
        hits.sort_by(|a, b| b.1.total_cmp(&a.1));
        Ok(hits)
    }
}

struct Text {
    session: ort::session::Session,
    tok: Tokenizer,
    plain: Vec<f32>,
}

fn session(model: &Path, threads: usize) -> ort::Result<ort::session::Session> {
    // pyke's ONNX Runtime has Microsoft's usage reports off already; this keeps them off whatever
    // the build. Only the first call counts.
    let _ = ort::init().with_name("lodisave").with_telemetry(false).commit();
    ort::session::Session::builder()?
        .with_optimization_level(ort::session::builder::GraphOptimizationLevel::Level3)?
        .with_intra_threads(threads)?
        .commit_from_file(model)
}

fn embed_images(vision: &mut ort::session::Session, n: usize, pixels: Vec<f32>) -> ort::Result<Vec<Vec<f32>>> {
    let input = ort::value::Tensor::from_array(([n, 3, SIDE, SIDE], pixels))?;
    let out = vision.run(ort::inputs!["pixel_values" => input])?;
    let (_, data) = out["image_embeds"].try_extract_tensor::<f32>()?;
    Ok(data.chunks_exact(DIM).map(unit).collect())
}

fn embed_text(text: &mut ort::session::Session, ids: &[i64]) -> ort::Result<Vec<f32>> {
    let input = ort::value::Tensor::from_array(([1, ids.len()], ids.to_vec()))?;
    let out = text.run(ort::inputs!["input_ids" => input])?;
    let (_, data) = out["text_embeds"].try_extract_tensor::<f32>()?;
    Ok(unit(&data[..DIM]))
}

fn unit(v: &[f32]) -> Vec<f32> {
    let n = v.iter().map(|x| x * x).sum::<f32>().sqrt().max(1e-12);
    v.iter().map(|x| x / n).collect()
}

/// A unit vector in half the space, each number to within 1/32767.
fn pack(v: &[f32]) -> Vec<i16> {
    v.iter().map(|x| (x.clamp(-1.0, 1.0) * 32767.0).round() as i16).collect()
}

/// The words in English where they're Tagalog the model may not know.
fn english(query: &str) -> String {
    query
        .split_whitespace()
        .map(|w| {
            let l = w.to_lowercase();
            TAGALOG.iter().find(|(tl, _)| *tl == l).map_or(w.to_string(), |(_, en)| en.to_string())
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn read_index(path: &Path) -> Option<HashMap<String, ((u64, u64), Vec<i16>)>> {
    let mut b = Vec::new();
    std::fs::File::open(path).ok()?.read_to_end(&mut b).ok()?;
    let mut at = 0usize;
    let mut take = |n: usize| -> Option<&[u8]> {
        let s = b.get(at..at + n)?;
        at += n;
        Some(s)
    };
    (take(4)? == b"LSI1").then_some(())?;
    let count = u32::from_le_bytes(take(4)?.try_into().ok()?) as usize;
    let mut index = HashMap::with_capacity(count);
    for _ in 0..count {
        let len = u32::from_le_bytes(take(4)?.try_into().ok()?) as usize;
        let key = String::from_utf8(take(len)?.to_vec()).ok()?;
        let size = u64::from_le_bytes(take(8)?.try_into().ok()?);
        let time = u64::from_le_bytes(take(8)?.try_into().ok()?);
        let v = take(DIM * 2)?.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect();
        index.insert(key, ((size, time), v));
    }
    Some(index)
}

fn sha256(bytes: &[u8]) -> [u8; 32] {
    use windows::Win32::Security::Cryptography::{BCRYPT_SHA256_ALG_HANDLE, BCryptHash};
    let mut out = [0u8; 32];
    let _ = unsafe { BCryptHash(BCRYPT_SHA256_ALG_HANDLE, None, bytes, &mut out) };
    out
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// RGBA pixels as the model takes them, as CLIP's own preparation does: the short side to 224 by
/// bicubic, the middle 224 by 224, each colour scaled and centred, in planes of red, green and blue.
fn prepare(px: &[u8], w: usize, h: usize) -> Vec<f32> {
    let (nw, nh) = if w <= h { (SIDE, (h * SIDE / w).max(SIDE)) } else { ((w * SIDE / h).max(SIDE), SIDE) };
    let rows = resample(px, w, h, nw, true);
    let full = resample(&rows, nw, h, nh, false);
    let (left, top) = ((nw - SIDE) / 2, (nh - SIDE) / 2);
    const MEAN: [f32; 3] = [0.481_454_66, 0.457_827_5, 0.408_210_73];
    const STD: [f32; 3] = [0.268_629_54, 0.261_302_6, 0.275_777_1];
    let mut out = vec![0f32; 3 * SIDE * SIDE];
    for y in 0..SIDE {
        for x in 0..SIDE {
            let p = ((top + y) * nw + left + x) * 4;
            for c in 0..3 {
                out[c * SIDE * SIDE + y * SIDE + x] = (full[p + c] as f32 / 255.0 - MEAN[c]) / STD[c];
            }
        }
    }
    out
}

/// Resizes one way, across (`across`, to `to` wide) or down (to `to` high), with a bicubic filter
/// widened when shrinking, as Pillow does.
fn resample(px: &[u8], w: usize, h: usize, to: usize, across: bool) -> Vec<u8> {
    let from = if across { w } else { h };
    let scale = from as f32 / to as f32;
    let wide = scale.max(1.0);
    let support = 2.0 * wide;
    let cubic = |x: f32| {
        let (a, x) = (-0.5f32, x.abs());
        if x < 1.0 {
            ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
        } else if x < 2.0 {
            (((x - 5.0) * x + 8.0) * x - 4.0) * a
        } else {
            0.0
        }
    };
    let taps: Vec<(usize, Vec<f32>)> = (0..to)
        .map(|i| {
            let center = (i as f32 + 0.5) * scale;
            let lo = ((center - support + 0.5).floor().max(0.0)) as usize;
            let hi = ((center + support + 0.5).floor() as usize).min(from);
            let mut k: Vec<f32> = (lo..hi).map(|j| cubic((j as f32 - center + 0.5) / wide)).collect();
            let sum: f32 = k.iter().sum();
            if sum != 0.0 {
                k.iter_mut().for_each(|x| *x /= sum);
            }
            (lo, k)
        })
        .collect();
    let (ow, oh) = if across { (to, h) } else { (w, to) };
    let mut out = vec![0u8; ow * oh * 4];
    for y in 0..oh {
        for x in 0..ow {
            let (lo, k) = &taps[if across { x } else { y }];
            let mut acc = [0f32; 4];
            for (n, wt) in k.iter().enumerate() {
                let p = if across { (y * w + lo + n) * 4 } else { ((lo + n) * w + x) * 4 };
                for c in 0..4 {
                    acc[c] += px[p + c] as f32 * wt;
                }
            }
            let o = (y * ow + x) * 4;
            for c in 0..4 {
                out[o + c] = acc[c].round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

/// CLIP's tokenizer: lower case, split into words, numbers and marks, then each into the pieces its
/// vocabulary has by byte-pair merges, between start and end marks.
struct Tokenizer {
    vocab: HashMap<String, i64>,
    ranks: HashMap<(String, String), usize>,
    bytes: [char; 256],
}

impl Tokenizer {
    fn load(json: &str) -> Option<Tokenizer> {
        let v: serde_json::Value = serde_json::from_str(json).ok()?;
        let vocab = v["model"]["vocab"].as_object()?.iter().filter_map(|(k, id)| Some((k.clone(), id.as_i64()?))).collect();
        let ranks = v["model"]["merges"]
            .as_array()?
            .iter()
            .enumerate()
            .filter_map(|(i, m)| {
                let (a, b) = match m {
                    serde_json::Value::String(s) => s.split_once(' ').map(|(a, b)| (a.to_string(), b.to_string()))?,
                    serde_json::Value::Array(p) => (p.first()?.as_str()?.to_string(), p.get(1)?.as_str()?.to_string()),
                    _ => return None,
                };
                Some(((a, b), i))
            })
            .collect();
        // GPT-2's bytes as printable characters.
        let mut bytes = ['\0'; 256];
        let mut extra = 0u32;
        for b in 0..256u32 {
            let shown = (33..=126).contains(&b) || (161..=172).contains(&b) || (174..=255).contains(&b);
            bytes[b as usize] = char::from_u32(if shown { b } else { 256 + { extra += 1; extra - 1 } })?;
        }
        Some(Tokenizer { vocab, ranks, bytes })
    }

    fn encode(&self, text: &str) -> Vec<i64> {
        let text = text.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
        let mut ids = vec![49406];
        for word in words(&text) {
            let mapped: String = word.bytes().map(|b| self.bytes[b as usize]).collect();
            ids.extend(self.bpe(&mapped).iter().filter_map(|p| self.vocab.get(p)));
        }
        ids.truncate(76);
        ids.push(49407);
        ids
    }

    fn bpe(&self, word: &str) -> Vec<String> {
        let mut parts: Vec<String> = word.chars().map(String::from).collect();
        if let Some(last) = parts.last_mut() {
            last.push_str("</w>");
        }
        loop {
            let best = parts
                .windows(2)
                .filter_map(|p| self.ranks.get(&(p[0].clone(), p[1].clone())).map(|r| (*r, p[0].clone(), p[1].clone())))
                .min_by_key(|(r, ..)| *r);
            let Some((_, a, b)) = best else { break };
            let mut merged = Vec::with_capacity(parts.len());
            let mut i = 0;
            while i < parts.len() {
                if i + 1 < parts.len() && parts[i] == a && parts[i + 1] == b {
                    merged.push(format!("{a}{b}"));
                    i += 2;
                } else {
                    merged.push(parts[i].clone());
                    i += 1;
                }
            }
            parts = merged;
        }
        parts
    }
}

/// CLIP's split: 's 't 're 've 'm 'll 'd, runs of letters, single digits, and runs of anything else
/// but spaces.
fn words(text: &str) -> Vec<String> {
    let cs: Vec<char> = text.chars().collect();
    let (mut out, mut i) = (Vec::new(), 0);
    let other = |c: char| !c.is_whitespace() && !c.is_alphabetic() && !c.is_numeric();
    while i < cs.len() {
        let c = cs[i];
        if c == '\'' {
            let rest: String = cs[i + 1..cs.len().min(i + 3)].iter().collect();
            if let Some(t) = ["s", "t", "re", "ve", "m", "ll", "d"].iter().find(|t| rest.starts_with(*t)) {
                out.push(format!("'{t}"));
                i += 1 + t.len();
                continue;
            }
        }
        let start = i;
        if c.is_alphabetic() {
            while i < cs.len() && cs[i].is_alphabetic() {
                i += 1;
            }
        } else if c.is_numeric() {
            i += 1;
        } else if c.is_whitespace() {
            i += 1;
            continue;
        } else {
            while i < cs.len() && other(cs[i]) {
                i += 1;
            }
        }
        out.push(cs[start..i].iter().collect());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_like_clip() {
        assert_eq!(words("dog on the beach!!"), ["dog", "on", "the", "beach", "!!"]);
        assert_eq!(words("hello world's"), ["hello", "world", "'s"]);
        assert_eq!(words("x2024 a'b"), ["x", "2", "0", "2", "4", "a", "'", "b"]);
    }

    #[test]
    fn says_tagalog_in_english() {
        assert_eq!(english("Pusa sa bundok"), "cat sa mountain");
    }

    #[test]
    fn packs_and_reads_the_index() {
        let dir = std::env::temp_dir().join(format!("lodisave-search-{}", std::process::id()));
        let s = Search::open(&dir);
        let v = pack(&unit(&(0..DIM).map(|i| i as f32 - 200.0).collect::<Vec<_>>()));
        s.state.lock().unwrap().index.insert("C:\\a b\\cat.jpg".into(), ((12, 34), v.clone()));
        drop(s.save(s.state.lock().unwrap()));
        let back = read_index(&dir.join("search").join("index.bin")).unwrap();
        assert_eq!(back["C:\\a b\\cat.jpg"], ((12, 34), v));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn prepares_a_picture() {
        // A 2:1 picture, red on the left half and blue on the right: the middle square is cut out.
        let (w, h) = (448, 224);
        let px: Vec<u8> = (0..w * h).flat_map(|i| if i % w < w / 2 { [255, 0, 0, 255] } else { [0, 0, 255, 255] }).collect();
        let out = prepare(&px, w, h);
        assert_eq!(out.len(), 3 * SIDE * SIDE);
        let red = |x: usize| out[x];
        assert!((red(0) - (1.0 - 0.481_454_66) / 0.268_629_54).abs() < 1e-3);
        assert!((red(SIDE - 1) - (0.0 - 0.481_454_66) / 0.268_629_54).abs() < 1e-3);
    }

    /// The whole way, with the model's files in LODI_ML and photos named for what's in them in
    /// LODI_ML\pics: "cat" finds the cats first, "mountain" the mountains, "pusa" the cats too.
    #[test]
    fn finds_by_whats_in_them() {
        let Ok(ml) = std::env::var("LODI_ML") else { return };
        let ml = PathBuf::from(ml);
        let dir = std::env::temp_dir().join(format!("lodisave-find-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("search")).unwrap();
        for (to, from) in [("tokenizer.json", "tokenizer.json"), ("text.onnx", "text_model_quantized.onnx"), ("vision.onnx", "vision_model_quantized.onnx")] {
            std::fs::copy(ml.join(from), dir.join("search").join(to)).unwrap();
        }
        let s = Search::open(&dir);
        assert!(s.ready());
        let _ = unsafe { windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_APARTMENTTHREADED) };
        let mut vision = session(&s.model("vision.onnx"), 2).unwrap();
        for f in std::fs::read_dir(ml.join("pics")).unwrap() {
            let p = f.unwrap().path();
            let (w, h, px) = crate::thumb::rgba(&p, 448).unwrap();
            let v = embed_images(&mut vision, 1, prepare(&px, w as usize, h as usize)).unwrap();
            s.state.lock().unwrap().index.insert(p.file_stem().unwrap().to_string_lossy().into_owned(), ((0, 0), pack(&v[0])));
        }
        let names = |q: &str| s.find(q).unwrap().into_iter().map(|(k, _)| k).collect::<Vec<_>>();
        let cats = names("cat");
        assert!(cats.len() >= 2 && cats[..2].iter().all(|k| k == "cat" || k == "kitten"), "{cats:?}");
        let mountains = names("mountain");
        assert!(mountains.len() >= 2 && mountains[..2].iter().all(|k| k.starts_with("mountain")), "{mountains:?}");
        assert_eq!(names("pusa")[..2], cats[..2]);
        assert_eq!(names("dog").first().map(String::as_str), Some("dog"));
        assert!(!names("beach").contains(&"burger".to_string()));
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Against Hugging Face's tokenizer, with the model's tokenizer.json in LODI_ML.
    #[test]
    fn tokenizes_like_hugging_face() {
        let Ok(dir) = std::env::var("LODI_ML") else { return };
        let tok = Tokenizer::load(&std::fs::read_to_string(Path::new(&dir).join("tokenizer.json")).unwrap()).unwrap();
        assert_eq!(tok.encode("cat"), [49406, 2368, 49407]);
        assert_eq!(tok.encode("a photo of a mountain"), [49406, 320, 1125, 539, 320, 3965, 49407]);
        assert_eq!(tok.encode("Dog on the BEACH!!"), [49406, 1929, 525, 518, 2117, 748, 49407]);
        assert_eq!(tok.encode("hello   world's"), [49406, 3306, 1002, 568, 49407]);
    }
}
