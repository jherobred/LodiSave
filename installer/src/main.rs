//! LodiSave's installer. The same program is the uninstaller: a build without a payload
//! (see build.rs) removes the app instead of installing it.
//!
//! Usage:
//!   LodiSave-Setup.exe [--silent] [--dir <folder>] [--no-desktop-shortcut] [--no-autostart]
//!   "Uninstall LodiSave.exe" [--silent] [--remove-data]

#![windows_subsystem = "windows"]

mod install;
mod ui;
mod win;

use std::path::PathBuf;
use std::process::ExitCode;

include!(concat!(env!("OUT_DIR"), "/payload.rs"));

pub const APP_NAME: &str = "LodiSave";
pub const APP_EXE: &str = "LodiSave.exe";
pub const UNINSTALLER_EXE: &str = "Uninstall LodiSave.exe";
/// The app's identifier, which names its settings and cache folders.
pub const IDENTIFIER: &str = "io.github.jherobred.lodisave";
pub const VERSION: &str = env!("LODI_APP_VERSION");
pub const REPO_URL: &str = "https://github.com/jherobred/LodiSave";

struct Args(Vec<String>);

impl Args {
    fn flag(&self, name: &str) -> bool {
        self.0.iter().any(|a| a == name)
    }

    fn value(&self, name: &str) -> Option<String> {
        let i = self.0.iter().position(|a| a == name)?;
        self.0.get(i + 1).cloned()
    }
}

fn main() -> ExitCode {
    let args = Args(std::env::args().collect());
    let silent = args.flag("--silent");
    win::init_com();

    if PAYLOAD.is_empty() {
        return uninstaller(&args, silent);
    }

    let existing = install::existing();
    if silent {
        let options = install::Options {
            dir: args
                .value("--dir")
                .map(PathBuf::from)
                .or_else(|| existing.as_ref().map(|e| e.dir.clone()))
                .unwrap_or_else(install::default_dir),
            desktop_shortcut: !args.flag("--no-desktop-shortcut"),
            start_with_windows: !args.flag("--no-autostart"),
        };
        return exit(install::install(&options, &|_, _| {}));
    }
    ui::run(ui::Mode::Install { existing })
}

/// The uninstaller can't delete itself while it runs, so it first copies itself to the
/// temp folder and continues from there once this copy has exited.
fn uninstaller(args: &Args, silent: bool) -> ExitCode {
    let Some(dir) = args.value("--uninstall-from").map(PathBuf::from) else {
        let Ok(exe) = std::env::current_exe() else { return ExitCode::FAILURE };
        let Some(dir) = exe.parent() else { return ExitCode::FAILURE };
        let copy = std::env::temp_dir().join(format!("LodiSave-Uninstall-{}.exe", std::process::id()));
        if std::fs::copy(&exe, &copy).is_err() {
            win::error_box("LodiSave couldn't start its uninstaller. Try again.");
            return ExitCode::FAILURE;
        }
        let mut command = std::process::Command::new(&copy);
        command
            .arg("--uninstall-from")
            .arg(dir)
            .arg("--wait-pid")
            .arg(std::process::id().to_string());
        for flag in ["--silent", "--remove-data"] {
            if args.flag(flag) {
                command.arg(flag);
            }
        }
        return match command.spawn() {
            Ok(_) => ExitCode::SUCCESS,
            Err(_) => ExitCode::FAILURE,
        };
    };
    if let Some(pid) = args.value("--wait-pid").and_then(|p| p.parse().ok()) {
        win::wait_for_process(pid, 10_000);
    }
    if silent {
        return exit(install::uninstall(&dir, args.flag("--remove-data"), &|_| {}));
    }
    ui::run(ui::Mode::Uninstall { dir })
}

/// Appends a line to %TEMP%\LodiSave-Setup.log, where problems end up when there is no
/// window to show them in.
pub fn note(line: &str) {
    use std::io::Write;
    if let Ok(mut log) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(std::env::temp_dir().join("LodiSave-Setup.log"))
    {
        let _ = writeln!(log, "{line}");
    }
}

fn exit(result: anyhow::Result<Vec<String>>) -> ExitCode {
    match result {
        Ok(notes) => {
            notes.iter().for_each(|n| note(n));
            ExitCode::SUCCESS
        }
        Err(e) => {
            note(&format!("{e:#}"));
            ExitCode::FAILURE
        }
    }
}
