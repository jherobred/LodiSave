//! Controlled folder access (Windows Security › Ransomware protection), which stops apps Windows
//! doesn't know from writing to protected folders: Pictures, Videos, Documents, Desktop, and any
//! folder the user adds, often Downloads. LodiSave reads whether it is on, and with the user's yes in
//! Windows' own admin prompt adds LodiSave.exe to its allowed apps, so nobody has to find that page.

use std::os::windows::ffi::OsStrExt;

/// "on", "off", "audit" (logs only), or "unknown" when Windows Security won't say (another
/// antivirus runs instead).
pub fn state() -> &'static str {
    let out = crate::links::command("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", "(Get-MpPreference).EnableControlledFolderAccess"])
        .output();
    match out.ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).as_deref() {
        Some("1") => "on",
        Some("0") => "off",
        Some("2" | "3" | "4") => "audit",
        _ => "unknown",
    }
}

/// Adds LodiSave.exe to the allowed apps through an elevated PowerShell. Windows shows its admin
/// prompt; "declined" when the user says no there.
pub fn allow() -> Result<(), String> {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Threading::{GetExitCodeProcess, INFINITE, WaitForSingleObject};
    use windows::Win32::UI::Shell::{SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW};
    use windows::Win32::UI::WindowsAndMessaging::SW_HIDE;
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    // A PowerShell string in single quotes takes a quote as two.
    let command = format!("Add-MpPreference -ControlledFolderAccessAllowedApplications '{}'", exe.to_string_lossy().replace('\'', "''"));
    let wide = |s: &str| std::ffi::OsStr::new(s).encode_wide().chain([0]).collect::<Vec<u16>>();
    let (verb, file, params) = (wide("runas"), wide("powershell.exe"), wide(&format!("-NoProfile -NonInteractive -WindowStyle Hidden -Command \"{command}\"")));
    let mut info = SHELLEXECUTEINFOW {
        cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS,
        lpVerb: windows::core::PCWSTR(verb.as_ptr()),
        lpFile: windows::core::PCWSTR(file.as_ptr()),
        lpParameters: windows::core::PCWSTR(params.as_ptr()),
        nShow: SW_HIDE.0,
        ..Default::default()
    };
    unsafe {
        ShellExecuteExW(&mut info).map_err(|_| "declined".to_string())?;
        WaitForSingleObject(info.hProcess, INFINITE);
        let mut code = 1u32;
        let _ = GetExitCodeProcess(info.hProcess, &mut code);
        let _ = CloseHandle(info.hProcess);
        if code == 0 { Ok(()) } else { Err("blocked".into()) }
    }
}
