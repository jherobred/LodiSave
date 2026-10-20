//! Saving a copied link. Threads, Instagram and X posts go through LodiSave's own readers first,
//! which see every photo and video of a post, as in android/app/src/main/python/lodi.py. Other
//! videos go through yt-dlp, which LodiSave fetches on first use. Pages with no video fall back to
//! their og:image. Progress reaches the panel as `job://progress` events.
//! Errors are short codes the panel translates ("login", "nothing", "offline", "helper"), or
//! yt-dlp's own message.

use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Read};
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use tauri::{AppHandle, Emitter, Manager};

const YTDLP_URL: &str = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
const NO_WINDOW: u32 = 0x0800_0000;
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

fn progress(app: &AppHandle, phase: &str, percent: Option<f64>) {
    let _ = app.emit("job://progress", json!({ "phase": phase, "percent": percent }));
}

fn command(exe: impl AsRef<std::ffi::OsStr>) -> Command {
    let mut c = Command::new(exe);
    c.creation_flags(NO_WINDOW).stdin(Stdio::null()).env("PYTHONIOENCODING", "utf-8");
    c
}

fn on_path(name: &str) -> bool {
    std::env::var_os("PATH").is_some_and(|p| std::env::split_paths(&p).any(|d| d.join(name).is_file()))
}

pub fn ytdlp_path(app: &AppHandle) -> PathBuf {
    let base = app.path().app_local_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("LodiSave"));
    base.join("bin").join("yt-dlp.exe")
}

fn ensure_ytdlp(app: &AppHandle) -> Result<PathBuf, String> {
    let exe = ytdlp_path(app);
    if exe.is_file() {
        return Ok(exe);
    }
    progress(app, "helper", None);
    let _ = std::fs::create_dir_all(exe.parent().unwrap());
    let part = exe.with_extension("part");
    let ok = command("curl.exe").args(["-fsSL", "--retry", "2", "-o"]).arg(&part).arg(YTDLP_URL).status().is_ok_and(|s| s.success());
    if !ok || std::fs::rename(&part, &exe).is_err() {
        let _ = std::fs::remove_file(&part);
        return Err("helper".into());
    }
    Ok(exe)
}

/// Sites change often and old yt-dlp versions stop working, so check for an update weekly.
pub fn update_ytdlp(app: &AppHandle) {
    let exe = ytdlp_path(app);
    let age = std::fs::metadata(&exe).and_then(|m| m.modified()).ok().and_then(|t| t.elapsed().ok());
    if age.is_some_and(|a| a.as_secs() > 7 * 86400) && command(&exe).arg("-U").output().is_ok() {
        // An up-to-date copy isn't rewritten, so mark the check as done.
        let _ = std::fs::File::options().append(true).open(&exe).and_then(|f| f.set_modified(std::time::SystemTime::now()));
    }
}

fn base(exe: &Path) -> Command {
    let mut c = command(exe);
    c.args(["--no-warnings", "--no-playlist", "--no-mtime", "--encoding", "utf-8"]);
    // YouTube needs a JavaScript runtime. yt-dlp looks for Deno itself; Node is opt-in.
    if !on_path("deno.exe") && on_path("node.exe") {
        c.args(["--js-runtimes", "node"]);
    }
    c
}

fn error_code(stderr: &str) -> String {
    let line = stderr.lines().rev().find(|l| l.contains("ERROR:")).unwrap_or(stderr.trim());
    let msg = line.split("ERROR:").last().unwrap_or(line).trim();
    let low = msg.to_lowercase();
    let any = |keys: &[&str]| keys.iter().any(|k| low.contains(k));
    // The same order as code_for in android/app/src/main/python/lodi.py. HTTP statuses come before
    // "offline" because yt-dlp words them "Unable to download webpage: HTTP Error 403".
    if any(&["login", "log in", "sign in", "cookies", "private", "authentication", "http error 401"]) {
        "login".into()
    } else if any(&["unsupported url", "no video", "no media", "there's no video", "is not a valid url"]) {
        "nothing".into()
    } else if any(&["http error 403", "http error 429", "ip address is blocked", "not available in your country", "geo restrict", "too many requests"]) {
        "refused".into()
    } else if any(&["http error 5"]) {
        "offline".into()
    } else if any(&["http error 404", "http error 410", "not found", "unavailable", "removed", "does not exist", "deleted"]) {
        "gone".into()
    } else if any(&["getaddrinfo", "timed out", "unable to download webpage", "connection", "name resolution", "network is unreachable"]) {
        "offline".into()
    } else if msg.is_empty() {
        "nothing".into()
    } else if any(&["report this issue", "unable to extract", "cannot parse"]) || !msg.starts_with('[') {
        // Extractor errors start with "[site] id:". Anything else is an extractor that crashed.
        "broken".into()
    } else {
        msg.chars().take(300).collect()
    }
}

