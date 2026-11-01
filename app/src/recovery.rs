//! Password recovery for locked files, through an authenticator app (Google Authenticator or any
//! other TOTP app). A six-digit code can't hold a key, so it guards one: every lock also wraps its
//! file key with a recovery public key, and the matching private key is sealed with DPAPI for this
//! Windows user. This side hands the private key to the gallery only after a correct, unused code.
//!
//! It keeps a locked file closed to anyone who copies it to another PC or opens LodiSave without
//! the phone. It works only on this PC and this Windows account, and it can't stop a program running
//! as the user, which can unseal DPAPI data too. The gallery says so where it is set up.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const STEP: u64 = 30;
/// Wrong codes allowed before a wait.
const TRIES: u32 = 5;
const WAIT: Duration = Duration::from_secs(60);

#[derive(Default, Serialize, Deserialize)]
struct Saved {
    /// The recovery public key (SPKI), in the clear: the gallery wraps every new lock's key with it.
    public: String,
    /// The private key (PKCS #8) and the authenticator secret, each sealed with DPAPI, as hex.
    private: String,
    secret: String,
}

pub struct Recovery {
    file: PathBuf,
    saved: Mutex<Option<Saved>>,
    /// A secret shown as a QR code and not yet confirmed with a code.
    pending: Mutex<Option<Vec<u8>>>,
    /// The last time step a code was used for, so a code works once.
    used: Mutex<u64>,
    wrong: Mutex<(u32, Option<Instant>)>,
}

#[derive(Serialize)]
pub struct Setup {
    secret: String,
    uri: String,
    svg: String,
}

fn now_step() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs()) / STEP
}

fn hmac_sha1(key: &[u8], data: &[u8]) -> [u8; 20] {
    use windows::Win32::Security::Cryptography::{BCRYPT_HMAC_SHA1_ALG_HANDLE, BCryptHash};
    let mut out = [0u8; 20];
    let _ = unsafe { BCryptHash(BCRYPT_HMAC_SHA1_ALG_HANDLE, Some(key), data, &mut out) };
    out
}

/// The six-digit code for one time step (RFC 6238 over RFC 4226).
fn code(secret: &[u8], step: u64) -> u32 {
    let h = hmac_sha1(secret, &step.to_be_bytes());
    let o = (h[19] & 0xf) as usize;
    (u32::from_be_bytes([h[o], h[o + 1], h[o + 2], h[o + 3]]) & 0x7fff_ffff) % 1_000_000
}

/// The time step a typed code matches, allowing one step of clock drift either way.
fn matches(secret: &[u8], typed: &str, step: u64) -> Option<u64> {
    let typed: String = typed.chars().filter(|c| !c.is_whitespace()).collect();
    if typed.len() != 6 || !typed.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let n: u32 = typed.parse().ok()?;
    [step.saturating_sub(1), step, step + 1].into_iter().find(|&s| code(secret, s) == n)
}

fn base32(b: &[u8]) -> String {
    const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let (mut out, mut buf, mut bits) = (String::new(), 0u32, 0);
    for &x in b {
        buf = (buf << 8) | x as u32;
        bits += 8;
        while bits >= 5 {
            out.push(A[((buf >> (bits - 5)) & 31) as usize] as char);
            bits -= 5;
        }
    }
    if bits > 0 {
        out.push(A[((buf << (5 - bits)) & 31) as usize] as char);
    }
    out
}

