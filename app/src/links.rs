//! Saving a copied link. Threads, Instagram, X and Reddit posts go through LodiSave's own readers
//! first, which see every photo and video of a post, as in android/app/src/main/python/lodi.py.
//! Other videos go through yt-dlp, which LodiSave fetches on first use. Pages with no video fall
//! back to their og:image. Saved files are named by their first bytes. Progress reaches the panel as
//! `job://progress` events. Errors are short codes the panel translates ("login", "nothing",
//! "offline", "helper"), or yt-dlp's own message.

use serde_json::{Value, json};
use std::cell::RefCell;
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

thread_local! {
    /// The user's sign-in for the save running on this thread: a Netscape cookies.txt file, which curl
    /// (-b) and yt-dlp (--cookies) read, each sending a cookie only to the domains it belongs to.
    static COOKIES: RefCell<Option<PathBuf>> = const { RefCell::new(None) };
}

/// Runs `f` with this thread's requests signed in with `cookies`, a file that must exist.
fn signed_in<T>(cookies: Option<&Path>, f: impl FnOnce() -> T) -> T {
    COOKIES.set(cookies.filter(|p| p.is_file()).map(Path::to_path_buf));
    let out = f();
    COOKIES.set(None);
    out
}

/// A curl (`-b`) or yt-dlp (`--cookies`) command that carries the save's sign-in, when it has one.
fn signed(mut c: Command, flag: &str) -> Command {
    if let Some(file) = COOKIES.with_borrow(Clone::clone) {
        c.arg(flag).arg(file);
    }
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
    let mut c = signed(command(exe), "--cookies");
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
        Some(f) if ok && f.is_file() => settle(f),
        _ => Err(error_code(&errors)),
    }
}

/// A file's type from its first bytes, as an extension, as sniff in lodi.py. Saves are named by this,
/// not by the URL or the server's Content-Type, which can be wrong.
fn sniff(head: &[u8]) -> Option<&'static str> {
    let at = |i: usize, s: &[u8]| head.get(i..i + s.len()) == Some(s);
    let has = |within: &[u8], s: &[u8]| within.windows(s.len()).any(|w| w == s);
    if at(0, b"\xff\xd8\xff") {
        Some("jpg")
    } else if at(0, b"\x89PNG\r\n\x1a\n") {
        Some("png")
    } else if at(0, b"GIF87a") || at(0, b"GIF89a") {
        Some("gif")
    } else if at(0, b"RIFF") && at(8, b"WEBP") {
        Some("webp")
    } else if at(4, b"ftyp") {
        // MP4 and its kin, told apart by the brands that follow.
        let brands = &head[8..head.len().min(40)];
        Some(if has(brands, b"avif") || has(brands, b"avis") {
            "avif"
        } else if has(brands, b"heic") || has(brands, b"heix") || has(brands, b"mif1") {
            "heic"
        } else if at(8, b"qt  ") {
            "mov"
        } else if at(8, b"M4A ") {
            "m4a"
        } else {
            "mp4"
        })
    } else if at(0, b"\x1a\x45\xdf\xa3") {
        Some(if has(&head[..head.len().min(64)], b"webm") { "webm" } else { "mkv" })
    } else if at(0, b"ID3") {
        Some("mp3")
    } else {
        None
    }
}

/// The extension a download saves under, as file_ext in lodi.py: its bytes decide, the server's
/// Content-Type only for a format sniff doesn't know. None for a web page, a login wall or anything
/// else that isn't media.
fn file_ext(head: &[u8], content_type: &str) -> Option<&'static str> {
    let said = FILE_TYPES.iter().find(|(t, _)| *t == content_type).map(|(_, e)| *e);
    match (sniff(head), said) {
        // The same container, and the server knows which.
        (Some("mp4"), Some(s @ ("m4a" | "mov"))) => Some(s),
        (Some(found), _) => Some(found),
        (None, _) if head.trim_ascii_start().starts_with(b"<") => None,
        (None, said) => said,
    }
}

/// Extensions for one kind of file, so a saved part isn't renamed for nothing.
fn same_kind(ext: &str) -> &str {
    match ext {
        "jpeg" => "jpg",
        "m4a" | "mov" | "m4v" => "mp4",
        "mkv" => "webm",
        e => e,
    }
}

