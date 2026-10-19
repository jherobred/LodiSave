//! Saving a copied link. Videos go through yt-dlp, which LodiSave fetches on first use. Pages with
//! no video fall back to their og:image. Progress reaches the panel as `job://progress` events.
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
    if ["login", "log in", "sign in", "cookies", "private", "authentication"].iter().any(|k| low.contains(k)) {
        "login".into()
    } else if ["unsupported url", "no video", "no media", "there's no video", "is not a valid url"].iter().any(|k| low.contains(k)) {
        "nothing".into()
    } else if ["getaddrinfo", "timed out", "unable to download webpage", "connection"].iter().any(|k| low.contains(k)) {
        "offline".into()
    } else if msg.is_empty() {
        "nothing".into()
    } else {
        msg.chars().take(300).collect()
    }
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
    let out = base(exe).arg("-J").arg("--").arg(url).output().map_err(|_| "helper".to_string())?;
    if !out.status.success() {
        return Err(error_code(&String::from_utf8_lossy(&out.stderr)));
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

fn image(app: &AppHandle, url: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    progress(app, "reading", None);
    let page = curl(&["--max-filesize", "8000000", "--"]).arg(url).output().map_err(|_| "offline".to_string())?;
    if !page.status.success() {
        return Err("offline".into());
    }
    let shown = og_image(&String::from_utf8_lossy(&page.stdout)).ok_or("nothing")?;
    let shown = if shown.starts_with("//") { format!("https:{shown}") } else { shown };
    if !shown.starts_with("http") {
        return Err("nothing".into());
    }
    let name = shown.split(['?', '#']).next().unwrap_or("").rsplit('/').next().unwrap_or("");
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
    Err("nothing".into())
}

pub fn save(app: &AppHandle, url: &str, dir: &Path) -> Result<Vec<PathBuf>, String> {
    let url = url.trim();
    if !(url.starts_with("https://") || url.starts_with("http://")) || url.contains(char::is_whitespace) {
        return Err("nothing".into());
    }
    crate::writable(dir)?;
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
}
