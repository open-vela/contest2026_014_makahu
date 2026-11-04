param(
    [string]$LabRoot = 'C:\Users\Simon\Projects\buffer-emulator-lab',
    [string]$ProductRoot = '',
    [string]$SourceBuild = '\\wsl.localhost\Ubuntu\tmp\buffer-private-root\build',
    [string]$PrivateDiskSource = '\\wsl.localhost\Ubuntu\tmp\buffer-private-littlefs.bin',
    [int]$Port = 5584,
    [int]$ShellPort = 4444,
    [int]$Width = 320,
    [int]$Height = 480,
    [int]$QmpPort = 45584,
    [switch]$UpdateFirmware,
    [switch]$Ble,
    [switch]$SkipAppStart,
    [int]$HciPort = 6402
)
$ErrorActionPreference = 'Stop'
if (!$ProductRoot) { $ProductRoot = Join-Path $LabRoot 'tools\vela-agent-oobe-20261016' }
$exe = Join-Path $LabRoot 'tools\vela-emulator-windows-dev\prebuilts_emulator_windows-x86_64-dev\emulator.exe'
if (!(Test-Path $exe)) { throw 'Existing Windows openvela runtime not found; set LabRoot.' }
$running = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match 'qemu-system-aarch64|emulator' -and $_.CommandLine -like "*$ProductRoot*"
}
if ($running) { throw 'This product is already running. Stop only its own instance before updating.' }
New-Item -ItemType Directory -Force -Path $ProductRoot, (Join-Path $LabRoot 'logs') | Out-Null
foreach ($name in @('nuttx','vela_system.bin','config.ini','advancedFeatures.ini')) {
    $target = Join-Path $ProductRoot $name
    if (!(Test-Path $target) -or $UpdateFirmware) {
        Copy-Item -LiteralPath (Join-Path $SourceBuild $name) -Destination $target
    }
}
# Existing Windows userdata is never replaced, including this new product's data.
foreach ($pair in @(@('vela_data.bin',(Join-Path $SourceBuild 'vela_data.bin')),
                     @('buffer-private-littlefs.bin',$PrivateDiskSource))) {
    $target = Join-Path $ProductRoot $pair[0]
    if (!(Test-Path $target)) { Copy-Item -LiteralPath $pair[1] -Destination $target }
}
# Out-of-tree Windows launch needs the same ARM64 marker as emulator.sh.
# Without it this runtime defaults to x86_64 even with an ARM64 ELF.
$systemDir = Join-Path $ProductRoot 'system'
New-Item -ItemType Directory -Force -Path $systemDir | Out-Null
Set-Content -LiteralPath (Join-Path $systemDir 'build.prop') -Encoding Ascii -Value 'ro.product.cpu.abi=arm64'
$configFile = Join-Path $ProductRoot 'config.ini'
$config = Get-Content -LiteralPath $configFile -Raw
$dataBytes = (Get-Item -LiteralPath (Join-Path $ProductRoot 'vela_data.bin')).Length
$config = $config -replace '(?m)^disk.dataPartition.size\s*=.*$', "disk.dataPartition.size = $dataBytes"
Set-Content -LiteralPath $configFile -Encoding Ascii -Value $config
$disk = (Join-Path $ProductRoot 'buffer-private-littlefs.bin').Replace('\','/')
$env:ANDROID_EMULATOR_VELA = 'true'
$env:ANDROID_BUILD_TOP = $LabRoot
$env:ANDROID_PRODUCT_OUT = $ProductRoot
$arguments = @('-show-kernel','-verbose','-no-snapshot','-skin',"${Width}x${Height}",
    '-gpu','swiftshader_indirect','-port',"$Port",
    '-shell-serial',"tcp:127.0.0.1:${ShellPort},server,nowait",
    '-kernel',(Join-Path $ProductRoot 'nuttx'),'-system',(Join-Path $ProductRoot 'vela_system.bin'),
    '-data',(Join-Path $ProductRoot 'vela_data.bin'),'-qemu',
    '-device','virtio-snd,bus=virtio-mmio-bus.2','-allow-host-audio',
    '-blockdev',"driver=file,filename=$disk,node-name=bufferfile",
    '-blockdev','driver=raw,file=bufferfile,node-name=bufferprivate',
    '-device','virtio-blk-device,drive=bufferprivate,bus=virtio-mmio-bus.3',
    '-semihosting-config','enable=on,target=native',
    '-qmp',"tcp:127.0.0.1:${QmpPort},server=on,wait=off")
if ($Ble) {
    # Bus3 is the private disk; retain the historical netsim H4 bridge on bus6.
    $arguments += @('-chardev',"socket,id=buffer-bt,host=127.0.0.1,port=$HciPort,reconnect=1",
        '-device','virtio-serial-device,id=buffer-bt-serial,bus=virtio-mmio-bus.6',
        '-device','virtconsole,chardev=buffer-bt,bus=buffer-bt-serial.0')
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $LabRoot "logs\vela-agent-$stamp.stdout.log"
$stderr = Join-Path $LabRoot "logs\vela-agent-$stamp.stderr.log"
$process = Start-Process $exe -WorkingDirectory $LabRoot -ArgumentList $arguments `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
[pscustomobject]@{ ProcessId=$process.Id; Product=$ProductRoot; Port=$Port;
    ShellPort=$ShellPort; QmpPort=$QmpPort; Stdout=$stdout; Stderr=$stderr }
if (!$SkipAppStart) {
    # The guest RTC is UTC. Give this demo process the host's current offset
    # so its local clock and alarm labels agree with the visible Windows clock.
    $offsetMinutes = [int][TimeZoneInfo]::Local.GetUtcOffset([DateTime]::UtcNow).TotalMinutes
    $offsetSign = if ($offsetMinutes -ge 0) { '-' } else { '+' }
    $absoluteOffset = [Math]::Abs($offsetMinutes)
    $guestTimezone = 'UTC{0}{1}:{2:00}' -f $offsetSign, [Math]::Floor($absoluteOffset / 60), ($absoluteOffset % 60)
    # Wait for this guest's actual NSH prompt; do not race serial registration
    # or leave the visible emulator showing an empty framebuffer after reboot.
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    $started=$false
    while ([DateTime]::UtcNow -lt $deadline -and !$started) {
        $client=$null
        try {
            $client=[Net.Sockets.TcpClient]::new('127.0.0.1',$ShellPort)
            $stream=$client.GetStream();$stream.ReadTimeout=1000
            $buffer=New-Object byte[] 8192
            $text=[Text.StringBuilder]::new()
            $newline=[Text.Encoding]::UTF8.GetBytes("`r`n")
            $stream.Write($newline,0,$newline.Length)
            do {
                $n=$stream.Read($buffer,0,$buffer.Length)
                if (!$n) { break }
                [void]$text.Append([Text.Encoding]::UTF8.GetString($buffer,0,$n))
            } while (!$text.ToString().Contains('goldfish-armv8a-ap>') -and [DateTime]::UtcNow -lt $deadline)
            if ($text.ToString().Contains('goldfish-armv8a-ap>')) {
                $command=[Text.Encoding]::UTF8.GetBytes("set TZ $guestTimezone`r`nbuffer_app &`r`n")
                $stream.Write($command,0,$command.Length)
                $started=$true
            }
        } catch [Net.Sockets.SocketException] { }
          catch [IO.IOException] { }
        finally { if ($client) { $client.Dispose() } }
        if (!$started) { Start-Sleep -Milliseconds 250 }
    }
    if (!$started) { Write-Warning 'Window started, but NSH was not ready to start Buffer; inspect guest serial.' }
    else { 'Buffer start command sent after actual guest prompt.' }
}

# Keep host audio enabled for the explicit virtio-snd MMIO device. In this
# Windows runtime, -no-audio produced zero-filled captures even after hostmicon.
# Its virtual microphone must also be enabled for real host input.
# Enable input for recordings explicitly started by the user in the device UI.
$console = $null
try {
    $console = [Net.Sockets.TcpClient]::new('127.0.0.1', $Port)
    $audioStream = $console.GetStream(); $audioStream.ReadTimeout = 3000
    function Read-AudioConsoleReply {
        $reply = [Text.StringBuilder]::new(); $bytes = New-Object byte[] 4096
        do {
            $count = $audioStream.Read($bytes, 0, $bytes.Length)
            if (!$count) { throw 'Emulator console disconnected' }
            [void]$reply.Append([Text.Encoding]::UTF8.GetString($bytes, 0, $count))
        } while (!$reply.ToString().EndsWith("OK`r`n") -and !$reply.ToString().Contains('KO:'))
        $reply.ToString()
    }
    function Send-AudioConsoleCommand([string]$command) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($command + "`n")
        $audioStream.Write($bytes, 0, $bytes.Length)
        Read-AudioConsoleReply
    }
    $greeting = Read-AudioConsoleReply
    if ($greeting.Contains('Authentication required')) {
        $consoleToken = (Get-Content -LiteralPath (Join-Path $env:USERPROFILE '.emulator_console_auth_token') -Raw).Trim()
        $authReply = Send-AudioConsoleCommand ('auth ' + $consoleToken)
        $consoleToken = $null
        if ($authReply.Contains('KO:')) { throw 'Emulator console authentication failed' }
    }
    $micReply = Send-AudioConsoleCommand 'avd hostmicon'
    if ($micReply.Contains('KO:')) { throw 'Emulator microphone enable failed' }
    'Host microphone input enabled for user recordings.'
} catch { Write-Warning ('Host microphone could not be enabled: ' + $_.Exception.Message) }
finally { if ($console) { $console.Dispose() } }
