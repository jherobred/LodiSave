//! Windows' own Share window (as in Photos and File Explorer), for files the user picked and then
//! confirmed in the gallery's Share sheet. Even then nothing leaves until they also pick an app or a
//! person in Windows' window; closing it sends nothing.

use std::sync::Mutex;
use windows::ApplicationModel::DataTransfer::{DataRequestedEventArgs, DataTransferManager};
use windows::Foundation::TypedEventHandler;
use windows::Storage::{IStorageItem, StorageFile};
use windows::Win32::Foundation::HWND;
use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx};
use windows::Win32::UI::Shell::IDataTransferManagerInterop;
use windows::core::{AgileReference, HSTRING, Interface, factory};

/// The window and the token of the handler that hands over the files, so the next share replaces it.
static HANDLER: Mutex<Option<(isize, i64)>> = Mutex::new(None);

/// Opens the Share window over `hwnd` for `paths`. Runs on the window's own thread. Windows asks for
/// the files once the window is up; they're read on a worker thread, as the window's thread mustn't
/// wait on Windows' storage.
pub fn show(hwnd: isize, paths: Vec<HSTRING>, title: HSTRING) -> windows::core::Result<()> {
    let interop = factory::<DataTransferManager, IDataTransferManagerInterop>()?;
    let window = HWND(hwnd as _);
    let manager: DataTransferManager = unsafe { interop.GetForWindow(window)? };
    let mut handler = HANDLER.lock().unwrap();
    if let Some((_, token)) = handler.take().filter(|(w, _)| *w == hwnd) {
        let _ = manager.RemoveDataRequested(token);
    }
    let token = manager.DataRequested(&TypedEventHandler::<DataTransferManager, DataRequestedEventArgs>::new(move |_, args| {
        let Some(args) = args.as_ref() else { return Ok(()) };
        let request = args.Request()?;
        request.Data()?.Properties()?.SetTitle(&title)?;
        let deferral = AgileReference::new(&request.GetDeferral()?)?;
        let request = AgileReference::new(&request)?;
        let paths = paths.clone();
        std::thread::spawn(move || {
            let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
            let hand = || -> windows::core::Result<()> {
                let request = request.resolve()?;
                let files: windows::core::Result<Vec<Option<IStorageItem>>> =
                    paths.iter().map(|p| Ok(Some(StorageFile::GetFileFromPathAsync(p)?.join()?.cast::<IStorageItem>()?))).collect();
                match files {
                    Ok(files) => request.Data()?.SetStorageItems(&windows_collections::IIterable::from(files), true),
                    Err(_) => request.FailWithDisplayText(&HSTRING::from("A file is gone or Windows blocked it.")),
                }
            };
            let _ = hand();
            if let Ok(d) = deferral.resolve() {
                let _ = d.Complete();
            }
        });
        Ok(())
    }))?;
    *handler = Some((hwnd, token));
    unsafe { interop.ShowShareUIForWindow(window) }
}
