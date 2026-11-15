//! Updates from the project's GitHub releases. Check reads the newest release; install downloads the
//! installer (or, for the portable copy, the new portable exe), checks it against the release's
//! SHA256SUMS.txt, puts it in place and starts the new version.

use crate::links::command;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use tauri::AppHandle;
use tauri_plugin_autostart::ManagerExt;

const LATEST: &str = "https://api.github.com/repos/jherobred/LodiSave/releases/latest";

/// "v0.4.1" or "0.4.1-fix1" as numbers, so 0.10.0 sorts after 0.9.0.
fn numbers(v: &str) -> Vec<u32> {
    v.trim_start_matches('v').split(['-', '+']).next().unwrap_or("").split('.').map(|n| n.parse().unwrap_or(0)).collect()
}

pub fn newer(latest: &str, current: &str) -> bool {
    numbers(latest) > numbers(current)
}

fn latest() -> Result<Value, String> {
    let out = command("curl.exe")
        .args(["-fsSL", "--connect-timeout", "20", "-H", "Accept: application/vnd.github+json", "-A", "LodiSave", LATEST])
        .output()
        .map_err(|_| "offline")?;
    if !out.status.success() {
        return Err("offline".into());
    }
    serde_json::from_slice(&out.stdout).map_err(|_| "offline".into())
}

fn asset<'a>(release: &'a Value, wanted: impl Fn(&str) -> bool) -> Option<(&'a str, &'a str)> {
    release["assets"].as_array()?.iter().find_map(|a| {
        let name = a["name"].as_str()?;
        wanted(name).then_some((name, a["browser_download_url"].as_str()?))
    })
}

/// The installed copy has its uninstaller beside it; a portable copy doesn't.
fn installed(exe: &Path) -> bool {
    exe.parent().is_some_and(|d| d.join("Uninstall LodiSave.exe").exists())
}

fn wanted_file(installed: bool) -> impl Fn(&str) -> bool {
    move |n: &str| if installed { n.starts_with("LodiSave-Setup-") && n.ends_with(".exe") } else { n.ends_with("-portable.exe") }
}

pub fn check(current: &str) -> Result<Value, String> {
    let release = latest()?;
    let tag = release["tag_name"].as_str().unwrap_or("");
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    Ok(json!({
        "current": current,
        "latest": tag.trim_start_matches('v'),
        "newer": newer(tag, current) && asset(&release, wanted_file(installed(&exe))).is_some(),
        "page": release["html_url"],
    }))
}

fn download(url: &str, to: &Path) -> Result<(), String> {
    let ok = command("curl.exe")
        .args(["-fsSL", "--retry", "2", "--connect-timeout", "30", "--speed-time", "30", "--speed-limit", "1", "-A", "LodiSave", "-o"])
        .arg(to)
        .arg(url)
        .status()
        .is_ok_and(|s| s.success());
    if ok { Ok(()) } else { Err("offline".into()) }
}

fn sha256(file: &Path) -> Option<String> {
    let out = command("certutil.exe").arg("-hashfile").arg(file).arg("SHA256").output().ok()?;
    // The second line is the hash, sometimes with spaces between byte pairs.
    let text = String::from_utf8_lossy(&out.stdout);
    let line = text.lines().nth(1)?.replace(' ', "").to_ascii_lowercase();
    (line.len() == 64).then_some(line)
}

/// Whether the user's Desktop has the LodiSave shortcut, so a silent install keeps their choice.
fn desktop_shortcut() -> bool {
    use windows::Win32::UI::Shell::{FOLDERID_Desktop, KF_FLAG_DEFAULT, SHGetKnownFolderPath};
    unsafe { SHGetKnownFolderPath(&FOLDERID_Desktop, KF_FLAG_DEFAULT, None) }
        .ok()
        .and_then(|p| unsafe { p.to_string() }.ok())
        .is_some_and(|d| PathBuf::from(d).join("LodiSave.lnk").exists())
}

/// Downloads and starts the new version. On success the caller quits the app (the installer also
/// asks it to).
pub fn install(app: &AppHandle) -> Result<(), String> {
    let release = latest()?;
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let setup = installed(&exe);
    let (name, url) = asset(&release, wanted_file(setup)).ok_or("noupdate")?;
    let (_, sums_url) = asset(&release, |n| n == "SHA256SUMS.txt").ok_or("nosums")?;

    let dir = std::env::temp_dir().join("LodiSave-update");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let file = dir.join(name);
    let sums = dir.join("SHA256SUMS.txt");
    download(sums_url, &sums)?;
    download(url, &file)?;
    let expected = std::fs::read_to_string(&sums)
        .ok()
        .and_then(|s| s.lines().find_map(|l| l.split_once(char::is_whitespace).filter(|(_, n)| n.trim().trim_start_matches('*') == name).map(|(h, _)| h.to_ascii_lowercase())))
        .ok_or("nosums")?;
    if sha256(&file).as_deref() != Some(expected.as_str()) {
        let _ = std::fs::remove_dir_all(&dir);
        return Err("corrupt".into());
    }

    if setup {
        let mut c = command("cmd.exe");
        // The installer quits this copy, replaces it, then cmd starts the new one.
        let mut line = format!("\"\"{}\" --silent", file.display());
        if !desktop_shortcut() {
            line += " --no-desktop-shortcut";
        }
        if !app.autolaunch().is_enabled().unwrap_or(false) {
            line += " --no-autostart";
        }
        line += &format!(" && start \"\" \"{}\"\"", exe.display());
        use std::os::windows::process::CommandExt;
        c.raw_arg("/c").raw_arg(line);
        c.spawn().map_err(|e| e.to_string())?;
    } else {
        // Windows lets a running exe be renamed, so the new one can take its name now.
        let old = exe.with_extension("exe.old");
        let _ = std::fs::remove_file(&old);
        std::fs::rename(&exe, &old).map_err(|_| "blocked")?;
        if std::fs::copy(&file, &exe).is_err() {
            let _ = std::fs::rename(&old, &exe);
            return Err("blocked".into());
        }
        // Started once this copy has quit, or single-instance would hand it back to this one.
        use std::os::windows::process::CommandExt;
        command("cmd.exe")
            .raw_arg("/c")
            .raw_arg(format!("\"ping -n 3 127.0.0.1 >nul & start \"\" \"{}\"\"", exe.display()))
            .spawn()
            .map_err(|e| e.to_string())?;
        app.exit(0);
    }
    Ok(())
}

/// The portable copy's previous exe, left behind by an update.
pub fn tidy() {
    if let Ok(exe) = std::env::current_exe() {
        let _ = std::fs::remove_file(exe.with_extension("exe.old"));
    }
}

#[cfg(test)]
mod tests {
    use super::newer;

    #[test]
    fn compares_versions_as_numbers() {
        assert!(newer("v0.4.1", "0.4.0"));
        assert!(newer("v0.10.0", "0.9.9"));
        assert!(!newer("v0.4.0", "0.4.0"));
        assert!(!newer("v0.3.2-fix1", "0.4.0"));
    }
}
