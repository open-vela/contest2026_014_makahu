param(
    [Parameter(Mandatory=$true)][string]$PeerAddress,
    [string]$Sdk = 'C:\Environments\Android\SDK',
    [string]$Serial = 'emulator-5582',
    [string]$Lab = "$env:USERPROFILE\Projects\buffer-emulator-lab"
)
$ErrorActionPreference = 'Stop'
if ($PeerAddress -notmatch '^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$') { throw 'Supply the actual peer Bluetooth address.' }
$adb=Join-Path $Sdk 'platform-tools\adb.exe'
if ((& $adb -s $Serial shell getprop sys.boot_completed).Trim() -ne '1') { throw 'Android is not booted.' }
$apk=Join-Path $Lab 'artifacts\fabric-portable-interop-20261016.apk'
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'GATT test APK install failed.' }
$package='com.mocharealm.foundation.fabric.ble.test'
& $adb -s $Serial shell pm grant $package android.permission.BLUETOOTH_CONNECT
if ($LASTEXITCODE -ne 0) { throw 'Bluetooth permission for the framework TEST package was not granted.' }
$output = & $adb -s $Serial shell am instrument -w -e fabricPeerAddress $PeerAddress -e class `
    com.mocharealm.foundation.fabric.ble.GattCarrierTest "$package/androidx.test.runner.AndroidJUnitRunner" 2>&1
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$output | Set-Content -Encoding UTF8 (Join-Path $Lab "logs\fabric-real-gatt-$stamp.log")
$output | ForEach-Object { "$_" }
if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Actual GATT setup did not pass; inspect the recorded failure.' }
'Actual GATT identity/CCC setup passed; this does not establish Fabric trust or application Session delivery.'