/// The real link inside a redirect wrapper, like the l.facebook.com links Messenger and Facebook
/// hand out, as unwrap in lodi.py.
fn unwrap(url: &str) -> String {
    let Some((scheme, rest)) = url.split_once("://") else { return url.into() };
    let (host, tail) = rest.split_once('/').unwrap_or((rest, ""));
    let host = host.to_lowercase();
    let (path, query) = tail.split_once('?').unwrap_or((tail, ""));
    let wrapper = ["l.facebook.com", "lm.facebook.com", "l.instagram.com", "l.messenger.com"].contains(&host.as_str())
        || ((host.starts_with("google.") || host.starts_with("www.google.")) && path == "url");
    if !scheme.starts_with("http") || !wrapper {
        return url.into();
    }
    for key in ["u", "q", "url"] {
        if let Some(v) = query.split('&').find_map(|p| p.strip_prefix(key).and_then(|v| v.strip_prefix('='))) {
            let inner = decode(v);
            if inner.starts_with("https://") || inner.starts_with("http://") {
                return unwrap(&inner);
            }
        }
    }
    url.into()
}

fn decode(s: &str) -> String {
    let b = s.as_bytes();
    let (mut out, mut i) = (Vec::with_capacity(b.len()), 0);
    while i < b.len() {
        let hex = (b[i] == b'%' && i + 2 < b.len())
            .then(|| std::str::from_utf8(&b[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok()))
            .flatten();
        match (hex, b[i]) {
            (Some(v), _) => { out.push(v); i += 3 }
            (None, b'+') => { out.push(b' '); i += 1 }
            (None, c) => { out.push(c); i += 1 }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Share images that are the site's own art, which pages show when the post itself is hidden.
// ponytail: name-based, a site whose logo file is called something else still gets its logo saved
fn site_art(name: &str) -> bool {
    let low = name.to_lowercase();
    ["logo", "favicon", "apple-touch-icon", "placeholder", "default-", "default_", "default.", "og-default", "og_default"].iter().any(|k| low.contains(k))
}

fn best<'a>(it: impl Iterator<Item = &'a Value>, key: impl Fn(&Value) -> (u64, f64)) -> Option<&'a Value> {
    it.max_by(|x, y| key(x).partial_cmp(&key(y)).unwrap_or(std::cmp::Ordering::Equal))
}

/// One file with both tracks if the site has one at the best height, else the best MP4 video
/// and M4A audio to join here. None lets yt-dlp choose.
fn pick(info: &Value) -> Option<(String, Option<String>)> {
    let formats = info["formats"].as_array()?;
    let has = |f: &Value, k: &str| f[k].as_str() != Some("none");
    let height = |f: &Value| f["height"].as_u64().unwrap_or(0);
    let rate = |f: &Value| f["tbr"].as_f64().or(f["abr"].as_f64()).unwrap_or(0.0);
    // Direct and DASH downloads keep MP4 fragments intact. HLS would give MPEG-TS.
    let http = |f: &Value| f["protocol"].as_str().is_some_and(|p| p.starts_with("http"));
    let by_video = |f: &Value| (height(f), rate(f));
    let id = |f: &Value| f["format_id"].as_str().map(str::to_string);
    let combined = best(formats.iter().filter(|f| has(f, "vcodec") && has(f, "acodec") && f["ext"] != "mhtml"), by_video);
    let video = best(formats.iter().filter(|f| has(f, "vcodec") && !has(f, "acodec") && f["ext"] == "mp4" && http(f)), by_video);
    let audio = best(formats.iter().filter(|f| !has(f, "vcodec") && has(f, "acodec") && (f["ext"] == "m4a" || f["ext"] == "mp4") && http(f)), |f| (0, rate(f)));
    match (combined, video, audio) {
        (c, Some(v), Some(a)) if c.is_none_or(|c| height(v) > height(c)) => Some((id(v)?, Some(id(a)?))),
        (Some(c), _, _) => Some((id(c)?, None)),
        _ => None,
    }
}

/// Runs one yt-dlp download, forwarding its progress scaled into [from, from + span].
fn fetch(app: &AppHandle, exe: &Path, info: &Path, format: Option<&str>, template: &Path, from: f64, span: f64) -> Result<PathBuf, String> {
    let mut c = base(exe);
    c.arg("--load-info-json").arg(info).arg("-o").arg(template);
    if let Some(f) = format {
        c.args(["-f", f]);
    }
    c.args(["--quiet", "--progress", "--newline", "--progress-template", "download:LODI %(progress._percent_str)s", "--print", "after_move:filepath"]);
    let mut child = c.stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|_| "helper".to_string())?;
    let mut stderr = child.stderr.take().unwrap();
    let errors = std::thread::spawn(move || {
        let mut s = String::new();
        let _ = stderr.read_to_string(&mut s);
        s
    });
    let mut file = None;
    for line in BufReader::new(child.stdout.take().unwrap()).lines().map_while(Result::ok) {
        let line = line.trim();
        if let Some(p) = line.strip_prefix("LODI") {
            if let Ok(p) = p.trim().trim_end_matches('%').parse::<f64>() {
                progress(app, "saving", Some(from + span * p / 100.0));
            }
        } else if !line.is_empty() {
            file = Some(PathBuf::from(line));
        }
    }
    let ok = child.wait().is_ok_and(|s| s.success());
    let errors = errors.join().unwrap_or_default();
    match file {
        Some(f) if ok && f.is_file() => Ok(f),
        _ => Err(error_code(&errors)),
    }
}

/// A file name Windows accepts, from a title.
fn clean_name(s: &str) -> String {
    let s: String = s.chars().map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { '_' } else { c }).take(70).collect();
    let s = s.trim().trim_end_matches('.').trim().to_string();
    if s.is_empty() { "lodisave".into() } else { s }
}

