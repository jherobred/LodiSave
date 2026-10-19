//! Packs the staged app into the installer and adds the Windows icon, manifest and
//! version information.
//!
//! `LODI_STAGING` names a folder holding everything to install (the app, its engines and
//! the uninstaller). Without it the build has no payload and runs as the uninstaller.

use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

fn main() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let version = app_version(&manifest_dir.join("../app/tauri.conf.json"));
    println!("cargo:rustc-env=LODI_APP_VERSION={version}");

    println!("cargo:rerun-if-env-changed=LODI_STAGING");
    let payload = match std::env::var_os("LODI_STAGING") {
        Some(dir) if !dir.is_empty() => {
            let dir = PathBuf::from(dir);
            println!("cargo:rerun-if-changed={}", dir.display());
            Some(pack(&dir, &out.join("payload.tar.zst")))
        }
        _ => None,
    };
    let (bytes, files) = payload.unwrap_or((0, 0));
    let include = if payload.is_some() {
        r#"include_bytes!(concat!(env!("OUT_DIR"), "/payload.tar.zst"))"#
    } else {
        "&[]"
    };
    fs::write(
        out.join("payload.rs"),
        format!(
            "/// The staged app as a zstd-compressed tar. Empty in the uninstaller.\n\
             pub static PAYLOAD: &[u8] = {include};\n\
             /// Uncompressed size of all files in the payload.\n\
             pub const PAYLOAD_BYTES: u64 = {bytes};\n\
             pub const PAYLOAD_FILES: u64 = {files};\n"
        ),
    )
    .unwrap();

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        resources(&manifest_dir, &out, &version, payload.is_some());
    }
}

fn app_version(conf: &Path) -> String {
    println!("cargo:rerun-if-changed={}", conf.display());
    let text = fs::read_to_string(conf).expect("read tauri.conf.json");
    let json: serde_json::Value = serde_json::from_str(&text).expect("parse tauri.conf.json");
    json["version"].as_str().expect("version in tauri.conf.json").to_string()
}

/// Files under `dir`, sorted so the archive is reproducible.
fn walk(dir: &Path, base: &Path, out: &mut Vec<(PathBuf, String)>) {
    let mut entries: Vec<_> = fs::read_dir(dir).unwrap().map(|e| e.unwrap().path()).collect();
    entries.sort();
    for path in entries {
        if path.is_dir() {
            walk(&path, base, out);
        } else {
            let rel = path.strip_prefix(base).unwrap();
            let name = rel.to_string_lossy().replace('\\', "/");
            out.push((path, name));
        }
    }
}

fn pack(dir: &Path, dest: &Path) -> (u64, u64) {
    let mut files = Vec::new();
    walk(dir, dir, &mut files);
    assert!(!files.is_empty(), "LODI_STAGING ({}) is empty", dir.display());

    let mut encoder = zstd::stream::Encoder::new(BufWriter::new(File::create(dest).unwrap()), 19).unwrap();
    // Small jobs let several threads share the work. Each thread holds its window and job in
    // memory, so a few threads keep the build usable on a busy PC.
    let threads = std::thread::available_parallelism().map(|n| n.get() as u32).unwrap_or(1);
    encoder.multithread(threads.min(4)).unwrap();
    encoder
        .set_parameter(zstd::stream::raw::CParameter::JobSize(32 << 20))
        .unwrap();
    // The payload is small, so a 16 MB window covers it and keeps the build's memory low.
    encoder.long_distance_matching(true).unwrap();
    encoder.window_log(24).unwrap();
    encoder.include_checksum(true).unwrap();

    let mut tar = tar::Builder::new(encoder);
    tar.mode(tar::HeaderMode::Deterministic);
    let mut total = 0;
    for (path, name) in &files {
        total += fs::metadata(path).unwrap().len();
        tar.append_path_with_name(path, name).unwrap();
    }
    tar.into_inner().unwrap().finish().unwrap().flush().unwrap();
    (total, files.len() as u64)
}

fn resources(manifest_dir: &Path, out: &Path, version: &str, setup: bool) {
    let icon = manifest_dir.join("../app/icons/icon.ico");
    let manifest = manifest_dir.join("app.manifest");
    println!("cargo:rerun-if-changed={}", icon.display());
    println!("cargo:rerun-if-changed={}", manifest.display());

    let mut parts: Vec<u16> = version
        .split(|c: char| !c.is_ascii_digit())
        .filter_map(|p| p.parse().ok())
        .collect();
    parts.resize(4, 0);
    let numeric = format!("{},{},{},{}", parts[0], parts[1], parts[2], parts[3]);
    let (description, file_name) = if setup {
        ("LodiSave Setup", format!("LodiSave-Setup-{version}.exe"))
    } else {
        ("LodiSave Uninstaller", "Uninstall LodiSave.exe".to_string())
    };
    let esc = |p: &Path| p.canonicalize().unwrap().display().to_string().replace('\\', "\\\\");
    let rc = format!(
        r#"#pragma code_page(65001)
1 ICON "{icon}"
1 24 "{manifest}"
1 VERSIONINFO
FILEVERSION {numeric}
PRODUCTVERSION {numeric}
FILEOS 0x40004
FILETYPE 0x1
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904B0"
    BEGIN
      VALUE "CompanyName", "LodiSave contributors"
      VALUE "FileDescription", "{description}"
      VALUE "FileVersion", "{version}"
      VALUE "InternalName", "lodisave-setup"
      VALUE "LegalCopyright", "Copyright (c) 2026 LodiSave contributors. MIT License."
      VALUE "OriginalFilename", "{file_name}"
      VALUE "ProductName", "LodiSave"
      VALUE "ProductVersion", "{version}"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
"#,
        icon = esc(&icon),
        manifest = esc(&manifest),
    );
    let rc_path = out.join("installer.rc");
    fs::write(&rc_path, rc).unwrap();
    embed_resource::compile(&rc_path, embed_resource::NONE)
        .manifest_required()
        .unwrap();
}
