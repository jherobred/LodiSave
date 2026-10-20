# Builds QuickJS (quickjs-ng, MIT) for the Android app into android/app/src/main/jniLibs/<abi>/libqjs.so.
# yt-dlp runs it to solve YouTube's JavaScript challenges. It is an executable named like a library
# so Android unpacks it into the app's native library folder, the one place an app may run files from.
#
# Usage: pwsh -File scripts/build-quickjs.ps1   (needs the Android NDK and the GitHub CLI)

$ErrorActionPreference = "Stop"
$tag = "v0.17.0"
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$ndk = Get-ChildItem (Join-Path $sdk "ndk") -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
$clang = Join-Path $ndk.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"

$work = Join-Path ([IO.Path]::GetTempPath()) "lodisave-quickjs-$tag"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory $work | Out-Null
gh api "repos/quickjs-ng/quickjs/tarball/$tag" > (Join-Path $work "src.tgz")
tar -xzf (Join-Path $work "src.tgz") -C $work
$src = (Get-ChildItem $work -Directory | Select-Object -First 1).FullName

$sources = "qjs.c", "gen/repl.c", "gen/standalone.c", "quickjs.c", "quickjs-libc.c", "libregexp.c", "libunicode.c", "dtoa.c"
foreach ($abi in @{ "arm64-v8a" = "aarch64"; "x86_64" = "x86_64" }.GetEnumerator()) {
    $out = Join-Path $root "android\app\src\main\jniLibs\$($abi.Key)\libqjs.so"
    New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
    Push-Location $src
    & $clang "--target=$($abi.Value)-linux-android29" -O2 -s -fPIE -pie -D_GNU_SOURCE -w -I. -o $out @sources -lm -ldl
    Pop-Location
    if ($LASTEXITCODE) { throw "QuickJS build failed for $($abi.Key)" }
    "{0,-12} {1,8:N0} KB" -f $abi.Key, ((Get-Item $out).Length / 1KB)
}
Remove-Item -Recurse -Force $work
