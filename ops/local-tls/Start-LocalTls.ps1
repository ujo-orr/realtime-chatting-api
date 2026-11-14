$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $taskRoot
foreach ($taskFile in @('.env','.local/tls/server.crt','.local/tls/server.key')) {
    if (-not (Test-Path -LiteralPath $taskFile)) { throw "준비 파일 없음: $taskFile" }
}
& .\gradlew.bat bootJar --console=plain
if ($LASTEXITCODE -ne 0) { throw '실행 JAR 빌드 실패' }
& docker compose -f compose.yaml -f compose.tls.yaml config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose 구성 확인 실패' }
& docker compose -f compose.yaml -f compose.tls.yaml up -d --build
if ($LASTEXITCODE -ne 0) { throw '로컬 TLS 서비스 시작 실패' }
Write-Output 'HTTPS: https://localhost:8443/chat-test.html (기본 포트 기준)'
Write-Output '검증 도구에 .local/tls/ca.crt를 명시적으로 신뢰시켜 주세요.'
