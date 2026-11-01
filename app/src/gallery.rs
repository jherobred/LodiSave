//! The gallery: photos, GIFs, videos and sounds in folders the user adds, shown in their own window.
//! Nothing outside those folders is read. Folders Windows and apps keep for themselves (a drive's
//! root, Windows, Program Files, ProgramData, AppData, the user folder itself) can't be added, links
//! and junctions are never followed, and every path the window sends back is checked to be inside an
//! added folder, after links are resolved, before anything touches it. Deletes go to the Recycle Bin.
//! Favorites, blur and tags live in gallery.json, keyed by the file's or folder's full path, and
//! follow the renames and moves made here. Locked files (.lodilock) are encrypted by the window with
//! the user's password; this side only writes, swaps and deletes them, inside the added folders.

use crate::library::{self, Store, clean, split, unique};
use serde::Serialize;
use std::os::windows::fs::MetadataExt;
use std::path::{Component, Path, PathBuf};
use std::sync::Mutex;

/// Folders deeper than this under an added one, and files past this many, aren't listed.
const DEPTH: usize = 10;
const MAX_FILES: usize = 100_000;
const HIDDEN: u32 = 0x2;
const SYSTEM: u32 = 0x4;
/// Offline, or a cloud file (OneDrive) that isn't on this PC: reading it would download it.
const NOT_HERE: u32 = 0x1000 | 0x40000 | 0x400000;

pub struct Gallery {
    file: PathBuf,
    roots: Mutex<Vec<PathBuf>>,
    pub store: Store,
}

#[derive(Serialize)]
pub struct Entry {
    path: PathBuf,
    name: String,
    dir: PathBuf,
    kind: &'static str,
    bytes: u64,
    modified: u64,
    fav: bool,
    blur: bool,
    tags: Vec<String>,
}

#[derive(Serialize)]
pub struct Folder {
    path: PathBuf,
    name: String,
    parent: Option<PathBuf>,
    blur: bool,
    /// Photos and videos right in it, and in it and every folder under it.
    count: usize,
    total: usize,
}

/// A path without Windows' \\?\ prefix, which canonicalize adds and nothing else wants.
fn plain(p: PathBuf) -> PathBuf {
    match p.to_str().and_then(|s| s.strip_prefix(r"\\?\")) {
        Some(rest) if !rest.starts_with("UNC") => PathBuf::from(rest),
        _ => p,
    }
}

/// A file's key in gallery.json: its full path with /, as the library's keys.
pub fn key(p: &Path) -> String {
    p.to_string_lossy().replace('\\', "/")
}

fn real(p: &Path) -> Option<PathBuf> {
    p.canonicalize().ok().map(plain)
}

/// Why a folder can't be added, or None. Checked on the resolved path, so a link to Windows is
/// Windows.
fn refused(p: &Path) -> Option<&'static str> {
    if p.parent().is_none() {
        return Some("root");
    }
    let env = |k: &str| std::env::var_os(k).map(PathBuf::from).and_then(|d| real(&d));
    let user = env("USERPROFILE");
    let kept = ["SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData", "APPDATA", "LOCALAPPDATA"]
        .iter()
        .filter_map(|k| env(k))
        .chain(user.as_ref().map(|u| u.join("AppData")));
    let lower = |q: &Path| q.to_string_lossy().to_lowercase();
    if kept.into_iter().any(|k| lower(p).starts_with(&lower(&k)) || lower(&k).starts_with(&format!("{}\\", lower(p)))) {
        return Some("system");
    }
    // The user folder holds AppData, so only folders inside it go in.
    if user.is_some_and(|u| lower(&u) == lower(p)) {
        return Some("system");
    }
    None
}

/// `p` is `root` or inside it, compared as Windows does, ignoring case.
fn inside(p: &Path, root: &Path) -> bool {
    let (p, r) = (p.to_string_lossy().to_lowercase(), root.to_string_lossy().to_lowercase());
    p == r || p.starts_with(&format!("{}\\", r.trim_end_matches('\\')))
}

impl Gallery {
    pub fn open(data: &Path) -> Gallery {
        let file = data.join("gallery-folders.json");
        let roots = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Gallery { file, roots: Mutex::new(roots), store: Store::open(data.join("gallery.json")) }
    }

    pub fn roots(&self) -> Vec<PathBuf> {
        self.roots.lock().unwrap().clone()
    }

