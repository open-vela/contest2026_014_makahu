param(
    [string]$Sdk = 'C:\Environments\Android\SDK',
    [string]$AvdHome = "",
    [string]$Avd = 'BufferWin_Android17_R7',
    [int]$Port = 5582,
    [int]$HciPort = 6402,
    [string]$Lab = "$env:USERPROFILE\Projects\buffer-emulator-lab"
)
$ErrorActionPreference = 'Stop'
if ($AvdHome) { $env:ANDROID_AVD_HOME = $AvdHome }
if ($Port -lt 5554 -or $Port -gt 5682 -or $Port % 2) { throw 'Use an even emulator console port.' }
$emulator = Join-Path $Sdk 'emulator\emulator.exe'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
if (!(Test-Path $emulator) -or !(Test-Path $adb)) { throw 'Windows SDK is missing.' }
$known = @(& $emulator -list-avds)
if ($known -notcontains $Avd) { throw "Unknown existing AVD: $Avd" }
$running = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -like 'qemu-system*' -and
    ($_.CommandLine -match "-port\s+$Port\b" -or $_.CommandLine -match [regex]::Escape($Avd))
}
if ($running) { throw 'Requested AVD/port already running; inspect it instead of restarting.' }
$logs = Join-Path $Lab 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
# Existing AVD/data are reused. No wipe-data, data copy, reset or snapshot write.
$process = Start-Process -FilePath $emulator -ArgumentList @('-avd', $Avd,
    '-port', "$Port", '-no-snapshot-load', '-no-snapshot-save', '-no-audio',
     '-gpu', 'software', '-netsim-args', ('"--hci-port {0}"' -f $HciPort)) -WorkingDirectory $Lab -RedirectStandardOutput (Join-Path $logs "fabric-android-$stamp.stdout.log") `
    -RedirectStandardError (Join-Path $logs "fabric-android-$stamp.stderr.log") -PassThru
"Windows Android started PID=$($process.Id), serial=emulator-$Port, AVD=$Avd"
"Visible window requested; boot/UI stability must be verified separately."
& $adb devices -l