/// A file yt-dlp saved, renamed when its bytes are another type than its extension, as settle in
/// lodi.py. "nothing" when it is a web page, which some sites send in place of the file.
fn settle(path: PathBuf) -> Result<PathBuf, String> {
    let mut head = Vec::new();
    std::fs::File::open(&path).and_then(|f| f.take(64).read_to_end(&mut head)).map_err(|e| e.to_string())?;
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    match sniff(&head) {
        None if head.trim_ascii_start().starts_with(b"<") => {
            let _ = std::fs::remove_file(&path);
            Err("nothing".into())
        }
        Some(found) if same_kind(found) != same_kind(&ext) => {
            let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("lodisave").to_string();
            let new = unique(path.parent().unwrap_or(Path::new(".")), &stem, found);
            std::fs::rename(&path, &new).map(|_| new).map_err(|e| e.to_string())
        }
        _ => Ok(path),
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
    // A bare v.redd.it link: its playlist is public, where the post page sends a bot check.
    let reddit = reddit_video(url);
    let playlist = reddit.map(|id| format!("https://v.redd.it/{id}/DASHPlaylist.mpd"));
    let url = playlist.as_deref().unwrap_or(url);
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
    let mut info: Value = serde_json::from_slice(&out.stdout).map_err(|_| "nothing".to_string())?;
    if let Some(id) = reddit {
        // The playlist's own name is DASHPlaylist.
        info["title"] = "reddit".into();
        info["id"] = id.into();
    }
    let stem = format!("{} [{}]", clean_name(info["title"].as_str().unwrap_or("")), clean_name(info["id"].as_str().unwrap_or("")));
    // A file the generic reader found named in a page (og:video) is fetched here, as in lodi.py:
    // yt-dlp asks for it as a browser asks for a page, and Giphy then sends its web page in place of
    // the video.
    let direct = info["extractor_key"] == "Generic" && info["formats"].is_null() && matches!(info["protocol"].as_str(), Some("http" | "https"));
    if let Some(file) = info["url"].as_str().filter(|_| direct) {
        progress(app, "saving", None);
        return fetch_item(&Item { srcs: vec![file.into()], name: stem }, url, dir).map(|p| vec![p]);
    }
    let tmp = std::env::temp_dir().join("LodiSave");
    let _ = std::fs::create_dir_all(&tmp);
    let json = tmp.join(format!("{}.json", std::process::id()));
    std::fs::write(&json, info.to_string()).map_err(|e| e.to_string())?;
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
    let mut c = signed(command("curl.exe"), "-b");
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

/// The first value of a page's meta tags named in the first of `groups` the page has.
fn meta(html: &str, groups: &[&[&str]]) -> Option<String> {
    let mut found = vec![None; groups.len()];
    for tag in html.split("<meta").skip(1) {
        let tag = &tag[..tag.find('>').unwrap_or(tag.len())];
        let key = attr(tag, "property").or_else(|| attr(tag, "name")).unwrap_or_default().to_ascii_lowercase();
        if let Some(i) = groups.iter().position(|names| names.contains(&key.as_str())) {
            found[i] = found[i].take().or_else(|| attr(tag, "content"));
        }
    }
    found.into_iter().flatten().next()
}

/// The page's share image: og:image, else twitter:image.
fn og_image(html: &str) -> Option<String> {
    meta(html, &[&["og:image", "og:image:url", "og:image:secure_url"], &["twitter:image", "twitter:image:src"]])
}

/// The video file the page names for sharing: og:video, else twitter:player:stream.
fn og_video(html: &str) -> Option<String> {
    meta(html, &[&["og:video", "og:video:url", "og:video:secure_url"], &["twitter:player:stream"]])
}

/// Whether a page says it shows a video, whose share image is only a still of it.
fn is_video_page(html: &str) -> bool {
    og_video(html).is_some()
        || meta(html, &[&["og:type"]]).is_some_and(|t| t.starts_with("video"))
        || meta(html, &[&["twitter:card"]]).as_deref() == Some("player")
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
        ["instagram.com", "p" | "reel" | "reels" | "tv", code, ..] => code,
        ["instagram.com", user, "p" | "reel" | "reels" | "tv", code, ..] if *user != "stories" => code,
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
    let media = post["carousel_media"].as_array().filter(|a| !a.is_empty()).map_or(vec![&post], |a| a.iter().collect());
    Some(named(meta_media(&media), &format!("{} {code}", post["user"]["username"].as_str().unwrap_or(""))))
}

fn giphy(url: &str) -> bool {
    url.strip_prefix("https://").and_then(|r| r.split_once(".giphy.com/")).is_some_and(|(sub, _)| sub == "i" || sub.trim_start_matches("media").bytes().all(|b| b.is_ascii_digit()))
}

/// Each photo, video and GIF in Meta's media data (a post, a carousel, a story), as media_items in
/// lodi.py: the biggest copy, and for a GIF from Threads' GIF picker, which has no picture of its
/// own, Giphy's GIF and then its WebP. A post's own page shows visitors a stand-in picture in place of
/// the GIF, which is left out.
fn meta_media(media: &[&Value]) -> Vec<Vec<String>> {
    let num = |v: &Value| v.as_u64().or_else(|| v.as_str()?.parse().ok()).unwrap_or(0);
    let area = |c: &&Value| num(&c["width"]) * num(&c["height"]);
    let biggest = |v: &Value| -> Option<String> { v.as_array()?.iter().max_by_key(area)?["url"].as_str().map(str::to_string) };
    media
        .iter()
        .filter_map(|m| {
            let gif = m["giphy_media_info"]["images"].as_object().and_then(|o| o.values().max_by_key(area));
            let gif = gif.filter(|g| g["url"].as_str().is_some_and(giphy)).map(|g| [&g["url"], &g["webp"]].into_iter().filter_map(|u| u.as_str().filter(|u| giphy(u))).map(str::to_string).collect());
            biggest(&m["video_versions"]).map(|v| vec![v]).or(gif).or_else(|| biggest(&m["image_versions2"]["candidates"]).map(|s| vec![s]))
        })
        .collect()
}

/// The GIFs of a Threads post, from its embed page, which shows Giphy's file through Meta's image
/// proxy (proxy?url=<the GIF>) where the post's own page shows visitors a stand-in, as threads_gifs in
/// lodi.py.
fn threads_gifs(post: &str) -> Vec<Vec<String>> {
    let Some((page, _)) = page_of(&format!("{post}/embed")) else { return Vec::new() };
    page.split(r#"<img class="img" src=""#)
        .skip(1)
        .filter_map(|s| {
            let src = s.split('"').next()?.replace("&amp;", "&");
            let url = decode(src.split_once('?')?.1.split('&').find_map(|p| p.strip_prefix("url="))?);
            giphy(&url).then(|| vec![url])
        })
        .collect()
}

/// What Instagram's website sends to its own API.
const IG_API: [&str; 5] = ["X-IG-App-ID: 936619743392459", "X-ASBD-ID: 359341", "X-IG-WWW-Claim: 0", "Origin: https://www.instagram.com", "Accept: */*"];

/// The user, highlight and story an Instagram story link names, as IG_STORY and shared_story in
/// lodi.py: stories/<user>/, stories/<user>/<id>/, stories/highlights/<id>/, and the app's highlight
/// share link s/<base64 of "highlight:<id>">?story_media_id=<id>_<user id>.
type Story = (Option<String>, Option<String>, Option<String>);
fn story(url: &str) -> Option<Story> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let rest = rest.strip_prefix("www.").unwrap_or(rest).strip_prefix("instagram.com/")?.split('#').next()?;
    let (path, query) = rest.split_once('?').unwrap_or((rest, ""));
    let digits = |s: &str| (!s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())).then(|| s.to_string());
    match path.split('/').collect::<Vec<_>>().as_slice() {
        ["stories", "highlights", rest @ ..] => Some((None, rest.first().and_then(|s| digits(s)), None)),
        ["stories", user, rest @ ..] if word(&user.replace('.', "_")) => Some((Some(user.to_string()), None, rest.first().and_then(|s| digits(s)))),
        ["s", code, ..] => {
            let only = query.split('&').find_map(|p| p.strip_prefix("story_media_id=")).and_then(|v| digits(v.split('_').next()?));
            Some((None, Some(digits(unbase64(code)?.strip_prefix("highlight:")?)?), only))
        }
        _ => None,
    }
}

/// The text in a base64 string, URL-safe or not.
fn unbase64(s: &str) -> Option<String> {
    const ABC: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let (mut out, mut acc, mut bits) = (Vec::new(), 0u32, 0);
    for c in s.trim_end_matches('=').bytes() {
        let c = match c { b'-' => b'+', b'_' => b'/', c => c };
        acc = (acc << 6) | ABC.iter().position(|&a| a == c)? as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    String::from_utf8(out).ok()
}

/// JSON from the API Instagram's website uses, which answers only a signed-in user. To a sign-in it
/// no longer takes, it answers 401 or, for a profile, 429.
fn ig_api(path: &str) -> Result<Value, String> {
    let mut c = curl(&["-w", "\n%{http_code} %{url_effective}"]);
    for h in IG_API {
        c.args(["-H", h]);
    }
    let out = c.arg("--").arg(format!("https://www.instagram.com/api/v1/{path}")).output().map_err(|_| "offline".to_string())?;
    let text = String::from_utf8_lossy(&out.stdout);
    let (body, tail) = text.rsplit_once('\n').unwrap_or(("", text.as_ref()));
    match tail.split_once(' ').unwrap_or((tail, "")) {
        (_, last) if last.contains("/accounts/login") => Err("login".into()),
        ("401" | "403" | "429", _) => Err("login".into()),
        // A login page in place of the JSON.
        ("200", _) => serde_json::from_str(body).map_err(|_| "login".to_string()),
        ("404" | "410", _) => Err("gone".into()),
        _ => Err("offline".into()),
    }
}

/// Every photo and video of a user's Instagram stories or of a highlight, or just one story, as
/// story_items in lodi.py. Stories need the user's sign-in.
fn story_items((user, highlight, only): Story) -> Result<Vec<Item>, String> {
    if user.is_none() && highlight.is_none() {
        return Err("nothing".into());
    }
    if COOKIES.with_borrow(Option::is_none) {
        return Err("login".into());
    }
    let reel = match (&highlight, &user) {
        (Some(h), _) => format!("highlight:{h}"),
        (_, u) => {
            let id = ig_api(&format!("users/web_profile_info/?username={}", u.as_deref().unwrap_or("")))?["data"]["user"]["id"].clone();
            id.as_str().map(str::to_string).or_else(|| id.as_u64().map(|n| n.to_string())).ok_or("gone")?
        }
    };
    let data = ig_api(&format!("feed/reels_media/?reel_ids={reel}"))?;
    let found = &data["reels"][reel.as_str()];
    let pk = |m: &Value| m["pk"].as_str().map(str::to_string).or_else(|| m["pk"].as_u64().map(|n| n.to_string()));
    let media: Vec<&Value> = found["items"].as_array().into_iter().flatten().filter(|m| only.is_none() || pk(m) == only).collect();
    if media.is_empty() {
        // A highlight stays, so Instagram showing none means it no longer takes this sign-in. A story
        // lasts a day.
        return Err(if highlight.is_some() { "login" } else { "gone" }.into());
    }
    let owner = found["user"]["username"].as_str().or(user.as_deref()).unwrap_or("instagram");
    Ok(named(meta_media(&media), &format!("{owner} {}", only.or(highlight).as_deref().unwrap_or("story"))))
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

/// The subreddit, when named, and the post id in a reddit.com path: r/<sub>/comments/<id>,
/// comments/<id> or gallery/<id>.
fn reddit_path(path: &str) -> Option<(Option<&str>, &str)> {
    let (sub, id) = match path.split('/').collect::<Vec<_>>().as_slice() {
        ["r", sub, "comments" | "gallery", id, ..] => (Some(*sub), *id),
        ["comments" | "gallery", id, ..] => (None, *id),
        _ => return None,
    };
    (sub.is_none_or(word) && word(id)).then_some((sub, id))
}

/// The subreddit, when the link names it, and the id of a link to a Reddit post.
fn reddit_post(url: &str) -> Option<(Option<&str>, &str)> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let (host, path) = rest.split(['?', '#']).next()?.split_once('/')?;
    if host == "redd.it" {
        let id = path.split('/').next()?;
        return word(id).then_some((None, id));
    }
    (host == "reddit.com" || host.ends_with(".reddit.com")).then(|| reddit_path(path)).flatten()
}

/// The id in a bare v.redd.it video link.
fn reddit_video(url: &str) -> Option<&str> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let id = rest.strip_prefix("v.redd.it/")?.split(['/', '?', '#']).next()?;
    word(id).then_some(id)
}

/// The subreddit, when named, and the id of every link to a Reddit post in a page.
fn reddit_links(page: &str) -> impl Iterator<Item = (Option<&str>, &str)> {
    page.match_indices("reddit.com/").filter_map(|(i, s)| {
        reddit_path(page[i + s.len()..].split(|c: char| !(c.is_alphanumeric() || c == '_' || c == '/')).next()?)
    })
}

/// The original of a Reddit preview image, which is named after it:
/// preview.redd.it/<title>-v0-<id>.jpeg?width=640 is i.redd.it/<id>.jpeg.
fn reddit_original(preview: &str) -> Option<String> {
    let name = preview.strip_prefix("https://preview.redd.it/")?.split(|c: char| !(c.is_alphanumeric() || "-_.".contains(c))).next()?;
    let (stem, ext) = name.split_once('.')?;
    let id = stem.rsplit_once("-v0-").map_or(stem, |(_, id)| id);
    let ok = !id.is_empty() && id.bytes().all(|b| b.is_ascii_alphanumeric()) && ["jpeg", "jpg", "png", "gif", "webp"].contains(&ext);
    ok.then(|| format!("https://i.redd.it/{id}.{ext}"))
}

/// What a Reddit embed page shows: its video, the biggest of the MP4s Reddit made with picture and
/// sound in one file, else its photos in order, a gallery's when it has one.
fn reddit_media(page: &str) -> Vec<String> {
    let mp4 = page.split_once(r#"packaged-media-json=""#).and_then(|(_, a)| {
        let data: Value = serde_json::from_str(&a[..a.find('"')?].replace("&quot;", "\"").replace("&amp;", "&")).ok()?;
        let sizes = data["playbackMp4s"]["permutations"].as_array()?;
        let biggest = sizes.iter().max_by_key(|p| p["source"]["dimensions"]["height"].as_u64().unwrap_or(0))?;
        biggest["source"]["url"].as_str().map(str::to_string)
    });
    if let Some(mp4) = mp4 {
        return vec![mp4];
    }
    let shown = page.split_once("<gallery-carousel").and_then(|(_, g)| g.split_once("</gallery-carousel>")).map_or(page, |(g, _)| g);
    let mut photos = Vec::new();
    for src in shown.match_indices("https://preview.redd.it/").filter_map(|(i, _)| reddit_original(&shown[i..])) {
        if !photos.contains(&src) {
            photos.push(src);
        }
    }
    // A GIF or video from another site (Imgur, Giphy): the MP4 of it that Reddit's player plays. Its
    // poster is only a still.
    let player = page.split_once("<shreddit-player").and_then(|(_, t)| attr(&t[..t.find('>')?], "src"));
    match player {
        Some(src) if photos.is_empty() && !src.contains(".m3u8") && !src.contains(".mpd") => vec![src],
        _ => photos,
    }
}

