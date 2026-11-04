param(
    [string]$Sdk = 'C:\Environments\Android\SDK',
    [string]$Serial = 'emulator-5582',
    [string]$Lab = "$env:USERPROFILE\Projects\buffer-emulator-lab"
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$api = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
$boot = (& $adb -s $Serial shell getprop sys.boot_completed).Trim()
if ($api -ne '37' -or $boot -ne '1') { throw 'Expected a booted Android17 guest, refusing to test another device.' }
$artifacts = Join-Path $Lab 'artifacts'
$logs = Join-Path $Lab 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
foreach ($apk in @('fabric-hub-20261016-debug.apk', 'fabric-portable-interop-20261016.apk', 'fabric-sdk-admission-20261016.apk')) {
    & $adb -s $Serial install -r (Join-Path $artifacts $apk)
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $apk" }
}
function Test-Instrumentation([string]$Package, [string]$Class, [string]$Name) {
    $output = & $adb -s $Serial shell am instrument -w -e class $Class "$Package/androidx.test.runner.AndroidJUnitRunner" 2>&1
    $output | Set-Content -Encoding UTF8 (Join-Path $logs "fabric-$Name-$stamp.log")
    $output | ForEach-Object { "$_" }
    if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw "Instrumentation did not pass: $Name" }
}
Test-Instrumentation 'com.mocharealm.foundation.fabric.ble.test' 'com.mocharealm.foundation.fabric.ble.PortableInteropTest' 'portable-interop'
$client = 'com.mocharealm.foundation.fabric.sdk.test'
$permission = 'com.mocharealm.foundation.fabric.permission.CONNECT_HUB'
# Only this framework test package's permission is changed. No user app reset.
& $adb -s $Serial shell pm revoke $client $permission
if ($LASTEXITCODE -ne 0) { throw 'Could not revoke the test client permission.' }
Test-Instrumentation $client 'com.mocharealm.foundation.fabric.sdk.BleBinderAdmissionTest#missingConnectPermissionCannotBind' 'binder-denied'
& $adb -s $Serial shell pm grant $client $permission
if ($LASTEXITCODE -ne 0) { throw 'Could not grant the test client permission.' }
Test-Instrumentation $client 'com.mocharealm.foundation.fabric.sdk.BleBinderAdmissionTest#callerAndLiveAbilityGatePortableSessions' 'binder-admission'