fn unique(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    let mut p = dir.join(format!("{stem}.{ext}"));
    let mut n = 2;
    while p.exists() {
        p = dir.join(format!("{stem} ({n}).{ext}"));
        n += 1;
    }
    p
}

fn video(app: &AppHandle, exe: &Path, url: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    progress(app, "reading", None);
    let read = |generic: bool| {
        let mut c = base(exe);
        if generic {
            c.arg("--force-generic-extractor");
        }
        c.arg("-J").arg("--").arg(url).output().map_err(|_| "helper".to_string())
    };
    let mut out = read(false)?;
    if !out.status.success() {
        let code = error_code(&String::from_utf8_lossy(&out.stderr));
        if code != "broken" {
            return Err(code);
        }
        // The site changed under its extractor. The page may still name its video in og:video,
        // JSON-LD or a <video> tag, which the generic extractor reads.
        out = read(true)?;
        if !out.status.success() {
            return Err(code);
        }
    }
    let info: Value = serde_json::from_slice(&out.stdout).map_err(|_| "nothing".to_string())?;
    let tmp = std::env::temp_dir().join("LodiSave");
    let _ = std::fs::create_dir_all(&tmp);
    let json = tmp.join(format!("{}.json", std::process::id()));
    std::fs::write(&json, &out.stdout).map_err(|e| e.to_string())?;
    let result = (|| -> Result<Vec<PathBuf>, String> {
        let template = dir.join("%(title).70B [%(id)s].%(ext)s");
        // With FFmpeg on PATH yt-dlp merges the best streams itself.
        let choice = if on_path("ffmpeg.exe") { None } else { pick(&info) };
        match choice {
            Some((v, Some(a))) => {
                let vpart = fetch(app, exe, &json, Some(&v), &tmp.join("part-v.%(ext)s"), 0.0, 85.0)?;
                let apart = fetch(app, exe, &json, Some(&a), &tmp.join("part-a.%(ext)s"), 85.0, 12.0);
                let joined = apart.as_ref().ok().map(|apart| {
                    progress(app, "joining", Some(97.0));
                    let stem = format!("{} [{}]", clean_name(info["title"].as_str().unwrap_or("")), clean_name(info["id"].as_str().unwrap_or("")));
                    let out = unique(dir, &stem, "mp4");
                    (crate::mp4::merge(&vpart, apart, &out).unwrap_or(false), out)
                });
                let _ = std::fs::remove_file(&vpart);
                if let Ok(a) = &apart {
                    let _ = std::fs::remove_file(a);
                }
                match joined {
                    Some((true, out)) => Ok(vec![out]),
                    // Not joinable here: take the best file that already has sound.
                    Some((false, out)) => {
                        let _ = std::fs::remove_file(out);
                        Ok(vec![fetch(app, exe, &json, Some("b"), &template, 0.0, 100.0)?])
                    }
                    None => Err(apart.unwrap_err()),
                }
            }
            Some((f, None)) => Ok(vec![fetch(app, exe, &json, Some(&f), &template, 0.0, 100.0)?]),
            None => Ok(vec![fetch(app, exe, &json, None, &template, 0.0, 100.0)?]),
        }
    })();
    let _ = std::fs::remove_file(&json);
    result
}

