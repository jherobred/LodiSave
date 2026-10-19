# Builds the files for a GitHub release into dist/:
#   LodiSave-Setup-<version>.exe       the installer, with the app and the extension inside
#   LodiSave-<version>-portable.exe    the app alone, nothing to install
#   LodiSave-extension-<version>.zip   the browser extension, for Load unpacked
#   SHA256SUMS.txt                     their checksums
#
# Steps: build the app, build the uninstaller (the installer crate without a payload), stage
# everything to install, then build the installer with the staged files inside.
#
# Usage: pwsh -File scripts/build-release.ps1 [-SkipApp]

param([switch]$SkipApp)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content -Raw (Join-Path $root "app\tauri.conf.json") | ConvertFrom-Json).version
$manifest = Get-Content -Raw (Join-Path $root "extension\manifest.json") | ConvertFrom-Json
if ($manifest.version -ne $version) { throw "extension/manifest.json is $($manifest.version), app is $version" }

function Invoke-Native([string]$what, [scriptblock]$block) {
  Write-Host "==> $what"
  & $block
  if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" }
}

if (-not $SkipApp) {
  Push-Location (Join-Path $root "app")
  try { Invoke-Native "Build the app" { cargo build --release --locked } } finally { Pop-Location }
}

$installer = Join-Path $root "installer"
Push-Location $installer
try {
  Remove-Item Env:LODI_STAGING -ErrorAction SilentlyContinue
  Invoke-Native "Build the uninstaller" { cargo build --release --locked }
} finally { Pop-Location }

$stage = Join-Path $installer "target\staging"
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $root "app\target\release\lodisave.exe") (Join-Path $stage "LodiSave.exe")
Copy-Item (Join-Path $installer "target\release\lodisave-setup.exe") (Join-Path $stage "Uninstall LodiSave.exe")
Copy-Item (Join-Path $root "extension") (Join-Path $stage "extension") -Recurse
Copy-Item (Join-Path $root "LICENSE") (Join-Path $stage "LICENSE.txt")

Push-Location $installer
try {
  $env:LODI_STAGING = $stage
  Invoke-Native "Build the installer" { cargo build --release --locked }
} finally {
  Remove-Item Env:LODI_STAGING -ErrorAction SilentlyContinue
  Pop-Location
}

$dist = Join-Path $root "dist"
if (Test-Path $dist) { Remove-Item -LiteralPath $dist -Recurse -Force }
New-Item -ItemType Directory -Force $dist | Out-Null
$files = @(
  @{ From = Join-Path $installer "target\release\lodisave-setup.exe"; To = "LodiSave-Setup-$version.exe" },
  @{ From = Join-Path $root "app\target\release\lodisave.exe"; To = "LodiSave-$version-portable.exe" }
)
foreach ($f in $files) { Copy-Item $f.From (Join-Path $dist $f.To) }
Compress-Archive -Path (Join-Path $root "extension\*") -DestinationPath (Join-Path $dist "LodiSave-extension-$version.zip")

$sums = Get-ChildItem $dist -File | Sort-Object Name | ForEach-Object {
  "$((Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant())  $($_.Name)"
}
[IO.File]::WriteAllText((Join-Path $dist "SHA256SUMS.txt"), ($sums -join "`n") + "`n")
Get-ChildItem $dist | ForEach-Object { Write-Host ("{0,-40} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
