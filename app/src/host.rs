//! The browser extension's way into any folder. Browsers keep an extension's downloads inside
//! Downloads, and with "Ask where to save each file" on they ask once per file. So Chrome, Edge and
//! Brave start LodiSave.exe as the extension's native messaging host, with `chrome-extension://<id>/`
//! and `--parent-window=<handle>`, and the extension hands it each file's bytes to write in the
//! folder the user chose with Windows' own folder picker.
//!
//! A message is a 4-byte little-endian length, then JSON. Each request has an `id` and gets one
//! answer with that id, with `error` when it failed:
//! - {op:"ping"} → {version}
//! - {op:"folder", title, start} → {dir}, null when the picker was closed
//! - {op:"home"} → {dir}: LodiSave's own folder, Downloads\LodiSave
//! - {op:"mkdir", parent, name} → {dir}: a folder made in `parent`, or in LodiSave's own folder when it's ""
//! - {op:"open", dir, name} → {file, path}: a new file named like `name`, never over another
//! - {op:"write", file, data} → {}: base64 bytes added to the end
//! - {op:"close", file} → {path}: renamed when its bytes are another type, "nothing" for a web page
//! - {op:"abort", file} → {}: the unfinished file deleted
//! - {op:"show", path} → {}: File Explorer with the file selected

use serde_json::{Value, json};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Write};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use windows::Win32::Foundation::HWND;
use windows::Win32::Security::Cryptography::{BCRYPT_SHA256_ALG_HANDLE, BCryptHash};
use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree};
use windows::Win32::System::Registry::{HKEY_CURRENT_USER, REG_SZ, RegSetKeyValueW};
use windows::Win32::UI::Shell::{
    FOLDERID_Downloads, FOS_FORCEFILESYSTEM, FOS_PICKFOLDERS, FileOpenDialog, IFileOpenDialog, IShellItem, KNOWN_FOLDER_FLAG, SHCreateItemFromParsingName,
    SHGetKnownFolderPath, SIGDN_FILESYSPATH,
};
use windows::core::{HSTRING, PCWSTR};

pub const NAME: &str = "io.github.jherobred.lodisave";
/// The extension's ID, fixed by the key in extension/manifest.json.
const EXTENSION: &str = "bpnhiobnhkpifeblfiiommlnncokmkjl";
/// Where each browser looks for native messaging hosts, under HKEY_CURRENT_USER.
const BROWSERS: [&str; 3] = [r"Software\Google\Chrome", r"Software\Microsoft\Edge", r"Software\BraveSoftware\Brave-Browser"];

/// Whether a browser started LodiSave as its native messaging host.
pub fn launched(args: &[String]) -> bool {
    args.iter().any(|a| a.starts_with("chrome-extension://"))
}

/// Answers the extension until the browser closes the connection.
pub fn run(args: &[String]) {
    // The browser window the extension runs in, which owns the folder picker so it opens in front.
    let owner = args.iter().find_map(|a| a.strip_prefix("--parent-window=")?.parse::<isize>().ok()).unwrap_or(0);
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
    let (mut input, mut output) = (std::io::stdin().lock(), std::io::stdout().lock());
    let mut files = HashMap::new();
    while let Some(request) = read(&mut input) {
        let mut answer = handle(&request, owner, &mut files).unwrap_or_else(|e| json!({ "error": e }));
        answer["id"] = request["id"].clone();
        if write(&mut output, &answer).is_err() {
            break;
        }
    }
    // The browser went away mid-save: unfinished files go.
    for (path, file) in files.into_values() {
        drop(file);
        let _ = std::fs::remove_file(path);
    }
}

fn read(input: &mut impl Read) -> Option<Value> {
    let mut len = [0u8; 4];
    input.read_exact(&mut len).ok()?;
    let mut body = vec![0u8; u32::from_le_bytes(len) as usize];
    input.read_exact(&mut body).ok()?;
    serde_json::from_slice(&body).ok()
}

fn write(output: &mut impl Write, answer: &Value) -> std::io::Result<()> {
    let body = serde_json::to_vec(answer)?;
    output.write_all(&(body.len() as u32).to_le_bytes())?;
    output.write_all(&body)?;
    output.flush()
}

/// Files being written, by the number the extension knows them by.
type Open = HashMap<u64, (PathBuf, File)>;