    fn keep(&self, roots: &[PathBuf]) {
        let tmp = self.file.with_extension("tmp");
        if std::fs::write(&tmp, serde_json::to_vec(roots).unwrap_or_default()).is_ok() {
            let _ = std::fs::rename(&tmp, &self.file);
        }
    }

    /// Adds a folder, also one inside or around folders already added: each shows as its own folder,
    /// and a file under two of them is listed once. Returns the folder as it is kept.
    pub fn add(&self, path: &Path) -> Result<PathBuf, String> {
        let p = real(path).filter(|p| p.is_dir()).ok_or("gone")?;
        if let Some(why) = refused(&p) {
            return Err(why.into());
        }
        let mut roots = self.roots.lock().unwrap();
        if roots.iter().any(|r| key(r).eq_ignore_ascii_case(&key(&p))) {
            return Ok(p);
        }
        roots.push(p.clone());
        roots.sort_by_key(|r| r.to_string_lossy().to_lowercase());
        self.keep(&roots);
        Ok(p)
    }

    pub fn remove(&self, path: &str) {
        let mut roots = self.roots.lock().unwrap();
        roots.retain(|r| !r.as_os_str().eq_ignore_ascii_case(path));
        self.keep(&roots);
    }

    /// A path the window names, resolved, when it is inside an added folder.
    pub fn check(&self, path: &str) -> Result<PathBuf, String> {
        let p = real(Path::new(path)).ok_or("gone")?;
        if self.roots.lock().unwrap().iter().any(|r| inside(&p, r)) { Ok(p) } else { Err("outside".into()) }
    }

    /// A path that may be gone (a file just deleted), checked by its parts: inside an added folder,
    /// with no `..`.
    pub fn under(&self, path: &str) -> Result<PathBuf, String> {
        let p = PathBuf::from(path);
        let plain = p.components().all(|c| matches!(c, Component::Prefix(_) | Component::RootDir | Component::Normal(_)));
        if plain && self.roots.lock().unwrap().iter().any(|r| inside(&p, r) && p != *r) { Ok(p) } else { Err("outside".into()) }
    }

    fn checked_file(&self, path: &str) -> Result<PathBuf, String> {
        self.check(path).and_then(|p| if p.is_file() { Ok(p) } else { Err("gone".into()) })
    }

    fn checked_dir(&self, path: &str) -> Result<PathBuf, String> {
        self.check(path).and_then(|p| if p.is_dir() { Ok(p) } else { Err("gone".into()) })
    }

    /// Every photo, GIF, video and sound in the added folders, and the folders that hold them.
    pub fn list(&self) -> (Vec<Entry>, Vec<Folder>) {
        use std::collections::HashMap;
        let roots = self.roots();
        let (mut files, mut dirs) = (Vec::new(), Vec::new());
        // A folder added inside another added one is walked with it, so its files list once.
        let tops: Vec<&PathBuf> = roots.iter().filter(|r| !roots.iter().any(|o| o != *r && inside(r, o))).collect();
        for root in &tops {
            walk(root, 0, &mut files, &mut dirs);
        }
        let blurred: Vec<PathBuf> = dirs.iter().filter(|d| self.store.get(&key(d)).blur).cloned().collect();
        let entries: Vec<Entry> = files
            .into_iter()
            .filter_map(|(path, m)| {
                let name = path.file_name()?.to_string_lossy().into_owned();
                let kind = if locked(&path) { "locked" } else { library::kind(&name)? };
                let meta = self.store.get(&key(&path));
                let modified = m.modified().ok()?.duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
                let dir = path.parent()?.to_path_buf();
                // A blurred folder blurs everything in it and under it.
                let blur = meta.blur || blurred.iter().any(|b| inside(&dir, b));
                Some(Entry { dir, name, kind, bytes: m.len(), modified, fav: meta.fav, blur, tags: meta.tags, path })
            })
            .collect();
        // Counted once per file and per folder above it, not once per folder over every file.
        let (mut count, mut total): (HashMap<&Path, usize>, HashMap<&Path, usize>) = (HashMap::new(), HashMap::new());
        for e in &entries {
            *count.entry(e.dir.as_path()).or_default() += 1;
            for d in e.dir.ancestors() {
                *total.entry(d).or_default() += 1;
                if tops.iter().any(|t| t.as_path() == d) {
                    break;
                }
            }
        }
        let mut folders: Vec<Folder> = dirs
            .iter()
            .map(|d| {
                let parent = if tops.contains(&d) { None } else { d.parent().map(Path::to_path_buf) };
                let name = d.file_name().map_or_else(|| d.to_string_lossy().into_owned(), |n| n.to_string_lossy().into_owned());
                let blur = self.store.get(&key(d)).blur;
                let (count, total) = (count.get(d.as_path()).copied().unwrap_or(0), total.get(d.as_path()).copied().unwrap_or(0));
                Folder { path: d.clone(), name, parent, blur, count, total }
            })
            .collect();
        // Folders with nothing to show anywhere under them stay out, but added folders always show.
        folders.retain(|f| f.total > 0 || roots.contains(&f.path));
        (entries, folders)
    }

