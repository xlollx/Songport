# Installs Trackhop Desktop from its MSI on GitHub (release server-v<version>), checked against its
# SHA-256. The MSI is per user and needs no administrator; it replaces an older Trackhop in place.
$ErrorActionPreference = 'Stop'

$packageArgs = @{
  packageName    = $env:ChocolateyPackageName
  fileType       = 'msi'
  url64bit       = 'https://github.com/xlollx/Trackhop/releases/download/server-v0.5.1/Trackhop-Desktop-v0.5.1-windows.msi'
  checksum64     = 'e78a04dcd69713c5342b01794a8854d3a558a6a7b2c514adf312fa81936604c9'
  checksumType64 = 'sha256'
  softwareName   = 'Trackhop'
  silentArgs     = "/qn /norestart /l*v `"$($env:TEMP)\$($env:ChocolateyPackageName).$($env:ChocolateyPackageVersion).MsiInstall.log`""
  validExitCodes = @(0, 3010, 1641)
}

Install-ChocolateyPackage @packageArgs
