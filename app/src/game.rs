//! Gaming mode: while a game, or anything else full screen, has the screen, LodiSave stays out of
//! the way. The panel doesn't peek, saves run at background priority so they don't take CPU or
//! disk from the game, and the weekly yt-dlp update waits.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use windows::Win32::Foundation::RECT;
use windows::Win32::Graphics::Gdi::{GetMonitorInfoW, MONITOR_DEFAULTTONEAREST, MONITORINFO, MonitorFromWindow};
use windows::Win32::System::Threading::{
    BELOW_NORMAL_PRIORITY_CLASS, GetCurrentProcess, NORMAL_PRIORITY_CLASS, PROCESS_MODE_BACKGROUND_BEGIN, PROCESS_MODE_BACKGROUND_END, SetPriorityClass,
};
use windows::Win32::UI::Shell::{QUNS_BUSY, QUNS_PRESENTATION_MODE, QUNS_RUNNING_D3D_FULL_SCREEN, SHQueryUserNotificationState};
use windows::Win32::UI::WindowsAndMessaging::{GWL_STYLE, GetClassNameW, GetForegroundWindow, GetShellWindow, GetWindowLongW, GetWindowRect, WS_CAPTION};

static AUTO: AtomicBool = AtomicBool::new(true);
static ON: AtomicBool = AtomicBool::new(false);

pub fn on() -> bool {
    ON.load(Ordering::SeqCst)
}

/// Gaming mode Auto (the default) or Off, from the panel's settings.
pub fn set_auto(auto: bool) {
    AUTO.store(auto, Ordering::SeqCst);
}

/// Windows says a full-screen game or a presentation is running, or the window in front fills its
/// monitor with no title bar, like a borderless game. Maximized apps keep their title bar.
fn detect() -> bool {
    let state = unsafe { SHQueryUserNotificationState() };
    if matches!(state, Ok(QUNS_RUNNING_D3D_FULL_SCREEN | QUNS_BUSY | QUNS_PRESENTATION_MODE)) {
        return true;
    }
    unsafe {
        let w = GetForegroundWindow();
        if w.is_invalid() || w == GetShellWindow() {
            return false;
        }
        let mut class = [0u16; 64];
        let n = GetClassNameW(w, &mut class).max(0) as usize;
        // The desktop and the taskbar fill their monitors too.
        if ["Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd"].contains(&String::from_utf16_lossy(&class[..n]).as_str()) {
            return false;
        }
        if GetWindowLongW(w, GWL_STYLE) as u32 & WS_CAPTION.0 == WS_CAPTION.0 {
            return false;
        }
        let mut r = RECT::default();
        let mut m = MONITORINFO { cbSize: size_of::<MONITORINFO>() as u32, ..Default::default() };
        if GetWindowRect(w, &mut r).is_err() || !GetMonitorInfoW(MonitorFromWindow(w, MONITOR_DEFAULTTONEAREST), &mut m).as_bool() {
            return false;
        }
        let s = m.rcMonitor;
        r.left <= s.left && r.top <= s.top && r.right >= s.right && r.bottom >= s.bottom
    }
}

/// Background mode lowers LodiSave's CPU, disk and memory priority. Below-normal priority is
/// what the helpers it starts while gaming (yt-dlp, curl, FFmpeg) inherit.
fn background(on: bool) {
    unsafe {
        let me = GetCurrentProcess();
        if on {
            let _ = SetPriorityClass(me, BELOW_NORMAL_PRIORITY_CLASS);
            let _ = SetPriorityClass(me, PROCESS_MODE_BACKGROUND_BEGIN);
        } else {
            let _ = SetPriorityClass(me, PROCESS_MODE_BACKGROUND_END);
            let _ = SetPriorityClass(me, NORMAL_PRIORITY_CLASS);
        }
    }
}

/// Checks every 2 seconds, and calls `changed` when gaming starts or ends.
pub fn watch(changed: impl Fn(bool) + Send + 'static) {
    std::thread::spawn(move || {
        loop {
            let now = AUTO.load(Ordering::SeqCst) && detect();
            if now != ON.swap(now, Ordering::SeqCst) {
                background(now);
                changed(now);
            }
            std::thread::sleep(Duration::from_secs(2));
        }
    });
}
