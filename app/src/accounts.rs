//! Signing in to sites, for posts only an account can see. The sign-in window shows the site's
//! own login page in a webview with its own profile, kept in memory only (InPrivate), with no
//! LodiSave script and no LodiSave commands (the app's capabilities only cover the panel), and
//! pages off the site open in the default browser. Beside it, LodiSave's own page says why this
//! is safe. Once the site sets its session cookie, LodiSave keeps the cookies that make up the
//! sign-in, encrypted by Windows (DPAPI) for this user, clears the window's profile and closes it.
//! A save gets them as a temporary cookies.txt holding only the cookies of the link's site,
//! deleted right after. Cookie values are never logged.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tauri::webview::{NewWindowResponse, WebviewBuilder};
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, WebviewUrl};
use tauri_plugin_opener::OpenerExt;

pub struct Site {
    pub id: &'static str,
    pub name: &'static str,
    login: &'static str,
    /// The page whose cookies are read.
    home: &'static str,
    /// The site's own domains. Links on them use the sign-in, and the window stays on them.
    domains: &'static [&'static str],
    /// Signed in once all of these are set.
    session: &'static [&'static str],
    /// The cookies that make up a sign-in, the only ones kept.
    keep: &'static [&'static str],
}

pub const SITES: [Site; 6] = [
    Site { id: "instagram", name: "Instagram", login: "https://www.instagram.com/accounts/login/", home: "https://www.instagram.com/", domains: &["instagram.com"], session: &["sessionid"], keep: &["sessionid", "csrftoken", "ds_user_id"] },
    Site { id: "threads", name: "Threads", login: "https://www.threads.com/login", home: "https://www.threads.com/", domains: &["threads.com", "threads.net"], session: &["sessionid"], keep: &["sessionid", "csrftoken", "ds_user_id"] },
    Site { id: "facebook", name: "Facebook", login: "https://www.facebook.com/login/", home: "https://www.facebook.com/", domains: &["facebook.com", "fb.com", "fb.watch"], session: &["c_user", "xs"], keep: &["c_user", "xs"] },
    Site { id: "x", name: "X", login: "https://x.com/i/flow/login", home: "https://x.com/", domains: &["x.com", "twitter.com", "t.co"], session: &["auth_token"], keep: &["auth_token", "ct0"] },
    Site {
        id: "tiktok",
        name: "TikTok",
        login: "https://www.tiktok.com/login",
        home: "https://www.tiktok.com/",
        domains: &["tiktok.com"],
        session: &["sessionid"],
        keep: &["sessionid", "sessionid_ss", "sid_tt", "sid_guard", "uid_tt", "uid_tt_ss", "sid_ucp_v1", "ssid_ucp_v1"],
    },
    Site { id: "reddit", name: "Reddit", login: "https://www.reddit.com/login/", home: "https://www.reddit.com/", domains: &["reddit.com", "redd.it"], session: &["reddit_session"], keep: &["reddit_session", "token_v2"] },
];

fn on(host: &str, domains: &[&str]) -> bool {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    domains.iter().any(|d| host == *d || host.ends_with(&format!(".{d}")))
}

/// The site a link belongs to, when it is one LodiSave can sign in to.
pub fn site_of(url: &str) -> Option<&'static Site> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let host = rest.split(['/', '?', '#']).next()?.rsplit('@').next()?.split(':').next()?;
    SITES.iter().find(|s| on(host, s.domains))
}

fn store(app: &AppHandle) -> PathBuf {
    app.path().app_local_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("LodiSave")).join("accounts")
}

pub fn signed_in(app: &AppHandle, site: &Site) -> bool {
    store(app).join(format!("{}.bin", site.id)).is_file()
}

#[derive(Serialize, Deserialize)]
struct Kept {
    name: String,
    value: String,
    domain: String,
    path: String,
    secure: bool,
    http_only: bool,
    expires: i64,
}