    /// Favorites files ("fav"), or blurs files and folders ("blur").
    pub fn mark(&self, paths: &[String], what: &str, on: bool) -> Result<(), String> {
        let keys = if what == "blur" { paths.iter().map(|p| self.check(p).map(|p| key(&p))).collect::<Result<_, _>>()? } else { self.keys(paths)? };
        self.store.edit(&keys, |m| match what {
            "fav" => m.fav = on,
            _ => m.blur = on,
        });
        Ok(())
    }

    /// Adds `add` and drops `drop` from each file's tags.
    pub fn tag(&self, paths: &[String], add: &[String], drop: &[String]) -> Result<(), String> {
        let keys = self.keys(paths)?;
        let add: Vec<String> = add.iter().map(|t| t.trim().chars().take(40).collect::<String>()).filter(|t| !t.is_empty()).collect();
        self.store.edit(&keys, |m| {
            m.tags.retain(|t| !drop.contains(t));
            for t in &add {
                if !m.tags.iter().any(|x| x.eq_ignore_ascii_case(t)) {
                    m.tags.push(t.clone());
                }
            }
            m.tags.sort_by_key(|t| t.to_lowercase());
        });
        Ok(())
    }

    fn keys(&self, paths: &[String]) -> Result<Vec<String>, String> {
        paths.iter().map(|p| self.checked_file(p).map(|p| key(&p))).collect()
    }

    /// Renames a file, keeping its extension, or a folder that isn't an added one.
    pub fn rename(&self, path: &str, name: &str) -> Result<PathBuf, String> {
        let from = self.check(path)?;
        if self.roots().contains(&from) {
            return Err("outside".into());
        }
        let name = clean(name)?;
        let file = from.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let ext = if from.is_dir() { "" } else { split(&file).1 };
        let to = from.with_file_name(if ext.is_empty() { name } else { format!("{name}.{ext}") });
        if to.exists() && key(&to).to_lowercase() != key(&from).to_lowercase() {
            return Err("taken".into());
        }
        std::fs::rename(&from, &to).map_err(|_| "blocked".to_string())?;
        self.store.rekey(&[(key(&from), key(&to))]);
        Ok(to)
    }

    /// Moves or copies files into a folder inside the added ones. A name taken there gets a number.
    /// Returns the new paths.
    pub fn place(&self, paths: &[String], dest: &str, copy: bool) -> Result<Vec<PathBuf>, String> {
        let dest = self.checked_dir(dest)?;
        let files = paths.iter().map(|p| self.checked_file(p)).collect::<Result<Vec<_>, _>>()?;
        let (mut done, mut moves) = (Vec::new(), Vec::new());
        for from in files {
            if !copy && from.parent() == Some(dest.as_path()) {
                done.push(from);
                continue;
            }
            let name = from.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            let (stem, ext) = split(&name);
            let to = unique(&dest, stem, ext);
            // A move to another drive is a copy, then the original goes to the Recycle Bin.
            let ok = if copy {
                std::fs::copy(&from, &to).is_ok()
            } else {
                std::fs::rename(&from, &to).is_ok() || (std::fs::copy(&from, &to).is_ok() && library::trash(std::slice::from_ref(&from)).is_ok())
            };
            if ok {
                if !copy {
                    moves.push((key(&from), key(&to)));
                }
                done.push(to);
            }
        }
        self.store.rekey(&moves);
        if done.is_empty() && !paths.is_empty() { Err("blocked".into()) } else { Ok(done) }
    }