fn curl(args: &[&str]) -> Command {
    let mut c = command("curl.exe");
    c.args(["-fsSL", "--compressed", "--max-time", "30", "-A", UA]).args(args);
    c
}

fn attr(tag: &str, name: &str) -> Option<String> {
    let low = tag.to_ascii_lowercase();
    let i = low.find(&format!(" {name}="))? + name.len() + 2;
    let rest = &tag[i..];
    let q = rest.chars().next()?;
    let v = if q == '"' || q == '\'' { rest[1..].split(q).next()? } else { rest.split(|c: char| c.is_whitespace() || c == '>').next()? };
    Some(v.replace("&amp;", "&"))
}

/// The page's share image: og:image, else twitter:image.
fn og_image(html: &str) -> Option<String> {
    let mut found = None;
    for tag in html.split("<meta").skip(1) {
        let tag = &tag[..tag.find('>').unwrap_or(tag.len())];
        let key = attr(tag, "property").or_else(|| attr(tag, "name")).unwrap_or_default().to_ascii_lowercase();
        match key.as_str() {
            "og:image" | "og:image:url" | "og:image:secure_url" => return attr(tag, "content"),
            "twitter:image" | "twitter:image:src" if found.is_none() => found = attr(tag, "content"),
            _ => {}
        }
    }
    found
}

/// Full-size originals for X and Pinterest, as in extension/rules.js.
fn upgrade(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://")?;
    let (host, tail) = rest.split_once('/')?;
    let path = tail.split(['?', '#']).next()?;
    match host {
        "pbs.twimg.com" if path.starts_with("media/") => {
            let (id, ext) = path.split(':').next()?.split_once('.').unwrap_or((path, ""));
            let format = tail.split_once("format=").map(|(_, f)| f.split('&').next().unwrap_or("jpg")).filter(|f| !f.is_empty());
            Some(format!("https://pbs.twimg.com/{id}?format={}&name=orig", format.unwrap_or(if ext.is_empty() { "jpg" } else { ext })))
        }
        "i.pinimg.com" => {
            let (size, rest) = path.split_once('/')?;
            (size != "originals" && size.starts_with(|c: char| c.is_ascii_digit()) && size.contains('x')).then(|| format!("https://i.pinimg.com/originals/{rest}"))
        }
        _ => None,
    }
}

/// What a browser sends for a page. Threads and Instagram send a post's data only to browsers.
const PAGE: [&str; 7] = [
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language: en-US,en;q=0.9",
    "Sec-Fetch-Dest: document",
    "Sec-Fetch-Mode: navigate",
    "Sec-Fetch-Site: none",
    "Sec-Fetch-User: ?1",
    "Upgrade-Insecure-Requests: 1",
];
const FILE_TYPES: [(&str, &str); 10] = [
    ("image/jpeg", "jpg"), ("image/png", "png"), ("image/webp", "webp"), ("image/gif", "gif"), ("image/avif", "avif"),
    ("video/mp4", "mp4"), ("video/webm", "webm"), ("video/quicktime", "mov"), ("audio/mp4", "m4a"), ("audio/mpeg", "mp3"),
];

fn browser(args: &[&str]) -> Command {
    let mut c = curl(&[]);
    for h in PAGE {
        c.args(["-H", h]);
    }
    c.args(args);
    c
}

/// A page's HTML and final address, as a browser gets them.
fn page_of(url: &str) -> Option<(String, String)> {
    let out = browser(&["--max-filesize", "8000000", "-w", "\n%{url_effective}", "--"]).arg(url).output().ok().filter(|o| o.status.success())?;
    let text = String::from_utf8_lossy(&out.stdout);
    let (page, last) = text.rsplit_once('\n')?;
    Some((page.into(), last.into()))
}

/// Share links from the apps' Share buttons, which redirect to the post.
fn short(url: &str) -> bool {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://")).unwrap_or("");
    ["www.facebook.com/share/", "m.facebook.com/share/", "web.facebook.com/share/", "fb.watch/", "pin.it/", "vm.tiktok.com/", "vt.tiktok.com/", "t.co/",
     "instagram.com/share/", "www.instagram.com/share/", "threads.net/", "www.threads.net/"]
        .iter()
        .any(|p| rest.starts_with(p))
}

