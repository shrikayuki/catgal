[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$statePath = Join-Path $projectRoot 'gal-server\target\local-share\ngrok-state.json'
if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Output '本项目没有已记录的隧道进程。'
    return
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$upstream = if ($state.upstream) { [string]$state.upstream } else { 'http://127.0.0.1:8088' }
if ($upstream -notin @('http://127.0.0.1:8088', 'http://192.168.150.104:8088')) {
    throw '记录的隧道入口不在本项目允许范围内，请核对后处理。'
}
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$state.processId)"
if ($process) {
    if ($process.Name -ne 'ngrok.exe' -or $process.CommandLine -notlike "*$upstream*") {
        throw '记录的进程与本项目 ngrok 隧道不匹配，请核对后处理。'
    }
    Stop-Process -Id $process.ProcessId
}
Remove-Item -LiteralPath $statePath
Write-Output '本项目的公网分享隧道已停止。'
