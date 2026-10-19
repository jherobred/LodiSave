//! The installer window: a frameless tao window hosting the page in `web/`.

use crate::install::{self, Existing, Options};
use crate::{APP_EXE, PAYLOAD_BYTES, REPO_URL, VERSION, win};
use serde::Deserialize;
use serde_json::json;
use std::borrow::Cow;
use std::path::PathBuf;
use tao::dpi::{LogicalSize, PhysicalPosition};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tao::platform::windows::{IconExtWindows, WindowBuilderExtWindows, WindowExtWindows};
use tao::window::{Icon, WindowBuilder};
use windows::Win32::Foundation::HWND;
use wry::http::{Request, Response, header::CONTENT_TYPE};
use wry::{NewWindowResponse, WebContext, WebViewBuilder, WebViewBuilderExtWindows};

pub enum Mode {
    Install { existing: Option<Existing> },
    Uninstall { dir: PathBuf },
}

enum UserEvent {
    Message(String),
    Progress(f64, String),
    /// Notes about skipped steps, or the error that stopped the work.
    Finished(Result<Vec<String>, String>),
    /// Shows the window even if the page never said it was ready.
    Reveal,
}

#[derive(Deserialize)]
#[serde(tag = "cmd", rename_all = "camelCase")]
enum Message {
    Ready,
    Drag,
    Minimize,
    Close,
    PickFolder { dir: PathBuf },
    Install(Options),
    #[serde(rename_all = "camelCase")]
    Uninstall { remove_data: bool },
    Launch,
    OpenUrl { url: String },
}

const INDEX: &str = include_str!("../web/index.html");
const STYLE: &str = include_str!("../web/style.css");
const SCRIPT: &str = include_str!("../web/app.js");
const GEIST: &[u8] = include_bytes!("../web/fonts/Geist.woff2");
const GEIST_MONO: &[u8] = include_bytes!("../web/fonts/GeistMono.woff2");

fn serve(request: Request<Vec<u8>>) -> Response<Cow<'static, [u8]>> {
    let (body, kind): (&'static [u8], &str) = match request.uri().path() {
        "/" | "/index.html" => (INDEX.as_bytes(), "text/html; charset=utf-8"),
        "/style.css" => (STYLE.as_bytes(), "text/css; charset=utf-8"),
        "/app.js" => (SCRIPT.as_bytes(), "text/javascript; charset=utf-8"),
        "/fonts/Geist.woff2" => (GEIST, "font/woff2"),
        "/fonts/GeistMono.woff2" => (GEIST_MONO, "font/woff2"),
        _ => {
            return Response::builder()
                .status(404)
                .body(Cow::Borrowed(&[][..]))
                .unwrap();
        }
    };
    Response::builder()
        .header(CONTENT_TYPE, kind)
        .body(Cow::Borrowed(body))
        .unwrap()
}

/// Only links to the project's own pages open, in the default browser.
fn allowed_url(url: &str) -> bool {
    url == REPO_URL || url.starts_with(&format!("{REPO_URL}/"))
}