/// A post's subreddit, from the post's feed.
fn reddit_sub(post: &str) -> Option<String> {
    let feed = format!("https://www.reddit.com/comments/{post}/.rss");
    for wait in [true, false] {
        let out = curl(&["--max-filesize", "2000000", "-w", "\n%{http_code} %header{x-ratelimit-reset}", "--"]).arg(&feed).output().ok()?;
        let text = String::from_utf8_lossy(&out.stdout);
        let (body, status) = text.rsplit_once('\n')?;
        match status.split_once(' ') {
            // Without a login Reddit answers few feed requests and says, in whole seconds, when to
            // come back. The wait is capped so the panel doesn't hang.
            Some(("429", reset)) if wait => std::thread::sleep(std::time::Duration::from_secs(reset.parse::<u64>().unwrap_or(2).min(5) + 1)),
            _ => return reddit_links(body).find_map(|(sub, id)| sub.filter(|_| id == post)).map(str::to_string),
        }
    }
    None
}

/// Every photo and video of a Reddit post, from the embed page Reddit makes for other sites
/// (reddit.com itself sends scripts a bot check). A link with no subreddit finds it in the post's
/// feed, and a crosspost is followed once to the post it shares. None when the post can't be read
/// this way.
fn reddit_items(sub: Option<&str>, post: &str, follow: bool) -> Option<Vec<Item>> {
    let sub = sub.map(str::to_string).or_else(|| reddit_sub(post))?;
    let (page, _) = page_of(&format!("https://embed.reddit.com/r/{sub}/comments/{post}/"))?;
    let found = reddit_media(&page);
    if found.is_empty() {
        // A crosspost shows only a link to the post it shares.
        let (sub, other) = reddit_links(&page).find(|(_, id)| *id != post)?;
        return follow.then(|| reddit_items(sub, other, false)).flatten();
    }
    Some(named(found.into_iter().map(|s| vec![s]).collect(), &format!("{sub} {post}")))
}

