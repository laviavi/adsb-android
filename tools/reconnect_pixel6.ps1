<#
Finds and connects to the Pixel 6 dev phone over WiFi ADB, regardless of its
current IP. Matches by adb device serial (stable across IP changes), not by
a hardcoded address.

Usage:
  powershell -File tools\reconnect_pixel6.ps1
  powershell -File tools\reconnect_pixel6.ps1 -Subnet 192.168.1. -TimeoutMs 150

Strategy:
  1. Fast path: if a wifi-connected device with the right serial is already
     in `adb devices`, done.
  2. Otherwise: async-scan the subnet for hosts with port 5555 open
     (parallel TcpClient connects, not a sequential loop), then adb-connect
     each candidate and check its serial until the Pixel 6 is found.
#>

param(
    [string]$Subnet    = "192.168.0.",
    [string]$AdbPath   = "C:\Android\Sdk\platform-tools\adb.exe",
    [string]$TargetSerial = "26211FDF60096D",   # Pixel 6 USB serial - stable identity
    [int]$Port         = 5555,
    [int]$TimeoutMs    = 200
)

function Get-ConnectedSerial($ip) {
    & $AdbPath connect "${ip}:$Port" *> $null
    # get-serialno returns the "ip:port" transport string for network devices,
    # not the hardware serial - must query the actual device property instead.
    $serial = & $AdbPath -s "${ip}:$Port" shell getprop ro.serialno 2>$null
    return "$serial".Trim()
}

# ── Fast path: already connected? ──────────────────────────────────────────
$existing = & $AdbPath devices | Select-String ":$Port\s+device"
foreach ($line in $existing) {
    $ip = ($line -split ":")[0]
    if ((Get-ConnectedSerial $ip) -eq $TargetSerial) {
        Write-Output "ALREADY CONNECTED: ${ip}:${Port}"
        exit 0
    }
}

# ── Scan subnet for hosts with the ADB port open ───────────────────────────
Write-Output "Scanning $($Subnet)0/24 for port $Port ..."
$tasks = 1..254 | ForEach-Object {
    $ip = "$Subnet$_"
    $client = New-Object System.Net.Sockets.TcpClient
    [PSCustomObject]@{
        Ip     = $ip
        Client = $client
        Async  = $client.BeginConnect($ip, $Port, $null, $null)
    }
}

$openHosts = @()
foreach ($t in $tasks) {
    if ($t.Async.AsyncWaitHandle.WaitOne($TimeoutMs)) {
        try {
            $t.Client.EndConnect($t.Async)
            $openHosts += $t.Ip
        } catch {}
    }
    $t.Client.Close()
}

Write-Output "Candidates with port $Port open: $($openHosts -join ', ')"

foreach ($ip in $openHosts) {
    $serial = Get-ConnectedSerial $ip
    if ($serial -eq $TargetSerial) {
        Write-Output "FOUND: Pixel 6 at ${ip}:${Port} (serial $serial)"
        exit 0
    } else {
        & $AdbPath disconnect "${ip}:$Port" *> $null
    }
}

Write-Output "NOT FOUND: Pixel 6 not reachable on $($Subnet)0/24. Is it on the same WiFi network?"
exit 1
