# Removes Trackhop Desktop through its MSI entry (found by name in Apps & features). The data in
# %APPDATA%\Trackhop stays.
$ErrorActionPreference = 'Stop'

Uninstall-BinFile -Name 'trackhop'

[array]$keys = Get-UninstallRegistryKey -SoftwareName 'Trackhop'
if ($keys.Count -eq 1) {
  $packageArgs = @{
    packageName    = $env:ChocolateyPackageName
    fileType       = 'msi'
    silentArgs     = "$($keys[0].PSChildName) /qn /norestart"
    file           = ''
    validExitCodes = @(0, 3010, 1605, 1614, 1641)
  }
  Uninstall-ChocolateyPackage @packageArgs
} elseif ($keys.Count -eq 0) {
  Write-Warning "Trackhop is not in Apps & features: it was removed already."
} else {
  Write-Warning "$($keys.Count) entries named Trackhop: remove it from Settings > Apps instead."
  $keys | ForEach-Object { Write-Warning "- $($_.DisplayName) $($_.DisplayVersion)" }
}
