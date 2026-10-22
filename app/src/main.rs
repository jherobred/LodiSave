//! LodiSave for Windows: a tray panel that saves the link or image you copied, captures a screen
//! area, and lists recent saves. Saves go to Downloads\LodiSave, next to the browser extension's.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod clip;
mod links;
mod mp4;

use serde::Serialize;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};
use tauri_plugin_opener::OpenerExt;
use windows::Win32::System::Registry::{HKEY, HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, RRF_RT_REG_SZ, RegGetValueW};
use windows::core::{HSTRING, PCWSTR};

/// The panel is 380x600; the window adds room for its shadow.
const W: f64 = 404.0;
const H: f64 = 624.0;
const SHORTCUT: &str = "ctrl+alt+l";
const REPO: &str = "https://github.com/jherobred/LodiSave";

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

fn save_dir(app: &AppHandle) -> PathBuf {
    let dir = app.path().download_dir().unwrap_or_else(|_| std::env::temp_dir()).join("LodiSave");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

/// Controlled folder access (Windows Security) stops unknown apps from writing to protected
/// folders, and it can protect Downloads. Checking first lets the panel say so instead of failing
/// halfway or quietly settling for a lower-quality file.
pub fn writable(dir: &Path) -> Result<(), String> {
    let probe = dir.join(format!(".lodisave-{}", std::process::id()));
    std::fs::create_dir_all(dir).and_then(|_| std::fs::write(&probe, b"")).map_err(|_| "blocked".to_string())?;
    let _ = std::fs::remove_file(probe);
    Ok(())
}

/// Paths the panel hands back must stay inside the save folder.
fn inside(app: &AppHandle, path: &str) -> Result<PathBuf, String> {
    let p = Path::new(path).canonicalize().map_err(|e| e.to_string())?;
    let dir = save_dir(app).canonicalize().map_err(|e| e.to_string())?;
    if p.starts_with(&dir) { Ok(p) } else { Err("outside".into()) }
}

static HIDDEN_AT: AtomicU64 = AtomicU64::new(0);

/// Above the tray icon at `anchor`, or at the bottom-right of the screen. A peek shows the panel
/// without taking focus, to report a save that finished in the background.
fn show_panel(app: &AppHandle, anchor: Option<(i32, i32)>, peek: bool) {
    let Some(win) = app.get_webview_window("panel") else { return };
    let monitor = anchor
        .and_then(|(x, y)| app.monitor_from_point(x as f64, y as f64).ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten());
    if let Some(m) = monitor {
        let (scale, area) = (m.scale_factor(), m.work_area());
        let (w, h) = ((W * scale).round() as i32, (H * scale).round() as i32);
        let (left, top) = (area.position.x, area.position.y);
        let (right, bottom) = (left + area.size.width as i32, top + area.size.height as i32);
        let x = anchor.map_or(right - w, |(ax, _)| (ax - w / 2).clamp(left, (right - w).max(left)));
        // The taskbar is usually at the bottom; a top taskbar puts the panel under it.
        let y = match anchor {
            Some((_, ay)) if ay < (top + bottom) / 2 => top,
            _ => bottom - h,
        };
        // Windows makes the window at the system scale, which with a 125% laptop screen and a 100%
        // monitor is the wrong size for the monitor it shows on, and a hidden window isn't rescaled.
        // Moved first so any rescale happens, then sized for this monitor, so it fits where it lands.
        let _ = win.set_position(PhysicalPosition::new(x, y));
        let _ = win.set_size(PhysicalSize::new(w as u32, h as u32));
        let _ = win.set_position(PhysicalPosition::new(x, y));
    }
    let _ = win.show();
    if !peek {
        let _ = win.set_focus();
    }
    let _ = win.emit("panel://shown", serde_json::json!({ "peek": peek }));
}

fn hide_panel(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("panel") {
        let _ = win.hide();
    }
}

fn toggle_panel(app: &AppHandle, anchor: (i32, i32)) {
    let visible = app.get_webview_window("panel").and_then(|w| w.is_visible().ok()).unwrap_or(false);
    // A click on the tray icon blurs the panel first, which hides it. That click meant "close".
    if visible {
        hide_panel(app);
    } else if now_ms().saturating_sub(HIDDEN_AT.load(Ordering::SeqCst)) > 300 {
        show_panel(app, Some(anchor), false);
    }
}

fn panel_visible(app: &AppHandle) -> bool {
    app.get_webview_window("panel").and_then(|w| w.is_visible().ok()).unwrap_or(false)
}

fn start_capture(app: &AppHandle) {
    if let Err(code) = writable(&save_dir(app)) {
        show_panel(app, None, false);
        let _ = app.emit("capture://failed", code);
        return;
    }
    hide_panel(app);
    let app = app.clone();
    std::thread::spawn(move || {
        if let Some(path) = clip::capture(&save_dir(&app)) {
            show_panel(&app, None, true);
            let _ = app.emit("capture://saved", path);
        }
    });
}

#[derive(Serialize)]
struct Clip {
    link: Option<String>,
    image: bool,
}

#[tauri::command]
fn clipboard() -> Clip {
    let link = clip::link();
    let image = link.is_none() && clip::has_image();
    Clip { link, image }
}

#[tauri::command]
async fn save_link(app: AppHandle, url: String) -> Result<Vec<PathBuf>, String> {
    let worker = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || links::save(&worker, &url, &save_dir(&worker)))
        .await
        .map_err(|e| e.to_string())?;
    if !panel_visible(&app) {
        show_panel(&app, None, true);
    }
    result
}

