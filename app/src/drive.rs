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
}

pub struct Drive {
    file: PathBuf,
    saved: Mutex<Saved>,
    access: Mutex<Option<(String, Instant)>>,
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
    let mut c = crate::links::command("curl.exe");
    c.args(["-sS", "-K", "-", "-D", "-", "--connect-timeout", "30", "--speed-time", "60", "--speed-limit", "1"]).args(args);
    let mut child = c.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().map_err(|_| "offline".to_string())?;
    let _ = child.stdin.take().unwrap().write_all(config.as_bytes());
    let mut out = String::new();
    let _ = child.stdout.take().unwrap().read_to_string(&mut out);
    if !child.wait().is_ok_and(|s| s.success()) {
        return Err("offline".into());
    }
    // Headers end at the last blank line before the body; a 100 Continue adds a block before them.
    let mut rest = out.as_str();
    let mut head = "";
    while rest.starts_with("HTTP/") {
        let Some((h, b)) = rest.split_once("\r\n\r\n") else { break };
        head = h;
        rest = b;
    }
    let status = head.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    Ok((status, head.to_string(), rest.to_string()))
}

fn json(body: &str) -> Value {
    serde_json::from_str(body).unwrap_or(Value::Null)
}

impl Drive {
    pub fn open(data: &Path) -> Drive {
        let file = data.join("drive.json");
        let saved = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Drive { file, saved: Mutex::new(saved), access: Mutex::new(None), busy: AtomicBool::new(false) }
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
        });
    }

    fn refresh(&self) -> Option<String> {
        let sealed = unhex(self.saved.lock().unwrap().token.as_deref()?)?;
        String::from_utf8(crate::accounts::dpapi(&sealed, false).ok()?).ok()
    }

    /// An access token, refreshed when it is about to run out. "login" when Google no longer takes
    /// the sign-in.
    fn token(&self) -> Result<String, String> {
        if let Some((t, until)) = self.access.lock().unwrap().clone()
            && Instant::now() < until
        {
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
        let size = path.metadata().map_err(|_| "gone".to_string())?.len();
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let meta = serde_json::json!({ "name": name, "parents": [folder] }).to_string();
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
}