/// Where an app's share link goes. Asked as a desktop browser, since Facebook sends a phone with
/// no login to a login page instead of the post.
fn resolve(url: &str) -> String {
    if !short(url) {
        return url.into();
    }
    let Some(out) = browser(&["-o", "NUL", "-w", "%{url_effective}", "--"]).arg(url).output().ok().filter(|o| o.status.success()) else {
        return url.into();
    };
    let last = String::from_utf8_lossy(&out.stdout).trim().to_string();
    let (before, query) = last.split_once('?').unwrap_or((&last, ""));
    let next = before.contains("/login").then(|| query.split('&').find_map(|p| p.strip_prefix("next=")).map(decode)).flatten();
    unwrap(next.as_deref().filter(|n| n.starts_with("http")).unwrap_or(&last))
}

/// A photo or video a site reader found: its addresses, full size first, and its file name.
#[derive(Debug, PartialEq)]
struct Item {
    srcs: Vec<String>,
    name: String,
}

fn named(found: Vec<Vec<String>>, stem: &str) -> Vec<Item> {
    let (stem, n) = (clean_name(stem.trim()), found.len());
    found.into_iter().enumerate().map(|(i, srcs)| Item { srcs, name: if n == 1 { stem.clone() } else { format!("{stem} ({})", i + 1) } }).collect()
}

fn word(s: &str) -> bool {
    !s.is_empty() && s.chars().all(|c| c.is_alphanumeric() || c == '_' || c == '-')
}

/// The post code in a Threads or Instagram post link.
fn meta_code(url: &str) -> Option<&str> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let rest = rest.strip_prefix("www.").unwrap_or(rest);
    let parts: Vec<&str> = rest.split(['?', '#']).next()?.split('/').collect();
    let code = match parts.as_slice() {
        ["threads.com" | "threads.net", user, "post", code, ..] if user.starts_with('@') => code,
        ["instagram.com", "p" | "reel" | "reels" | "tv", code, ..] | ["instagram.com", _, "p" | "reel" | "reels" | "tv", code, ..] => code,
        _ => return None,
    };
    word(code).then_some(*code)
}

/// Every photo and video of a Threads or Instagram post, from the post data in its page. None
/// when the page doesn't carry the post.
fn meta_items(page: &str, code: &str) -> Option<Vec<Item>> {
    fn find<'a>(v: &'a Value, code: &str) -> Option<&'a Value> {
        match v {
            Value::Object(o) if o.get("code").and_then(Value::as_str) == Some(code) && (o.contains_key("image_versions2") || o.contains_key("carousel_media")) => Some(v),
            Value::Object(o) => o.values().find_map(|v| find(v, code)),
            Value::Array(a) => a.iter().find_map(|v| find(v, code)),
            _ => None,
        }
    }
    let post = page
        .split(r#"<script type="application/json""#)
        .skip(1)
        .filter_map(|b| b.split_once('>').map(|(_, b)| b.split("</script>").next().unwrap_or("")))
        .filter(|b| b.contains(code))
        .filter_map(|b| serde_json::from_str::<Value>(b).ok())
        .find_map(|v| find(&v, code).cloned())?;
    let biggest = |v: &Value| -> Option<String> {
        let area = |c: &&Value| c["width"].as_u64().unwrap_or(0) * c["height"].as_u64().unwrap_or(0);
        v.as_array()?.iter().max_by_key(area)?["url"].as_str().map(str::to_string)
    };
    let media = post["carousel_media"].as_array().filter(|a| !a.is_empty()).map_or(vec![&post], |a| a.iter().collect());
    let found = media.iter().filter_map(|m| biggest(&m["video_versions"]).or_else(|| biggest(&m["image_versions2"]["candidates"]))).map(|s| vec![s]).collect();
    Some(named(found, &format!("{} {code}", post["user"]["username"].as_str().unwrap_or(""))))
}

/// The id in a link to a post on X.
fn x_id(url: &str) -> Option<&str> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let rest = rest.strip_prefix("www.").or_else(|| rest.strip_prefix("mobile.")).unwrap_or(rest);
    match rest.split(['?', '#']).next()?.split('/').collect::<Vec<_>>().as_slice() {
        ["x.com" | "twitter.com", user, "status" | "statuses", id, ..] if word(user) && !id.is_empty() && id.bytes().all(|b| b.is_ascii_digit()) => Some(id),
        _ => None,
    }
}

