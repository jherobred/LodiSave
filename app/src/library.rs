//! The library: every saved file in the save folder and its subfolders, which are the albums.
//! Favorites, hidden-from-home and source links live in library.json in the app's data folder,
//! keyed by the file's path in the save folder, and follow the renames and moves made here.
//! Deletes go to the Recycle Bin, and Undo brings files back from it. Paths from the panel are
//! checked by `within` before anything touches them.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Mutex;

#[derive(Serialize)]
pub struct Item {
    pub path: PathBuf,
    pub name: String,
    pub album: String,
    pub kind: &'static str,
    pub bytes: u64,
    pub modified: u64,
    pub fav: bool,
    pub hidden: bool,
}

pub fn kind(name: &str) -> Option<&'static str> {
    Some(match name.rsplit_once('.')?.1.to_ascii_lowercase().as_str() {
        "gif" => "gif",
        "jpg" | "jpeg" | "png" | "webp" | "avif" | "svg" | "bmp" => "image",
        "mp4" | "webm" | "mov" | "m4v" | "mkv" => "video",
        "m4a" | "mp3" | "aac" | "opus" | "weba" | "ogg" | "wav" | "flac" => "audio",
        _ => return None,
    })
}

#[derive(Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Meta {
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub fav: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub hidden: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
}

/// library.json, keyed by the lowercased path in the save folder (Windows ignores case).
pub struct Store {
    file: PathBuf,
    map: Mutex<BTreeMap<String, Meta>>,
}

impl Store {
    pub fn open(file: PathBuf) -> Store {
        let map = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Store { file, map: Mutex::new(map) }
    }

    fn save(&self, map: &BTreeMap<String, Meta>) {
        // Written beside and renamed over, so a crash mid-write never leaves half a file.
        let tmp = self.file.with_extension("tmp");
        let _ = self.file.parent().map(std::fs::create_dir_all);
        if std::fs::write(&tmp, serde_json::to_vec(map).unwrap_or_default()).is_ok() {
            let _ = std::fs::rename(&tmp, &self.file);
        }
    }

    pub fn get(&self, rel: &str) -> Meta {
        self.map.lock().unwrap().get(&rel.to_lowercase()).cloned().unwrap_or_default()
    }

    /// Changes the entries of `rels` with `f`, dropping entries left empty.
    pub fn edit(&self, rels: &[String], f: impl Fn(&mut Meta)) {
        let mut map = self.map.lock().unwrap();
        for rel in rels {
            let key = rel.to_lowercase();
            let meta = map.entry(key.clone()).or_default();
            f(meta);
            if *meta == Meta::default() {
                map.remove(&key);
            }
        }
        self.save(&map);
    }

    /// Files that a save just made start unmarked, with the link they came from.
    pub fn fresh(&self, rels: &[String], source: Option<&str>) {
        self.edit(rels, |m| *m = Meta { source: source.map(str::to_string), ..Meta::default() });
    }

    /// Moves the entries of files and folders that moved: (from, to) pairs of paths in the folder.
    fn rekey(&self, moves: &[(String, String)]) {
        if moves.is_empty() {
            return;
        }
        let mut map = self.map.lock().unwrap();
        for (from, to) in moves {
            let (from, to) = (from.to_lowercase(), to.to_lowercase());
            let inside = format!("{from}/");
            let keys: Vec<String> = map.keys().filter(|k| **k == from || k.starts_with(&inside)).cloned().collect();
            let moved: Vec<(String, Meta)> = keys.iter().filter_map(|k| Some((format!("{to}{}", &k[from.len()..]), map.remove(k)?))).collect();
            // An entry left at `to` by a file deleted earlier doesn't belong to the newcomer.
            map.remove(&to);
            map.extend(moved);
        }
        self.save(&map);
    }

    /// Forgets files that are gone, run at start: files deleted in an earlier session.
    pub fn prune(&self, dir: &Path) {
        let mut map = self.map.lock().unwrap();
        let before = map.len();
        map.retain(|k, _| at(dir, k).exists());
        if map.len() != before {
            self.save(&map);
        }
    }
}

