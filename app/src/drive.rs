//! Google Drive backup, as on Android: picked files, and on its own the photos and videos older than
//! some days or a date, into a LodiSave folder in the user's Drive. A backed-up file can leave the PC
//! (to the Recycle Bin) some days later, when it is still as Drive got it.
//!
//! Sign-in is Google's own page in the user's browser (OAuth for installed apps): a one-time listener
//! on 127.0.0.1 takes the answer, and a PKCE verifier ties it to this request. The drive.file scope
//! lets LodiSave see only the files it made. The refresh token is sealed for this Windows user with
//! DPAPI. curl reads the token from stdin, so it never shows on a command line.
//!
//! The OAuth client ("Desktop app" type) is built in from LODISAVE_GOOGLE_CLIENT_ID and
//! LODISAVE_GOOGLE_CLIENT_SECRET; Google says an installed app's secret isn't secret. A build
//! without them shows Drive as not set up.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};
use tauri_plugin_opener::OpenerExt;

const CLIENT_ID: Option<&str> = option_env!("LODISAVE_GOOGLE_CLIENT_ID");
const CLIENT_SECRET: Option<&str> = option_env!("LODISAVE_GOOGLE_CLIENT_SECRET");
const SCOPE: &str = "https://www.googleapis.com/auth/drive.file";
const FILES: &str = "https://www.googleapis.com/drive/v3/files";
const UPLOAD: &str = "https://www.googleapis.com/upload/drive/v3/files";
const DAY: u64 = 86_400;

/// Settings › Google Drive: "off", "days" (older than `days`) or "date" (before `before`, Unix
/// seconds); `free` is the days after backup when a file leaves the PC, or -1 to keep it.
#[derive(Clone, Serialize, Deserialize)]
pub struct Rules {
    pub rule: String,
    pub days: u32,
    pub before: u64,
    pub free: i32,
}

impl Default for Rules {
    fn default() -> Rules {
        Rules { rule: "off".into(), days: 30, before: 0, free: -1 }
    }
}

#[derive(Clone, Serialize, Deserialize)]
struct Done {
    at: u64,
    size: u64,
    id: String,
}

#[derive(Default, Serialize, Deserialize)]
struct Saved {
    folder: Option<String>,
    /// The refresh token, sealed with DPAPI, as hex.
    token: Option<String>,
    /// Backed-up files, keyed by their lowercased path.
    done: BTreeMap<String, Done>,
    queue: Vec<PathBuf>,
    rules: Rules,
    last: u64,
    /// What Drive listed last time, so the gallery shows it offline too.
    #[serde(default)]
    cloud: Vec<Cloud>,
}

pub struct Drive {
    file: PathBuf,
    saved: Mutex<Saved>,
    access: Mutex<Option<(String, Instant)>>,
    /// Held while a token is fetched, so a grid of thumbnails asks Google once.
    renew: Mutex<()>,
    busy: AtomicBool,
}

#[derive(Serialize)]
pub struct Status {
    ready: bool,
    connected: bool,
    backed: usize,
    waiting: usize,
    busy: bool,
    rules: Rules,
}

/// A file the backup may cover: one the gallery or the library lists.
pub struct Candidate {
    pub path: PathBuf,
    pub kind: &'static str,
    pub modified_ms: u64,
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn key(p: &Path) -> String {
    p.to_string_lossy().to_lowercase()
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn unhex(s: &str) -> Option<Vec<u8>> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok()).collect()
}

pub fn random(n: usize) -> String {
    hex(&random_bytes(n))
}

pub fn random_bytes(n: usize) -> Vec<u8> {
    use windows::Win32::Security::Cryptography::{BCRYPT_USE_SYSTEM_PREFERRED_RNG, BCryptGenRandom};
    let mut b = vec![0u8; n];
    let _ = unsafe { BCryptGenRandom(None, &mut b, BCRYPT_USE_SYSTEM_PREFERRED_RNG) };
    b
}

fn enc(s: &str) -> String {
    s.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-._~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") }).collect()
}

