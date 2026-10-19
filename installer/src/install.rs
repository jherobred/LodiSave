//! Installing: unpack the payload, then add shortcuts, the Apps entry and autostart.

use crate::win::{self, Key};
use crate::{APP_EXE, APP_NAME, PAYLOAD, PAYLOAD_BYTES, UNINSTALLER_EXE, VERSION};
use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::os::windows::process::CommandExt;
use std::path::{Component, Path, PathBuf};
use std::process::Command;
use std::time::Duration;

pub const UNINSTALL_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\LodiSave";
const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
const STARTUP_APPROVED_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
/// Lists every installed file so updates and the uninstaller remove exactly those.
pub const MANIFEST: &str = "install-manifest.txt";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const DESCRIPTION: &str = "Save the images and videos that sites block you from saving.";
const BLOCKED: &str = "If Controlled folder access is on, allow LodiSave Setup under Ransomware protection in Windows Security, then run Setup again.";

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Options {
    pub dir: PathBuf,
    pub desktop_shortcut: bool,
    pub start_with_windows: bool,
}

/// A copy that is already installed.
#[derive(Clone, Debug, Serialize)]
pub struct Existing {
    pub dir: PathBuf,
    pub version: String,
}

pub fn existing() -> Option<Existing> {
    let key = Key::open(UNINSTALL_KEY)?;
    let dir = PathBuf::from(key.get_str("InstallLocation")?);
    dir.join(APP_EXE).exists().then(|| Existing {
        dir,
        version: key.get_str("DisplayVersion").unwrap_or_default(),
    })
}

pub fn default_dir() -> PathBuf {
    win::known_folder(&win::FOLDERID_LocalAppData)
        .map(|p| p.join("Programs"))
        .unwrap_or_else(|_| std::env::temp_dir())
        .join(APP_NAME)
}

fn start_menu_shortcut() -> Result<PathBuf> {
    Ok(win::known_folder(&win::FOLDERID_Programs)?.join(format!("{APP_NAME}.lnk")))
}

fn desktop_shortcut() -> Result<PathBuf> {
    Ok(win::known_folder(&win::FOLDERID_Desktop)?.join(format!("{APP_NAME}.lnk")))
}

/// `report` receives the number of bytes written so far and the file being written.
/// Returns notes about the steps Windows didn't allow, such as a shortcut that Controlled
/// folder access blocked. Those steps don't fail the install.
pub fn install(options: &Options, report: &dyn Fn(u64, &str)) -> Result<Vec<String>> {
    let dir = &options.dir;
    quit_running(dir)?;
    fs::create_dir_all(dir).with_context(|| format!("create {}", dir.display()))?;

    let previous = read_manifest(dir);
    let files = unpack(dir, report)?;
    // Remove what an earlier version installed and this one no longer ships.
    for old in previous.iter().filter(|f| !files.contains(f)) {
        let _ = fs::remove_file(dir.join(old));
    }
    remove_empty_dirs(dir, &previous);
    fs::write(dir.join(MANIFEST), files.join("\n")).context("write the install manifest")?;

    let mut notes = Vec::new();
    let exe = dir.join(APP_EXE);
    // Controlled folder access refuses these with "file not found", so the note says what to do.
    if let Err(e) = win::create_shortcut(&start_menu_shortcut()?, &exe, DESCRIPTION) {
        crate::note(&format!("start menu shortcut: {e}"));
        notes.push(format!("Windows didn't let Setup add the Start menu shortcut. {BLOCKED}"));
    }
    let desktop = desktop_shortcut()?;
    if options.desktop_shortcut {
        if let Err(e) = win::create_shortcut(&desktop, &exe, DESCRIPTION) {
            crate::note(&format!("desktop shortcut: {e}"));
            notes.push(format!("Windows didn't let Setup add the desktop shortcut. {BLOCKED}"));
        }
    } else if !remove_file(&desktop) {
        notes.push(left_behind(&[desktop]));
    }
    register(dir)?;
    set_autostart(options.start_with_windows.then_some(exe.as_path()))?;
    Ok(notes)
}

fn left_behind(paths: &[PathBuf]) -> String {
    let mut list: Vec<String> = paths.iter().take(3).map(|p| p.display().to_string()).collect();
    if paths.len() > 3 {
        list.push(format!("{} more", paths.len() - 3));
    }
    let them = if paths.len() == 1 { "it" } else { "them" };
    format!("Windows didn't allow deleting {}. You can delete {them} yourself.", list.join(", "))
}