    /// Moves a folder into another one on the same drive. Added folders, and folders holding one,
    /// stay where they are.
    pub fn move_dir(&self, path: &str, dest: &str) -> Result<PathBuf, String> {
        let (from, dest) = (self.checked_dir(path)?, self.checked_dir(dest)?);
        if self.roots().iter().any(|r| inside(r, &from)) {
            return Err("added".into());
        }
        if inside(&dest, &from) {
            return Err("into".into());
        }
        if from.parent() == Some(dest.as_path()) {
            return Ok(from);
        }
        let to = dest.join(from.file_name().ok_or("gone")?);
        if to.exists() {
            return Err("taken".into());
        }
        std::fs::rename(&from, &to).map_err(|e| if e.raw_os_error() == Some(17) { "drive".to_string() } else { "blocked".to_string() })?;
        self.store.rekey(&[(key(&from), key(&to))]);
        Ok(to)
    }

    /// Puts a repaired copy in place of a file, under the same name. The original goes to the
    /// Recycle Bin, so a bad repair can be undone there.
    pub fn replace(&self, path: &str, bytes: &[u8]) -> Result<(), String> {
        let to = self.checked_file(path)?;
        let tmp = unique(to.parent().ok_or("gone")?, &crate::drive::random(6), "lodisave-part");
        std::fs::write(&tmp, bytes).map_err(|_| "blocked".to_string())?;
        if let Err(e) = library::trash(std::slice::from_ref(&to)) {
            let _ = std::fs::remove_file(&tmp);
            return Err(e);
        }
        std::fs::rename(&tmp, &to).map_err(|_| "blocked".to_string())
    }

    /// Writes a locked copy of `original` beside it, under a random name so the name doesn't tell
    /// what it is. The original stays until `drop_original`, once the window has read the copy back.
    pub fn write_locked(&self, original: &str, bytes: &[u8]) -> Result<PathBuf, String> {
        let from = self.checked_file(original)?;
        if locked(&from) {
            return Err("locked".into());
        }
        let to = unique(from.parent().ok_or("gone")?, &crate::drive::random(6), "lodilock");
        write_whole(&to, bytes)?;
        Ok(to)
    }

    /// Deletes the original of a locked copy for good (the Recycle Bin would keep it readable), and
    /// moves its favorite and tags to the copy.
    pub fn drop_original(&self, original: &str, copy: &str) -> Result<(), String> {
        let (from, to) = (self.checked_file(original)?, self.checked_file(copy)?);
        if !locked(&to) || locked(&from) || from.parent() != to.parent() {
            return Err("outside".into());
        }
        std::fs::remove_file(&from).map_err(|_| "blocked".to_string())?;
        self.store.rekey(&[(key(&from), key(&to))]);
        Ok(())
    }

    /// Writes an unlocked file back under its own name and deletes the locked copy.
    pub fn write_unlocked(&self, copy: &str, name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
        let from = self.checked_file(copy)?;
        if !locked(&from) {
            return Err("outside".into());
        }
        let name = clean(name)?;
        let (stem, ext) = split(&name);
        let to = unique(from.parent().ok_or("gone")?, stem, ext);
        write_whole(&to, bytes)?;
        std::fs::remove_file(&from).map_err(|_| "blocked".to_string())?;
        self.store.rekey(&[(key(&from), key(&to))]);
        Ok(to)
    }

    /// Rewrites a locked file in place: the same file under a new password.
    pub fn relock(&self, copy: &str, bytes: &[u8]) -> Result<(), String> {
        let to = self.checked_file(copy)?;
        if !locked(&to) {
            return Err("outside".into());
        }
        write_whole(&to, bytes)
    }

    pub fn mkdir(&self, parent: &str, name: &str) -> Result<PathBuf, String> {
        let dir = self.checked_dir(parent)?.join(clean(name)?);
        if dir.exists() {
            return Err("taken".into());
        }
        std::fs::create_dir(&dir).map_err(|_| "blocked".to_string())?;
        Ok(dir)
    }

    pub fn trash(&self, paths: &[String]) -> Result<(), String> {
        let files = paths.iter().map(|p| self.checked_file(p)).collect::<Result<Vec<_>, _>>()?;
        library::trash(&files)
    }

    pub fn restore(&self, paths: &[String]) -> Result<Vec<PathBuf>, String> {
        let files = paths.iter().map(|p| self.under(p)).collect::<Result<Vec<_>, _>>()?;
        let back = library::restore(&files);
        if back.is_empty() { Err("restore".into()) } else { Ok(back) }
    }

