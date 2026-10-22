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
use windows::Win32::UI::Shell::{QUNS_BUSY, QUNS_NOT_PRESENT, QUNS_PRESENTATION_MODE, QUNS_RUNNING_D3D_FULL_SCREEN, SHQueryUserNotificationState};
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

/// A window that fills its monitor with no title bar, like a borderless game. The desktop and the
/// taskbar fill theirs too, and maximized apps keep their title bar.
fn full_screen(window: RECT, screen: RECT, style: u32, class: &str) -> bool {
    let shell = ["Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd"].contains(&class);
    let covers = window.left <= screen.left && window.top <= screen.top && window.right >= screen.right && window.bottom >= screen.bottom;
    covers && !shell && style & WS_CAPTION.0 != WS_CAPTION.0
}

/// Windows says a full-screen game or a presentation is running, or the window in front is full
/// screen. A locked PC (whose lock screen fills the monitor too) is not gaming.
fn detect() -> bool {
    match unsafe { SHQueryUserNotificationState() } {
        Ok(QUNS_NOT_PRESENT) => return false,
        Ok(QUNS_RUNNING_D3D_FULL_SCREEN | QUNS_BUSY | QUNS_PRESENTATION_MODE) => return true,
        _ => {}
    }
    unsafe {
        let w = GetForegroundWindow();
        if w.is_invalid() || w == GetShellWindow() {
            return false;
        }
        let mut class = [0u16; 64];
        let n = GetClassNameW(w, &mut class).max(0) as usize;
        let (mut r, mut m) = (RECT::default(), MONITORINFO { cbSize: size_of::<MONITORINFO>() as u32, ..Default::default() });
        GetWindowRect(w, &mut r).is_ok()
            && GetMonitorInfoW(MonitorFromWindow(w, MONITOR_DEFAULTTONEAREST), &mut m).as_bool()
            && full_screen(r, m.rcMonitor, GetWindowLongW(w, GWL_STYLE) as u32, &String::from_utf16_lossy(&class[..n]))
    }
}

/// Background mode lowers LodiSave's CPU, disk and memory priority. Below-normal priority is
/// what the helpers it starts while gaming (yt-dlp, curl, FFmpeg) inherit.
fn background(on: bool) {
    unsafe {
        let me = GetCurrentProcess();
        if on {
            let _ = SetPriorityClass(me, PROCESS_MODE_BACKGROUND_BEGIN);
            let _ = SetPriorityClass(me, BELOW_NORMAL_PRIORITY_CLASS);
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

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::System::Threading::GetPriorityClass;

    #[test]
    fn spots_full_screen_windows() {
        let rect = |left, top, right, bottom| RECT { left, top, right, bottom };
        let screen = rect(0, 0, 1920, 1080);
        let popup = 0x9400_0000; // WS_POPUP | WS_VISIBLE | WS_CLIPSIBLINGS, as borderless games use
        let titled = 0x16CF_0000; // a maximized app's WS_OVERLAPPEDWINDOW
        assert!(full_screen(screen, screen, popup, "UnityWndClass"));
        assert!(full_screen(rect(-8, -8, 1928, 1088), screen, popup, "SDL_app"), "a little larger still covers");
        assert!(!full_screen(rect(-8, -8, 1928, 1088), screen, titled, "Chrome_WidgetWin_1"), "maximized with a title bar");
        assert!(!full_screen(rect(0, 0, 1920, 1040), screen, popup, "SDL_app"), "leaves the taskbar showing");
        assert!(!full_screen(screen, screen, popup, "Progman") && !full_screen(screen, screen, popup, "WorkerW"), "the desktop");
        assert!(!full_screen(rect(1920, 0, 3456, 864), screen, popup, "SDL_app"), "on another monitor than this one");
    }

    #[test]
    fn steps_back_while_gaming() {
        use windows::Win32::System::Threading::{GetProcessInformation, MEMORY_PRIORITY_INFORMATION, ProcessMemoryPriority};
        // Background mode shows as very low memory priority (1); normal is 5.
        let state = || unsafe {
            let mut m = MEMORY_PRIORITY_INFORMATION::default();
            let _ = GetProcessInformation(GetCurrentProcess(), ProcessMemoryPriority, (&raw mut m).cast(), size_of::<MEMORY_PRIORITY_INFORMATION>() as u32);
            (GetPriorityClass(GetCurrentProcess()), m.MemoryPriority.0)
        };
        background(true);
        let low = state();
        background(false);
        assert_eq!((low, state()), ((BELOW_NORMAL_PRIORITY_CLASS.0, 1), (NORMAL_PRIORITY_CLASS.0, 5)));
    }
}
