//! Thin wrappers over the Windows APIs the installer uses.

use anyhow::{Context, Result};
use std::path::{Path, PathBuf};
use windows::Win32::Foundation::{COLORREF, HWND};
use windows::Win32::Graphics::Dwm::{
    DWMWA_BORDER_COLOR, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND, DwmSetWindowAttribute,
};
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx,
    CoTaskMemFree, IPersistFile,
};
use windows::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_READ, KEY_WRITE, REG_BINARY, REG_DWORD, REG_OPTION_NON_VOLATILE,
    REG_SZ, REG_VALUE_TYPE, RRF_RT_REG_SZ, RegCloseKey, RegCreateKeyExW, RegDeleteTreeW,
    RegDeleteValueW, RegGetValueW, RegOpenKeyExW, RegSetValueExW,
};
use windows::Win32::System::Threading::{OpenProcess, PROCESS_SYNCHRONIZE, WaitForSingleObject};
use windows::Win32::UI::Shell::{
    FOS_FORCEFILESYSTEM, FOS_PICKFOLDERS, FileOpenDialog, IFileOpenDialog, IShellItem,
    IShellLinkW, KNOWN_FOLDER_FLAG, SHCreateItemFromParsingName, SHGetKnownFolderPath,
    SIGDN_FILESYSPATH, ShellExecuteW, ShellLink,
};
use windows::Win32::UI::WindowsAndMessaging::{
    IDOK, MB_ICONERROR, MB_ICONWARNING, MB_OK, MB_OKCANCEL, MESSAGEBOX_STYLE, MessageBoxW,
    SW_SHOWNORMAL,
};
use windows::core::{GUID, HSTRING, Interface, PCWSTR, w};

pub use windows::Win32::UI::Shell::{FOLDERID_Desktop, FOLDERID_LocalAppData, FOLDERID_Programs, FOLDERID_RoamingAppData};

/// COM must be initialized on every thread that creates shortcuts or dialogs.
pub fn init_com() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
}

pub fn known_folder(id: &GUID) -> Result<PathBuf> {
    unsafe {
        let p = SHGetKnownFolderPath(id, KNOWN_FOLDER_FLAG(0), None).context("find a known folder")?;
        let s = p.to_string();
        CoTaskMemFree(Some(p.0 as *const _));
        Ok(PathBuf::from(s?))
    }
}

pub fn create_shortcut(lnk: &Path, target: &Path, description: &str) -> Result<()> {
    unsafe {
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        link.SetPath(&HSTRING::from(target.as_os_str()))?;
        if let Some(dir) = target.parent() {
            link.SetWorkingDirectory(&HSTRING::from(dir.as_os_str()))?;
        }
        link.SetDescription(&HSTRING::from(description))?;
        link.SetIconLocation(&HSTRING::from(target.as_os_str()), 0)?;
        let file: IPersistFile = link.cast()?;
        file.Save(&HSTRING::from(lnk.as_os_str()), true)?;
    }
    Ok(())
}

/// A key under HKEY_CURRENT_USER.
pub struct Key(HKEY);

impl Drop for Key {
    fn drop(&mut self) {
        unsafe {
            let _ = RegCloseKey(self.0);
        }
    }
}

impl Key {
    pub fn create(path: &str) -> Result<Key> {
        let mut key = HKEY::default();
        unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                &HSTRING::from(path),
                None,
                PCWSTR::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_READ | KEY_WRITE,
                None,
                &mut key,
                None,
            )
            .ok()
            .with_context(|| format!("create registry key {path}"))?;
        }
        Ok(Key(key))
    }

    pub fn open(path: &str) -> Option<Key> {
        let mut key = HKEY::default();
        unsafe {
            RegOpenKeyExW(HKEY_CURRENT_USER, &HSTRING::from(path), None, KEY_READ | KEY_WRITE, &mut key)
                .ok()
                .ok()?;
        }
        Some(Key(key))
    }

    fn set(&self, name: &str, kind: REG_VALUE_TYPE, data: &[u8]) -> Result<()> {
        unsafe {
            RegSetValueExW(self.0, &HSTRING::from(name), None, kind, Some(data))
                .ok()
                .with_context(|| format!("write registry value {name}"))
        }
    }

    pub fn set_str(&self, name: &str, value: &str) -> Result<()> {
        let wide: Vec<u8> = value
            .encode_utf16()
            .chain(Some(0))
            .flat_map(|c| c.to_le_bytes())
            .collect();
        self.set(name, REG_SZ, &wide)
    }

    pub fn set_dword(&self, name: &str, value: u32) -> Result<()> {
        self.set(name, REG_DWORD, &value.to_le_bytes())
    }

    pub fn set_binary(&self, name: &str, value: &[u8]) -> Result<()> {
        self.set(name, REG_BINARY, value)
    }

    pub fn get_str(&self, name: &str) -> Option<String> {
        let name = HSTRING::from(name);
        let mut size = 0u32;
        unsafe {
            RegGetValueW(self.0, PCWSTR::null(), &name, RRF_RT_REG_SZ, None, None, Some(&mut size))
                .ok()
                .ok()?;
            let mut buf = vec![0u16; (size as usize).div_ceil(2)];
            RegGetValueW(
                self.0,
                PCWSTR::null(),
                &name,
                RRF_RT_REG_SZ,
                None,
                Some(buf.as_mut_ptr().cast()),
                Some(&mut size),
            )
            .ok()
            .ok()?;
            let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
            Some(String::from_utf16_lossy(&buf[..len]))
        }
    }

    pub fn delete_value(&self, name: &str) {
        unsafe {
            let _ = RegDeleteValueW(self.0, &HSTRING::from(name));
        }
    }
}