/// The token X's embedded posts send with an id: (id / 1e15 * pi) in base 36, zeros and point dropped.
fn x_token(id: &str) -> String {
    const DIGITS: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let n = id.parse::<f64>().unwrap_or(0.0) / 1e15 * std::f64::consts::PI;
    let (mut whole, mut frac, mut s) = (n.trunc() as u64, n.fract(), Vec::new());
    while whole > 0 {
        s.insert(0, DIGITS[(whole % 36) as usize]);
        whole /= 36;
    }
    for _ in 0..11 {
        frac *= 36.0;
        s.push(DIGITS[frac.trunc() as usize]);
        frac = frac.fract();
    }
    String::from_utf8_lossy(&s).replace('0', "")
}

/// Every photo and video of a post on X, from the API its embedded posts use. None when the API
/// doesn't answer.
fn x_items(id: &str) -> Option<Vec<Item>> {
    let api = format!("https://cdn.syndication.twimg.com/tweet-result?id={id}&lang=en&token={}", x_token(id));
    let data: Value = serde_json::from_slice(&curl(&["--"]).arg(api).output().ok()?.stdout).ok()?;
    let found: Vec<Vec<String>> = data["mediaDetails"]
        .as_array()?
        .iter()
        .filter_map(|m| match m["media_url_https"].as_str() {
            Some(still) if m["type"] == "photo" => Some(upgrade(still).into_iter().chain([still.to_string()]).collect()),
            _ => {
                let mp4 = m["video_info"]["variants"].as_array()?.iter().filter(|v| v["content_type"] == "video/mp4");
                Some(vec![mp4.max_by_key(|v| v["bitrate"].as_u64().unwrap_or(0))?["url"].as_str()?.to_string()])
            }
        })
        .collect();
    let stem = format!("{} {id}", data["user"]["screen_name"].as_str().unwrap_or(""));
    Some(named(found, &stem)).filter(|items| !items.is_empty())
}

/// A post's photos and videos from LodiSave's own readers. None hands the link to yt-dlp.
fn site_items(url: &str) -> Option<Vec<Item>> {
    match meta_code(url) {
        Some(code) => meta_items(&page_of(url)?.0, code),
        None => x_items(x_id(url)?),
    }
}

/// Saves an item's file, trying its full-size address first and the shown copy after.
fn fetch_item(item: &Item, referer: &str, dir: &Path) -> Result<PathBuf, String> {
    let part = dir.join(format!(".lodisave-{}.part", std::process::id()));
    let mut failure = None;
    for src in &item.srcs {
        // Videos can be long, so only a stalled transfer times out.
        let out = command("curl.exe")
            .args(["-sSL", "--connect-timeout", "30", "--speed-time", "30", "--speed-limit", "1", "-A", UA, "-e", referer])
            .args(["-w", "%{http_code} %{content_type}", "-o"])
            .arg(&part)
            .arg("--")
            .arg(src)
            .output();
        let Some(out) = out.ok().filter(|o| o.status.success()) else {
            failure.get_or_insert("offline");
            continue;
        };
        let written = String::from_utf8_lossy(&out.stdout);
        let (status, kind) = written.split_once(' ').unwrap_or((&written, ""));
        let kind = kind.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
        let code = match (status, FILE_TYPES.iter().find(|(t, _)| *t == kind)) {
            ("200", Some((_, ext))) => {
                let path = unique(dir, &item.name, ext);
                if std::fs::rename(&part, &path).is_ok() {
                    return Ok(path);
                }
                "blocked"
            }
            ("401", _) => "login",
            ("403" | "429", _) => "refused",
            ("404" | "410", _) => "gone",
            (s, _) if !s.starts_with('2') => "offline",
            // A web page or a login wall, not the file.
            _ => "nothing",
        };
        failure.get_or_insert(code);
    }
    let _ = std::fs::remove_file(&part);
    Err(failure.unwrap_or("nothing").into())
}

/// Saves every photo and video a site reader found. The panel has no picker, so a post with
/// several saves them all.
fn save_items(app: &AppHandle, items: &[Item], referer: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    let (mut saved, mut failure) = (Vec::new(), None);
    for (i, item) in items.iter().enumerate() {
        progress(app, "saving", (items.len() > 1).then(|| 100.0 * i as f64 / items.len() as f64));
        match fetch_item(item, referer, dir) {
            Ok(p) => saved.push(p),
            Err(e) => {
                failure.get_or_insert(e);
            }
        }
    }
    if saved.is_empty() { Err(failure.unwrap_or_else(|| "nothing".into())) } else { Ok(saved) }
}