/// A path the panel names, as its path in the save folder `dir` with / between folders, or an
/// error when it is outside the folder, is the folder itself, or is gone.
pub fn within(dir: &Path, path: &str) -> Result<String, String> {
    let p = Path::new(path).canonicalize().map_err(|_| "gone".to_string())?;
    let d = dir.canonicalize().map_err(|e| e.to_string())?;
    let rel = p.strip_prefix(&d).map_err(|_| "outside".to_string())?;
    if rel.as_os_str().is_empty() {
        return Err("outside".into());
    }
    Ok(rel.components().map(|c| c.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("/"))
}

/// A path in the save folder that may be gone (a file just deleted), checked by its parts: under
/// `dir`, with no `..`.
pub fn under(dir: &Path, path: &str) -> Result<String, String> {
    let rest = Path::new(path).strip_prefix(dir).map_err(|_| "outside".to_string())?;
    let parts: Vec<String> = rest
        .components()
        .map(|c| match c {
            std::path::Component::Normal(p) => Ok(p.to_string_lossy().into_owned()),
            _ => Err("outside".to_string()),
        })
        .collect::<Result<_, _>>()?;
    if parts.is_empty() { Err("outside".into()) } else { Ok(parts.join("/")) }
}

/// A file or album name typed in the panel, or "name" when Windows can't use it.
pub fn clean(name: &str) -> Result<String, String> {
    let name = name.trim().trim_end_matches(['.', ' ']);
    let device = name.split('.').next().unwrap_or("").trim_end().to_ascii_uppercase();
    let reserved = ["CON", "PRN", "AUX", "NUL"].contains(&device.as_str())
        || (device.len() == 4 && (device.starts_with("COM") || device.starts_with("LPT")) && device.as_bytes()[3].is_ascii_digit());
    let bad = name.is_empty() || name.starts_with('.') || name.chars().count() > 120 || name.chars().any(|c| c.is_control() || r#"<>:"/\|?*"#.contains(c));
    if bad || reserved { Err("name".into()) } else { Ok(name.to_string()) }
}

fn split(name: &str) -> (&str, &str) {
    name.rsplit_once('.').filter(|(s, _)| !s.is_empty()).unwrap_or((name, ""))
}

fn unique(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    let dot = if ext.is_empty() { String::new() } else { format!(".{ext}") };
    (1..).map(|n| if n < 2 { dir.join(format!("{stem}{dot}")) } else { dir.join(format!("{stem} ({n}){dot}")) }).find(|p| !p.exists()).unwrap()
}

/// The file at a path in the folder. Paths in the folder use /, which the shell won't take.
pub fn at(dir: &Path, rel: &str) -> PathBuf {
    dir.join(rel.replace('/', "\\"))
}

fn rel_of(dir: &Path, path: &Path) -> String {
    path.strip_prefix(dir).unwrap_or(path).components().map(|c| c.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("/")
}

/// Files under `dir/rel`, as paths in `dir`. Links (junctions) are skipped so nothing outside
/// the folder is reached, and so are dot-files (partial downloads) unless `all`.
fn walk(dir: &Path, rel: &str, all: bool, out: &mut Vec<(String, std::fs::Metadata)>) {
    for e in std::fs::read_dir(at(dir, rel)).into_iter().flatten().flatten() {
        let (name, Ok(t)) = (e.file_name().to_string_lossy().into_owned(), e.file_type()) else { continue };
        if t.is_symlink() || (!all && name.starts_with('.')) {
            continue;
        }
        let r = if rel.is_empty() { name } else { format!("{rel}/{name}") };
        if t.is_dir() && r.matches('/').count() < 4 {
            walk(dir, &r, all, out);
        } else if let (true, Ok(m)) = (t.is_file(), e.metadata()) {
            out.push((r, m));
        }
    }
}

/// Every photo, GIF, video and sound file, and the albums (subfolders of the save folder).
pub fn list(dir: &Path, store: &Store) -> (Vec<Item>, Vec<String>) {
    let mut files = Vec::new();
    walk(dir, "", false, &mut files);
    let items = files
        .into_iter()
        .filter_map(|(rel, m)| {
            let name = rel.rsplit('/').next().unwrap_or(&rel).to_string();
            let kind = kind(&name)?;
            let modified = m.modified().ok()?.duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
            let album = rel.split_once('/').map_or("", |(a, _)| a).to_string();
            let meta = store.get(&rel);
            Some(Item { path: at(dir, &rel), name, album, kind, bytes: m.len(), modified, fav: meta.fav, hidden: meta.hidden })
        })
        .collect();
    let mut albums: Vec<String> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir() && !t.is_symlink()))
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| !n.starts_with('.'))
        .collect();
    albums.sort_by_key(|a| a.to_lowercase());
    (items, albums)
}