    /// Groups of files with the same bytes, biggest first. Only files of the same size are read.
    pub fn duplicates(&self, paths: &[String]) -> Vec<Vec<PathBuf>> {
        use std::collections::HashMap;
        use std::hash::{BuildHasher, Hasher};
        let mut by_size: HashMap<u64, Vec<PathBuf>> = HashMap::new();
        for p in paths.iter().filter_map(|p| self.checked_file(p).ok()) {
            if let Ok(m) = p.metadata() {
                by_size.entry(m.len()).or_default().push(p);
            }
        }
        let state = std::hash::RandomState::new();
        let mut groups: Vec<(u64, Vec<PathBuf>)> = Vec::new();
        for (size, same) in by_size.into_iter().filter(|(s, v)| *s > 0 && v.len() > 1) {
            let mut by_hash: HashMap<u64, Vec<PathBuf>> = HashMap::new();
            for p in same {
                let Ok(bytes) = std::fs::read(&p) else { continue };
                let mut h = state.build_hasher();
                h.write(&bytes);
                by_hash.entry(h.finish()).or_default().push(p);
            }
            groups.extend(by_hash.into_values().filter(|g| g.len() > 1).map(|g| (size, g)));
        }
        groups.sort_by(|a, b| b.0.cmp(&a.0));
        groups.into_iter().map(|(_, g)| g).collect()
    }
}

fn locked(p: &Path) -> bool {
    p.extension().is_some_and(|e| e.eq_ignore_ascii_case("lodilock"))
}

/// Written beside and renamed over, so a crash mid-write never leaves half a file.
fn write_whole(to: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = to.with_extension("lodisave-part");
    std::fs::write(&tmp, bytes).and_then(|_| std::fs::rename(&tmp, to)).map_err(|_| {
        let _ = std::fs::remove_file(&tmp);
        "blocked".to_string()
    })
}

/// Folders and files under `dir`, skipping links and junctions, hidden and system folders,
/// dot-folders and files that are only in the cloud.
fn walk(dir: &Path, depth: usize, files: &mut Vec<(PathBuf, std::fs::Metadata)>, dirs: &mut Vec<PathBuf>) {
    dirs.push(dir.to_path_buf());
    for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        if files.len() >= MAX_FILES {
            return;
        }
        let (Ok(t), Ok(m)) = (e.file_type(), e.metadata()) else { continue };
        let name = e.file_name();
        let attrs = m.file_attributes();
        if t.is_symlink() || name.to_string_lossy().starts_with(['.', '$']) {
            continue;
        }
        if t.is_dir() {
            if depth < DEPTH && attrs & (HIDDEN | SYSTEM) == 0 && name != "node_modules" {
                walk(&e.path(), depth + 1, files, dirs);
            }
        } else if t.is_file() && attrs & (HIDDEN | NOT_HERE) == 0 {
            files.push((e.path(), m));
        }
    }
}

