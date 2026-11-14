$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $taskRoot
$taskHttpPort = '8081'
$taskTlsPort = '8443'
if (Test-Path -LiteralPath '.env') {
    foreach ($taskLine in Get-Content -LiteralPath '.env') {
        if ($taskLine -match '^HTTP_PORT=(\d+)$') { $taskHttpPort = $Matches[1] }
        if ($taskLine -match '^TLS_PORT=(\d+)$') { $taskTlsPort = $Matches[1] }
    }
}
$env:CHAPTER4_BASE_URL = "https://localhost:$taskTlsPort"
$env:CHAPTER4_HTTP_URL = "http://localhost:$taskHttpPort"
$env:CHAPTER4_CA_CERT = (Resolve-Path -LiteralPath '.local/tls/ca.crt').Path
& .\gradlew.bat proxyTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'HTTPS 프록시 통합 테스트 실패' }
