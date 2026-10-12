<#
.SYNOPSIS
  Read-only smoke test for the booking-api stack (Windows PowerShell 5.1 compatible).
.DESCRIPTION
  Sends only GET requests; never writes business data. Exits 0 when all checks pass, 1 otherwise.
  Docker checks are skipped with -SkipDocker.
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$BackendContainer = 'booking-backend',
    [switch]$SkipDocker
)

$ErrorActionPreference = 'Stop'
$script:failures = 0

function Get-Http([string]$Path) {
    # curl.exe (not Invoke-WebRequest) so 4xx/5xx responses do not throw in PowerShell 5.1.
    $raw = & curl.exe -s --max-time 10 -w '|||%{http_code}' ($BaseUrl + $Path)
    if ($LASTEXITCODE -ne 0) { return @{ Status = 0; Body = '' } }
    $parts = ([string]($raw -join "`n")) -split '\|\|\|'
    return @{ Status = [int]$parts[-1]; Body = $parts[0] }
}

function Check([string]$Name, [bool]$Passed, [string]$Detail) {
    if ($Passed) { Write-Host "PASS  $Name" }
    else { Write-Host "FAIL  $Name  ($Detail)"; $script:failures++ }
}

foreach ($probe in 'liveness', 'readiness') {
    $r = Get-Http "/actuator/health/$probe"
    Check "$probe returns 200 UP" (($r.Status -eq 200) -and ($r.Body -match '"status"\s*:\s*"UP"')) "status=$($r.Status)"
    Check "$probe exposes no internals" (-not ($r.Body -match 'components|details|jdbc|postgres|password')) 'body contains internal detail'
}

$aggregate = Get-Http '/actuator/health'
Check 'aggregate health has no components' (($aggregate.Status -eq 200) -and -not ($aggregate.Body -match 'components|details')) "status=$($aggregate.Status)"

$api = Get-Http '/anything'
$expected401 = '{"status":401,"error":"Unauthorized","message":"authentication required"}'
Check 'protected API returns 401 with standard body' (($api.Status -eq 401) -and ($api.Body -eq $expected401)) "status=$($api.Status)"

foreach ($path in '/actuator', '/actuator/env', '/actuator/beans', '/actuator/info', '/actuator/metrics', '/actuator/health/db') {
    $r = Get-Http $path
    Check "$path is not available" (($r.Status -eq 401) -or ($r.Status -eq 404)) "status=$($r.Status)"
    Check "$path returns no data" (-not ($r.Body -match 'propertySources|beans|contexts|activeProfiles|components')) 'unexpected body content'
}

if (-not $SkipDocker) {
    $health = & docker inspect $BackendContainer --format '{{.State.Health.Status}}' 2>$null
    Check "docker health of $BackendContainer is healthy" ($health -eq 'healthy') "health=$health"
}

if ($script:failures -gt 0) {
    Write-Host "`nSMOKE TEST FAILED: $($script:failures) check(s)"
    exit 1
}
Write-Host "`nSMOKE TEST PASSED"
exit 0
