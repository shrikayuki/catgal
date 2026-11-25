param(
    [string]$Version = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [switch]$EnableEstablishedAiFeatures,
    [switch]$GenerateServiceSecret,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$secretDir = Join-Path $PSScriptRoot 'secrets'
$outputDir = Join-Path $projectRoot 'target/linux'
if ($Version -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}$') {
    throw 'Version must be a valid Docker image tag of at most 80 characters.'
}
foreach ($kind in @('source', 'private')) {
    if (Test-Path -LiteralPath (Join-Path $outputDir "catgal-$kind-$Version.zip")) {
        throw 'An archive for this version already exists. Choose a new version before exporting.'
    }
}
if ((Test-Path $secretDir) -and -not $Force) {
    throw 'Deployment secrets already exist. Review them before explicitly using -Force.'
}

function Read-Setting([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name, 'User')
    if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    if ([string]::IsNullOrEmpty($value)) { $value = $Default }
    return $value
}

function Read-Template([string]$Path) {
    $settings = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $name = $Matches[1]
            $defaultValue = $Matches[2]
            if ($defaultValue -match '<[^>]+>') { $defaultValue = '' }
            $value = Read-Setting $name $defaultValue
            $settings[$name] = $value
        }
    }
    return $settings
}

function Write-Environment([string]$Path, $Settings) {
    $lines = foreach ($entry in $Settings.GetEnumerator()) {
        if ([string]$entry.Value -match '[\r\n\x00]') {
            throw "Environment value must be a single line. Check $($entry.Key)."
        }
        # Compose env_file format raw preserves $, quotes and JSON literally.
        "$($entry.Key)=$($entry.Value)"
    }
    [IO.File]::WriteAllText($Path, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}

function New-Archive([string]$Path, [string[]]$Files) {
    if (Test-Path -LiteralPath $Path) { throw "Archive already exists. Choose a new version. $Path" }
    $archive = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in $Files) {
            $source = Join-Path $projectRoot $file
            if (Test-Path -LiteralPath $source -PathType Leaf) {
                [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive, $source, $file.Replace('\', '/'), [IO.Compression.CompressionLevel]::Optimal)
            }
        }
    } finally {
        $archive.Dispose()
    }
}

$java = Read-Template (Join-Path $projectRoot '.env.example')
$ai = Read-Template (Join-Path $projectRoot 'ai-service/.env.example')
if ([string]::IsNullOrWhiteSpace($java['AI_SERVICE_HMAC_SECRET']) -and $GenerateServiceSecret) {
    $randomBytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
    $java['AI_SERVICE_HMAC_SECRET'] = [Convert]::ToBase64String($randomBytes)
}
$required = @('MYSQL_URL', 'MYSQL_USERNAME', 'MYSQL_PASSWORD', 'REDIS_HOST', 'RABBITMQ_HOST',
    'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD', 'JWT_SECRET', 'AI_SERVICE_HMAC_SECRET')
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace($java[$_]) })
if ($missing.Count -gt 0) { throw ('Missing environment variable names: ' + ($missing -join ', ')) }
if ($java['STORAGE_ENABLED'] -eq 'true') {
    foreach ($name in @('MINIO_ENDPOINT', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'MINIO_BUCKET')) {
        if ([string]::IsNullOrWhiteSpace($java[$name])) { throw ('Missing storage environment variable: ' + $name) }
    }
}
if ($java['AI_SERVICE_HMAC_SECRET'].Length -lt 32) { throw 'AI_SERVICE_HMAC_SECRET must contain at least 32 characters.' }

$mysqlUri = [Uri]($java['MYSQL_URL'] -replace '^jdbc:', '')
if ($mysqlUri.Scheme -ne 'mysql' -or $mysqlUri.AbsolutePath -eq '/') { throw 'MYSQL_URL must identify a MySQL database.' }
$mysqlPort = if ($mysqlUri.Port -gt 0) { $mysqlUri.Port } else { 3306 }
$java['MYSQL_URL'] = 'jdbc:mysql://host.docker.internal:' + $mysqlPort + $mysqlUri.PathAndQuery
$java['REDIS_HOST'] = 'host.docker.internal'
$java['RABBITMQ_HOST'] = 'host.docker.internal'
$esUri = [UriBuilder]::new($java['ELASTICSEARCH_URL'])
$esUri.Host = 'host.docker.internal'
$java['ELASTICSEARCH_URL'] = $esUri.Uri.AbsoluteUri.TrimEnd('/')
# A Windows proxy address cannot be inherited by Linux containers.
$java['CATALOG_EXTERNAL_PROXY_URL'] = ''
$java['SPRING_PROFILES_ACTIVE'] = 'linux'
$java['FLYWAY_ENABLED'] = 'false'
$java['AI_SERVICE_BASE_URL'] = 'http://ai:8090'
$java['LOAD_TEST_TARGET_PORT'] = '8080'
$java['LOAD_TEST_TARGET_PREFIX'] = ''