/// Renames a file, keeping its extension. Returns its new path in the folder.
pub fn rename(dir: &Path, store: &Store, rel: &str, stem: &str) -> Result<String, String> {
    let stem = clean(stem)?;
    let from = at(dir, rel);
    let name = from.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let ext = split(&name).1;
    let to = from.with_file_name(if ext.is_empty() { stem } else { format!("{stem}.{ext}") });
    let to_rel = rel_of(dir, &to);
    // A change of case only is the same file to Windows.
    if to.exists() && to_rel.to_lowercase() != rel.to_lowercase() {
        return Err("taken".into());
    }
    std::fs::rename(&from, &to).map_err(|_| "blocked".to_string())?;
    store.rekey(&[(rel.to_string(), to_rel.clone())]);
    Ok(to_rel)
}

/// Moves files into an album, or back to the main folder when `album` is empty. A name that is
/// taken there gets a number. Returns the new paths in the folder.
pub fn move_to(dir: &Path, store: &Store, rels: &[String], album: &str) -> Result<Vec<String>, String> {
    let dest = if album.is_empty() { dir.to_path_buf() } else { dir.join(clean(album)?) };
    std::fs::create_dir_all(&dest).map_err(|_| "blocked".to_string())?;
    let (mut done, mut moves) = (Vec::new(), Vec::new());
    for rel in rels {
        let from = at(dir, rel);
        if from.parent() == Some(dest.as_path()) {
            done.push(rel.clone());
            continue;
        }
        let name = from.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let (stem, ext) = split(&name);
        let to = unique(&dest, stem, ext);
        if std::fs::rename(&from, &to).is_ok() {
            let to_rel = rel_of(dir, &to);
            moves.push((rel.clone(), to_rel.clone()));
            done.push(to_rel);
        }
    }
    store.rekey(&moves);
    if done.is_empty() && !rels.is_empty() { Err("blocked".into()) } else { Ok(done) }
}

pub fn create_album(dir: &Path, name: &str) -> Result<String, String> {
    let name = clean(name)?;
    if dir.join(&name).exists() {
        return Err("taken".into());
    }
    std::fs::create_dir(dir.join(&name)).map_err(|_| "blocked".to_string())?;
    Ok(name)
}

pub fn rename_album(dir: &Path, store: &Store, old: &str, new: &str) -> Result<String, String> {
    let (old, new) = (clean(old)?, clean(new)?);
    if !dir.join(&old).is_dir() {
        return Err("gone".into());
    }
    if dir.join(&new).exists() && old.to_lowercase() != new.to_lowercase() {
        return Err("taken".into());
    }
    std::fs::rename(dir.join(&old), dir.join(&new)).map_err(|_| "blocked".to_string())?;
    store.rekey(&[(old, new.clone())]);
    Ok(new)
}

