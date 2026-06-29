$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Internet Banking - Loan Review Dev (Minimal)" -ForegroundColor Cyan
Write-Host " customer / loan / auto-loan-review / review-ai-gateway" -ForegroundColor Cyan
Write-Host " doc-agent / api-gateway / web" -ForegroundColor Cyan
Write-Host "============================================================"
Write-Host ""

# Docker check
docker info 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Docker Desktop is not running." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Env vars (loan-review 심사 흐름에 필요한 것만)
$env:SPRING_PROFILES_ACTIVE   = "local"
$env:CUSTOMER_DB_PORT         = "15432"
$env:DEPOSIT_DB_PORT          = "5433"
$env:DEPOSIT_DB_PASSWORD      = "deposit"
$env:LOAN_DB_PORT             = "5434"
$env:AI_DB_PORT               = "5437"
$env:COMMON_DB_PORT           = "5438"
# doc-agent-db: 5440 (common-db 5438 / langfuse-db 5439 충돌 회피)
$env:DOC_AGENT_DB_PORT        = "5440"
$env:VAULT_PORT               = "8200"
$env:MINIO_PORT               = "9000"
$env:REDIS_HOST               = "localhost"
# 호스트 bootRun 모드: 도커 네트워크 호스트명 대신 localhost로 오버라이드
$env:AUTO_REVIEW_BASE_URL     = "http://localhost:8089"
$env:DOC_AGENT_BASE_URL       = "http://localhost:8087"
$env:AIGATEWAY_BASE_URL       = "http://localhost:8088"
$env:CRYPTO_KEY_BASE64        = "OhIN2XxxrFW6nzY6iIQ8sTfTr/L6NQJxgdCZfqJon+k="
# payment-service (대출 실행/집행 단계) — payment-db 5435, 단일 브로커(localhost:9092) 통합
$env:PAYMENT_DB_PORT          = "5435"
$env:SCHEMA_REGISTRY_URL      = "http://localhost:18081"
$env:KFTC_KAFKA_BOOTSTRAP     = "localhost:9092"
$env:BOK_KAFKA_BOOTSTRAP      = "localhost:9092"
$env:INTERNAL_KAFKA_BOOTSTRAP = "localhost:9092"
# loan-service → payment-service 직결(기본값은 게이트웨이 8080). 호스트 bootRun이라 8084로 오버라이드.
$env:PAYMENT_SERVICE_URL      = "http://localhost:8084"

# .env 에서 API 키 등 시크릿 로드 (OPENAI_API_KEY, ANTHROPIC_API_KEY 등)
$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^[A-Za-z_][A-Za-z_0-9]*=' } | ForEach-Object {
        $idx = $_.IndexOf('=')
        $key = $_.Substring(0, $idx)
        $val = $_.Substring($idx + 1)
        [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
    }
}

# alertmanager.yml 없으면 sample에서 복사 (docker compose 마운트 오류 방지)
$amConf   = Join-Path $root "infra\alertmanager\alertmanager.yml"
$amSample = Join-Path $root "infra\alertmanager\alertmanager.yml.sample"
if ((Test-Path $amConf -PathType Container)) { Remove-Item -Recurse -Force $amConf }
if (-not (Test-Path $amConf) -and (Test-Path $amSample)) { Copy-Item $amSample $amConf }

# 1. 인프라 컨테이너 (loan 심사 플로우 필수 9개)
#    --profile doc: doc-agent-db / minio / vault 가 doc 프로파일 소속이므로 활성화 필요
Write-Host "[1/3] Starting infrastructure (loan-review subset)..." -ForegroundColor Yellow
$infra = @(
    "kafka",          # loan-domain-events 발행·소비 (loan → auto-review / review-ai-gateway)
    "loan-db",        # loan-service 전용 DB
    "common-db",      # loan-service 공통 계좌 DB
    "customer-db",    # customer-service DB
    "deposit-db",     # deposit-service DB (집행 자금 입금 계좌)
    "ai-db",          # auto-loan-review DB (pgvector)
    "doc-agent-db",   # doc-agent DB  [profile: doc]
    "payment-db",     # payment-service DB (대출 실행/집행)
    "redis",          # loan-service + auto-loan-review 캐시
    "minio",          # doc-agent 파일 저장소  [profile: doc]
    "vault",          # doc-agent 문서 암호화  [profile: doc]
    "schema-registry",   # payment-service Avro 스키마 레지스트리(18081)
    "payment-topic-init" # kafka healthy 후 결제계 토픽 선생성(원샷) — payment bootRun 전 토픽 보장
)
docker compose --profile doc up -d @infra
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] docker compose failed" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# 2. 헬스체크 대기
Write-Host ""
Write-Host "[2/3] Waiting for health checks (up to 90s)..." -ForegroundColor Yellow
$waited = 0
while ($waited -lt 90) {
    Start-Sleep -Seconds 5
    $waited += 5
    $starting = docker compose ps --format "{{.Health}}" 2>$null | Where-Object { $_ -eq "starting" }
    if (-not $starting) { break }
    Write-Host "  waiting... ($waited`s)"
}
Write-Host "  Infrastructure ready ($waited`s)" -ForegroundColor Green

# 3. Spring Boot 서비스 + web 기동
Write-Host ""
Write-Host "[3/3] Starting services..." -ForegroundColor Yellow

Start-Process cmd -ArgumentList "/k gradlew.bat :services:customer-service:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:loan-service:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:deposit-service:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:payment-service:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:api-gateway:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:auto-loan-review:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:review-ai-gateway:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:doc-agent:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2

Start-Process cmd -ArgumentList "/k npm run dev" -WorkingDirectory "$root\web" -WindowStyle Normal

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " 9 service windows opened (ready in ~30-90s)"
Write-Host ""
Write-Host " Web UI      http://localhost:3001" -ForegroundColor Green
Write-Host ""
Write-Host " Swagger:"
Write-Host "   customer   http://localhost:8081/swagger-ui.html"
Write-Host "   deposit    http://localhost:8082/swagger-ui.html"
Write-Host "   loan       http://localhost:8083/swagger-ui.html"
Write-Host "   payment    http://localhost:8084/swagger-ui.html"
Write-Host "   api-gw     http://localhost:8080/actuator/health"
Write-Host "   auto-loan  http://localhost:8089/swagger-ui.html"
Write-Host "   review-ai  http://localhost:8088/swagger-ui.html"
Write-Host "   doc-agent  http://localhost:8087/swagger-ui.html"
Write-Host ""
Write-Host " 미포함 (loan 심사 외):"
Write-Host "   master-service / 모니터링(Grafana/Prometheus/Langfuse)"
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Read-Host "Press Enter to exit"