$ai['AI_APP_ENV'] = 'production'
$ai['AI_SERVICE_HMAC_SECRET'] = $java['AI_SERVICE_HMAC_SECRET']
if ([string]::IsNullOrWhiteSpace($ai['AI_CHAT_API_KEY'])) {
    $ai['AI_CHAT_API_KEY'] = (Read-Setting 'DASHSCOPE_API_KEY' '').Trim()
}
if ([string]::IsNullOrWhiteSpace($ai['AI_EMBEDDING_API_KEY'])) {
    $ai['AI_EMBEDDING_API_KEY'] = (Read-Setting 'DASHSCOPE_API_KEY' '').Trim()
}
$ai['AI_MYSQL_HOST'] = 'host.docker.internal'
$ai['AI_MYSQL_PORT'] = [string]$mysqlPort
$ai['AI_MYSQL_DATABASE'] = $mysqlUri.AbsolutePath.TrimStart('/')
$ai['AI_MYSQL_USERNAME'] = $java['MYSQL_USERNAME']
$ai['AI_MYSQL_PASSWORD'] = $java['MYSQL_PASSWORD']
$redisPassword = [Uri]::EscapeDataString([string]$java['REDIS_PASSWORD'])
$redisAuth = if ($redisPassword) { ':' + $redisPassword + '@' } else { '' }
$ai['AI_REDIS_URL'] = 'redis://' + $redisAuth + 'host.docker.internal:' + $java['REDIS_PORT'] + '/' + $java['REDIS_DATABASE']
$ai['AI_JAVA_TOOLS_BASE_URL'] = 'http://java:8080'
$ai['AI_SEMANTIC_ES_URL'] = $java['ELASTICSEARCH_URL']
$ai['AI_SEMANTIC_ES_USERNAME'] = $java['ELASTICSEARCH_USERNAME']
$ai['AI_SEMANTIC_ES_PASSWORD'] = $java['ELASTICSEARCH_PASSWORD']
$sharedFlags = @('AI_PERSISTENCE_ENABLED', 'AI_PERSONALIZATION_ENABLED', 'AI_LONG_TERM_MEMORY_ENABLED',
    'AI_SESSION_SUMMARY_ENABLED', 'AI_MEMORY_CANDIDATES_ENABLED', 'AI_GAME_TOOLS_ENABLED',
    'AI_SEMANTIC_SEARCH_ENABLED', 'AI_MEMORY_VECTOR_ENABLED')
foreach ($name in $sharedFlags) { $ai[$name] = $java[$name] }
if ($EnableEstablishedAiFeatures) {
    foreach ($name in $sharedFlags | Where-Object { $_ -notin @('AI_SEMANTIC_SEARCH_ENABLED', 'AI_MEMORY_VECTOR_ENABLED') }) {
        $java[$name] = 'true'
        $ai[$name] = 'true'
    }
}
if ([string]::IsNullOrWhiteSpace($ai['AI_CREDENTIAL_ENCRYPTION_KEYS'])) {
    $ai['AI_CREDENTIAL_ENCRYPTION_KEYS'] = '{}'
}
if ($ai['AI_PERSONALIZATION_ENABLED'] -eq 'true') {
    try {
        $keyRing = ConvertFrom-Json -InputObject $ai['AI_CREDENTIAL_ENCRYPTION_KEYS']
        $activeKey = $keyRing.PSObject.Properties[$ai['AI_CREDENTIAL_ACTIVE_KEY_ID']]
        if ($null -eq $activeKey) { throw 'Missing active key' }
        foreach ($property in $keyRing.PSObject.Properties) {
            if ([Convert]::FromBase64String([string]$property.Value).Length -ne 32) { throw 'Invalid key length' }
        }
    } catch {
        throw 'AI_CREDENTIAL_ENCRYPTION_KEYS must preserve valid 32-byte keys and the active key ID.'
    }
}
if ([string]::IsNullOrWhiteSpace($ai['AI_CHAT_API_KEY']) -and $ai['AI_PERSONALIZATION_ENABLED'] -ne 'true') {
    throw 'Set AI_CHAT_API_KEY or enable migrated personal bots before deploying the AI service.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force -Path $secretDir, $outputDir | Out-Null
Write-Environment (Join-Path $secretDir 'java.env') $java
Write-Environment (Join-Path $secretDir 'ai.env') $ai
Write-Environment (Join-Path $PSScriptRoot '.env') ([ordered]@{
    APP_VERSION = $Version; HTTP_BIND = '0.0.0.0'; HTTP_PORT = '8088'
    JAVA_MEMORY_LIMIT = '1024m'; AI_MEMORY_LIMIT = '768m'
})

Push-Location $projectRoot
try {
    # Include current uncommitted source, while respecting repository ignores.
    $files = @(& git -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate workspace source files.' }
    $sourceFiles = @($files | Where-Object {
        $_ -notmatch '(^|/)(docs|prompts|secrets|target|node_modules|\.git|\.venv)(/|$)' -and
        $_ -notmatch '(?i)\.md$|(^|/)\.env($|\.)|\.log$|\.zip$'
    } | Sort-Object -Unique)
    # Templates contain placeholders only and are needed for future updates.
    $sourceFiles += @('.env.example', 'ai-service/.env.example', 'deploy/linux/.env.example')
    New-Archive (Join-Path $outputDir "catgal-source-$Version.zip") $sourceFiles
    New-Archive (Join-Path $outputDir "catgal-private-$Version.zip") @(
        'deploy/linux/.env', 'deploy/linux/secrets/java.env', 'deploy/linux/secrets/ai.env')
} finally {
    Pop-Location
}
Write-Output "Prepared version $Version in $outputDir"
Write-Output 'The private archive contains credentials. Transfer privately and retain it only in protected storage.'