/// Sets a photo as the desktop background.
pub fn wallpaper(path: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::UI::WindowsAndMessaging::{SPI_SETDESKWALLPAPER, SPIF_SENDCHANGE, SPIF_UPDATEINIFILE, SystemParametersInfoW};
    let mut wide: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
    unsafe { SystemParametersInfoW(SPI_SETDESKWALLPAPER, 0, Some(wide.as_mut_ptr().cast()), SPIF_UPDATEINIFILE | SPIF_SENDCHANGE) }.map_err(|_| "blocked".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        // Not in %TEMP%, which is inside AppData, a folder the gallery refuses.
        let d = Path::new(env!("CARGO_MANIFEST_DIR")).join("target").join(format!("gallery-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        plain(d.canonicalize().unwrap())
    }

    #[test]
    fn only_added_folders() {
        let d = scratch("safe");
        let g = Gallery::open(&d.join("data"));
        let pics = d.join("pics");
        std::fs::create_dir_all(pics.join("trip")).unwrap();
        std::fs::write(pics.join("a.jpg"), b"x").unwrap();
        std::fs::write(pics.join("trip/b.mp4"), b"yy").unwrap();
        std::fs::write(pics.join("notes.txt"), b"z").unwrap();
        std::fs::write(d.join("outside.jpg"), b"x").unwrap();

        // System folders and drive roots are refused.
        assert_eq!(g.add(Path::new("C:\\")), Err("root".into()));
        assert_eq!(g.add(&PathBuf::from(std::env::var("SystemRoot").unwrap())), Err("system".into()));
        assert_eq!(g.add(&PathBuf::from(std::env::var("LOCALAPPDATA").unwrap())), Err("system".into()));
        assert_eq!(g.add(&PathBuf::from(std::env::var("USERPROFILE").unwrap())), Err("system".into()));

        g.add(&pics).unwrap();
        g.add(&pics.join("trip")).unwrap(); // its own folder too, but its files list once
        assert_eq!(g.roots(), [pics.clone(), pics.join("trip")]);
        let (items, folders) = g.list();
        assert_eq!(items.len(), 2);
        assert_eq!(folders.len(), 2);
        assert_eq!(folders.iter().find(|f| f.path == pics).map(|f| f.total), Some(2));
        g.remove(&pics.join("trip").to_string_lossy());

        // Paths outside, or reached through .., are refused.
        let out = d.join("outside.jpg").to_string_lossy().into_owned();
        assert_eq!(g.check(&out), Err("outside".into()));
        assert_eq!(g.check(&pics.join("..\\outside.jpg").to_string_lossy()), Err("outside".into()));
        assert_eq!(g.under(&pics.join("..\\outside.jpg").to_string_lossy()), Err("outside".into()));
        assert_eq!(g.trash(&[out]), Err("outside".into()));

        // Organizing inside stays inside, and favorites follow the file.
        let a = pics.join("a.jpg").to_string_lossy().into_owned();
        g.mark(std::slice::from_ref(&a), "fav", true).unwrap();
        g.tag(std::slice::from_ref(&a), &["beach".into()], &[]).unwrap();
        let moved = g.place(&[a], &pics.join("trip").to_string_lossy(), false).unwrap();
        assert_eq!(moved, [pics.join("trip").join("a.jpg")]);
        let meta = g.store.get(&key(&moved[0]));
        assert!(meta.fav && meta.tags == ["beach"]);
        let copy = g.place(&[moved[0].to_string_lossy().into_owned()], &pics.to_string_lossy(), true).unwrap();
        assert!(copy[0].is_file() && moved[0].is_file());
        assert_eq!(g.duplicates(&[moved[0].to_string_lossy().into_owned(), copy[0].to_string_lossy().into_owned()]).len(), 1);
        let renamed = g.rename(&copy[0].to_string_lossy(), "sunset").unwrap();
        assert_eq!(renamed.file_name().unwrap(), "sunset.jpg");
        assert_eq!(g.rename(&pics.to_string_lossy(), "x"), Err("outside".into()));

        // Folder blur reaches the files under it.
        g.mark(&[pics.join("trip").to_string_lossy().into_owned()], "blur", true).unwrap();
        assert!(g.list().0.iter().filter(|e| e.dir == pics.join("trip")).all(|e| e.blur));

        // Locking swaps the file for a .lodilock copy, and unlocking swaps it back, keeping its favorite.
        let photo = moved[0].to_string_lossy().into_owned();
        let copy = g.write_locked(&photo, b"sealed").unwrap();
        assert!(copy.extension().unwrap() == "lodilock" && moved[0].is_file());
        assert!(g.write_locked(&copy.to_string_lossy(), b"x").is_err());
        g.drop_original(&photo, &copy.to_string_lossy()).unwrap();
        assert!(!moved[0].exists() && g.store.get(&key(&copy)).fav);
        assert_eq!(g.list().0.iter().filter(|e| e.kind == "locked").count(), 1);
        let back = g.write_unlocked(&copy.to_string_lossy(), "a.jpg", b"x").unwrap();
        assert!(back.is_file() && !copy.exists() && g.store.get(&key(&back)).fav);
        assert!(g.write_unlocked(&back.to_string_lossy(), "b.jpg", b"x").is_err());
        assert!(g.mkdir(&pics.to_string_lossy(), "..\\evil").is_err());

        // A repaired file takes the original's place.
        g.replace(&back.to_string_lossy(), b"fixed").unwrap();
        assert_eq!(std::fs::read(&back).unwrap(), b"fixed");

        // A folder moves into another and its marks go with it; never into itself.
        let inner = g.mkdir(&pics.to_string_lossy(), "keep").unwrap();
        let trip = pics.join("trip").to_string_lossy().into_owned();
        assert_eq!(g.move_dir(&trip, &trip), Err("into".into()));
        let moved = g.move_dir(&trip, &inner.to_string_lossy()).unwrap();
        assert!(moved.is_dir() && g.store.get(&key(&moved)).blur);
        assert_eq!(g.move_dir(&pics.to_string_lossy(), &inner.to_string_lossy()), Err("added".into()));
        g.remove(&pics.to_string_lossy());
        assert!(g.roots().is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }
}