pub fn run(mode: Mode) -> ! {
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let (title, dir) = match &mode {
        Mode::Install { existing } => (
            "LodiSave Setup",
            existing.as_ref().map(|e| e.dir.clone()).unwrap_or_else(install::default_dir),
        ),
        Mode::Uninstall { dir } => ("Uninstall LodiSave", dir.clone()),
    };

    let window = WindowBuilder::new()
        .with_title(title)
        .with_inner_size(LogicalSize::new(820.0, 520.0))
        .with_resizable(false)
        .with_maximizable(false)
        .with_decorations(false)
        .with_undecorated_shadow(true)
        .with_visible(false)
        .with_window_icon(Icon::from_resource(1, None).ok())
        .build(&event_loop)
        .expect("create the window");
    let hwnd = HWND(window.hwnd() as *mut _);
    win::style_window(hwnd);
    if let Some(monitor) = window.current_monitor() {
        let (m, size) = (monitor.position(), monitor.size());
        let own = window.outer_size();
        window.set_outer_position(PhysicalPosition::new(
            m.x + (size.width as i32 - own.width as i32) / 2,
            m.y + (size.height as i32 - own.height as i32) / 2,
        ));
    }

    let init = json!({
        "mode": if matches!(mode, Mode::Install { .. }) { "install" } else { "uninstall" },
        "version": VERSION,
        "dir": dir,
        "existing": match &mode { Mode::Install { existing } => json!(existing), _ => json!(null) },
        "sizeMb": (PAYLOAD_BYTES as f64 / 1_048_576.0).round(),
        "repo": REPO_URL,
    });

    // WebView2 keeps its profile in this folder. The default would be beside this program,
    // often the Downloads folder.
    let data_dir = std::env::temp_dir().join("LodiSave-Setup.WebView2");
    let mut context = WebContext::new(Some(data_dir.clone()));
    let ipc_proxy = proxy.clone();
    let webview = WebViewBuilder::new_with_web_context(&mut context)
        .with_custom_protocol("lodi".into(), |_, request| serve(request))
        .with_url("lodi://localhost/index.html")
        .with_initialization_script(format!("window.__LODI__ = {init};"))
        .with_ipc_handler(move |request| {
            let _ = ipc_proxy.send_event(UserEvent::Message(request.into_body()));
        })
        .with_navigation_handler(|url| url.starts_with("http://lodi.localhost/") || url.starts_with("lodi://"))
        .with_new_window_req_handler(|_, _| NewWindowResponse::Deny)
        .with_background_color((15, 15, 16, 255))
        .with_devtools(cfg!(debug_assertions))
        .with_hotkeys_zoom(false)
        .with_default_context_menus(false)
        .with_browser_accelerator_keys(false)
        .build(&window);
    let webview = match webview {
        Ok(w) => w,
        Err(e) => {
            if win::confirm_box(&format!(
                "LodiSave needs the Microsoft Edge WebView2 Runtime, which comes with Windows 11 \
                 and most Windows 10 PCs. Select OK to open its download page.\n\n({e})"
            )) {
                win::shell_open("https://developer.microsoft.com/microsoft-edge/webview2/");
            }
            std::process::exit(1);
        }
    };

    let reveal = proxy.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_secs(3));
        let _ = reveal.send_event(UserEvent::Reveal);
    });

    let mut busy = false;
    let mut installed_dir: Option<PathBuf> = None;
    let mut webview = Some(webview);
    let mut context = Some(context);
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        let call = |js: String| {
            if let Some(w) = &webview {
                let _ = w.evaluate_script(&js);
            }
        };
        match event {
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } if !busy => {
                *control_flow = ControlFlow::Exit;
            }
            Event::UserEvent(UserEvent::Progress(fraction, file)) => {
                call(format!("lodi.progress({fraction}, {})", json!(file)));
            }
            Event::UserEvent(UserEvent::Finished(result)) => {
                busy = false;
                let (notes, error) = match result {
                    Ok(notes) => (notes, None),
                    Err(e) => (Vec::new(), Some(e)),
                };
                call(format!("lodi.finished({}, {})", json!(error), json!(notes)));
            }
            Event::UserEvent(UserEvent::Reveal) if !window.is_visible() => {
                window.set_visible(true);
                window.set_focus();
            }
            Event::UserEvent(UserEvent::Message(text)) => match serde_json::from_str::<Message>(&text) {
                Ok(Message::Ready) => {
                    window.set_visible(true);
                    window.set_focus();
                }
                Ok(Message::Drag) => {
                    let _ = window.drag_window();
                }
                Ok(Message::Minimize) => window.set_minimized(true),
                Ok(Message::Close) if !busy => *control_flow = ControlFlow::Exit,
                Ok(Message::PickFolder { dir }) => {
                    if let Some(picked) = win::pick_folder(hwnd, &dir) {
                        // Always install into a folder of our own inside the chosen one.
                        let target = if picked.ends_with(crate::APP_NAME) {
                            picked
                        } else {
                            picked.join(crate::APP_NAME)
                        };
                        call(format!("lodi.folderPicked({})", json!(target)));
                    }
                }
                Ok(Message::Install(options)) if !busy => {
                    busy = true;
                    installed_dir = Some(options.dir.clone());
                    start_install(proxy.clone(), options);
                }
                Ok(Message::Uninstall { remove_data }) if !busy => {
                    busy = true;
                    start_uninstall(proxy.clone(), dir.clone(), remove_data);
                }
                Ok(Message::Launch) => {
                    if let Some(dir) = &installed_dir {
                        let _ = std::process::Command::new(dir.join(APP_EXE)).current_dir(dir).spawn();
                    }
                    *control_flow = ControlFlow::Exit;
                }
                Ok(Message::OpenUrl { url }) if allowed_url(&url) => win::shell_open(&url),
                _ => {}
            },
            Event::LoopDestroyed => {
                window.set_visible(false);
                drop(webview.take());
                drop(context.take());
                // WebView2's helper processes release the profile a moment after it closes.
                for _ in 0..20 {
                    if std::fs::remove_dir_all(&data_dir).is_ok() || !data_dir.exists() {
                        break;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(150));
                }
            }
            _ => {}
        }
    })
}

