$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$local = Join-Path $repo '.local'
$envFile = Join-Path $local '.env'
$keyFile = Join-Path $local 'sentinel.p12'
if (Test-Path $envFile) { Write-Output "Using existing $envFile"; exit 0 }
New-Item -ItemType Directory -Path $local -Force | Out-Null
function New-Secret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}
if (Test-Path $keyFile) { throw "Signing keystore already exists. Restore its matching environment file first." }
$sentinelKeyPassword = New-Secret
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool.exe' }
$env:SENTINEL_INIT_KEY_PASSWORD = $sentinelKeyPassword
try {
    & $keytool -genkeypair -alias sentinel -keyalg RSA -keysize 3072 -validity 1825 -dname 'CN=API Sentinel Local' -storetype PKCS12 -keystore $keyFile -storepass:env SENTINEL_INIT_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
} finally {
    Remove-Item Env:SENTINEL_INIT_KEY_PASSWORD -ErrorAction SilentlyContinue
}
$lines = @(
    'JWT_ISSUER=http://localhost:5173',
    'JWT_KEYSTORE_PATH=../.local/sentinel.p12',
    "JWT_KEYSTORE_PASSWORD=$sentinelKeyPassword",
    'SECURE_COOKIE=false',
    'CORS_ORIGINS=http://localhost:5173',
    'EUREKA_SELF_PRESERVATION=false',
    'RABBITMQ_USER=sentinel_local'
)
foreach ($name in 'POSTGRES_PASSWORD','IDENTITY_DB_PASSWORD','REGISTRY_DB_PASSWORD','ANALYZER_DB_PASSWORD','RABBITMQ_PASSWORD','ANALYZER_REGISTRY_KEY','REGISTRY_ANALYZER_KEY','IMPACT_SERVICE_KEY','METRICS_SERVICE_KEY') {
    $lines += "$name=$(New-Secret)"
}
[IO.File]::WriteAllLines($envFile, $lines, [Text.UTF8Encoding]::new($false))
Write-Output "Created local environment at $envFile"