/// A post's photos and videos from LodiSave's own readers. None hands the link to yt-dlp.
fn site_items(url: &str) -> Option<Vec<Item>> {
    if let Some(code) = meta_code(url) {
        let items = meta_items(&page_of(url)?.0, code)?;
        let post = &url[..url.find(code)? + code.len()];
        match post.split_once("/@") {
            // A Threads post with no photo or video may have a GIF, which its page hides from visitors.
            Some((_, user)) if items.is_empty() => Some(named(threads_gifs(post), &format!("{} {code}", user.split('/').next()?))),
            _ => Some(items),
        }
    } else if let Some((sub, post)) = reddit_post(url) {
        reddit_items(sub, post, true)
    } else {
        x_items(x_id(url)?)
    }
}

/// Saves an item's file, trying its full-size address first and the shown copy after. Its first
/// bytes name it, so a GIF or a video a server sends under another type still saves as what it is.
fn fetch_item(item: &Item, referer: &str, dir: &Path) -> Result<PathBuf, String> {
    let part = dir.join(format!(".lodisave-{}.part", std::process::id()));
    let mut failure = None;
    for src in &item.srcs {
        // Videos can be long, so only a stalled transfer times out.
        let out = signed(command("curl.exe"), "-b")
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
        let mut head = Vec::new();
        let _ = std::fs::File::open(&part).and_then(|f| f.take(64).read_to_end(&mut head));
        let code = match (status, file_ext(&head, &kind)) {
            ("200", Some(ext)) => {
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

/// What a link yt-dlp found no video in shows, as page_items in lodi.py: the video file the page
/// names, else its share image, full size on X and Pinterest, else the link itself, which may be the
/// file. Giphy, Tenor, Tumblr and Imgur send a web page in place of a file to whatever asks the way a
/// browser asks for a page; fetch_item asks plainly, and refuses pages. A page that says it shows a
/// video never saves its share image, a still of the video.
fn page_items(url: &str) -> Result<Vec<Item>, String> {
    let file = |u: &str| u.split(['?', '#']).next().unwrap_or("").rsplit('/').next().unwrap_or("").to_string();
    let stem = |u: &str| file(u).rsplit_once('.').map_or(file(u), |(s, _)| s.to_string());
    // A page too big to read whole (over 8 MB) is a file.
    let Some((page, last)) = page_of(url) else { return Ok(named(vec![vec![url.into()]], &stem(url))) };
    let origin = last.splitn(4, '/').take(3).collect::<Vec<_>>().join("/");
    let absolute = |u: String| if u.starts_with("//") { format!("https:{u}") } else if u.starts_with('/') { format!("{origin}{u}") } else { u };
    if let Some(moving) = og_video(&page).map(absolute) {
        return Ok(named(vec![vec![moving.clone()]], &stem(&moving)));
    }
    if is_video_page(&page) {
        return Err("nothing".into());
    }
    let Some(shown) = og_image(&page).map(absolute) else { return Ok(named(vec![vec![last.clone()]], &stem(&last))) };
    if !shown.starts_with("http") || site_art(&file(&shown)) {
        return Err("nothing".into());
    }
    Ok(named(vec![upgrade(&shown).into_iter().chain([shown.clone()]).collect()], &stem(&shown)))
}

/// Saves a link. `cookies` is the user's sign-in for the link's site, a Netscape cookies.txt file
/// (the format yt-dlp reads), or None.
pub fn save(app: &AppHandle, url: &str, dir: &Path, cookies: Option<&Path>) -> Result<Vec<PathBuf>, String> {
    unsigned_retry(cookies, || save_link(app, url, dir))
}

/// `run` signed in with `cookies`, then without when that fails, as unsigned_retry in lodi.py: a
/// sign-in a site no longer takes can make even a public post fail (Instagram's reader breaks on it).
/// Answers "login" when the post needs one.
fn unsigned_retry<T>(cookies: Option<&Path>, run: impl Fn() -> Result<T, String>) -> Result<T, String> {
    match signed_in(cookies, &run) {
        Err(e) if cookies.is_some() && e != "offline" => match run() {
            Err(again) if again != "login" => Err(e),
            again => again,
        },
        first => first,
    }
}

fn save_link(app: &AppHandle, url: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    let url = unwrap(url.trim());
    let url = url.as_str();
    if !(url.starts_with("https://") || url.starts_with("http://")) || url.contains(char::is_whitespace) {
        return Err("nothing".into());
    }
    crate::writable(dir)?;
    progress(app, "reading", None);
    let url = resolve(url);
    let url = url.as_str();
    if let Some(story) = story(url) {
        return save_items(app, &story_items(story)?, url, dir);
    }
    match site_items(url) {
        Some(items) if items.is_empty() => return Err("nothing".into()), // a post with text only
        Some(items) => return save_items(app, &items, url, dir),
        None => {}
    }
    let exe = ensure_ytdlp(app)?;
    match video(app, &exe, url, dir) {
        Err(code) if code == "nothing" => save_items(app, &page_items(url)?, url, dir),
        r => r,
    }
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

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

    #[test]
    fn reddit_reader() {
        use super::{reddit_links, reddit_media, reddit_original, reddit_post, reddit_video};
        assert_eq!(reddit_post("https://www.reddit.com/r/aww/comments/90bu6w/heat_index/?utm_source=share"), Some((Some("aww"), "90bu6w")));
        assert_eq!(reddit_post("https://old.reddit.com/comments/90bu6w"), Some((None, "90bu6w")));
        assert_eq!(reddit_post("https://www.reddit.com/gallery/1xb6c3p"), Some((None, "1xb6c3p")));
        assert_eq!(reddit_post("https://redd.it/1xambj5"), Some((None, "1xambj5")));
        assert_eq!(reddit_post("https://www.reddit.com/r/aww/"), None);
        assert_eq!(reddit_post("https://notreddit.com/comments/90bu6w"), None);
        assert_eq!(reddit_video("https://v.redd.it/gyh95hiqc0b11"), Some("gyh95hiqc0b11"));
        assert_eq!(reddit_video("https://www.reddit.com/r/aww/comments/90bu6w/"), None);

        assert_eq!(reddit_original("https://preview.redd.it/tiny-room-v0-fatco1py8pwh1.jpeg?width=640&amp;crop=smart").as_deref(), Some("https://i.redd.it/fatco1py8pwh1.jpeg"));
        assert_eq!(reddit_original("https://preview.redd.it/abc123.png?width=640").as_deref(), Some("https://i.redd.it/abc123.png"));
        assert_eq!(reddit_original("https://preview.redd.it/no-id.jpeg"), None);

        let video = r#"<shreddit-player-2 packaged-media-json="{&quot;playbackMp4s&quot;:{&quot;permutations&quot;:[{&quot;source&quot;:{&quot;dimensions&quot;:{&quot;height&quot;:1080},&quot;url&quot;:&quot;https://packaged-media.redd.it/v/1080.mp4?a=1&amp;b=2&quot;}},{&quot;source&quot;:{&quot;dimensions&quot;:{&quot;height&quot;:480},&quot;url&quot;:&quot;https://packaged-media.redd.it/v/480.mp4&quot;}}]}}">"#;
        assert_eq!(reddit_media(video), ["https://packaged-media.redd.it/v/1080.mp4?a=1&b=2"]);
        let gallery = r#"<img src="https://preview.redd.it/icon-v0-zz1.png"><gallery-carousel><img src="https://preview.redd.it/t-v0-b2.jpeg?width=640"><img srcset="https://preview.redd.it/t-v0-a1.jpeg?width=108 108w"><img src="https://preview.redd.it/t-v0-b2.jpeg?width=1080"></gallery-carousel>"#;
        assert_eq!(reddit_media(gallery), ["https://i.redd.it/b2.jpeg", "https://i.redd.it/a1.jpeg"]);
        // A crosspost links to the post it shares, and a feed to its post with the subreddit.
        let crosspost = r#"<a href="https://www.reddit.com/r/pics/comments/1xapc2a/porto/"></a><div data="{&quot;url&quot;:&quot;https://www.reddit.com/gallery/1x9anef&quot;}">"#;
        assert_eq!(reddit_links(crosspost).find(|(_, id)| *id != "1xapc2a"), Some((None, "1x9anef")));
        assert_eq!(reddit_links(r#"<link href="https://www.reddit.com/r/portugal/comments/1x9anef/protestos/phl66l4/"/>"#).next(), Some((Some("portugal"), "1x9anef")));
        // An Imgur GIF's post: Reddit's MP4 of it, never the poster, a still.
        let external = r#"<shreddit-player src="https://external-preview.redd.it/73ev.gif?width=500&amp;format=mp4&amp;s=d4" gif poster="https://external-preview.redd.it/t-v0-73ev.gif?format=png8&amp;s=8d">"#;
        assert_eq!(reddit_media(external), ["https://external-preview.redd.it/73ev.gif?width=500&format=mp4&s=d4"]);
        assert!(reddit_media(r#"<shreddit-player src="https://v.redd.it/x/HLSPlaylist.m3u8">"#).is_empty());
    }

    #[test]
    fn files_are_named_by_their_bytes() {
        use super::{file_ext, settle, sniff};
        let (mp4, gif) = (b"\x00\x00\x00\x20ftypisom\x00\x00\x02\x00isomiso2avc1mp41".as_slice(), b"GIF89a\x01\x00\x01\x00".as_slice());
        let heads: [&[u8]; 10] = [b"\xff\xd8\xff\xe0", b"\x89PNG\r\n\x1a\n", gif, b"RIFF\x1e\xc6\x06\x00WEBPVP8X", mp4, b"\x00\x00\x00\x1cftypavif\x00\x00\x00\x00avifmif1",
            b"\x00\x00\x00\x18ftypheic\x00\x00\x00\x00mif1heic", b"\x00\x00\x00\x14ftypqt  \x00\x00\x02\x00", b"\x1a\x45\xdf\xa3\x9fB\x86\x81\x01B\xf7\x81\x01B\x82\x84webm", b"<!DOCTYPE html>"];
        let got: Vec<_> = heads.iter().map(|h| sniff(h)).collect();
        assert_eq!(got, [Some("jpg"), Some("png"), Some("gif"), Some("webp"), Some("mp4"), Some("avif"), Some("heic"), Some("mov"), Some("webm"), None]);
        // A video sent as octet-stream, a GIF under a JPEG's type, a web page sent as a video (Giphy,
        // when asked like a browser asks for a page).
        assert_eq!((file_ext(mp4, "application/octet-stream"), file_ext(mp4, "audio/mp4")), (Some("mp4"), Some("m4a")));
        assert_eq!((file_ext(gif, "image/jpeg"), file_ext(b"\n<!doctype html>", "video/mp4")), (Some("gif"), None));
        assert_eq!((file_ext(b"\x00\x00\x01\xba", "video/quicktime"), file_ext(b"\x00\x00\x01\xba", "binary/octet-stream")), (Some("mov"), None));

        let dir = std::env::temp_dir().join(format!("lodisave-settle-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        for (name, data) in [("page.mp4", b"\n<!doctype html>".as_slice()), ("moving.jpg", gif), ("a.m4a", mp4)] {
            std::fs::write(dir.join(name), data).unwrap();
        }
        assert_eq!(settle(dir.join("moving.jpg")), Ok(dir.join("moving.gif")));
        assert_eq!(settle(dir.join("a.m4a")), Ok(dir.join("a.m4a")));
        assert_eq!(settle(dir.join("page.mp4")), Err("nothing".into()));
        assert!(!dir.join("page.mp4").exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn videos_and_gifs_are_not_stills() {
        let giphy = r#"<meta property="og:image" content="https://m.test/giphy.webp"><meta property="og:video" content="https://m.test/giphy.mp4"><meta property="og:video:secure_url" content="https://m.test/s.mp4"><meta property="og:type" content="video.other">"#;
        assert_eq!(super::og_video(giphy).as_deref(), Some("https://m.test/giphy.mp4"));
        assert!(super::is_video_page(giphy) && super::is_video_page(r#"<meta content="video.other" property="og:type">"#));
        assert_eq!(super::og_video(r#"<meta class="dynamic" name="twitter:player:stream" content="https://m.test/t.mp4">"#).as_deref(), Some("https://m.test/t.mp4"));
        assert!(!super::is_video_page(r#"<meta property="og:type" content="article"><meta property="og:image" content="https://m.test/a.jpg">"#));

        // A Threads GIF post has no picture of its own, only Giphy's GIF and WebP; the post's own page
        // shows visitors a stand-in, which is no save.
        let gif = |url: &str, webp: Value| json!({ "code": "DJcW", "media_type": 19, "image_versions2": { "candidates": [] }, "video_versions": null,
            "giphy_media_info": { "images": { "fixed_height": { "height": 200, "width": 327, "url": url, "webp": webp } } } });
        let real = gif("https://media4.giphy.com/media/v1.x/U1Y/200.gif", json!("https://media4.giphy.com/media/v1.x/U1Y/200.webp"));
        let page = format!(r#"<script type="application/json">{}</script>"#, json!({ "x": [real] }));
        assert_eq!(super::meta_items(&page, "DJcW").unwrap()[0].srcs, ["https://media4.giphy.com/media/v1.x/U1Y/200.gif", "https://media4.giphy.com/media/v1.x/U1Y/200.webp"]);
        let stand_in = gif("https://static.cdninstagram.com/rsrc.php/v4/yr/r/xg.gif", Value::Null);
        assert!(super::meta_media(&[&stand_in]).is_empty());
        assert!(super::giphy("https://i.giphy.com/x.webp") && super::giphy("https://media.giphy.com/media/x/giphy.gif") && !super::giphy("https://evil.test/a.giphy.com/x.gif"));
    }

    #[test]
    fn stories_need_a_sign_in() {
        use super::{story, story_items};
        let s = |a: Option<&str>, b: Option<&str>, c: Option<&str>| Some((a.map(String::from), b.map(String::from), c.map(String::from)));
        assert_eq!(story("https://www.instagram.com/stories/nasa/3170000000000000000?utm_source=ig_story_item_share"), s(Some("nasa"), None, Some("3170000000000000000")));
        assert_eq!(story("https://www.instagram.com/stories/nasa.gov/"), s(Some("nasa.gov"), None, None));
        assert_eq!(story("https://www.instagram.com/stories/highlights/17990651084283579/"), s(None, Some("17990651084283579"), None));
        assert_eq!(story("https://www.instagram.com/s/aGlnaGxpZ2h0OjE3OTkwNjUxMDg0MjgzNTc?story_media_id=3171_528817151&igsh=x"), s(None, Some("1799065108428357"), Some("3171")));
        assert_eq!((story("https://www.instagram.com/s/bm90aGluZw"), story("https://www.instagram.com/p/BQ0e/")), (None, None));
        assert_eq!(super::meta_code("https://www.instagram.com/stories/tv/3170000000000000000/"), None);
        assert!(!super::short("https://www.instagram.com/stories/nasa/"));
        // No sign-in, or one whose cookies all expired: the panel offers to sign in.
        assert_eq!(super::signed_in(None, || story_items(story("https://www.instagram.com/stories/nasa/").unwrap())), Err("login".into()));
        assert_eq!(story_items(story("https://www.instagram.com/stories/highlights/").unwrap()), Err("nothing".into()));
    }

    #[test]
    fn cookies_reach_curl_and_ytdlp() {
        use std::path::Path;
        let file = std::env::temp_dir().join(format!("lodisave-cookies-{}.txt", std::process::id()));
        std::fs::write(&file, "# Netscape HTTP Cookie File\n").unwrap();
        let args = |c: std::process::Command| c.get_args().map(|a| a.to_string_lossy().into_owned()).collect::<Vec<_>>();
        let passes = |a: Vec<String>, flag: &str| a.windows(2).any(|w| w[0] == flag && Path::new(&w[1]) == file);
        super::signed_in(Some(&file), || {
            assert!(passes(args(super::curl(&[])), "-b"));
            assert!(passes(args(super::base(Path::new("yt-dlp.exe"))), "--cookies"));
        });
        assert!(!args(super::curl(&[])).contains(&"-b".into()) && !args(super::base(Path::new("yt-dlp.exe"))).contains(&"--cookies".into()));
        // A sign-in the site no longer takes: the save runs again without it, and asks for a new one
        // when the post needs it.
        let run = |with: Result<&str, &str>, without: Result<&str, &str>| {
            super::unsigned_retry(Some(&file), || if super::COOKIES.with_borrow(Option::is_some) { with } else { without }.map(str::to_string).map_err(str::to_string))
        };
        assert_eq!(run(Err("broken"), Ok("saved")), Ok("saved".into()));
        assert_eq!(run(Err("broken"), Err("login")), Err("login".into()));
        assert_eq!(run(Err("refused"), Err("nothing")), Err("refused".into()));
        assert_eq!(run(Err("offline"), Ok("saved")), Err("offline".into()));
        // A file that's gone counts as no sign-in.
        std::fs::remove_file(&file).unwrap();
        super::signed_in(Some(&file), || assert!(!args(super::curl(&[])).contains(&"-b".into())));
    }
}