fn handle(r: &Value, owner: isize, files: &mut Open) -> Result<Value, String> {
    let text = |k: &str| r[k].as_str().unwrap_or("");
    let file = || r["file"].as_u64().ok_or_else(|| "file".to_string());
    match text("op") {
        "ping" => Ok(json!({ "version": env!("CARGO_PKG_VERSION") })),
        "folder" => Ok(json!({ "dir": pick_folder(owner, text("title"), Path::new(text("start"))) })),
        "home" => Ok(json!({ "dir": home().ok_or("folder")? })),
        "mkdir" => {
            let parent = if text("parent").is_empty() { home().ok_or("folder")? } else { PathBuf::from(text("parent")) };
            // A chosen folder that's gone isn't made again: the user picks another.
            if !text("parent").is_empty() && !parent.is_dir() {
                return Err("folder".into());
            }
            let dir = parent.join(crate::library::clean(text("name"))?);
            std::fs::create_dir_all(&dir).map_err(|_| "blocked".to_string())?;
            Ok(json!({ "dir": dir }))
        }
        "open" => {
            let dir = Path::new(text("dir"));
            if !dir.is_dir() {
                return Err("folder".into());
            }
            let (stem, ext) = split(text("name"));
            let path = crate::links::unique(dir, &stem, &ext);
            // Controlled folder access refuses unknown apps in protected folders, like Documents.
            let f = File::create_new(&path).map_err(|_| "blocked".to_string())?;
            let id = files.keys().max().map_or(1, |n| n + 1);
            files.insert(id, (path.clone(), f));
            Ok(json!({ "file": id, "path": path }))
        }
        "write" => {
            let (_, f) = files.get_mut(&file()?).ok_or("file")?;
            f.write_all(&crate::links::base64(text("data")).ok_or("data")?).map_err(|e| e.to_string())?;
            Ok(json!({}))
        }
        "close" => {
            let (path, f) = files.remove(&file()?).ok_or("file")?;
            drop(f);
            crate::links::settle(path).map(|p| json!({ "path": p }))
        }
        "abort" => {
            if let Some((path, f)) = files.remove(&file()?) {
                drop(f);
                let _ = std::fs::remove_file(path);
            }
            Ok(json!({}))
        }
        "show" => {
            let path = Path::new(text("path"));
            if !path.is_file() {
                return Err("gone".into());
            }
            std::process::Command::new("explorer").raw_arg(format!("/select,\"{}\"", path.display())).spawn().map_err(|e| e.to_string())?;
            Ok(json!({}))
        }
        _ => Err("op".into()),
    }
}

/// LodiSave's own folder, where the app saves too: Downloads\LodiSave, or LODISAVE_DIR in tests.
fn home() -> Option<PathBuf> {
    if let Some(dir) = std::env::var_os("LODISAVE_DIR") {
        return Some(dir.into());
    }
    unsafe {
        let p = SHGetKnownFolderPath(&FOLDERID_Downloads, KNOWN_FOLDER_FLAG(0), None).ok()?;
        let downloads = p.to_string().ok();
        CoTaskMemFree(Some(p.0 as _));
        Some(PathBuf::from(downloads?).join("LodiSave"))
    }
}

/// A file name's stem and extension, made safe for Windows and kept to the name alone. A name with
/// no extension gets "bin", which `settle` replaces with the type its bytes show.
fn split(name: &str) -> (String, String) {
    let name = Path::new(name).file_name().and_then(|n| n.to_str()).unwrap_or("");
    match name.rsplit_once('.') {
        Some((stem, ext)) if (1..=5).contains(&ext.len()) && ext.chars().all(|c| c.is_ascii_alphanumeric()) => {
            (crate::links::clean_name(stem), ext.to_ascii_lowercase())
        }
        _ => (crate::links::clean_name(name), "bin".into()),
    }
}

pub fn pick_folder(owner: isize, title: &str, start: &Path) -> Option<PathBuf> {
    unsafe {
        let dialog: IFileOpenDialog = CoCreateInstance(&FileOpenDialog, None, CLSCTX_INPROC_SERVER).ok()?;
        dialog.SetOptions(dialog.GetOptions().ok()? | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM).ok()?;
        if !title.is_empty() {
            let _ = dialog.SetTitle(&HSTRING::from(title));
        }
        if start.is_dir()
            && let Ok(item) = SHCreateItemFromParsingName::<_, _, IShellItem>(&HSTRING::from(start.as_os_str()), None)
        {
            let _ = dialog.SetFolder(&item);
        }
        dialog.Show(Some(HWND(owner as *mut _))).ok()?;
        let name = dialog.GetResult().ok()?.GetDisplayName(SIGDN_FILESYSPATH).ok()?;
        let path = name.to_string().ok();
        CoTaskMemFree(Some(name.0 as *const _));
        path.map(PathBuf::from)
    }
}