/// A curl config line: curl reads `-K -` from stdin, so tokens stay off the command line.
fn line(name: &str, value: &str) -> String {
    format!("{name} = \"{}\"\n", value.replace('\\', "\\\\").replace('"', "\\\""))
}

/// Runs curl with `config` on stdin and `args`. Returns the HTTP status, the response headers and
/// the body.
fn curl(config: &str, args: &[&std::ffi::OsStr]) -> Result<(u16, String, String), String> {
    let (status, head, body) = curl_bytes(config, args)?;
    Ok((status, head, String::from_utf8_lossy(&body).into_owned()))
}

/// As `curl`, with the body as bytes, for pictures and parts of videos.
fn curl_bytes(config: &str, args: &[&std::ffi::OsStr]) -> Result<(u16, String, Vec<u8>), String> {
    let mut c = crate::links::command("curl.exe");
    c.args(["-sS", "-K", "-", "-D", "-", "--connect-timeout", "30", "--speed-time", "60", "--speed-limit", "1"]).args(args);
    let mut child = c.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().map_err(|_| "offline".to_string())?;
    let _ = child.stdin.take().unwrap().write_all(config.as_bytes());
    let mut out = Vec::new();
    let _ = child.stdout.take().unwrap().read_to_end(&mut out);
    if !child.wait().is_ok_and(|s| s.success()) {
        return Err("offline".into());
    }
    // Headers end at the last blank line before the body; a 100 Continue adds a block before them.
    let mut rest = &out[..];
    let mut head: &[u8] = b"";
    while rest.starts_with(b"HTTP/") {
        let Some(end) = rest.windows(4).position(|w| w == b"\r\n\r\n") else { break };
        head = &rest[..end];
        rest = &rest[end + 4..];
    }
    let head = String::from_utf8_lossy(head).into_owned();
    let status = head.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    Ok((status, head, rest.to_vec()))
}

/// Unix milliseconds as Drive writes times: 2024-05-06T07:08:09.010Z.
fn rfc3339(ms: u64) -> String {
    let (days, rest) = ((ms / 1000 / DAY) as i64, ms / 1000 % DAY);
    // Howard Hinnant's days-to-civil.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{:03}Z", rest / 3600, rest / 60 % 60, rest % 60, ms % 1000)
}

/// Drive's times back to Unix milliseconds. Drive always writes UTC with a Z.
fn parse_time(s: &str) -> Option<u64> {
    let n = |a: usize, b: usize| s.get(a..b)?.parse::<i64>().ok();
    let (y, m, d) = (n(0, 4)?, n(5, 7)?, n(8, 10)?);
    let (h, mi, sec) = (n(11, 13)?, n(14, 16)?, n(17, 19)?);
    let frac = s.get(19..).and_then(|f| f.strip_prefix('.')).map_or(0, |f| f.trim_end_matches('Z').get(..3).and_then(|x| x.parse().ok()).unwrap_or(0));
    // Howard Hinnant's civil-to-days.
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let days = era * 146_097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719_468;
    u64::try_from(((days * 86_400 + h * 3600 + mi * 60 + sec) * 1000) + frac).ok()
}

/// A photo, video or sound LodiSave put in the user's Drive, from this PC or the phone.
#[derive(Clone, Serialize, Deserialize)]
pub struct Cloud {
    pub id: String,
    pub name: String,
    pub mime: String,
    pub bytes: u64,
    /// Unix milliseconds: the file's own date when LodiSave sent it, else when Drive got it.
    pub modified: u64,
}

impl Cloud {
    fn from_json(v: &Value) -> Option<Cloud> {
        let mime = v["mimeType"].as_str()?;
        if !["image/", "video/", "audio/"].iter().any(|k| mime.starts_with(k)) {
            return None;
        }
        Some(Cloud {
            id: v["id"].as_str()?.into(),
            name: v["name"].as_str()?.into(),
            mime: mime.into(),
            bytes: v["size"].as_str().and_then(|s| s.parse().ok()).unwrap_or(0),
            modified: v["modifiedTime"].as_str().and_then(parse_time).unwrap_or(0),
        })
    }
}