/// The QR code as an SVG drawing, black modules on white with a quiet zone, for the gallery to show.
fn qr_svg(text: &str) -> Result<String, String> {
    let qr = qrcode::QrCode::new(text.as_bytes()).map_err(|e| e.to_string())?;
    let (w, cells) = (qr.width(), qr.to_colors());
    let mut path = String::new();
    for (i, c) in cells.iter().enumerate() {
        if *c == qrcode::Color::Dark {
            path += &format!("M{} {}h1v1h-1z", i % w + 4, i / w + 4);
        }
    }
    let side = w + 8;
    Ok(format!(r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {side} {side}" shape-rendering="crispEdges" role="img" aria-label="QR code for your authenticator app"><rect width="{side}" height="{side}" fill="#fff"/><path d="{path}" fill="#000"/></svg>"##))
}

fn seal(b: &[u8]) -> Result<String, String> {
    crate::accounts::dpapi(b, true).map(|s| crate::drive::hex(&s))
}

fn open(s: &str) -> Result<Vec<u8>, String> {
    crate::accounts::dpapi(&crate::drive::unhex(s).ok_or("broken")?, false)
}

impl Recovery {
    pub fn open(data: &Path) -> Recovery {
        let file = data.join("recovery.json");
        let saved = std::fs::read(&file).ok().and_then(|b| serde_json::from_slice(&b).ok());
        Recovery { file, saved: Mutex::new(saved), pending: Mutex::new(None), used: Mutex::new(0), wrong: Mutex::new((0, None)) }
    }

    pub fn is_set(&self) -> bool {
        self.saved.lock().unwrap().is_some()
    }

    /// The public key new locks wrap their file key with, or None when recovery is off.
    pub fn public(&self) -> Option<Vec<u8>> {
        crate::drive::unhex(&self.saved.lock().unwrap().as_ref()?.public)
    }

    /// A new secret for the authenticator app, as a QR code and as text to type in.
    pub fn begin(&self) -> Result<Setup, String> {
        let secret = crate::drive::random_bytes(20);
        let b32 = base32(&secret);
        let uri = format!("otpauth://totp/LodiSave:Locked%20files?secret={b32}&issuer=LodiSave&algorithm=SHA1&digits=6&period={STEP}");
        let svg = qr_svg(&uri)?;
        *self.pending.lock().unwrap() = Some(secret);
        let spaced = b32.as_bytes().chunks(4).map(|c| String::from_utf8_lossy(c).into_owned()).collect::<Vec<_>>().join(" ");
        Ok(Setup { secret: spaced, uri, svg })
    }

    /// Turns recovery on once the app shows a matching code, keeping the gallery's new key pair.
    pub fn finish(&self, typed: &str, public: &[u8], private: &[u8]) -> Result<(), String> {
        let secret = self.pending.lock().unwrap().clone().ok_or("expired")?;
        let step = matches(&secret, typed, now_step()).ok_or("code")?;
        if public.is_empty() || private.is_empty() {
            return Err("broken".into());
        }
        let saved = Saved { public: crate::drive::hex(public), private: seal(private)?, secret: seal(&secret)? };
        let tmp = self.file.with_extension("tmp");
        std::fs::write(&tmp, serde_json::to_vec(&saved).map_err(|e| e.to_string())?)
            .and_then(|_| std::fs::rename(&tmp, &self.file))
            .map_err(|_| "blocked".to_string())?;
        *self.saved.lock().unwrap() = Some(saved);
        *self.pending.lock().unwrap() = None;
        *self.used.lock().unwrap() = step;
        Ok(())
    }

    /// Checks a code: "code" when wrong, "wait" after too many wrong ones, "used" for a code
    /// already spent.
    fn check(&self, typed: &str) -> Result<(), String> {
        let mut wrong = self.wrong.lock().unwrap();
        if let Some(until) = wrong.1 {
            if Instant::now() < until {
                return Err("wait".into());
            }
            *wrong = (0, None);
        }
        let secret = open(&self.saved.lock().unwrap().as_ref().ok_or("off")?.secret)?;
        let Some(step) = matches(&secret, typed, now_step()) else {
            wrong.0 += 1;
            if wrong.0 >= TRIES {
                wrong.1 = Some(Instant::now() + WAIT);
            }
            return Err("code".into());
        };
        let mut used = self.used.lock().unwrap();
        if step <= *used {
            return Err("used".into());
        }
        *used = step;
        *wrong = (0, None);
        Ok(())
    }

    /// The private key, for a correct code.
    pub fn unlock(&self, typed: &str) -> Result<Vec<u8>, String> {
        self.check(typed)?;
        open(&self.saved.lock().unwrap().as_ref().ok_or("off")?.private)
    }

    /// Turns recovery off, for a correct code. Files locked so far keep only their passwords.
    pub fn turn_off(&self, typed: &str) -> Result<(), String> {
        self.check(typed)?;
        let _ = std::fs::remove_file(&self.file);
        *self.saved.lock().unwrap() = None;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn totp_matches_the_rfc() {
        // RFC 6238 appendix B, SHA-1, the last six digits.
        let secret = b"12345678901234567890";
        for (t, want) in [(59u64, 287082), (1111111109, 81804), (1234567890, 5924), (2000000000, 279037)] {
            assert_eq!(code(secret, t / 30), want, "t = {t}");
        }
        assert_eq!(matches(secret, "287 082", 1), Some(1));
        assert_eq!(matches(secret, "287082", 3), None);
        assert_eq!(matches(secret, "28708", 1), None);
        assert_eq!(base32(b"foobar"), "MZXW6YTBOI");
    }

    #[test]
    fn codes_guard_the_key() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("target").join(format!("recovery-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let r = Recovery::open(&dir);
        let setup = r.begin().unwrap();
        assert!(setup.svg.starts_with("<svg") && setup.uri.contains("issuer=LodiSave"));
        let secret = r.pending.lock().unwrap().clone().unwrap();
        let now = |s| format!("{:06}", code(&secret, s));
        assert_eq!(r.finish("000000", b"pub", b"priv"), Err("code".into()));
        // The code that turned it on can't be used again.
        r.finish(&now(now_step()), b"pub", b"priv").unwrap();
        assert_eq!(r.unlock(&now(now_step())), Err("used".into()));
        // A fresh Recovery reads it back; the next step's code opens the key once.
        let r = Recovery::open(&dir);
        assert_eq!(r.public().unwrap(), b"pub");
        assert_eq!(r.unlock(&now(now_step() + 1)).unwrap(), b"priv");
        assert_eq!(r.unlock(&now(now_step() + 1)), Err("used".into()));
        // Five wrong codes make it wait, even for a right one.
        for _ in 0..TRIES {
            let _ = r.unlock("000001");
        }
        assert_eq!(r.unlock(&now(now_step() - 1)), Err("wait".into()));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