/// Asks a running LodiSave to quit, then waits until its files can be replaced.
pub fn quit_running(dir: &Path) -> Result<()> {
    let exe = dir.join(APP_EXE);
    if !exe.exists() {
        return Ok(());
    }
    // With a copy running this hands it "--quit". Otherwise it starts and exits at once.
    let _ = Command::new(&exe).arg("--quit").creation_flags(CREATE_NO_WINDOW).status();
    for _ in 0..50 {
        if fs::OpenOptions::new().write(true).open(&exe).is_ok() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    bail!("LodiSave is still running. Quit it from its tray icon, then try again.")
}

fn unpack(dir: &Path, report: &dyn Fn(u64, &str)) -> Result<Vec<String>> {
    let mut decoder = zstd::stream::read::Decoder::new(PAYLOAD).context("read the payload")?;
    decoder.window_log_max(27)?;
    let mut archive = tar::Archive::new(decoder);
    let mut buf = vec![0u8; 1 << 20];
    let mut written = 0u64;
    let mut names = Vec::new();
    for entry in archive.entries().context("read the payload")? {
        let mut entry = entry.context("read the payload")?;
        if !entry.header().entry_type().is_file() {
            continue;
        }
        let rel = entry.path()?.into_owned();
        if rel.components().any(|c| !matches!(c, Component::Normal(_))) {
            bail!("the payload contains an unsafe path: {}", rel.display());
        }
        let name = rel.to_string_lossy().replace('\\', "/");
        let dest = dir.join(&rel);
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut out = create_file(&dest)?;
        loop {
            let n = entry.read(&mut buf).context("unpack the payload")?;
            if n == 0 {
                break;
            }
            out.write_all(&buf[..n])
                .with_context(|| format!("write {}", dest.display()))?;
            written += n as u64;
            report(written, &name);
        }
        names.push(name);
    }
    if written != PAYLOAD_BYTES {
        bail!("the installer is damaged. Download it again.");
    }
    Ok(names)
}

/// Creates or truncates a file, waiting briefly if something still holds it open.
fn create_file(path: &Path) -> Result<File> {
    let mut last = None;
    for _ in 0..25 {
        match File::create(path) {
            Ok(f) => return Ok(f),
            Err(e) => last = Some(e),
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    Err(last.unwrap()).with_context(|| format!("replace {}. Close any program using it and try again", path.display()))
}

/// Deletes a file, retrying briefly while another program has it open. Returns false when
/// the file is still there, for example because Controlled folder access protects it.
fn remove_file(path: &Path) -> bool {
    for _ in 0..10 {
        match fs::remove_file(path) {
            Ok(()) => return true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return true,
            // Retrying won't change a refusal.
            Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => return false,
            Err(_) => std::thread::sleep(Duration::from_millis(200)),
        }
    }
    !path.exists()
}

pub fn read_manifest(dir: &Path) -> Vec<String> {
    fs::read_to_string(dir.join(MANIFEST))
        .map(|s| s.lines().filter(|l| !l.is_empty()).map(str::to_string).collect())
        .unwrap_or_default()
}

/// Removes the folders that held `files`, deepest first, when they are empty.
pub fn remove_empty_dirs(dir: &Path, files: &[String]) {
    let mut dirs: Vec<PathBuf> = files
        .iter()
        .flat_map(|f| Path::new(f).ancestors().skip(1).map(Path::to_path_buf).collect::<Vec<_>>())
        .filter(|d| !d.as_os_str().is_empty())
        .collect();
    dirs.sort_by_key(|d| std::cmp::Reverse(d.components().count()));
    dirs.dedup();
    for d in dirs {
        let _ = fs::remove_dir(dir.join(d));
    }
}

/// The entry in Settings > Apps > Installed apps.
fn register(dir: &Path) -> Result<()> {
    let key = Key::create(UNINSTALL_KEY)?;
    let exe = dir.join(APP_EXE);
    let uninstaller = dir.join(UNINSTALLER_EXE);
    key.set_str("DisplayName", APP_NAME)?;
    key.set_str("DisplayVersion", VERSION)?;
    key.set_str("Publisher", "LodiSave contributors")?;
    key.set_str("Comments", DESCRIPTION)?;
    key.set_str("DisplayIcon", &format!("\"{}\",0", exe.display()))?;
    key.set_str("InstallLocation", &dir.display().to_string())?;
    key.set_str("UninstallString", &format!("\"{}\"", uninstaller.display()))?;
    key.set_str("QuietUninstallString", &format!("\"{}\" --silent", uninstaller.display()))?;
    key.set_str("URLInfoAbout", crate::REPO_URL)?;
    key.set_str("HelpLink", &format!("{}/issues", crate::REPO_URL))?;
    key.set_str("InstallDate", &today())?;
    key.set_dword("EstimatedSize", (PAYLOAD_BYTES / 1024) as u32)?;
    key.set_dword("NoModify", 1)?;
    key.set_dword("NoRepair", 1)?;
    Ok(())
}

/// The same registry values the app's own "Start with Windows" setting writes, under the
/// app's product name.
pub fn set_autostart(exe: Option<&Path>) -> Result<()> {
    let run = Key::create(RUN_KEY)?;
    match exe {
        Some(exe) => {
            run.set_str(APP_NAME, &format!("\"{}\" --background", exe.display()))?;
            // Task Manager's startup switch. 02 followed by zeros means enabled.
            if let Some(approved) = Key::open(STARTUP_APPROVED_KEY) {
                approved.set_binary(APP_NAME, &[2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])?;
            }
        }
        None => {
            run.delete_value(APP_NAME);
            if let Some(approved) = Key::open(STARTUP_APPROVED_KEY) {
                approved.delete_value(APP_NAME);
            }
        }
    }
    Ok(())
}

/// Removes everything `install` created. `report` receives progress from 0 to 1. Returns a
/// note listing anything Windows didn't allow deleting.
pub fn uninstall(dir: &Path, remove_data: bool, report: &dyn Fn(f64)) -> Result<Vec<String>> {
    let mut files = read_manifest(dir);
    let fallback = files.is_empty();
    if fallback {
        if !dir.join(APP_EXE).exists() {
            bail!("LodiSave isn't installed in {}.", dir.display());
        }
        files = vec![APP_EXE.into(), UNINSTALLER_EXE.into()];
    }
    quit_running(dir)?;
    let mut left = Vec::new();
    for lnk in [start_menu_shortcut(), desktop_shortcut()].into_iter().flatten() {
        if !remove_file(&lnk) {
            left.push(lnk);
        }
    }
    set_autostart(None)?;

    let total = files.len() as f64;
    for (i, f) in files.iter().enumerate() {
        let path = dir.join(f);
        if !remove_file(&path) {
            left.push(path);
        }
        report((i + 1) as f64 / total * 0.9);
    }
    if fallback {
        // Without a manifest, the bundled folders are known by name.
        for folder in ["extension"] {
            let _ = fs::remove_dir_all(dir.join(folder));
        }
    }
    let _ = fs::remove_file(dir.join(MANIFEST));
    remove_empty_dirs(dir, &files);
    let _ = fs::remove_dir(dir);
    win::delete_key(UNINSTALL_KEY);

    if remove_data {
        for root in [win::FOLDERID_RoamingAppData, win::FOLDERID_LocalAppData] {
            if let Ok(base) = win::known_folder(&root) {
                let _ = fs::remove_dir_all(base.join(crate::IDENTIFIER));
            }
        }
    }
    report(1.0);
    Ok(if left.is_empty() { Vec::new() } else { vec![left_behind(&left)] })
}

fn today() -> String {
    // Days since 1970 to a civil date (Howard Hinnant's algorithm), to avoid a date crate.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let z = (secs / 86_400) as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}{m:02}{d:02}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_dirs_are_removed_deepest_first() {
        let root = std::env::temp_dir().join(format!("lodi-setup-test-{}", std::process::id()));
        fs::create_dir_all(root.join("a/b")).unwrap();
        fs::create_dir_all(root.join("c")).unwrap();
        fs::write(root.join("c/keep.txt"), "user file").unwrap();
        remove_empty_dirs(&root, &["a/b/x.dll".into(), "c/y.dll".into()]);
        assert!(!root.join("a").exists());
        assert!(root.join("c/keep.txt").exists());
        fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn leftovers_are_summarized() {
        let one = left_behind(&[PathBuf::from(r"C:\Users\x\Desktop\LodiSave.lnk")]);
        assert!(one.contains(r"Desktop\LodiSave.lnk") && one.ends_with("delete it yourself."));
        let many: Vec<PathBuf> = (0..5).map(|i| PathBuf::from(format!("f{i}.dll"))).collect();
        let text = left_behind(&many);
        assert!(text.contains("f2.dll, 2 more") && text.ends_with("delete them yourself."));
    }

    #[test]
    fn install_date_is_eight_digits() {
        let d = today();
        assert_eq!(d.len(), 8);
        assert!(d.starts_with("20"));
    }
}