/// The part of a file a `Range: bytes=a-b` header asks for, at most `most` bytes: (start, end), end
/// included. None when there is no such part.
fn part(range: &str, size: u64, most: u64) -> Option<(u64, u64)> {
    let (a, b) = range.trim().strip_prefix("bytes=")?.split(',').next()?.split_once('-')?;
    let (start, end) = match (a.trim(), b.trim()) {
        ("", n) => (size.saturating_sub(n.parse().ok()?), size.checked_sub(1)?),
        (a, "") => (a.parse().ok()?, size.checked_sub(1)?),
        (a, b) => (a.parse().ok()?, b.parse::<u64>().ok()?.min(size.checked_sub(1)?)),
    };
    (start <= end).then(|| (start, end.min(start + most - 1)))
}

fn json(body: &str) -> Value {
    serde_json::from_str(body).unwrap_or(Value::Null)
}

impl Drive {
    pub fn open(data: &Path) -> Drive {
        let file = data.join("drive.json");
        let saved = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Drive { file, saved: Mutex::new(saved), access: Mutex::new(None), renew: Mutex::new(()), busy: AtomicBool::new(false) }
    }

    fn keep(&self, s: &Saved) {
        let tmp = self.file.with_extension("tmp");
        if std::fs::write(&tmp, serde_json::to_vec(s).unwrap_or_default()).is_ok() {
            let _ = std::fs::rename(&tmp, &self.file);
        }
    }

    fn edit<T>(&self, f: impl FnOnce(&mut Saved) -> T) -> T {
        let mut s = self.saved.lock().unwrap();
        let out = f(&mut s);
        self.keep(&s);
        out
    }

    pub fn status(&self) -> Status {
        let s = self.saved.lock().unwrap();
        Status {
            ready: CLIENT_ID.is_some() && CLIENT_SECRET.is_some(),
            connected: s.token.is_some(),
            backed: s.done.len(),
            waiting: s.queue.len(),
            busy: self.busy.load(Ordering::SeqCst),
            rules: s.rules.clone(),
        }
    }

    pub fn set_rules(&self, rules: Rules) {
        self.edit(|s| s.rules = rules);
    }

    /// Paths already backed up, for the gallery's badge.
    pub fn backed(&self) -> Vec<String> {
        self.saved.lock().unwrap().done.keys().cloned().collect()
    }

    pub fn queue(&self, paths: Vec<PathBuf>) {
        self.edit(|s| {
            for p in paths {
                if !s.done.contains_key(&key(&p)) && !s.queue.contains(&p) {
                    s.queue.push(p);
                }
            }
        });
    }

