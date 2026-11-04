param([int]$Port = 6402, [switch]$ProbeReset)
$ErrorActionPreference = 'Stop'
# Read-only by default. Windows may serialize TCP Listen State as null; never
# infer readiness only from the text 'LISTENING'. Verify the endpoint and owner.
Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | ForEach-Object {
    [pscustomobject]@{ LocalAddress=$_.LocalAddress; LocalPort=$_.LocalPort;
        RemoteAddress=$_.RemoteAddress; RemotePort=$_.RemotePort; State=$_.State;
        Owner=(Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).Path }
} | ConvertTo-Json
if (!$ProbeReset) { return }
# Each new netsim HCI socket creates a new temporary virtual controller. This
# probe checks H4 transport, not GATT, Fabric pairing or physical radio.
$client = New-Object System.Net.Sockets.TcpClient
try {
    if (!$client.ConnectAsync('127.0.0.1', $Port).Wait(3000)) { throw 'HCI connect timeout' }
    $stream=$client.GetStream(); $stream.ReadTimeout=3000
    $reset=[byte[]](1,3,12,0); $stream.Write($reset,0,$reset.Length)
    $reply=New-Object byte[] 7; $offset=0
    while ($offset -lt 7) {
        $read=$stream.Read($reply,$offset,7-$offset)
        if ($read -eq 0) { throw 'HCI closed before CommandComplete' }
        $offset += $read
    }
    $encoded=[BitConverter]::ToString($reply)
    if ($encoded -ne '04-0E-04-01-03-0C-00') { throw "Unexpected Reset response: $encoded" }
    "H4 Reset successful: $encoded (temporary controller only)"
} finally { $client.Dispose() }