fn image(app: &AppHandle, url: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    progress(app, "reading", None);
    let (page, last) = page_of(url).ok_or("offline")?;
    let shown = og_image(&page).ok_or("nothing")?;
    let origin = last.splitn(4, '/').take(3).collect::<Vec<_>>().join("/");
    let shown = if shown.starts_with("//") {
        format!("https:{shown}")
    } else if shown.starts_with('/') {
        format!("{origin}{shown}")
    } else {
        shown
    };
    let name = shown.split(['?', '#']).next().unwrap_or("").rsplit('/').next().unwrap_or("");
    if !shown.starts_with("http") || site_art(name) {
        return Err("nothing".into());
    }
    let (stem, ext) = name.rsplit_once('.').filter(|(_, e)| e.len() <= 4).unwrap_or((name, "jpg"));
    let format = shown.split_once("format=").map(|(_, f)| f.split('&').next().unwrap_or("").to_string()).filter(|f| !f.is_empty());
    let out = unique(dir, &clean_name(stem), &format.unwrap_or_else(|| ext.to_string()));
    progress(app, "saving", None);
    for src in [upgrade(&shown), Some(shown.clone())].into_iter().flatten() {
        if curl(&["-e", url, "-o"]).arg(&out).arg("--").arg(&src).status().is_ok_and(|s| s.success()) {
            return Ok(vec![out]);
        }
    }
    let _ = std::fs::remove_file(&out);
    // The image is there and the page loaded, so the site refused to send it.
    Err("refused".into())
}