/// Seals (DPAPI's CryptProtectData) or opens data for the current Windows user only. The extra
/// entropy keeps other programs that ask DPAPI from opening it without knowing it's LodiSave's.
fn dpapi(data: &[u8], seal: bool) -> Result<Vec<u8>, String> {
    use windows::Win32::Foundation::{HLOCAL, LocalFree};
    use windows::Win32::Security::Cryptography::{CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData};
    let blob = |b: &[u8]| CRYPT_INTEGER_BLOB { cbData: b.len() as u32, pbData: b.as_ptr() as *mut u8 };
    let (input, entropy) = (blob(data), blob(b"LodiSave sign-in"));
    let mut out = CRYPT_INTEGER_BLOB::default();
    unsafe {
        let done = if seal {
            CryptProtectData(&input, windows::core::PCWSTR::null(), Some(&entropy), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)
        } else {
            CryptUnprotectData(&input, None, Some(&entropy), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)
        };
        done.map_err(|_| "dpapi".to_string())?;
        let bytes = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        LocalFree(Some(HLOCAL(out.pbData.cast())));
        Ok(bytes)
    }
}

/// Cookies in the cookies.txt format curl and yt-dlp read. Each is sent only to its own domain.
fn netscape(kept: &[Kept]) -> String {
    let mut out = String::from("# Netscape HTTP Cookie File\n");
    for c in kept {
        let http = if c.http_only { "#HttpOnly_" } else { "" };
        let dot = if c.domain.starts_with('.') { "" } else { "." };
        let secure = if c.secure { "TRUE" } else { "FALSE" };
        out += &format!("{http}{dot}{}\tTRUE\t{}\t{secure}\t{}\t{}\t{}\n", c.domain, c.path, c.expires.max(0), c.name, c.value);
    }
    out
}

/// A save's temporary cookies.txt, deleted when dropped, so even a failed save removes it.
pub struct Jar(PathBuf);

