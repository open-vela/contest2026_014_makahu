param([int]$Port=5584,[string]$Output='D:/buffer-vela-agent-oobe-20261016/oobe-windows.png')
$ErrorActionPreference='Stop'
$tcp=[Net.Sockets.TcpClient]::new('127.0.0.1',$Port)
try {
    $stream=$tcp.GetStream();$stream.ReadTimeout=5000
    function Read-Reply {
        $text=[Text.StringBuilder]::new();$buffer=New-Object byte[] 4096
        do {
            $n=$stream.Read($buffer,0,$buffer.Length)
            if ($n -eq 0) { throw 'Console disconnected' }
            [void]$text.Append([Text.Encoding]::UTF8.GetString($buffer,0,$n))
        } while (!$text.ToString().EndsWith("OK`r`n") -and !$text.ToString().Contains('KO:'))
        return $text.ToString()
    }
    function Send-Command([string]$command) {
        $bytes=[Text.Encoding]::UTF8.GetBytes($command+"`n")
        $stream.Write($bytes,0,$bytes.Length)
        return Read-Reply
    }
    $greeting=Read-Reply
    if ($greeting.Contains('Authentication required')) {
        $token=(Get-Content -LiteralPath (Join-Path $env:USERPROFILE '.emulator_console_auth_token') -Raw).Trim()
        $reply=Send-Command ('auth '+$token);$token=$null
        if ($reply.Contains('KO:')) { throw 'Console authentication failed' }
    }
    if ($Output.Contains("`n") -or $Output.Contains(' ')) { throw 'Use an output path without spaces/newlines' }
    $reply=Send-Command ('screenrecord screenshot '+$Output.Replace('\','/'))
    if ($reply.Contains('KO:')) { throw "Screenshot failed: $reply" }
    if (!(Test-Path -LiteralPath $Output)) { throw 'Screenshot was not written' }
    Get-Item -LiteralPath $Output | Select-Object FullName,Length,LastWriteTime
} finally { $tcp.Dispose() }