#[tauri::command]
fn save_clipboard_image(app: AppHandle) -> Result<PathBuf, String> {
    clip::save_image(&save_dir(&app), "Image")
}

#[tauri::command]
fn capture(app: AppHandle) {
    start_capture(&app);
}

#[derive(Serialize)]
struct Recent {
    path: PathBuf,
    name: String,
    kind: &'static str,
    bytes: u64,
    age: u64,
}

#[tauri::command]
fn recent(app: AppHandle) -> Vec<Recent> {
    let mut out: Vec<Recent> = std::fs::read_dir(save_dir(&app))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let meta = e.metadata().ok().filter(|m| m.is_file())?;
            let name = e.file_name().to_string_lossy().into_owned();
            let ext = name.rsplit_once('.')?.1.to_ascii_lowercase();
            let kind = match ext.as_str() {
                "jpg" | "jpeg" | "png" | "gif" | "webp" | "avif" | "svg" | "bmp" => "image",
                "mp4" | "webm" | "mov" | "m4v" | "mkv" => "video",
                "m4a" | "mp3" | "aac" | "opus" | "weba" | "ogg" => "audio",
                _ => return None,
            };
            let age = meta.modified().ok()?.elapsed().map_or(0, |d| d.as_secs());
            Some(Recent { path: e.path(), name, kind, bytes: meta.len(), age })
        })
        .collect();
    out.sort_by_key(|r| r.age);
    out.truncate(6);
    out
}

