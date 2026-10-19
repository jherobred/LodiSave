<p align="center"><img src="brand/icon.svg" width="96" alt=""></p>

# LodiSave

Save the photos and videos that sites won't let you save.

LodiSave is a Windows tray app and a browser extension for Chrome and Edge. Right-click blocked, an invisible layer over the picture, a video that only streams: LodiSave gets the file anyway, in its original quality.

<p align="center"><img src="docs/picker.png" width="720" alt="Hover picker: two photos ticked with round checkboxes, and a bar that says 3 picked, Save 3"></p>

## Features

- **Hover picker.** Hover a photo or video on any site and tick the round checkbox at its top right. Tick several, then save them all from the bar at the bottom. Works under click-blocking overlays and inside embedded frames.
- **Popup grid.** The toolbar button lists every image, video and stream on the page, biggest first.
- **Shift + right-click** opens the menu on pages that block it. "Save with LodiSave" saves what's under the pointer.
- **Streams.** Saves MediaSource, HLS and DASH video, and joins separate video and audio into one MP4. Asks X and Pinterest for the full-size original.
- **Tray app.** Copy a link from Instagram, TikTok, X, Facebook, Reddit or YouTube, click Lodi in the tray and save it. It also saves a copied image, and Ctrl+Alt+L captures a window or area of the screen.
- English or Taglish, light and dark, motion on or off.

<p align="center"><img src="docs/panel.png" width="300" alt="Tray panel"> <img src="docs/installer.png" width="480" alt="Setup"></p>

## Install

1. Download `LodiSave-Setup-0.1.0.exe` from [Releases](https://github.com/jherobred/LodiSave/releases). The builds aren't code-signed yet, so SmartScreen may warn: choose More info, then Run anyway.
2. For the browser extension, open LodiSave from the tray and choose Settings, then Browser extension, Set up. In `chrome://extensions` or `edge://extensions`, turn on Developer mode, choose Load unpacked and pick the folder that opened. The same files are in `LodiSave-extension-0.1.0.zip`.

`LodiSave-0.1.0-portable.exe` runs without installing.

Saves go to `Downloads\LodiSave`. If Windows Security's Controlled folder access protects Downloads, allow LodiSave under Ransomware protection.

## Limits

- DRM-protected video is refused. LodiSave doesn't decrypt.
- The extension skips YouTube, as Chrome Web Store rules require. The tray app saves YouTube links.
- A stream saves what the player has loaded. Play it to the end to save all of it.
- Posts behind a login need the extension in your signed-in browser.

## Privacy

Everything runs on your PC, with no account and no analytics. The first time you save a link, the tray app downloads [yt-dlp](https://github.com/yt-dlp/yt-dlp) from its GitHub releases, and it checks for a yt-dlp update once a week.

## Build

Needs Windows, Node 20+, Rust 1.90+, Microsoft Edge and FFmpeg on PATH (the tests make their media with it).

```bash
npm install
npm test
cargo test --release --manifest-path app/Cargo.toml
pwsh scripts/build-release.ps1
```

`npm test` runs the extension on local fixture pages in Edge and checks every saved file against its original. The release script writes the installer, the portable app, the extension zip and `SHA256SUMS.txt` to `dist/`.

| Folder | What |
|---|---|
| `extension/` | Browser extension, Manifest V3, no dependencies |
| `app/` | Tray app, Tauri 2 and Rust |
| `installer/` | Setup and uninstaller, Rust and WebView2 |
| `fixtures/` | Test pages and the end-to-end check |
| `brand/` | Icon sources and the script that renders them |

## License

MIT. The Geist fonts are under the SIL Open Font License 1.1. yt-dlp is under the Unlicense and is downloaded at first use, not bundled.

Save only what you have the right to keep, and respect creators and each site's terms.
