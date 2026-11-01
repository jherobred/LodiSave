//! Thumbnails for the gallery grid from Windows' own thumbnail cache, the pictures File Explorer
//! shows, served as PNG at http://thumb.localhost/<path>?s=<px>. A tile then decodes a few hundred
//! pixels instead of a whole 12-megapixel photo, and a video tile is a picture until hovered.

use std::path::Path;
use std::sync::mpsc::{Sender, channel};
use std::sync::{Arc, Mutex, OnceLock};
use windows::Win32::Foundation::SIZE;
use windows::Win32::Graphics::Gdi::{
    BI_RGB, BITMAP, BITMAPINFO, BITMAPINFOHEADER, CreateCompatibleDC, DIB_RGB_COLORS, DeleteDC, DeleteObject, GetDIBits, GetObjectW, HGDIOBJ,
};
use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx};
use windows::Win32::UI::Shell::{IShellItemImageFactory, SHCreateItemFromParsingName, SIIGBF_RESIZETOFIT, SIIGBF_THUMBNAILONLY};
use windows::core::HSTRING;

type Job = Box<dyn FnOnce() + Send>;

/// Runs `job` on one of a few thumbnail threads, so a grid asking for hundreds at once doesn't
/// start hundreds of threads.
pub fn queue(job: impl FnOnce() + Send + 'static) {
    static JOBS: OnceLock<Mutex<Sender<Job>>> = OnceLock::new();
    let jobs = JOBS.get_or_init(|| {
        let (tx, rx) = channel::<Job>();
        let rx = Arc::new(Mutex::new(rx));
        let n = std::thread::available_parallelism().map_or(4, |n| n.get().clamp(2, 6));
        for _ in 0..n {
            let rx = rx.clone();
            std::thread::spawn(move || {
                let _ = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
                loop {
                    let job = rx.lock().unwrap().recv();
                    match job {
                        Ok(job) => job(),
                        Err(_) => return,
                    }
                }
            });
        }
        Mutex::new(tx)
    });
    let _ = jobs.lock().unwrap().send(Box::new(job));
}

/// The thumbnail of `path` at most `size` pixels on its long side, as PNG. None when Windows has no
/// thumbnail for it (a format without a thumbnail handler), so the window falls back to the file.
pub fn png(path: &Path, size: i32) -> Option<Vec<u8>> {
    let (w, h, mut px) = unsafe {
        let item: IShellItemImageFactory = SHCreateItemFromParsingName(&HSTRING::from(path.as_os_str()), None).ok()?;
        let bmp = item.GetImage(SIZE { cx: size, cy: size }, SIIGBF_RESIZETOFIT | SIIGBF_THUMBNAILONLY).ok()?;
        let mut info = BITMAP::default();
        GetObjectW(HGDIOBJ(bmp.0), size_of::<BITMAP>() as i32, Some((&mut info as *mut BITMAP).cast()));
        let (w, h) = (info.bmWidth, info.bmHeight);
        let mut bi = BITMAPINFO {
            bmiHeader: BITMAPINFOHEADER { biSize: size_of::<BITMAPINFOHEADER>() as u32, biWidth: w, biHeight: -h, biPlanes: 1, biBitCount: 32, biCompression: BI_RGB.0, ..Default::default() },
            ..Default::default()
        };
        let mut px = vec![0u8; (w.max(0) * h.max(0) * 4) as usize];
        let dc = CreateCompatibleDC(None);
        let rows = GetDIBits(dc, bmp, 0, h as u32, Some(px.as_mut_ptr().cast()), &mut bi, DIB_RGB_COLORS);
        let _ = DeleteDC(dc);
        let _ = DeleteObject(HGDIOBJ(bmp.0));
        if rows == 0 || px.is_empty() {
            return None;
        }
        (w as u32, h as u32, px)
    };
    // Windows hands back premultiplied BGRA, and pictures without transparency with alpha 0 throughout.
    let opaque = px.chunks_exact(4).all(|p| p[3] == 0);
    for p in px.chunks_exact_mut(4) {
        p.swap(0, 2);
        if opaque {
            p[3] = 255;
        } else if p[3] > 0 && p[3] < 255 {
            let a = p[3] as u32;
            for c in &mut p[..3] {
                *c = ((*c as u32 * 255) / a).min(255) as u8;
            }
        }
    }
    let mut out = Vec::new();
    let mut e = png::Encoder::new(&mut out, w, h);
    e.set_color(png::ColorType::Rgba);
    e.set_depth(png::BitDepth::Eight);
    e.set_compression(png::Compression::Fast);
    e.write_header().ok()?.write_image_data(&px).ok()?;
    Some(out)
}

/// The file path in a thumb.localhost URL path, which the window percent-encodes.
pub fn path_of(uri_path: &str) -> String {
    let b = uri_path.trim_start_matches('/').as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let hex = |c: u8| (c as char).to_digit(16);
        match (b[i], b.get(i + 1).and_then(|&c| hex(c)), b.get(i + 2).and_then(|&c| hex(c))) {
            (b'%', Some(x), Some(y)) => {
                out.push((x * 16 + y) as u8);
                i += 3;
            }
            (c, ..) => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes_paths() {
        assert_eq!(super::path_of("/C%3A%5CUsers%5Cme%5Cpi%C3%B1a%20%40x.jpg"), "C:\\Users\\me\\piña @x.jpg");
    }
}
