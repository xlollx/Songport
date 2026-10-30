# Sets the Chocolatey package to a Trackhop Desktop release: the version in trackhop.nuspec, and the
# MSI's address and SHA-256 in tools/chocolateyinstall.ps1. Run by .github/workflows/desktop.yml
# before `choco pack`; by hand:  pwsh packaging/chocolatey/update.ps1 -Version 0.5.2 -Checksum <sha256>
param(
  [Parameter(Mandatory)] [string] $Version,
  [Parameter(Mandatory)] [string] $Checksum
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Not a version: $Version" }
if ($Checksum -notmatch '^[0-9a-fA-F]{64}$') { throw "Not a SHA-256: $Checksum" }
$dir = Join-Path $PSScriptRoot 'trackhop'

$nuspec = Join-Path $dir 'trackhop.nuspec'
$text = [IO.File]::ReadAllText($nuspec)
$text = $text -replace '<version>[^<]*</version>', "<version>$Version</version>"
$text = $text -replace 'releases/tag/server-v[\d.]+', "releases/tag/server-v$Version"
[IO.File]::WriteAllText($nuspec, $text)

$install = Join-Path $dir 'tools/chocolateyinstall.ps1'
$text = [IO.File]::ReadAllText($install)
$text = $text -replace 'server-v[\d.]+/Trackhop-Desktop-v[\d.]+-windows\.msi', "server-v$Version/Trackhop-Desktop-v$Version-windows.msi"
$text = $text -replace "checksum64     = '[0-9a-fA-F]*'", "checksum64     = '$($Checksum.ToLower())'"
[IO.File]::WriteAllText($install, $text)
Write-Host "trackhop $Version, MSI sha256 $($Checksum.ToLower())"