impl Jar {
    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for Jar {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

static JARS: AtomicU64 = AtomicU64::new(0);

/// The cookies.txt for a save of `url`: only its site's sign-in, or None when not signed in there.
pub fn jar(app: &AppHandle, url: &str) -> Option<Jar> {
    jar_in(&store(app), url)
}

fn jar_in(dir: &Path, url: &str) -> Option<Jar> {
    let site = site_of(url)?;
    let sealed = std::fs::read(dir.join(format!("{}.bin", site.id))).ok()?;
    let kept: Vec<Kept> = serde_json::from_slice(&dpapi(&sealed, false).ok()?).ok()?;
    let jar = Jar(dir.join(format!("jar-{}-{}.txt", std::process::id(), JARS.fetch_add(1, Ordering::SeqCst))));
    // curl -b reads a value with "=" as cookies, not a file. Windows user names can't hold one.
    if jar.0.to_string_lossy().contains('=') {
        return None;
    }
    std::fs::write(&jar.0, netscape(&kept)).ok()?;
    Some(jar)
}

/// Keeps a site's sign-in, sealed, replacing any earlier one whole.
fn keep(dir: &Path, site: &Site, kept: &[Kept]) -> Result<(), String> {
    let sealed = dpapi(&serde_json::to_vec(kept).map_err(|e| e.to_string())?, true)?;
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let part = dir.join(format!("{}.part", site.id));
    std::fs::write(&part, sealed).map_err(|e| e.to_string())?;
    std::fs::rename(&part, dir.join(format!("{}.bin", site.id))).map_err(|e| e.to_string())
}

/// Removes cookies.txt files left by a save that crashed.
pub fn sweep(app: &AppHandle) {
    for e in std::fs::read_dir(store(app)).into_iter().flatten().flatten() {
        if e.file_name().to_string_lossy().starts_with("jar-") {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

static ATTEMPT: AtomicU64 = AtomicU64::new(0);

fn close_window(app: &AppHandle, n: u64) {
    if let Some(w) = app.get_window(&format!("signin-{n}")) {
        let _ = w.destroy();
    }
}

/// Opens the sign-in window for a site. `lang` picks the sidebar's language.
pub fn sign_in(app: &AppHandle, id: &str, lang: &str) -> Result<(), String> {
    let site = SITES.iter().find(|s| s.id == id).ok_or("site")?;
    let n = ATTEMPT.fetch_add(1, Ordering::SeqCst) + 1;
    close_window(app, n - 1);
    let (w, h, side) = (900.0, 680.0, 300.0);
    let title = |host: &str| format!("Sign in to {} · {host}", site.name);
    let login: tauri::Url = site.login.parse().map_err(|_| "site")?;
    let window = tauri::window::WindowBuilder::new(app, format!("signin-{n}"))
        .title(title(login.host_str().unwrap_or("")))
        .inner_size(w, h)
        .resizable(false)
        .maximizable(false)
        .center()
        .build()
        .map_err(|e| e.to_string())?;
    let lang = if lang == "tl" { "tl" } else { "en" };
    let note = WebviewBuilder::new(format!("signin-note-{n}"), WebviewUrl::App(format!("signin.html?name={}&lang={lang}", site.name).into()));
    window.add_child(note, LogicalPosition::new(0.0, 0.0), LogicalSize::new(side, h)).map_err(|e| e.to_string())?;

    let (nav, popup, titled) = (app.clone(), app.clone(), window.clone());
    let elsewhere = |app: &AppHandle, url: &tauri::Url| {
        if matches!(url.scheme(), "http" | "https") {
            let _ = app.opener().open_url(url.as_str(), None::<&str>);
        }
    };
    let page = WebviewBuilder::new(format!("signin-site-{n}"), WebviewUrl::External(login))
        .data_directory(app.path().app_local_data_dir().map_err(|e| e.to_string())?.join("signin"))
        .incognito(true)
        .on_navigation(move |url| {
            let ok = url.scheme() == "https" && url.host_str().is_some_and(|h| on(h, site.domains));
            if ok {
                // The real address, in the window's own title bar.
                let _ = titled.set_title(&title(url.host_str().unwrap_or("")));
            } else if url.scheme() != "about" {
                elsewhere(&nav, url);
            }
            ok || url.as_str() == "about:blank"
        })
        .on_new_window(move |url, _| {
            elsewhere(&popup, &url);
            NewWindowResponse::Deny
        })
        .on_download(|_, _| false);
    let page = window.add_child(page, LogicalPosition::new(side, 0.0), LogicalSize::new(w - side, h)).map_err(|e| e.to_string())?;

    // Cookies can't be read on the main thread, so a thread watches for the session cookie.
    let app = app.clone();
    std::thread::spawn(move || {
        let home: tauri::Url = site.home.parse().unwrap();
        while ATTEMPT.load(Ordering::SeqCst) == n && app.get_window(&format!("signin-{n}")).is_some() {
            std::thread::sleep(Duration::from_millis(1000));
            let Ok(cookies) = page.cookies_for_url(home.clone()) else { continue };
            let kept: Vec<Kept> = cookies
                .iter()
                .filter(|c| site.keep.contains(&c.name()) && !c.value().is_empty())
                .map(|c| Kept {
                    name: c.name().into(),
                    value: c.value().into(),
                    domain: c.domain().unwrap_or(site.domains[0]).into(),
                    path: c.path().unwrap_or("/").into(),
                    secure: c.secure().unwrap_or(true),
                    http_only: c.http_only().unwrap_or(false),
                    expires: c.expires_datetime().map_or(0, |t| t.unix_timestamp()),
                })
                .collect();
            if !site.session.iter().all(|s| kept.iter().any(|c| c.name == *s)) {
                continue;
            }
            let saved = keep(&store(&app), site, &kept);
            // The window's profile keeps nothing: LodiSave's sealed copy is the only one.
            let _ = page.clear_all_browsing_data();
            close_window(&app, n);
            crate::show_panel(&app, None, false);
            let _ = app.emit("accounts://changed", serde_json::json!({ "site": site.id, "name": site.name, "signed": saved.is_ok() }));
            return;
        }
    });
    Ok(())
}

/// Deletes a site's kept sign-in. A sign-in window still open for it is cleared and closed.
pub fn sign_out(app: &AppHandle, id: &str) -> Result<(), String> {
    let site = SITES.iter().find(|s| s.id == id).ok_or("site")?;
    let n = ATTEMPT.load(Ordering::SeqCst);
    if let Some(page) = app.get_webview(&format!("signin-site-{n}")) {
        let _ = page.clear_all_browsing_data();
        close_window(app, n);
    }
    match std::fs::remove_file(store(app).join(format!("{}.bin", site.id))) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.to_string()),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sealed_for_this_user() {
        let secret = br#"[{"name":"sessionid","value":"abc"}]"#;
        let sealed = dpapi(secret, true).unwrap();
        assert!(!sealed.windows(3).any(|w| w == b"abc"), "sealed bytes hide the value");
        assert_eq!(dpapi(&sealed, false).unwrap(), secret);
        let mut tampered = sealed.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(dpapi(&tampered, false).is_err());
    }

    #[test]
    fn sites_and_cookie_files() {
        let id = |u: &str| site_of(u).map(|s| s.id);
        assert_eq!(id("https://www.instagram.com/stories/nasa/1/"), Some("instagram"));
        assert_eq!(id("https://www.threads.net/@a/post/B"), Some("threads"));
        assert_eq!(id("https://fb.watch/abc/"), Some("facebook"));
        assert_eq!(id("https://x.com/a/status/1"), Some("x"));
        assert_eq!(id("https://vm.tiktok.com/ZS1/"), Some("tiktok"));
        assert_eq!(id("https://v.redd.it/abc"), Some("reddit"));
        assert_eq!(id("https://instagram.com.evil.test/p/1"), None);
        assert_eq!(id("https://evil.test/?u=https://x.com/"), None);
        assert_eq!(id("https://x.com@evil.test/"), None);
        assert_eq!(id("https://youtube.com/watch?v=1"), None);

        let kept = [
            Kept { name: "sessionid".into(), value: "v1".into(), domain: "instagram.com".into(), path: "/".into(), secure: true, http_only: true, expires: 1900000000 },
            Kept { name: "csrftoken".into(), value: "v2".into(), domain: ".instagram.com".into(), path: "/".into(), secure: true, http_only: false, expires: 0 },
        ];
        assert_eq!(
            netscape(&kept),
            "# Netscape HTTP Cookie File\n#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t1900000000\tsessionid\tv1\n.instagram.com\tTRUE\t/\tTRUE\t0\tcsrftoken\tv2\n"
        );
    }

    #[test]
    fn jar_holds_only_the_links_site() {
        let dir = std::env::temp_dir().join(format!("lodisave-test-{}-accounts", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let cookie = |name: &str, value: &str, domain: &str| Kept { name: name.into(), value: value.into(), domain: domain.into(), path: "/".into(), secure: true, http_only: true, expires: 0 };
        keep(&dir, &SITES[0], &[cookie("sessionid", "ig-value", ".instagram.com")]).unwrap();
        keep(&dir, &SITES[3], &[cookie("auth_token", "x-value", ".x.com"), cookie("ct0", "x-csrf", ".x.com")]).unwrap();
        let sealed = std::fs::read(dir.join("instagram.bin")).unwrap();
        assert!(!sealed.windows(8).any(|w| w == b"ig-value"), "kept sealed, never in plain text");

        let jar = jar_in(&dir, "https://www.instagram.com/stories/nasa/1/").unwrap();
        let text = std::fs::read_to_string(jar.path()).unwrap();
        assert!(text.contains("\tsessionid\tig-value") && !text.contains("x-value") && !text.contains("auth_token"));
        let path = jar.path().to_path_buf();
        drop(jar);
        assert!(!path.exists(), "deleted right after the save");

        assert!(jar_in(&dir, "https://x.com/a/status/1").is_some_and(|j| std::fs::read_to_string(j.path()).unwrap().contains("\tct0\tx-csrf")));
        assert!(jar_in(&dir, "https://www.tiktok.com/@a/video/1").is_none(), "not signed in there");
        assert!(jar_in(&dir, "https://www.youtube.com/watch?v=1").is_none(), "not a sign-in site");
        std::fs::write(dir.join("facebook.bin"), b"not sealed by DPAPI").unwrap();
        assert!(jar_in(&dir, "https://www.facebook.com/reel/1").is_none());
        assert_eq!(std::fs::read_dir(&dir).unwrap().filter(|e| e.as_ref().unwrap().file_name().to_string_lossy().starts_with("jar-")).count(), 0);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
