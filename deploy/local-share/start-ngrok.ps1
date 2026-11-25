[CmdletBinding()]
param(
    [switch]$DirectConnection,
    [ValidateSet('http://127.0.0.1:8088', 'http://192.168.150.104:8088')]
    [string]$Upstream = 'http://127.0.0.1:8088'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runtimeDirectory = Join-Path $projectRoot 'gal-server\target\local-share'
$statePath = Join-Path $runtimeDirectory 'ngrok-state.json'

if (-not $env:NGROK_AUTHTOKEN) {
    $env:NGROK_AUTHTOKEN = [Environment]::GetEnvironmentVariable('NGROK_AUTHTOKEN', 'User')
}
if ([string]::IsNullOrWhiteSpace($env:NGROK_AUTHTOKEN)) {
    throw '请先将 ngrok Authtoken 保存到 Windows 用户环境变量 NGROK_AUTHTOKEN。'
}

$ngrokCommand = Get-Command ngrok.exe -ErrorAction SilentlyContinue
$ngrokPath = if ($ngrokCommand) { $ngrokCommand.Source } else {
    Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Microsoft\WindowsApps\ngrok.exe'
}
if (-not (Test-Path -LiteralPath $ngrokPath)) {
    throw '请安装 ngrok Microsoft Store 版本，产品 ID 为 9MVS1J51GMK6。'
}
if (Test-Path -LiteralPath $statePath) {
    $previous = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if (Get-Process -Id $previous.processId -ErrorAction SilentlyContinue) {
        throw '本项目的隧道已有运行记录，请先执行 stop-ngrok.ps1，或核对 ngrok-state.json。'
    }
}

$page = Invoke-WebRequest "$Upstream/" -UseBasicParsing -TimeoutSec 10
if ($page.Content -match '/@vite/client' -or $page.Content -notmatch '/assets/index-') {
    throw '请先启动所选入口的静态前端。'
}
$api = Invoke-RestMethod "$Upstream/api/game/page?pageSize=1" -TimeoutSec 10
if ($api.code -ne 200) { throw 'Java API 健康检查失败。' }
if ($Upstream -eq 'http://127.0.0.1:8088') {
    $ai = Invoke-RestMethod 'http://127.0.0.1:8090/health/ready' -TimeoutSec 10
    if ($ai.status -ne 'ready') { throw 'Python AI 服务尚未就绪。' }
}

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$outputPath = Join-Path $runtimeDirectory 'ngrok-out.log'
$errorPath = Join-Path $runtimeDirectory 'ngrok-error.log'
$proxyVariables = @{}
try {
    if ($DirectConnection) {
        foreach ($name in 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY') {
            $proxyVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
    }
    $process = Start-Process -FilePath $ngrokPath -ArgumentList @(
        'http', $Upstream, '--inspect=false', '--log=stdout', '--log-format=json'
    ) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $outputPath -RedirectStandardError $errorPath
} finally {
    foreach ($name in $proxyVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $proxyVariables[$name], 'Process')
    }
}

$publicUrl = $null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    try {
        $tunnels = Invoke-RestMethod 'http://127.0.0.1:4040/api/tunnels' -TimeoutSec 2
        $tunnel = $tunnels.tunnels | Where-Object {
            $_.proto -eq 'https' -and $_.config.addr -eq $Upstream
        } | Select-Object -First 1
        if ($tunnel) {
            $publicUrl = $tunnel.public_url
            break
        }
    } catch {
        # The local agent API becomes available after the tunnel session is established.
    }
    if ($process.HasExited) { break }
}

if (-not $publicUrl) {
    if (-not $process.HasExited) { $process.Kill() }
    throw "ngrok 尚未建立隧道，请检查本地日志 $errorPath 和 $outputPath。"
}

$agent = Get-CimInstance Win32_Process -Filter "Name = 'ngrok.exe'" | Where-Object {
    $_.CommandLine -like "*$Upstream*"
} | Select-Object -First 1
$agentId = if ($agent) { $agent.ProcessId } else { $process.Id }
@{
    processId = $agentId
    publicUrl = $publicUrl
    upstream = $Upstream
    startedAt = (Get-Date).ToUniversalTime().ToString('o')
} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8

Write-Output "公网访问地址 $publicUrl"
Write-Output "停止分享时执行 $PSScriptRoot\stop-ngrok.ps1"