pub fn save(app: &AppHandle, url: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    let url = unwrap(url.trim());
    let url = url.as_str();
    if !(url.starts_with("https://") || url.starts_with("http://")) || url.contains(char::is_whitespace) {
        return Err("nothing".into());
    }
    crate::writable(dir)?;
    progress(app, "reading", None);
    let url = resolve(url);
    let url = url.as_str();
    match site_items(url) {
        Some(items) if items.is_empty() => return Err("nothing".into()), // a post with text only
        Some(items) => return save_items(app, &items, url, dir),
        None => {}
    }
    let exe = ensure_ytdlp(app)?;
    match video(app, &exe, url, dir) {
        Err(code) if code == "nothing" => image(app, url, dir),
        r => r,
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    #[test]
    fn picks_and_parses() {
        let info = json!({ "formats": [
            { "format_id": "18", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360, "protocol": "https" },
            { "format_id": "137", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 1080, "protocol": "https" },
            { "format_id": "248", "ext": "webm", "vcodec": "vp9", "acodec": "none", "height": 1080, "protocol": "https" },
            { "format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a", "abr": 128.0, "protocol": "https" },
            { "format_id": "sb0", "ext": "mhtml", "vcodec": "none", "acodec": "none", "protocol": "mhtml" },
        ]});
        assert_eq!(super::pick(&info), Some(("137".into(), Some("140".into()))));
        let tiktok = json!({ "formats": [{ "format_id": "h264", "ext": "mp4", "height": 1024, "protocol": "https" }] });
        assert_eq!(super::pick(&tiktok), Some(("h264".into(), None)));

        let html = r#"<meta name="twitter:image" content="https://a.test/t.jpg"><meta property="og:image:width" content="9"><meta property="og:image" content="https://i.pinimg.com/736x/ab/cd/x.jpg?a=1&amp;b=2">"#;
        assert_eq!(super::og_image(html).as_deref(), Some("https://i.pinimg.com/736x/ab/cd/x.jpg?a=1&b=2"));
        assert_eq!(super::upgrade("https://i.pinimg.com/736x/ab/cd/x.jpg").as_deref(), Some("https://i.pinimg.com/originals/ab/cd/x.jpg"));
        assert_eq!(super::upgrade("https://pbs.twimg.com/media/Gx1?format=png&name=small").as_deref(), Some("https://pbs.twimg.com/media/Gx1?format=png&name=orig"));
        assert_eq!(super::upgrade("https://pbs.twimg.com/media/Gx1.jpg:large").as_deref(), Some("https://pbs.twimg.com/media/Gx1?format=jpg&name=orig"));
        assert_eq!(super::clean_name(r#"a/b: "c"?. "#), "a_b_ _c__");
    }

    #[test]
    fn codes_links_and_site_art() {
        use super::error_code as code;
        assert_eq!(code("ERROR: [Rumble] v1: Unable to download webpage: HTTP Error 403: Forbidden"), "refused");
        assert_eq!(code("ERROR: [TikTok] 67: Your IP address is blocked from accessing this post"), "refused");
        assert_eq!(code("ERROR: [vimeo] 56: Unable to download webpage: HTTP Error 404: Not Found"), "gone");
        assert_eq!(code("ERROR: [twitter] 65: Video #1 is unavailable"), "gone");
        assert_eq!(code("ERROR: [x] 1: Unable to download webpage: HTTP Error 503: Service Unavailable"), "offline");
        assert_eq!(code("ERROR: [youtube] a: Video unavailable. This video is not available in your country"), "refused");
        assert_eq!(code("ERROR: [vimeo] 11: Unable to download webpage: HTTP Error 401: Unauthorized"), "login");
        assert_eq!(code("ERROR: [facebook] 36: Cannot parse data; please report this issue on https://github.com/yt-dlp/yt-dlp/issues"), "broken");
        assert_eq!(code("ERROR: the JSON object must be str, bytes or bytearray, not NoneType"), "broken");
        assert_eq!(code("ERROR: [x] 1: Unable to download webpage: <urlopen error [Errno 11001] getaddrinfo failed>"), "offline");
        assert_eq!(code("ERROR: Unsupported URL: https://x.test/"), "nothing");

        assert_eq!(super::unwrap("https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.tiktok.com%2F%40a%2Fvideo%2F1%3Fx%3D1&h=AT0"), "https://www.tiktok.com/@a/video/1?x=1");
        assert_eq!(super::unwrap("https://www.google.com/url?q=https://x.com/a/status/1&sa=D"), "https://x.com/a/status/1");
        assert_eq!(super::unwrap("https://www.facebook.com/reel/1?u=https://evil.test/"), "https://www.facebook.com/reel/1?u=https://evil.test/");
        assert_eq!(super::unwrap("https://l.facebook.com/l.php?u=javascript%3Aalert(1)"), "https://l.facebook.com/l.php?u=javascript%3Aalert(1)");

        assert!(super::site_art("t_logo_2x.png") && super::site_art("og-default.jpg"));
        assert!(!super::site_art("5645318632_09ff5eef3b_b.jpg"));

        assert!(super::short("https://www.facebook.com/share/r/1AbC/") && super::short("https://vt.tiktok.com/ZS1/") && super::short("https://t.co/x"));
        assert!(super::short("https://www.threads.net/@a/post/B") && !super::short("https://www.tiktok.com/@a/video/1") && !super::short("https://www.facebook.com/reel/1"));
    }

    #[test]
    fn site_readers() {
        let carousel = json!({ "code": "ABC", "user": { "username": "nasa" }, "carousel_media": [
            { "image_versions2": { "candidates": [
                { "url": "https://x.test/s.jpg", "width": 150, "height": 150 },
                { "url": "https://x.test/b.jpg", "width": 1080, "height": 1080 }] } },
            { "image_versions2": { "candidates": [{ "url": "https://x.test/t.jpg", "width": 640, "height": 640 }] },
              "video_versions": [{ "url": "https://x.test/v.mp4", "width": 720, "height": 1280 }] },
        ]});
        let page = format!(r#"<script type="application/json" data-sjs>{}</script>"#, json!({ "a": [{ "code": "ABC" }, carousel] }));
        let items = super::meta_items(&page, "ABC").unwrap();
        let got: Vec<(&str, &str)> = items.iter().map(|i| (i.srcs[0].as_str(), i.name.as_str())).collect();
        assert_eq!(got, [("https://x.test/b.jpg", "nasa ABC (1)"), ("https://x.test/v.mp4", "nasa ABC (2)")]);
        assert_eq!(super::meta_items(&page, "XYZ"), None);
        let single = format!(r#"<script type="application/json">{}</script>"#, json!({ "code": "Q", "image_versions2": { "candidates": [{ "url": "https://x.test/q.jpg" }] } }));
        assert_eq!(super::meta_items(&single, "Q").unwrap()[0].name, "Q");

        assert_eq!(super::meta_code("https://www.threads.com/@nasa/post/Decc755lYhC?xmt=AQF0"), Some("Decc755lYhC"));
        assert_eq!(super::meta_code("https://www.instagram.com/reel/C1a2b3/?igsh=x"), Some("C1a2b3"));
        assert_eq!(super::meta_code("https://www.instagram.com/nasa/p/BQ0e-Al_w/"), Some("BQ0e-Al_w"));
        assert_eq!(super::meta_code("https://www.instagram.com/nasa/"), None);
        assert_eq!(super::x_id("https://x.com/elonmusk/status/1585341984679469056?s=46"), Some("1585341984679469056"));
        assert_eq!(super::x_id("https://x.com/i/spaces/1"), None);
        // Tokens from x_token in lodi.py.
        assert_eq!(super::x_token("1585341984679469056"), "3uchycv2wqcdge");
        assert_eq!(super::x_token("266031293945503744"), "n7rfhxzwhkcjm");
        assert_eq!(super::x_token("20"), "6dq");
    }
}
