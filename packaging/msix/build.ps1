# Packs Trackhop Desktop's app image (jpackage's, from server/desktop/package.sh) as an unsigned MSIX
# for the Microsoft Store, which signs it itself when it publishes it. Run on Windows with the
# Windows SDK (MakeAppx), as .github/workflows/desktop.yml does after package.sh:
#
#   pwsh packaging/msix/build.ps1 -IdentityName <Package/Identity/Name> -Publisher "CN=…" -PublisherDisplayName <name>
#
# Unsigned, the MSIX cannot be installed by double-clicking it: it is for the Store's upload only
# (packaging/README.md has how to try it locally).
param(
  [string] $AppImage = 'server/build/desktop/out/Trackhop',
  [string] $OutDir = 'dist',
  [string] $IdentityName = 'xlollx.Trackhop',
  [string] $Publisher = 'CN=xlollx',
  [string] $PublisherDisplayName = 'xlollx'
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '../..')
$version = (Get-Content (Join-Path $root 'server/VERSION') -Raw).Trim()
if ($version -notmatch '^(\d+)\.(\d+)\.(\d+)$') { throw "server/VERSION is not x.y.z: $version" }
# The Store wants a first number above 0 and keeps the fourth for itself, so Trackhop x.y.z is
# package (x+1).y.z.0: 0.5.1 is 1.5.1.0, and 1.0.0 will be 2.0.0.0, always higher than before.
$packageVersion = "$([int]$Matches[1] + 1).$($Matches[2]).$($Matches[3])"

$image = Join-Path $root $AppImage
if (-not (Test-Path (Join-Path $image 'Trackhop.exe'))) { throw "No app image at $($image): run server/desktop/package.sh first" }
$stage = Join-Path ([IO.Path]::GetTempPath()) "trackhop-msix-$PID"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
Copy-Item $image $stage -Recurse

# The start with the computer (AppxManifest.xml's startup task): the same launcher under another
# name reads app/<name>.cfg, here Trackhop's settings plus the argument that keeps the window closed.
Copy-Item (Join-Path $stage 'Trackhop.exe') (Join-Path $stage 'TrackhopStartup.exe')
$cfg = Get-Content (Join-Path $stage 'app/Trackhop.cfg') -Raw
Set-Content (Join-Path $stage 'app/TrackhopStartup.cfg') ($cfg.TrimEnd() + "`r`n`r`n[ArgOptions]`r`narguments=--background`r`n") -NoNewline

Copy-Item (Join-Path $PSScriptRoot 'Assets') (Join-Path $stage 'Assets') -Recurse
$esc = { param($s) [Security.SecurityElement]::Escape($s) }
$manifest = (Get-Content (Join-Path $PSScriptRoot 'AppxManifest.xml') -Raw).
  Replace('@IDENTITY_NAME@', (& $esc $IdentityName)).
  Replace('@PUBLISHER_DISPLAY_NAME@', (& $esc $PublisherDisplayName)).
  Replace('@PUBLISHER@', (& $esc $Publisher)).
  Replace('@VERSION@', $packageVersion)
Set-Content (Join-Path $stage 'AppxManifest.xml') $manifest -Encoding utf8

# MakeAppx from the newest Windows SDK on the machine.
$makeappx = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\makeappx.exe" -ErrorAction SilentlyContinue |
  Sort-Object { [version]$_.Directory.Parent.Name } -Descending | Select-Object -First 1
if (-not $makeappx) { throw 'MakeAppx not found: install the Windows SDK' }

New-Item -ItemType Directory -Force (Join-Path $root $OutDir) | Out-Null
$msix = Join-Path (Join-Path $root $OutDir) "Trackhop-Desktop-v$version-windows.msix"
& $makeappx.FullName pack /d $stage /p $msix /o
if ($LASTEXITCODE -ne 0) { throw "MakeAppx failed ($LASTEXITCODE)" }
Remove-Item $stage -Recurse -Force
Write-Host "$msix (package $IdentityName $packageVersion.0, publisher $Publisher)"