/// Deletes an album: its files go back to the main folder, then its emptied folders are removed.
/// Explorer's own desktop.ini and Thumbs.db go to the Recycle Bin instead.
pub fn delete_album(dir: &Path, store: &Store, name: &str) -> Result<(), String> {
    let name = clean(name)?;
    if !dir.join(&name).is_dir() {
        return Err("gone".into());
    }
    let mut files = Vec::new();
    walk(dir, &name, true, &mut files);
    let (system, files): (Vec<_>, Vec<_>) = files.into_iter().map(|(rel, _)| rel).partition(|rel| {
        let low = rel.rsplit('/').next().unwrap_or("").to_lowercase();
        low == "desktop.ini" || low == "thumbs.db"
    });
    move_to(dir, store, &files, "")?;
    let _ = trash(&system.iter().map(|r| at(dir, r)).collect::<Vec<_>>());
    fn remove_empty(p: &Path) {
        for e in std::fs::read_dir(p).into_iter().flatten().flatten() {
            if e.file_type().is_ok_and(|t| t.is_dir() && !t.is_symlink()) {
                remove_empty(&e.path());
            }
        }
        let _ = std::fs::remove_dir(p);
    }
    remove_empty(&dir.join(&name));
    if dir.join(&name).exists() { Err("kept".into()) } else { Ok(()) }
}

/// Sends files to the Recycle Bin. Windows asks first if one can't go there (too big, or a drive
/// with no bin), so nothing is deleted for good without a word.
pub fn trash(paths: &[PathBuf]) -> Result<(), String> {
    use windows::Win32::UI::Shell::{FO_DELETE, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_NOERRORUI, FOF_SILENT, FOF_WANTNUKEWARNING, SHFILEOPSTRUCTW, SHFileOperationW};
    if paths.is_empty() {
        return Ok(());
    }
    // A list of paths, each ending in a 0, and one more 0 at the end.
    let mut from: Vec<u16> = Vec::new();
    for p in paths {
        from.extend(p.as_os_str().encode_wide());
        from.push(0);
    }
    from.push(0);
    let mut op = SHFILEOPSTRUCTW {
        wFunc: FO_DELETE,
        pFrom: windows::core::PCWSTR(from.as_ptr()),
        fFlags: (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_WANTNUKEWARNING | FOF_NOERRORUI | FOF_SILENT).0 as u16,
        ..Default::default()
    };
    let code = unsafe { SHFileOperationW(&mut op) };
    if code != 0 || op.fAnyOperationsAborted.as_bool() { Err("blocked".into()) } else { Ok(()) }
}

/// A Recycle Bin record ($I file): when the file was deleted and where it was. Version 1 is
/// Windows Vista to 8.1, version 2 is Windows 10 and later.
fn bin_record(b: &[u8]) -> Option<(u64, String)> {
    let num = |o: usize| b.get(o..o + 8).map(|s| u64::from_le_bytes(s.try_into().unwrap()));
    let path = match num(0)? {
        1 => b.get(24..544)?,
        2 => b.get(28..28 + 2 * u32::from_le_bytes(b.get(24..28)?.try_into().ok()?) as usize)?,
        _ => return None,
    };
    let wide: Vec<u16> = path.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).take_while(|&c| c != 0).collect();
    Some((num(16)?, String::from_utf16_lossy(&wide)))
}

/// Undo for `trash`: brings each file back from the Recycle Bin to where it was, or beside it
/// when that name is taken now. Each deleted file is $R<id> in the bin with a $I<id> record of
/// where it came from; the newest record for the path wins. Returns the files brought back.
pub fn restore(paths: &[PathBuf]) -> Vec<PathBuf> {
    let mut back = Vec::new();
    for p in paths {
        let original = p.to_string_lossy().to_lowercase();
        let Some(drive) = original.split('\\').next().filter(|d| d.ends_with(':')) else { continue };
        let bin = PathBuf::from(format!("{drive}\\$Recycle.Bin"));
        // Other users' bins can't be read, which leaves this user's.
        let newest = std::fs::read_dir(&bin)
            .into_iter()
            .flatten()
            .flatten()
            .flat_map(|user| std::fs::read_dir(user.path()).into_iter().flatten().flatten())
            .filter(|e| e.file_name().to_string_lossy().starts_with("$I"))
            .filter_map(|e| {
                let (when, from) = bin_record(&std::fs::read(e.path()).ok()?)?;
                (from.to_lowercase() == original).then(|| (when, e.path()))
            })
            .max_by_key(|(when, _)| *when);
        let Some((_, record)) = newest else { continue };
        let name = record.file_name().unwrap_or_default().to_string_lossy().replacen("$I", "$R", 1);
        let data = record.with_file_name(name);
        let target = match (p.exists(), p.parent(), p.file_name()) {
            (true, Some(dir), Some(file)) => {
                let file = file.to_string_lossy();
                let (stem, ext) = split(&file);
                unique(dir, stem, ext)
            }
            _ => p.clone(),
        };
        let _ = target.parent().map(std::fs::create_dir_all);
        if std::fs::rename(&data, &target).is_ok() {
            let _ = std::fs::remove_file(&record);
            back.push(target);
        }
    }
    back
}