fn start_install(proxy: EventLoopProxy<UserEvent>, options: Options) {
    std::thread::spawn(move || {
        win::init_com();
        let total = PAYLOAD_BYTES.max(1) as f64;
        let last = std::cell::Cell::new(None::<std::time::Instant>);
        let result = install::install(&options, &|done, file| {
            // About 60 updates a second is plenty for the progress ring.
            if last.get().is_none_or(|t| t.elapsed().as_millis() >= 16) || done == PAYLOAD_BYTES {
                last.set(Some(std::time::Instant::now()));
                let _ = proxy.send_event(UserEvent::Progress(done as f64 / total, file.to_string()));
            }
        });
        let _ = proxy.send_event(UserEvent::Finished(result.map_err(|e| format!("{e:#}"))));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn page_messages_parse() {
        let install: Message = serde_json::from_str(
            r#"{"cmd":"install","dir":"C:\\Apps\\LodiSave","desktopShortcut":true,"startWithWindows":false}"#,
        )
        .unwrap();
        assert!(matches!(install, Message::Install(o) if o.desktop_shortcut && !o.start_with_windows));
        let remove: Message = serde_json::from_str(r#"{"cmd":"uninstall","removeData":true}"#).unwrap();
        assert!(matches!(remove, Message::Uninstall { remove_data: true }));
        for cmd in ["ready", "drag", "minimize", "close", "launch"] {
            assert!(serde_json::from_str::<Message>(&format!(r#"{{"cmd":"{cmd}"}}"#)).is_ok(), "{cmd}");
        }
        assert!(serde_json::from_str::<Message>(r#"{"cmd":"pickFolder","dir":"C:\\"}"#).is_ok());
        assert!(serde_json::from_str::<Message>(r#"{"cmd":"openUrl","url":"https://x"}"#).is_ok());
    }

    #[test]
    fn only_project_links_open() {
        assert!(allowed_url(REPO_URL));
        assert!(allowed_url(&format!("{REPO_URL}/blob/main/LICENSE")));
        assert!(!allowed_url("https://github.com/jherobred/LodiSave.evil.example"));
        assert!(!allowed_url("file:///C:/Windows/System32/calc.exe"));
    }
}

fn start_uninstall(proxy: EventLoopProxy<UserEvent>, dir: PathBuf, remove_data: bool) {
    std::thread::spawn(move || {
        win::init_com();
        let result = install::uninstall(&dir, remove_data, &|fraction| {
            let _ = proxy.send_event(UserEvent::Progress(fraction, String::new()));
        });
        let _ = proxy.send_event(UserEvent::Finished(result.map_err(|e| format!("{e:#}"))));
    });
}
