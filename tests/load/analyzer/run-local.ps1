param(
    [ValidateSet('volume','sustained','jobs')][string]$Scenario = 'jobs',
    [int]$Jobs = 100,
    [int]$Vus = 100,
    [string]$Project = 'sentinel-a',
    [string]$Label = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$results = Join-Path $repo '.local/evidence'
New-Item -ItemType Directory -Path $results -Force | Out-Null
Push-Location -LiteralPath $repo
try {
    $compose = @('compose','-p',$Project,'--env-file','.local/.env','-f','infrastructure/developer-a.compose.yml','-f','infrastructure/discovery/standalone.compose.yml')
    $instances = @(& docker @compose ps -q analyzer)
    if ($LASTEXITCODE -or $instances.Count -eq 0) { throw 'Start the local Compose project before k6.' }
    $replicas = $instances.Count
    if (!$Label) { $Label = "$Scenario-$replicas-replicas" }
    if ($Label -notmatch '^[a-z0-9-]+$') { throw 'Use lowercase letters, numbers or dashes in Label.' }
    $os = [Environment]::OSVersion.VersionString
    $cpus = [Environment]::ProcessorCount
    $dockerCapacity = & docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes memory'
    $environment = "$os, $cpus host logical CPUs; Docker engine $dockerCapacity"
    $metricsLine = Get-Content -LiteralPath '.local/.env' |
        Where-Object { $_.StartsWith('METRICS_SERVICE_KEY=') } | Select-Object -First 1
    function Read-ReplicaMetrics {
        if (!$metricsLine) { return }
        $headers = @{ 'X-Sentinel-Service-Key' = $metricsLine.Substring('METRICS_SERVICE_KEY='.Length) }
        for ($index = 1; $index -le $replicas; $index++) {
            $port = & docker @compose port --index $index analyzer 8083
            if ($LASTEXITCODE) { continue }
            try {
                $metrics = (Invoke-WebRequest -UseBasicParsing -Uri "http://$port/actuator/prometheus" -Headers $headers -TimeoutSec 5).Content
                $progress = [regex]::Match($metrics, '(?m)^sentinel_analysis_jobs_total\{state="COMPLETED"\}\s+([0-9.eE+-]+)')
                $backlog = [regex]::Match($metrics, '(?m)^sentinel_analysis_pending_jobs\s+([0-9.eE+-]+)')
                [pscustomobject]@{
                    index = $index
                    completed = if ($progress.Success) { [double]$progress.Groups[1].Value } else { 0 }
                    pendingJobs = if ($backlog.Success) { [double]$backlog.Groups[1].Value } else { $null }
                }
            } catch {
                [pscustomobject]@{ index = $index; completed = $null; pendingJobs = $null }
            }
        }
    }
    $replicaBefore = @(Read-ReplicaMetrics)
    $queueBefore = & docker @compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged
    $runArgs = @('run','--rm','--user','0','--network',"${Project}_default",
        '--mount',"type=bind,source=$PSScriptRoot,target=/work,readonly",
        '--mount',"type=bind,source=$results,target=/results",
        '-e',"SCENARIO=$Scenario",'-e',"JOBS=$Jobs",'-e',"VUS=$Vus",'-e',"ANALYZER_REPLICAS=$replicas",
        '-e',"SENTINEL_TEST_ENV=$environment",'-e',"SUMMARY_PATH=/results/k6-$Label.json",
        '-e','K6_NO_USAGE_REPORT=true','grafana/k6:1.3.0','run','--quiet','/work/sentinel.js')
    & docker @runArgs
    $result = $LASTEXITCODE
    $queueAfter = & docker @compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged
    $replicaAfter = @(Read-ReplicaMetrics)
    $queueReport = @{
        environment = $environment; replicas = $replicas; exitCode = $result
        before = @($queueBefore); after = @($queueAfter)
        replicaBefore = $replicaBefore; replicaAfter = $replicaAfter
    } | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText((Join-Path $results "queue-$Label.json"), $queueReport, [Text.UTF8Encoding]::new($false))
    exit $result
} finally {
    Pop-Location
}