/// Writes an edited photo beside its original as "<name> (edit).<ext>". The panel draws it, so
/// this checks the bytes are the image type they claim to be.
pub fn write_copy(src: &Path, ext: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    let ok = match ext {
        "png" => bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
        "jpg" => bytes.starts_with(b"\xFF\xD8\xFF"),
        "webp" => bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WEBP"),
        _ => false,
    };
    let (Some(dir), Some(name)) = (src.parent(), src.file_name()) else { return Err("gone".into()) };
    if !ok {
        return Err("type".into());
    }
    let name = name.to_string_lossy();
    let out = unique(dir, &format!("{} (edit)", split(&name).0), ext);
    std::fs::write(&out, bytes).map_err(|_| "blocked".to_string())?;
    Ok(out)
}

pub fn ffmpeg() -> bool {
    std::env::var_os("PATH").is_some_and(|p| std::env::split_paths(&p).any(|d| d.join("ffmpeg.exe").is_file()))
}

/// The video tools, through FFmpeg: "trim" a video to [from, to] seconds, make a "gif" of a video
/// (of that part when `to` > `from`), or make an "mp4" of a GIF. The result goes beside the original.
pub fn convert(src: &Path, op: &str, from: f64, to: f64) -> Result<PathBuf, String> {
    let (Some(dir), Some(name)) = (src.parent(), src.file_name()) else { return Err("gone".into()) };
    let name = name.to_string_lossy();
    let stem = split(&name).0;
    let (out, args): (PathBuf, &[&str]) = match op {
        "trim" if to > from => (unique(dir, &format!("{stem} (trim)"), "mp4"), &["-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-movflags", "+faststart"]),
        "gif" => (unique(dir, stem, "gif"), &["-vf", "fps=12,scale='min(480,iw)':-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", "-loop", "0"]),
        "mp4" => (unique(dir, stem, "mp4"), &["-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-pix_fmt", "yuv420p", "-movflags", "+faststart"]),
        _ => return Err("op".into()),
    };
    let mut c = Command::new("ffmpeg.exe");
    c.creation_flags(0x0800_0000).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    c.args(["-hide_banner", "-loglevel", "error", "-n"]);
    if to > from && op != "mp4" {
        c.args(["-ss", &format!("{from:.3}"), "-t", &format!("{:.3}", to - from)]);
    }
    c.arg("-i").arg(src).args(args).arg(&out);
    if c.status().is_ok_and(|s| s.success()) && out.is_file() {
        Ok(out)
    } else {
        let _ = std::fs::remove_file(&out);
        Err(if ffmpeg() { "ffmpeg" } else { "noffmpeg" }.into())
    }
}

