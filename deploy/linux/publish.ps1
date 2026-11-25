param(
    [string]$Version,
    [switch]$Plan,
    [switch]$Bootstrap,
    [switch]$StorageBootstrap,
    [switch]$NoWait,
    [string]$Retry,
    [string]$Status,
    [int]$TimeoutSeconds = 1800
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$python = Join-Path $projectRoot 'ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Prepare the existing project Python environment first.' }
$java17 = Join-Path $env:USERPROFILE '.jdks/ms-17.0.17'
if (Test-Path -LiteralPath (Join-Path $java17 'bin/java.exe')) {
    $env:JAVA_HOME = $java17
    $env:Path = (Join-Path $java17 'bin') + ';' + $env:Path
}
$arguments = @((Join-Path $PSScriptRoot 'publish.py'), '--timeout', [string]$TimeoutSeconds)
if ($Version) { $arguments += @('--version', $Version) }
if ($Plan) { $arguments += '--plan' }
if ($Bootstrap) { $arguments += '--bootstrap' }
if ($StorageBootstrap) { $arguments += '--storage-bootstrap' }
if ($NoWait) { $arguments += '--no-wait' }
if ($Retry) { $arguments += @('--retry', $Retry) }
if ($Status) { $arguments += @('--status', $Status) }
& $python @arguments
exit $LASTEXITCODE