#[tauri::command]
fn open_path(app: AppHandle, path: String) -> Result<(), String> {
    let p = inside(&app, &path)?;
    app.opener().open_path(p.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn reveal(app: AppHandle, path: String) -> Result<(), String> {
    let p = inside(&app, &path)?;
    app.opener().reveal_item_in_dir(p).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_folder(app: AppHandle) -> Result<(), String> {
    app.opener().open_path(save_dir(&app).to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
}

/// Virus & threat protection, where Ransomware protection lets the user allow LodiSave.
#[tauri::command]
fn open_security(app: AppHandle) -> Result<(), String> {
    app.opener().open_url("windowsdefender://threat/", None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_repo(app: AppHandle) -> Result<(), String> {
    app.opener().open_url(REPO, None::<&str>).map_err(|e| e.to_string())
}

/// A registry string, or None.
fn reg_text(root: HKEY, key: &str, name: Option<&str>) -> Option<String> {
    let (key, name) = (HSTRING::from(key), name.map(HSTRING::from));
    let name = name.as_ref().map_or(PCWSTR::null(), |n| PCWSTR(n.as_ptr()));
    let mut buf = [0u16; 1024];
    let mut size = std::mem::size_of_val(&buf) as u32;
    let ok = unsafe { RegGetValueW(root, &key, name, RRF_RT_REG_SZ, None, Some(buf.as_mut_ptr().cast()), Some(&mut size)) };
    ok.is_ok().then(|| String::from_utf16_lossy(&buf[..(size as usize / 2).saturating_sub(1)]))
}

/// Browsers that load unpacked extensions: name, exe, default-browser ProgId prefix, Extensions page.
const BROWSERS: [(&str, &str, &str, &str); 3] = [
    ("Chrome", "chrome.exe", "ChromeHTML", "chrome://extensions"),
    ("Edge", "msedge.exe", "MSEdgeHTM", "edge://extensions"),
    ("Brave", "brave.exe", "BraveHTML", "brave://extensions"),
];

/// The default browser when it is one of BROWSERS, else the first one installed.
fn extension_browser() -> Option<(&'static str, &'static str, PathBuf)> {
    let find = |exe: &str| {
        let key = format!(r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\{exe}");
        [HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE]
            .into_iter()
            .find_map(|root| reg_text(root, &key, None))
            .map(|p| PathBuf::from(p.trim_matches('"')))
            .filter(|p| p.is_file())
    };
    let choice = r"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice";
    let default = reg_text(HKEY_CURRENT_USER, choice, Some("ProgId")).unwrap_or_default();
    let mut order = BROWSERS;
    order.sort_by_key(|b| !default.starts_with(b.2));
    order.into_iter().find_map(|(name, exe, _, page)| Some((name, page, find(exe)?)))
}

/// While set, the panel is the extension checklist and stays up when the browser takes focus.
static SETUP: AtomicBool = AtomicBool::new(false);

/// Browsers only let programs install extensions from their stores, and they ignore their own
/// pages (chrome://extensions) when another program passes one. So this opens a new browser
/// window with the Extensions address on the clipboard, and keeps the panel up as a checklist
/// that copies the folder for Load unpacked.
fn setup_extension(app: &AppHandle) -> Result<(), String> {
    let dir = std::env::current_exe().map_err(|e| e.to_string())?.with_file_name("extension");
    if !dir.is_dir() {
        return app.opener().open_url(format!("{REPO}/releases/latest"), None::<&str>).map_err(|e| e.to_string());
    }
    let Some((name, page, exe)) = extension_browser() else {
        return app.opener().open_path(dir.to_string_lossy(), None::<&str>).map_err(|e| e.to_string());
    };
    copy_text(page.into())?;
    std::process::Command::new(exe).arg("--new-window").spawn().map_err(|e| e.to_string())?;
    hide_panel(app);
    let app = app.clone();
    let payload = serde_json::json!({ "name": name, "page": page, "dir": dir });
    std::thread::spawn(move || {
        // After the browser takes focus, so the panel shows over it without taking focus back.
        std::thread::sleep(std::time::Duration::from_millis(1200));
        SETUP.store(true, Ordering::SeqCst);
        show_panel(&app, None, true);
        let _ = app.emit("extension://opened", payload);
    });
    Ok(())
}

#[tauri::command]
fn open_extension(app: AppHandle) -> Result<(), String> {
    setup_extension(&app)
}

#[tauri::command]
fn end_setup() {
    SETUP.store(false, Ordering::SeqCst);
}

#[tauri::command]
fn copy_text(text: String) -> Result<(), String> {
    arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).map_err(|e| e.to_string())
}

#[derive(Serialize)]
struct Info {
    version: String,
    autostart: bool,
    shortcut: bool,
}

#[tauri::command]
fn info(app: AppHandle) -> Info {
    Info {
        version: app.package_info().version.to_string(),
        autostart: app.autolaunch().is_enabled().unwrap_or(false),
        shortcut: app.global_shortcut().is_registered(SHORTCUT),
    }
}

#[tauri::command]
fn set_autostart(app: AppHandle, on: bool) -> Result<(), String> {
    let launch = app.autolaunch();
    if on { launch.enable() } else { launch.disable() }.map_err(|e| e.to_string())
}

#[tauri::command]
fn hide(app: AppHandle) {
    hide_panel(&app);
}

fn create_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open LodiSave", true, None::<&str>)?;
    let capture = MenuItem::with_id(app, "capture", "Capture area", true, Some("Ctrl+Alt+L"))?;
    let quit = MenuItem::with_id(app, "quit", "Quit LodiSave", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &capture, &PredefinedMenuItem::separator(app)?, &quit])?;
    TrayIconBuilder::with_id("main")
        .icon(tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png"))?)
        .tooltip("LodiSave")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_panel(app, None, false),
            "capture" => start_capture(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, rect, .. } = event {
                let pos = rect.position.to_physical::<i32>(1.0);
                let size = rect.size.to_physical::<i32>(1.0);
                toggle_panel(tray.app_handle(), (pos.x + size.width / 2, pos.y));
            }
        })
        .build(app)?;
    Ok(())
}

fn on_launch(app: &AppHandle, args: &[String]) {
    // The installer asks a running copy to quit before it replaces or removes the files.
    if args.iter().any(|a| a == "--quit") {
        app.exit(0);
    } else if args.iter().any(|a| a == "--extension") {
        let _ = setup_extension(app);
    } else if !args.iter().any(|a| a == "--background") {
        show_panel(app, None, false);
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| on_launch(app, &args)))
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--background"])))
        .plugin(tauri_plugin_opener::init())
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state() == ShortcutState::Pressed {
                        start_capture(app);
                    }
                })
                .build(),
        )
        .setup(|app| {
            // Reaching setup means no other copy was running, so there is nothing to quit.
            let args: Vec<String> = std::env::args().collect();
            if args.iter().any(|a| a == "--quit") {
                std::process::exit(0);
            }
            let handle = app.handle().clone();
            create_tray(&handle)?;
            let panel = WebviewWindowBuilder::new(app, "panel", WebviewUrl::App("index.html".into()))
                .title("LodiSave")
                .inner_size(W, H)
                .resizable(false)
                .decorations(false)
                .transparent(true)
                .shadow(false)
                .always_on_top(true)
                .skip_taskbar(true)
                .visible(false)
                .build()?;
            let blur = handle.clone();
            panel.on_window_event(move |e| {
                if let WindowEvent::Focused(false) = e && !SETUP.load(Ordering::SeqCst) {
                    HIDDEN_AT.store(now_ms(), Ordering::SeqCst);
                    hide_panel(&blur);
                }
            });
            // Another app may own the shortcut. The panel then shows no shortcut hint.
            let _ = handle.global_shortcut().register(SHORTCUT);
            let update = handle.clone();
            std::thread::spawn(move || links::update_ytdlp(&update));
            on_launch(&handle, &args);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            clipboard,
            save_link,
            save_clipboard_image,
            capture,
            recent,
            open_path,
            reveal,
            open_folder,
            open_security,
            open_repo,
            open_extension,
            end_setup,
            copy_text,
            info,
            set_autostart,
            hide,
        ])
        .build(tauri::generate_context!())
        .expect("LodiSave failed to start")
        .run(|_app, event| {
            // LodiSave lives in the tray: hiding the panel must not quit it.
            if let tauri::RunEvent::ExitRequested { api, code, .. } = event {
                if code.is_none() {
                    api.prevent_exit();
                }
            }
        });
}