    /// Google's sign-in page in the browser, then the code it hands back to 127.0.0.1. Waits up to
    /// five minutes for the user.
    pub fn connect(&self, app: &AppHandle) -> Result<(), String> {
        let (Some(id), Some(secret)) = (CLIENT_ID, CLIENT_SECRET) else { return Err("nodrive".into()) };
        let listener = TcpListener::bind("127.0.0.1:0").map_err(|_| "offline".to_string())?;
        let redirect = format!("http://127.0.0.1:{}", listener.local_addr().map_err(|e| e.to_string())?.port());
        let (verifier, state) = (random(48), random(16));
        let url = format!(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id={}&redirect_uri={}&response_type=code&scope={}&code_challenge={verifier}&code_challenge_method=plain&access_type=offline&prompt=consent&state={state}",
            enc(id),
            enc(&redirect),
            enc(SCOPE)
        );
        app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let end = Instant::now() + Duration::from_secs(300);
        let code = loop {
            if Instant::now() > end {
                return Err("timeout".into());
            }
            let Ok((mut stream, _)) = listener.accept() else {
                std::thread::sleep(Duration::from_millis(200));
                continue;
            };
            let _ = stream.set_nonblocking(false);
            let mut first = String::new();
            let _ = BufReader::new(&stream).read_line(&mut first);
            let query = first.split_whitespace().nth(1).and_then(|p| p.split_once('?')).map_or("", |(_, q)| q).to_string();
            let get = |k: &str| query.split('&').find_map(|kv| kv.strip_prefix(&format!("{k}="))).map(str::to_string);
            // Browsers ask for a favicon too; only the answer with our state counts.
            if get("state").as_deref() != Some(state.as_str()) {
                let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                continue;
            }
            let ok = get("code");
            let page = if ok.is_some() { "LodiSave is connected to Google Drive. You can close this tab." } else { "Google Drive wasn't connected. You can close this tab." };
            let html = format!("<!doctype html><meta charset=utf-8><title>LodiSave</title><body style=\"font:16px system-ui;padding:40px\">{page}</body>");
            let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{html}", html.len());
            break ok.ok_or("denied")?;
        };
        let config = [
            line("data-urlencode", &format!("code={code}")),
            line("data-urlencode", &format!("client_id={id}")),
            line("data-urlencode", &format!("client_secret={secret}")),
            line("data-urlencode", &format!("code_verifier={verifier}")),
            line("data-urlencode", "grant_type=authorization_code"),
            line("data-urlencode", &format!("redirect_uri={redirect}")),
        ]
        .concat();
        let (_, _, body) = curl(&config, &["https://oauth2.googleapis.com/token".as_ref()])?;
        let answer = json(&body);
        let refresh = answer["refresh_token"].as_str().ok_or("denied")?;
        let sealed = crate::accounts::dpapi(refresh.as_bytes(), true)?;
        if let Some(access) = answer["access_token"].as_str() {
            *self.access.lock().unwrap() = Some((access.into(), Instant::now() + Duration::from_secs(answer["expires_in"].as_u64().unwrap_or(3600).saturating_sub(60))));
        }
        self.edit(|s| s.token = Some(hex(&sealed)));
        Ok(())
    }

    /// Forgets the sign-in here and asks Google to end it.
    pub fn disconnect(&self) {
        if let Some(refresh) = self.refresh() {
            let _ = curl(&line("data-urlencode", &format!("token={refresh}")), &["https://oauth2.googleapis.com/revoke".as_ref()]);
        }
        *self.access.lock().unwrap() = None;
        self.edit(|s| {
            s.token = None;
            s.folder = None;
            s.cloud.clear();
        });
    }

    fn refresh(&self) -> Option<String> {
        let sealed = unhex(self.saved.lock().unwrap().token.as_deref()?)?;
        String::from_utf8(crate::accounts::dpapi(&sealed, false).ok()?).ok()
    }

    /// An access token, refreshed when it is about to run out. "login" when Google no longer takes
    /// the sign-in.
    fn token(&self) -> Result<String, String> {
        let fresh = || self.access.lock().unwrap().clone().filter(|(_, until)| Instant::now() < *until).map(|(t, _)| t);
        if let Some(t) = fresh() {
            return Ok(t);
        }
        let _one = self.renew.lock().unwrap();
        if let Some(t) = fresh() {
            return Ok(t);
        }
        let (Some(id), Some(secret)) = (CLIENT_ID, CLIENT_SECRET) else { return Err("nodrive".into()) };
        let refresh = self.refresh().ok_or("login")?;
        let config = [
            line("data-urlencode", &format!("client_id={id}")),
            line("data-urlencode", &format!("client_secret={secret}")),
            line("data-urlencode", &format!("refresh_token={refresh}")),
            line("data-urlencode", "grant_type=refresh_token"),
        ]
        .concat();
        let (status, _, body) = curl(&config, &["https://oauth2.googleapis.com/token".as_ref()])?;
        let answer = json(&body);
        let access = answer["access_token"].as_str().ok_or(if status == 400 { "login" } else { "offline" })?.to_string();
        let life = answer["expires_in"].as_u64().unwrap_or(3600).saturating_sub(60);
        *self.access.lock().unwrap() = Some((access.clone(), Instant::now() + Duration::from_secs(life)));
        Ok(access)
    }

    fn auth(token: &str) -> String {
        line("header", &format!("Authorization: Bearer {token}"))
    }