pub fn delete_key(path: &str) {
    unsafe {
        let _ = RegDeleteTreeW(HKEY_CURRENT_USER, &HSTRING::from(path));
        let _ = windows::Win32::System::Registry::RegDeleteKeyW(HKEY_CURRENT_USER, &HSTRING::from(path));
    }
}

pub fn message(text: &str, style: MESSAGEBOX_STYLE) -> bool {
    unsafe { MessageBoxW(None, &HSTRING::from(text), w!("LodiSave Setup"), style) == IDOK }
}

pub fn error_box(text: &str) {
    message(text, MB_OK | MB_ICONERROR);
}

pub fn confirm_box(text: &str) -> bool {
    message(text, MB_OKCANCEL | MB_ICONWARNING)
}

/// Opens a URL or folder with its default handler.
pub fn shell_open(target: &str) {
    unsafe {
        ShellExecuteW(None, w!("open"), &HSTRING::from(target), PCWSTR::null(), PCWSTR::null(), SW_SHOWNORMAL);
    }
}

pub fn pick_folder(owner: HWND, start: &Path) -> Option<PathBuf> {
    unsafe {
        let dialog: IFileOpenDialog = CoCreateInstance(&FileOpenDialog, None, CLSCTX_INPROC_SERVER).ok()?;
        let options = dialog.GetOptions().ok()?;
        dialog.SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM).ok()?;
        dialog.SetTitle(w!("Choose where to install LodiSave")).ok()?;
        let mut folder = start;
        while !folder.exists() {
            folder = folder.parent()?;
        }
        if let Ok(item) = SHCreateItemFromParsingName::<_, _, IShellItem>(&HSTRING::from(folder.as_os_str()), None) {
            let _ = dialog.SetFolder(&item);
        }
        dialog.Show(Some(owner)).ok()?;
        let item = dialog.GetResult().ok()?;
        let name = item.GetDisplayName(SIGDN_FILESYSPATH).ok()?;
        let path = name.to_string().ok();
        CoTaskMemFree(Some(name.0 as *const _));
        path.map(PathBuf::from)
    }
}

/// Rounded corners and a quiet border on Windows 11. Earlier versions ignore both.
pub fn style_window(hwnd: HWND) {
    unsafe {
        let corners = DWMWCP_ROUND;
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            (&raw const corners).cast(),
            size_of_val(&corners) as u32,
        );
        let border = COLORREF(0x002f_2c2c);
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_BORDER_COLOR,
            (&raw const border).cast(),
            size_of_val(&border) as u32,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_values_round_trip() {
        let path = format!(r"Software\LodiSave-test-{}", std::process::id());
        let key = Key::create(&path).unwrap();
        key.set_str("Name", "LodiSave \u{1F439}").unwrap();
        key.set_dword("Size", 42).unwrap();
        assert_eq!(key.get_str("Name").as_deref(), Some("LodiSave \u{1F439}"));
        assert_eq!(key.get_str("Missing"), None);
        drop(key);
        assert!(Key::open(&path).is_some());
        delete_key(&path);
        assert!(Key::open(&path).is_none());
    }

    #[test]
    fn known_folders_resolve() {
        for (name, id) in [("Desktop", FOLDERID_Desktop), ("Programs", FOLDERID_Programs)] {
            println!("{name}: {}", known_folder(&id).unwrap().display());
        }
    }

    #[test]
    fn shortcuts_are_written() {
        init_com();
        let dir = std::env::temp_dir().join(format!("lodi-lnk-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let lnk = dir.join("Test.lnk");
        let target = std::env::current_exe().unwrap();
        create_shortcut(&lnk, &target, "test").unwrap();
        assert!(lnk.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }
}

/// Waits up to `ms` for a process to exit.
pub fn wait_for_process(pid: u32, ms: u32) {
    unsafe {
        if let Ok(handle) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) {
            WaitForSingleObject(handle, ms);
            let _ = windows::Win32::Foundation::CloseHandle(handle);
        }
    }
}
