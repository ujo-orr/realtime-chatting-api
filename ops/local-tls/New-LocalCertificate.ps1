param([string]$OpenSsl = 'C:/Program Files/Git/usr/bin/openssl.exe')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskTls = Join-Path $taskRoot '.local/tls'
if (-not (Test-Path -LiteralPath $OpenSsl)) { throw 'OpenSSL 실행 파일 경로를 -OpenSsl로 지정해 주세요.' }
New-Item -ItemType Directory -Force -Path $taskTls | Out-Null
if (Test-Path -LiteralPath (Join-Path $taskTls 'server.key')) { throw '기존 인증서를 덮어쓰지 않습니다. 기존 .local/tls를 별도 보관한 뒤 다시 실행하세요.' }
function Invoke-OpenSsl([string[]]$Arguments) {
    & $OpenSsl @Arguments
    if ($LASTEXITCODE -ne 0) { throw "OpenSSL 실패: $($Arguments[0])" }
}
$taskCaKey = Join-Path $taskTls 'ca.key'
$taskCaCert = Join-Path $taskTls 'ca.crt'
if ((Test-Path -LiteralPath $taskCaKey) -or (Test-Path -LiteralPath $taskCaCert)) { throw '부분 생성된 CA를 발견했습니다. 기존 파일을 확인하세요.' }
Invoke-OpenSsl @('req','-x509','-newkey','rsa:3072','-nodes','-sha256','-days','365','-subj','/CN=Realtime Chat Local Learning CA',
    '-addext','basicConstraints=critical,CA:TRUE,pathlen:0','-addext','keyUsage=critical,keyCertSign,cRLSign',
    '-keyout',$taskCaKey,'-out',$taskCaCert)
@'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
'@ | Set-Content -Encoding ascii -LiteralPath (Join-Path $taskTls 'server.ext')
Invoke-OpenSsl @('req','-new','-newkey','rsa:2048','-nodes','-sha256','-subj','/CN=localhost',
    '-keyout',(Join-Path $taskTls 'server.key'),'-out',(Join-Path $taskTls 'server.csr'))
Invoke-OpenSsl @('x509','-req','-in',(Join-Path $taskTls 'server.csr'),'-CA',$taskCaCert,'-CAkey',$taskCaKey,
    '-CAcreateserial','-days','30','-sha256','-extfile',(Join-Path $taskTls 'server.ext'),'-out',(Join-Path $taskTls 'server.crt'))
Invoke-OpenSsl @('verify','-CAfile',$taskCaCert,'-verify_hostname','localhost',(Join-Path $taskTls 'server.crt'))
Write-Output "로컬 인증서 생성 완료: $taskTls (Windows 신뢰 저장소 변경 없음)"