    /// LodiSave's folder in the user's Drive, made the first time or when it was deleted.
    fn folder(&self, token: &str, fresh: bool) -> Result<String, String> {
        if !fresh && let Some(f) = self.saved.lock().unwrap().folder.clone() {
            return Ok(f);
        }
        let meta = serde_json::json!({ "name": "LodiSave", "mimeType": "application/vnd.google-apps.folder" }).to_string();
        let config = Drive::auth(token) + &line("header", "Content-Type: application/json; charset=UTF-8") + &line("data", &meta);
        let (_, _, body) = curl(&config, &[format!("{FILES}?fields=id").as_ref()])?;
        let id = json(&body)["id"].as_str().ok_or("offline")?.to_string();
        self.edit(|s| s.folder = Some(id.clone()));
        Ok(id)
    }

    /// Uploads one file whole in a resumable session, and keeps Drive's id once Drive confirms the
    /// size. Err(404) when the folder is gone.
    fn upload(&self, token: &str, folder: &str, path: &Path) -> Result<(), String> {
        let m = path.metadata().map_err(|_| "gone".to_string())?;
        let size = m.len();
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let mut meta = serde_json::json!({ "name": name, "parents": [folder] });
        // The file's own date, so it sorts where it did once it's only on Drive.
        if let Some(ms) = m.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()) {
            meta["modifiedTime"] = rfc3339(ms.as_millis() as u64).into();
        }
        let meta = meta.to_string();
        let config = Drive::auth(token)
            + &line("header", "Content-Type: application/json; charset=UTF-8")
            + &line("header", &format!("X-Upload-Content-Length: {size}"))
            + &line("data", &meta);
        let (status, head, _) = curl(&config, &[format!("{UPLOAD}?uploadType=resumable&fields=id,size").as_ref()])?;
        if status == 404 {
            return Err("404".into());
        }
        let session = head
            .lines()
            .find_map(|l| l.split_once(':').filter(|(k, _)| k.eq_ignore_ascii_case("location")).map(|(_, v)| v.trim().to_string()))
            .ok_or("offline")?;
        let (_, _, body) = curl(&Drive::auth(token), &["-X".as_ref(), "PUT".as_ref(), "-T".as_ref(), path.as_os_str(), session.as_ref()])?;
        let done = json(&body);
        if done["size"].as_str().and_then(|s| s.parse::<u64>().ok()) != Some(size) {
            return Err("size".into());
        }
        let id = done["id"].as_str().unwrap_or("").to_string();
        self.edit(|s| {
            s.done.insert(key(path), Done { at: now(), size, id });
            s.queue.retain(|q| q != path);
        });
        Ok(())
    }

    /// Backs up what's waiting and what the rule covers, then frees space. `candidates` are the
    /// files the gallery and the library list, so nothing else is ever sent or removed. Progress goes
    /// to "drive://progress" as {done, total}.
    pub fn run(&self, app: &AppHandle, candidates: &[Candidate]) -> Result<usize, String> {
        if self.busy.swap(true, Ordering::SeqCst) {
            return Err("busy".into());
        }
        let result = self.run_inner(app, candidates);
        self.busy.store(false, Ordering::SeqCst);
        let _ = app.emit("drive://progress", serde_json::json!({ "done": 0, "total": 0, "end": true }));
        result
    }

    fn run_inner(&self, app: &AppHandle, candidates: &[Candidate]) -> Result<usize, String> {
        let token = self.token()?;
        let (rules, queue, done) = {
            let s = self.saved.lock().unwrap();
            (s.rules.clone(), s.queue.clone(), s.done.clone())
        };
        let listed: std::collections::HashSet<String> = candidates.iter().map(|c| key(&c.path)).collect();
        let cutoff = match rules.rule.as_str() {
            "days" => Some(now().saturating_sub(rules.days as u64 * DAY)),
            "date" => Some(rules.before),
            _ => None,
        };
        let mut todo: Vec<PathBuf> = queue.into_iter().filter(|p| listed.contains(&key(p))).collect();
        if let Some(cut) = cutoff {
            todo.extend(candidates.iter().filter(|c| c.kind != "audio" && c.modified_ms / 1000 < cut).map(|c| c.path.clone()));
        }
        let mut seen = std::collections::HashSet::new();
        todo.retain(|p| !done.contains_key(&key(p)) && seen.insert(key(p)));
        // Files that left the gallery's folders stop waiting.
        self.edit(|s| s.queue.retain(|p| listed.contains(&key(p))));
        let mut folder = self.folder(&token, false)?;
        let (total, mut sent) = (todo.len(), 0);
        for (i, path) in todo.iter().enumerate() {
            let _ = app.emit("drive://progress", serde_json::json!({ "done": i, "total": total }));
            let token = self.token()?;
            let mut r = self.upload(&token, &folder, path);
            if r.as_ref().is_err_and(|e| e == "404") {
                folder = self.folder(&token, true)?;
                r = self.upload(&token, &folder, path);
            }
            if r.is_ok() {
                sent += 1;
            }
        }
        self.free(&rules, &listed);
        self.edit(|s| s.last = now());
        // What just left the PC now shows from Drive.
        let _ = self.refresh_cloud();
        Ok(sent)
    }

    /// Backed-up files past the days set go to the Recycle Bin, when they're still the size Drive
    /// got and the gallery or library still lists them.
    fn free(&self, rules: &Rules, listed: &std::collections::HashSet<String>) {
        if rules.free < 0 {
            return;
        }
        let due: Vec<PathBuf> = self
            .saved
            .lock()
            .unwrap()
            .done
            .iter()
            .filter(|(k, d)| listed.contains(*k) && d.at + rules.free as u64 * DAY <= now())
            .map(|(k, d)| (PathBuf::from(k), d.size))
            .filter(|(p, size)| p.metadata().is_ok_and(|m| m.len() == *size))
            .map(|(p, _)| p)
            .collect();
        if !due.is_empty() && crate::library::trash(&due).is_ok() {
            self.edit(|s| due.iter().for_each(|p| drop(s.done.remove(&key(p)))));
        }
    }

    /// Asks Drive for every photo, video and sound LodiSave put there (the drive.file scope shows
    /// nothing else) and keeps the list. Returns how many.
    pub fn refresh_cloud(&self) -> Result<usize, String> {
        let q = "trashed=false and (mimeType contains 'image/' or mimeType contains 'video/' or mimeType contains 'audio/')";
        let fields = "nextPageToken,files(id,name,mimeType,size,modifiedTime)";
        let (mut found, mut page) = (Vec::new(), String::new());
        loop {
            let token = self.token()?;
            let mut url = format!("{FILES}?pageSize=1000&spaces=drive&q={}&fields={}", enc(q), enc(fields));
            if !page.is_empty() {
                url += &format!("&pageToken={}", enc(&page));
            }
            let (status, _, body) = curl(&Drive::auth(&token), &[url.as_ref()])?;
            if status != 200 {
                return Err(if status == 401 { "login" } else { "offline" }.into());
            }
            let answer = json(&body);
            found.extend(answer["files"].as_array().into_iter().flatten().filter_map(Cloud::from_json));
            match answer["nextPageToken"].as_str() {
                Some(next) => page = next.into(),
                None => break,
            }
        }
        let n = found.len();
        self.edit(|s| s.cloud = found);
        Ok(n)
    }

    /// The listed files that aren't on this PC: each with the path it was backed up from, when it was
    /// sent from here, and that path no longer has it.
    pub fn only_in_cloud(&self) -> Vec<(Cloud, Option<PathBuf>)> {
        let s = self.saved.lock().unwrap();
        let from: std::collections::HashMap<&str, &String> = s.done.iter().map(|(k, d)| (d.id.as_str(), k)).collect();
        s.cloud
            .iter()
            .filter_map(|c| {
                let local = from.get(c.id.as_str()).map(PathBuf::from);
                (!local.as_ref().is_some_and(|p| p.exists())).then(|| (c.clone(), local))
            })
            .collect()
    }

    fn listed(&self, id: &str) -> Option<Cloud> {
        self.saved.lock().unwrap().cloud.iter().find(|c| c.id == id).cloned()
    }

    /// Answers the gallery's cloud: address for a listed file: its thumbnail `px` wide, kept on disk
    /// once fetched, or the file itself, a part at a time when the viewer asks for a range, as a
    /// video does.
    pub fn serve(&self, id: &str, px: Option<u32>, range: Option<&str>) -> tauri::http::Response<Vec<u8>> {
        let no = |status: u16| tauri::http::Response::builder().status(status).body(Vec::new()).unwrap();
        let Some(c) = self.listed(id) else { return no(404) };
        let Ok(token) = self.token() else { return no(503) };
        let ok = tauri::http::Response::builder().header("Access-Control-Allow-Origin", "*");
        if let Some(px) = px {
            let dir = self.file.with_file_name("drive-thumbs");
            let cached = dir.join(format!("{}-{px}.jpg", c.id.replace(|ch: char| !ch.is_ascii_alphanumeric() && ch != '-' && ch != '_', "")));
            let png = std::fs::read(&cached).ok().or_else(|| {
                let (_, _, body) = curl(&Drive::auth(&token), &[format!("{FILES}/{}?fields=thumbnailLink", enc(&c.id)).as_ref()]).ok()?;
                let link = json(&body)["thumbnailLink"].as_str()?.to_string();
                // The token goes only to Google's own picture server.
                let host = link.strip_prefix("https://")?.split('/').next()?.to_string();
                if !host.ends_with(".googleusercontent.com") {
                    return None;
                }
                let link = match link.rsplit_once("=s") {
                    Some((head, n)) if n.chars().all(|ch| ch.is_ascii_digit()) => format!("{head}=s{px}"),
                    _ => link,
                };
                let (status, _, bytes) = curl_bytes(&Drive::auth(&token), &[link.as_ref()]).ok()?;
                (status == 200 && !bytes.is_empty()).then_some(())?;
                let _ = std::fs::create_dir_all(&dir).and_then(|_| std::fs::write(&cached, &bytes));
                Some(bytes)
            });
            return match png {
                Some(b) => ok.header("Content-Type", "image/jpeg").header("Cache-Control", "max-age=31536000, immutable").body(b).unwrap(),
                None => no(404),
            };
        }
        let url = format!("{FILES}/{}?alt=media", enc(&c.id));
        let ok = ok.header("Content-Type", c.mime.as_str()).header("Accept-Ranges", "bytes");
        match range.map(|r| part(r, c.bytes, 8 << 20)) {
            Some(None) => tauri::http::Response::builder().status(416).header("Content-Range", format!("bytes */{}", c.bytes)).body(Vec::new()).unwrap(),
            Some(Some((a, b))) => match curl_bytes(&Drive::auth(&token), &["-r".as_ref(), format!("{a}-{b}").as_ref(), url.as_ref()]) {
                Ok((206 | 200, _, body)) if !body.is_empty() => {
                    // Drive may answer with the whole file; then the part is cut here.
                    let body = if body.len() as u64 == c.bytes && b - a + 1 < c.bytes { body[a as usize..=b as usize].to_vec() } else { body };
                    let end = a + body.len() as u64 - 1;
                    ok.status(206).header("Content-Range", format!("bytes {a}-{end}/{}", c.bytes)).body(body).unwrap()
                }
                _ => no(502),
            },
            None => match curl_bytes(&Drive::auth(&token), &[url.as_ref()]) {
                Ok((200, _, body)) => ok.body(body).unwrap(),
                _ => no(502),
            },
        }
    }

    /// Brings a listed file back from Drive to `to`, or a new name beside it when that's taken, with
    /// its own date. It counts as backed up again, and the days before it may leave the PC start
    /// over. curl writes into LodiSave's own folder first, as ransomware protection may keep it out
    /// of Downloads and the like.
    pub fn download(&self, id: &str, to: &Path) -> Result<PathBuf, String> {
        let c = self.listed(id).ok_or("gone")?;
        let (dir, name) = (to.parent().ok_or("gone")?, to.file_name().ok_or("gone")?.to_string_lossy().into_owned());
        let (stem, ext) = crate::library::split(&name);
        let dest = crate::library::unique(dir, stem, ext);
        let tmp = self.file.with_file_name(format!("drive-get-{}.part", random(6)));
        let token = self.token()?;
        let got = curl(&Drive::auth(&token), &["-o".as_ref(), tmp.as_os_str(), format!("{FILES}/{}?alt=media", enc(&c.id)).as_ref()]);
        let size = tmp.metadata().map_or(0, |m| m.len());
        let placed = match got {
            Ok((200, _, _)) if c.bytes == 0 || size == c.bytes => {
                std::fs::rename(&tmp, &dest).or_else(|_| std::fs::copy(&tmp, &dest).map(drop)).map_err(|_| "blocked".to_string())
            }
            Ok((404, _, _)) => Err("gone".into()),
            Ok(_) | Err(_) => Err("offline".into()),
        };
        let _ = std::fs::remove_file(&tmp);
        placed?;
        if c.modified > 0 {
            let _ = std::fs::File::options().write(true).open(&dest).and_then(|f| f.set_modified(UNIX_EPOCH + Duration::from_millis(c.modified)));
        }
        self.edit(|s| {
            s.done.retain(|_, d| d.id != c.id);
            s.done.insert(key(&dest), Done { at: now(), size, id: c.id.clone() });
        });
        Ok(dest)
    }

    /// A rule or freeing space is on and the last backup was a day ago or more.
    pub fn due(&self) -> bool {
        let s = self.saved.lock().unwrap();
        s.token.is_some() && (s.rules.rule != "off" || s.rules.free >= 0) && now() >= s.last + DAY
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn helpers() {
        assert_eq!(super::unhex(&super::hex(b"\x00\xffab")).unwrap(), b"\x00\xffab");
        assert_eq!(super::enc("http://127.0.0.1:5"), "http%3A%2F%2F127.0.0.1%3A5");
        assert_eq!(super::line("header", "a \"b\""), "header = \"a \\\"b\\\"\"\n");
        assert_eq!(super::random(16).len(), 32);
    }

    #[test]
    fn times() {
        for ms in [0, 951_782_400_000, 1_709_164_800_123, 1_778_000_000_999] {
            assert_eq!(super::parse_time(&super::rfc3339(ms)), Some(ms));
        }
        assert_eq!(super::rfc3339(1_709_164_800_123), "2024-02-29T00:00:00.123Z");
        assert_eq!(super::parse_time("2026-11-16T01:02:03Z"), Some(1_794_790_923_000));
    }

    #[test]
    fn parts() {
        let mb = 1 << 20;
        assert_eq!(super::part("bytes=0-", 100, mb), Some((0, 99)));
        assert_eq!(super::part("bytes=0-", 100, 10), Some((0, 9)));
        assert_eq!(super::part("bytes=10-19", 100, mb), Some((10, 19)));
        assert_eq!(super::part("bytes=90-500", 100, mb), Some((90, 99)));
        assert_eq!(super::part("bytes=-30", 100, mb), Some((70, 99)));
        assert_eq!(super::part("bytes=100-", 100, mb), None);
        assert_eq!(super::part("items=0-1", 100, mb), None);
        assert_eq!(super::part("bytes=0-", 0, mb), None);
    }

    #[test]
    fn listing() {
        let v = serde_json::json!({ "id": "a1", "name": "x.mp4", "mimeType": "video/mp4", "size": "1234", "modifiedTime": "2024-02-29T00:00:00.123Z" });
        let c = super::Cloud::from_json(&v).unwrap();
        assert_eq!((c.bytes, c.modified, c.mime.as_str()), (1234, 1_709_164_800_123, "video/mp4"));
        assert!(super::Cloud::from_json(&serde_json::json!({ "id": "f", "name": "LodiSave", "mimeType": "application/vnd.google-apps.folder" })).is_none());
    }
}