/// Tells Chrome, Edge and Brave where the host is: a manifest in LodiSave's data folder, named in
/// each browser's registry key. It lets in the extension's own ID and, for a copy loaded before the
/// extension had one, the ID the browser gave the unpacked folder next to LodiSave.exe.
pub fn register(data: &Path) -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let mut origins = vec![format!("chrome-extension://{EXTENSION}/")];
    origins.extend(unpacked_id(&exe.with_file_name("extension")).map(|id| format!("chrome-extension://{id}/")));
    let manifest = data.join("native-host.json");
    let body = json!({ "name": NAME, "description": "LodiSave saves into the folders you choose", "path": exe, "type": "stdio", "allowed_origins": origins });
    std::fs::create_dir_all(data).and_then(|_| std::fs::write(&manifest, body.to_string())).map_err(|e| e.to_string())?;
    let value: Vec<u16> = manifest.as_os_str().encode_wide().chain(Some(0)).collect();
    for browser in BROWSERS {
        let key = HSTRING::from(format!(r"{browser}\NativeMessagingHosts\{NAME}"));
        unsafe { RegSetKeyValueW(HKEY_CURRENT_USER, &key, PCWSTR::null(), REG_SZ.0, Some(value.as_ptr().cast()), (value.len() * 2) as u32) }
            .ok()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// The ID a browser gives an unpacked extension that has no key: SHA-256 of its folder's path in
/// UTF-16 with the drive letter upper-cased, the first 16 bytes as letters a to p.
fn unpacked_id(dir: &Path) -> Option<String> {
    let mut wide: Vec<u16> = dir.as_os_str().encode_wide().collect();
    if wide.get(1) == Some(&(b':' as u16)) && (b'a' as u16..=b'z' as u16).contains(&wide[0]) {
        wide[0] -= 32;
    }
    let bytes: Vec<u8> = wide.iter().flat_map(|c| c.to_le_bytes()).collect();
    let mut hash = [0u8; 32];
    unsafe { BCryptHash(BCRYPT_SHA256_ALG_HANDLE, None, &bytes, &mut hash) }.ok().ok()?;
    Some(hash[..16].iter().flat_map(|b| [b >> 4, b & 15]).map(|n| (b'a' + n) as char).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message(v: Value) -> Vec<u8> {
        let body = v.to_string().into_bytes();
        [(body.len() as u32).to_le_bytes().to_vec(), body].concat()
    }

    #[test]
    fn writes_a_file_in_the_chosen_folder() {
        let dir = std::env::temp_dir().join(format!("lodisave-host-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut files = Open::new();
        let ask = |files: &mut Open, v: Value| handle(&v, 0, files);
        let png = b"\x89PNG\r\n\x1a\nrest of the picture";
        let b64 = |b: &[u8]| {
            const ABC: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
            b.chunks(3).flat_map(|c| {
                let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
                (0..4).map(move |i| if i <= c.len() { ABC[(n >> (18 - 6 * i) & 63) as usize] as char } else { '=' })
            }).collect::<String>()
        };
        // Named .jpg by its URL, a PNG by its bytes, and a path in the name stays out.
        let open = ask(&mut files, json!({ "op": "open", "dir": dir, "name": r"..\..\photo.jpg" })).unwrap();
        let id = open["file"].clone();
        ask(&mut files, json!({ "op": "write", "file": id, "data": b64(&png[..9]) })).unwrap();
        ask(&mut files, json!({ "op": "write", "file": id, "data": b64(&png[9..]) })).unwrap();
        let path = PathBuf::from(ask(&mut files, json!({ "op": "close", "file": id })).unwrap()["path"].as_str().unwrap());
        assert_eq!(path, dir.join("photo.png"));
        assert_eq!(std::fs::read(&path).unwrap(), png);

        // A second one never replaces the first; a web page in place of the file is refused.
        let again = ask(&mut files, json!({ "op": "open", "dir": dir, "name": "photo.png" })).unwrap();
        assert_eq!(again["path"].as_str().unwrap(), dir.join("photo (2).png").to_str().unwrap());
        ask(&mut files, json!({ "op": "write", "file": again["file"], "data": b64(b"<!doctype html>") })).unwrap();
        assert_eq!(ask(&mut files, json!({ "op": "close", "file": again["file"] })), Err("nothing".into()));
        assert!(!dir.join("photo (2).png").exists());

        let gone = ask(&mut files, json!({ "op": "open", "dir": dir, "name": "v.mp4" })).unwrap();
        ask(&mut files, json!({ "op": "abort", "file": gone["file"] })).unwrap();
        assert!(!dir.join("v.mp4").exists());
        assert_eq!(ask(&mut files, json!({ "op": "open", "dir": dir.join("missing"), "name": "a.jpg" })), Err("folder".into()));
        assert_eq!(split("clip"), ("clip".into(), "bin".into()));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn messages_are_length_prefixed_json() {
        let mut input = std::io::Cursor::new([message(json!({ "id": 1, "op": "ping" })), message(json!({ "id": 2 }))].concat());
        assert_eq!(read(&mut input).unwrap()["op"], "ping");
        assert_eq!(read(&mut input).unwrap()["id"], 2);
        assert!(read(&mut input).is_none());
        let mut out = Vec::new();
        write(&mut out, &json!({ "id": 1 })).unwrap();
        assert_eq!(out, message(json!({ "id": 1 })));
    }

    #[test]
    fn unpacked_ids_follow_the_path() {
        let id = unpacked_id(Path::new(r"c:\LodiSave\extension")).unwrap();
        assert_eq!(id, unpacked_id(Path::new(r"C:\LodiSave\extension")).unwrap(), "drive letter case doesn't matter");
        assert!(id.len() == 32 && id.chars().all(|c| ('a'..='p').contains(&c)));
    }
}