/// Where a file came from: the link it was saved from here, else the page or address the browser
/// noted when it downloaded the file (its Zone.Identifier stream).
pub fn source(dir: &Path, store: &Store, rel: &str) -> Option<String> {
    store.get(rel).source.or_else(|| {
        let mut ads = at(dir, rel).into_os_string();
        ads.push(":Zone.Identifier");
        let zone = std::fs::read_to_string(ads).ok()?;
        let find = |k: &str| zone.lines().find_map(|l| l.strip_prefix(k)).map(|v| v.trim().to_string());
        find("ReferrerUrl=").or_else(|| find("HostUrl=")).filter(|u| u.starts_with("http"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("lodisave-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn names_and_paths() {
        assert_eq!(clean("  Trip 2026. ").as_deref(), Ok("Trip 2026"));
        for bad in ["", " . ", "..", "a/b", r"a\b", "a:b", "CON", "com1.txt", "lpt9", ".hidden", "tab\there"] {
            assert!(clean(bad).is_err(), "{bad}");
        }
        assert!(clean("Console").is_ok() && clean("COM").is_ok());

        let dir = scratch("paths");
        std::fs::create_dir_all(dir.join("lib/Trip")).unwrap();
        std::fs::write(dir.join("lib/Trip/a.jpg"), b"x").unwrap();
        std::fs::write(dir.join("secret.txt"), b"x").unwrap();
        let lib = dir.join("lib");
        assert_eq!(within(&lib, &lib.join("Trip/a.jpg").to_string_lossy()).as_deref(), Ok("Trip/a.jpg"));
        assert_eq!(within(&lib, &lib.join(r"Trip\..\..\secret.txt").to_string_lossy()), Err("outside".to_string()));
        assert_eq!(within(&lib, &dir.join("secret.txt").to_string_lossy()), Err("outside".to_string()));
        assert_eq!(within(&lib, &lib.to_string_lossy()), Err("outside".to_string()));
        assert_eq!(within(&lib, &lib.join("nope.jpg").to_string_lossy()), Err("gone".to_string()));
        assert_eq!(under(&lib, &lib.join(r"Trip\gone.jpg").to_string_lossy()).as_deref(), Ok("Trip/gone.jpg"));
        assert_eq!(under(&lib, &lib.join(r"Trip\..\..\secret.txt").to_string_lossy()), Err("outside".to_string()));
        assert_eq!(under(&lib, &dir.join("x.jpg").to_string_lossy()), Err("outside".to_string()));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn organizes_files() {
        let dir = scratch("organize");
        let lib = dir.join("lib");
        std::fs::create_dir_all(&lib).unwrap();
        for f in ["a.jpg", "b.GIF", "c.mp4", "d.m4a", "notes.txt", ".lodisave-1.part"] {
            std::fs::write(lib.join(f), f).unwrap();
        }
        let store = Store::open(dir.join("library.json"));
        let (items, albums) = list(&lib, &store);
        let mut kinds: Vec<(&str, &str)> = items.iter().map(|i| (i.name.as_str(), i.kind)).collect();
        kinds.sort();
        assert_eq!(kinds, [("a.jpg", "image"), ("b.GIF", "gif"), ("c.mp4", "video"), ("d.m4a", "audio")]);
        assert!(albums.is_empty());

        assert_eq!(create_album(&lib, "Trip").as_deref(), Ok("Trip"));
        assert_eq!(create_album(&lib, "trip"), Err("taken".to_string()));
        assert_eq!(move_to(&lib, &store, &["a.jpg".into(), "c.mp4".into()], "Trip").unwrap(), ["Trip/a.jpg", "Trip/c.mp4"]);
        store.edit(&["Trip/a.jpg".into()], |m| m.fav = true);
        store.edit(&["Trip/c.mp4".into()], |m| m.hidden = true);
        let (items, albums) = list(&lib, &store);
        assert_eq!(albums, ["Trip"]);
        let a = items.iter().find(|i| i.name == "a.jpg").unwrap();
        assert!(a.album == "Trip" && a.fav && !a.hidden);

        assert_eq!(rename_album(&lib, &store, "Trip", "Travel").as_deref(), Ok("Travel"));
        assert!(store.get("Travel/a.jpg").fav && store.get("travel/c.mp4").hidden && !store.get("Trip/a.jpg").fav);
        assert_eq!(rename(&lib, &store, "Travel/a.jpg", "beach").as_deref(), Ok("Travel/beach.jpg"));
        assert_eq!(rename(&lib, &store, "Travel/beach.jpg", "c").as_deref(), Ok("Travel/c.jpg"), "another extension is another name");
        assert_eq!(rename(&lib, &store, "Travel/c.jpg", "C").as_deref(), Ok("Travel/C.jpg"), "a change of case");
        assert_eq!(rename(&lib, &store, "Travel/C.jpg", "a/b"), Err("name".to_string()));
        assert!(store.get("Travel/C.jpg").fav);

        // Files deleted earlier left entries; a file that takes such a name brings its own marks.
        store.edit(&["C (2).jpg".into(), "b.jpg".into()], |m| m.hidden = true);
        std::fs::write(lib.join("C.jpg"), b"clash").unwrap();
        assert_eq!(move_to(&lib, &store, &["Travel/C.jpg".into()], "").unwrap(), ["C (2).jpg"]);
        let moved = store.get("C (2).jpg");
        assert!(moved.fav && !moved.hidden && !store.get("C.jpg").fav);

        std::fs::write(lib.join("Travel/desktop.ini"), b"[.ShellClassInfo]").unwrap();
        std::fs::create_dir_all(lib.join("Travel/deeper")).unwrap();
        std::fs::write(lib.join("Travel/deeper/e.png"), b"e").unwrap();
        assert_eq!(delete_album(&lib, &store, "Travel"), Ok(()));
        assert!(!lib.join("Travel").exists() && lib.join("c.mp4").is_file() && lib.join("e.png").is_file());
        assert!(store.get("c.mp4").hidden);
        // desktop.ini went to the Recycle Bin; bring it back so the test leaves nothing there.
        assert_eq!(restore(&[lib.join(r"Travel\desktop.ini")]), [lib.join(r"Travel\desktop.ini")]);

        // Marks survive a restart, and entries of files that are gone are dropped.
        let again = Store::open(dir.join("library.json"));
        assert!(again.get("C (2).jpg").fav && again.get("b.jpg").hidden);
        again.prune(&lib);
        assert!(!again.get("b.jpg").hidden && again.get("c.mp4").hidden);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn recycles_and_restores() {
        let dir = scratch("bin");
        let file = dir.join("lodisave restore check.txt");
        std::fs::write(&file, b"back again").unwrap();
        trash(std::slice::from_ref(&file)).unwrap();
        assert!(!file.exists());
        std::fs::write(&file, b"newer file with the same name").unwrap();
        let back = restore(std::slice::from_ref(&file));
        assert_eq!(back, [dir.join("lodisave restore check (2).txt")]);
        assert_eq!(std::fs::read(&back[0]).unwrap(), b"back again");
        assert!(restore(std::slice::from_ref(&dir.join("never deleted.txt"))).is_empty());
        std::fs::remove_dir_all(dir).unwrap();

        let mut rec = vec![2, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0];
        rec.extend("C:\\a".encode_utf16().chain([0]).flat_map(u16::to_le_bytes));
        assert_eq!(bin_record(&rec), Some((7, "C:\\a".into())));
        assert_eq!(bin_record(&rec[..30]), None);
    }

    #[test]
    fn writes_copies() {
        let dir = scratch("copies");
        let src = dir.join("cat.jpg");
        std::fs::write(&src, b"\xFF\xD8\xFF").unwrap();
        assert_eq!(write_copy(&src, "png", b"not a png").unwrap_err(), "type");
        assert_eq!(write_copy(&src, "exe", b"MZ").unwrap_err(), "type");
        let png = b"\x89PNG\r\n\x1a\nrest";
        assert_eq!(write_copy(&src, "png", png).unwrap(), dir.join("cat (edit).png"));
        assert_eq!(write_copy(&src, "png", png).unwrap(), dir.join("cat (edit) (2).png"));
        assert_eq!(write_copy(&src, "webp", b"RIFF\0\0\0\0WEBPVP8 ").unwrap(), dir.join("cat (edit).webp"));

        if ffmpeg() {
            let clip = dir.join("clip.mp4");
            let made = Command::new("ffmpeg.exe")
                .args(["-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p"])
                .arg(&clip)
                .status();
            assert!(made.is_ok_and(|s| s.success()));
            assert_eq!(convert(&clip, "trim", 0.5, 1.5).unwrap(), dir.join("clip (trim).mp4"));
            let gif = convert(&clip, "gif", 0.0, 0.0).unwrap();
            assert!(std::fs::read(&gif).unwrap().starts_with(b"GIF89a"));
            assert_eq!(convert(&gif, "mp4", 0.0, 0.0).unwrap(), dir.join("clip (2).mp4"));
            assert_eq!(convert(&clip, "trim", 1.0, 1.0).unwrap_err(), "op");
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
}
