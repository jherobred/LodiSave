//! The clipboard: a copied link or image, and Windows' own area capture (ms-screenclip:), whose
//! result lands on the clipboard.

use arboard::Clipboard;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub fn link() -> Option<String> {
    let text = Clipboard::new().ok()?.get_text().ok()?;
    let text = text.trim();
    let ok = (text.starts_with("https://") || text.starts_with("http://")) && !text.contains(char::is_whitespace) && text.len() < 4096;
    ok.then(|| text.to_string())
}

pub fn has_image() -> bool {
    Clipboard::new().is_ok_and(|mut c| c.get_image().is_ok())
}

fn sequence() -> u32 {
    unsafe { windows::Win32::System::DataExchange::GetClipboardSequenceNumber() }
}

fn stamp() -> String {
    let t = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
    format!("{}-{:02}-{:02} {:02}.{:02}.{:02}", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond)
}

/// Saves the clipboard image as a PNG in `dir`.
pub fn save_image(dir: &Path, prefix: &str) -> Result<PathBuf, String> {
    crate::writable(dir)?;
    let img = Clipboard::new().and_then(|mut c| c.get_image()).map_err(|_| "noimage".to_string())?;
    let mut path = dir.join(format!("{prefix} {}.png", stamp()));
    let mut n = 2;
    while path.exists() {
        path = dir.join(format!("{prefix} {} ({n}).png", stamp()));
        n += 1;
    }
    let file = std::fs::File::create(&path).map_err(|e| e.to_string())?;
    let mut enc = png::Encoder::new(std::io::BufWriter::new(file), img.width as u32, img.height as u32);
    enc.set_color(png::ColorType::Rgba);
    enc.set_depth(png::BitDepth::Eight);
    enc.write_header().and_then(|mut w| w.write_image_data(&img.bytes)).map_err(|e| e.to_string())?;
    Ok(path)
}

/// Opens the Windows capture overlay and waits up to two minutes for the image it copies.
pub fn capture(dir: &Path) -> Option<PathBuf> {
    let before = sequence();
    std::process::Command::new("explorer.exe").arg("ms-screenclip:").spawn().ok()?;
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(120) {
        std::thread::sleep(Duration::from_millis(300));
        if sequence() != before && has_image() {
            return save_image(dir, "Capture").ok();
        }
    }
    None
}
