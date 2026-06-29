# 실 임베딩 전환 — Vertex AI text-embedding-005 (ES-only)

> Last updated: 2026-06-29 (v1.1 — 운영 컷오버 런북 추가)
> 근거: next-phase-roadmap.md §Phase B "실 임베딩 전환"
> 패키지 루트: `com.bank.ai`
> 범위 확정: **ES-only** (pgvector/inline 경로는 레거시로 deprecate)
> 기술 스택: Spring Boot 3.3.x / Java 17 / Spring AI 1.0.0 / Elasticsearch 8.15 / Micrometer

---

## 전체 목표

RAG 임베딩을 결정론 `StubEmbeddingClient`(SHA-256 기반, 의미 없음)에서
**Vertex AI `text-embedding-005`(768차원) 실 임베딩**으로 전환한다.

전환은 신규 개발이 아니라 **이미 존재하는 스캐폴딩의 활성화 + 운영 배선 + 전량 재색인**이
핵심이다. 활성 RAG 백엔드는 **Elasticsearch(`ai.rag.backend=es`)** 로 확정하며,
pgvector/inline 경로는 재작업 없이 문서상 deprecate 한다(RAG kill-switch 시 fallback 으로만 잔존).

---

## 현황 (이미 구현되어 있는 것)

| 요소 | 상태 | 위치 |
|---|---|---|
| `EmbeddingClient` 추상화 | ✅ | `com.bank.ai.rag.embedding.EmbeddingClient` |
| `StubEmbeddingClient` (기본, `provider=stub`) | ✅ | 〃 (SHA-256 결정론 768d) |
| `SpringAiEmbeddingClient` (`provider=vertex`) | ✅ | 〃 (`EmbeddingModel` 위임) |
| Gradle 의존성 | ✅ | `spring-ai-starter-model-vertex-ai-embedding` (build.gradle) |
| Vertex 설정 키 | ✅ | `spring.ai.vertex.ai.embedding.*` (application.yml) |
| 배치 적재 경로 | ✅ | `EmbeddingBatchService` → `CorpusIndexer`(=`EsCorpusIndexer`) |
| ES 매핑 3종 | ✅ | `es/mappings/kb_{policy,similar_cases,internal_faq}_v1.json` |

---

## 핵심 갭 / 블로커

### 🔴 G1. 자동구성 제외가 켜져 있음 (1순위 부팅 블로커)

`application.yml` 의 `spring.autoconfigure.exclude` 에
`VertexAiTextEmbeddingAutoConfiguration` 이 포함되어 있다. 이 상태에서는 `EmbeddingModel`
빈이 생성되지 않아, `provider=vertex` 로 바꿔도 `SpringAiEmbeddingClient` 주입이 실패해 부팅이
막힌다.

**제약**: 단순히 exclude 를 제거하면 stub/test 부팅 시에도 `EmbeddingModel` 자동구성이
돌아 GCP project-id/크레덴셜을 요구할 수 있다. → **`provider=vertex` 일 때만 자동구성이 살고,
stub/test 부팅은 GCP 자격증명 없이 되도록** 조건화해야 한다.

### 🔴 G2. stub ↔ vertex 벡터 비호환 → 전량 재색인 필수

`StubEmbeddingClient` 벡터는 SHA-256 결정론 값으로 의미가 없다. 기존 색인을 둔 채 쿼리만
vertex 로 바꾸면 검색이 전부 깨진다. ES 에 적재된 **모든 청크를 vertex 로 재임베딩**해야 하며,
재색인 동안 stub/vertex 벡터가 한 인덱스에 섞이면 안 된다 → **인덱스 버저닝(`kb_*_v2`) +
alias 스왑**으로 격리한다.

### 🟡 G3. 클라이언트 견고성 부족

현재 `SpringAiEmbeddingClient` 는 `embeddingModel.embed()` 직호출뿐이다.
참조 구현(`AdvisoryOpenAiEmbeddingClient`)처럼 **재시도/지수 백오프·타임아웃·차원(768) 검증·
4xx(영구) vs 5xx(일시) 분리·배치 상한**(Vertex 요청당 최대 250 인스턴스)이 없다.

### 🟡 G4. 인증 · 비용 · 관측

GCP ADC/서비스계정·`project-id`·`location` 시크릿 배선, 임베딩 호출 비용/지연 메트릭
(B2 `AgentMetrics` 연계), Vertex 장애 시 fallback 정책이 필요하다.

### ⚪ G5. (범위 외) pgvector/inline 레거시

V4 마이그레이션의 `ai_embedding.embedding vector(1024)` 는 768d 와 불일치하나,
**ES-only 확정으로 재작업하지 않는다.** 문서상 deprecate 만 명문화.

---

## 단계별 실행 계획

> 커밋 컨벤션: `feat`/`test` 항상 별도 커밋, 한 줄 한글 subject.
> 진행은 한 단계(=1 feat + 1 test 묶음) 끝낼 때마다 커밋 + 보고 후 멈춤.

### Step 1 — 부팅 블로커 해소 + 클라이언트 견고화 (G1, G3)

1. `feat(auto-review): Vertex 임베딩 자동구성 provider=vertex 시에만 활성화`
   - `VertexAiTextEmbeddingAutoConfiguration` exclude 를 조건부로 전환.
     stub/test 부팅은 GCP 자격증명 없이 유지(EmbeddingModel 미요구).
2. `feat(auto-review): SpringAiEmbeddingClient 재시도·타임아웃·768 차원검증·에러분리`
   - 지수 백오프 재시도, 4xx→영구 실패(즉시 중단), 5xx/timeout→재시도,
     응답 차원 != 768 시 명시적 예외, 배치 250 상한 청크 분할.
3. `test(auto-review): SpringAiEmbeddingClient 단위테스트(차원·재시도·4xx 즉시중단)`
   - `EmbeddingModel` 모킹 — 실제 Vertex 미호출.

**완료조건**: `provider=stub` 기본 부팅이 GCP 자격증명 없이 그대로 통과하고,
`provider=vertex` + 더미 크레덴셜에서 빈 주입까지 성공. 단위테스트 green.

### Step 2 — ES 재색인 파이프라인 + 인덱스 버저닝 (G2)

1. `feat(auto-review): kb_*_v2 인덱스 생성 + Vertex 재임베딩 백필 잡`
   - `kb_{policy,similar_cases,internal_faq}_v2` 매핑(768d) 추가, 기존 코퍼스를
     vertex 임베딩으로 재적재. 멱등(docId=`sourceId_chunkSeq`).
2. `feat(auto-review): 재색인 완료 후 alias 원자 스왑(v1→v2)`
   - 검색 alias 를 v2 로 무중단 스왑, 롤백 시 v1 로 즉시 복귀.
3. `test(auto-review): 백필 멱등·stub/vertex 혼입 방지 단위테스트`

**완료조건**: v2 인덱스에 vertex 벡터만 존재, alias 스왑/롤백 동작 확인,
재실행해도 중복 없음.

### Step 3 — 레거시 정리 (G5, 범위 축소)

1. `docs(auto-review): pgvector/inline RAG 경로 deprecate 명문화`
   - V4(1024d)·inline 검색은 kill-switch fallback 전용으로 문서화. 코드 변경 없음.

### Step 4 — 컷오버 / 검증 / 롤백 (G4)

1. `feat(auto-review): 임베딩 호출 비용·지연 메트릭(AgentMetrics 연계)`
2. shadow 모드로 stub vs vertex 검색 품질 비교(회귀 리포트 동일성·citation miss rate)
   후 `ai.rag.embedding.provider=vertex` 플립.
3. 실패 시 즉시 `provider=stub` + alias v1 롤백.

**게이트 기준**(canary 기준 재사용): agreementRate ≥ 0.95, citationMissRate ≤ 0.05,
p99 search < 500ms.

---

## 환경 변수 / 설정

| 키 | 용도 | 비고 |
|---|---|---|
| `AI_RAG_EMB_PROVIDER` | `stub`(기본) / `vertex` | 컷오버 플래그 |
| `VERTEX_PROJECT_ID` | GCP 프로젝트 | 운영 필수 |
| `VERTEX_LOCATION` | `us-central1`(기본) | |
| `VERTEX_EMBEDDING_MODEL` | `text-embedding-005`(기본) | |
| `GOOGLE_APPLICATION_CREDENTIALS` | ADC 서비스계정 경로 | 운영 필수, `.env` 커밋 금지 |

---

## 리스크

- **재색인 누락**: alias 스왑 전 v2 적재가 부분 실패하면 검색 품질 저하 → 스왑 전 건수 검증 게이트.
- **GCP 비용/쿼터**: 대량 백필 시 Vertex 임베딩 쿼터·비용 급증 → 배치 상한·백오프·야간 실행.
- **테스트 격리**: CI 는 실 Vertex 미호출(stub 유지), vertex 경로는 모킹 단위테스트 + 수동/야간 통합으로 검증.
- **부팅 의존성**: G1 조건화 실패 시 로컬/CI 가 GCP 자격증명을 요구하게 됨 → Step 1 단위테스트로 stub 부팅 보장.

---

## 운영 컷오버 런북

> 대상: `ai.rag.backend=es` 운영 인스턴스. 관리 API 는 `/admin/**`(HTTP Basic, role `AI_ADMIN`,
> 계정 `AI_ADMIN_USER`/`AI_ADMIN_PASSWORD`). 기본 포트 `8089`(`AUTO_LOAN_REVIEW_APP_PORT`).
> 코퍼스 식별자: `policy_regulation` / `similar_cases` / `internal_faq`.

### ⚠️ 핵심 위험 — 쿼리/인덱스 임베딩 정합성

검색 시 **쿼리 임베딩은 현재 활성 provider 로 생성**된다(`EsHybridSearchService` 가
`EmbeddingClient` 사용). 따라서 인덱스 벡터와 쿼리 벡터의 모델이 일치해야 한다.

- `kb_*_v1`(stub 벡터) ↔ `provider=stub`
- `kb_*_v2`(vertex 벡터) ↔ `provider=vertex`

`provider=vertex` 로 재기동한 시점부터 alias 를 v2 로 promote 하기 전까지는 **vertex 쿼리 ↔ v1(stub)
벡터** 가 되어 검색 품질이 깨진다. 이 구간을 없애기 위해 **컷오버 중에는 RAG kill-switch 를 끈다**
(`ai.rag.enabled=false` → 인라인 policy fallback). 또는 점검창(저트래픽)에서 수행한다.

### 0. 사전 준비

```bash
# GCP 자격증명 (서비스계정 키, .env 커밋 금지)
export GOOGLE_APPLICATION_CREDENTIALS=/secret/vertex-sa.json
export VERTEX_PROJECT_ID=<gcp-project>
export VERTEX_LOCATION=us-central1
export VERTEX_EMBEDDING_MODEL=text-embedding-005
export AI_ADMIN_PASSWORD=<강력한 비밀번호>   # 기본 dev-only-change-in-prod 덮어쓰기 필수
```

- 백필은 Vertex 쿼터·비용을 소모 → **저트래픽/야간** 권장. `AI_RAG_EMB_BATCH_SIZE`(≤250)·
  `AI_RAG_EMB_BACKOFF_MS` 로 throughput 조절.

### 1. 컷오버 창 진입 — RAG 끄기

```bash
# RAG 검색을 인라인 fallback 으로 전환(벡터 쿼리 중단) — 정합성 위험 구간 차단
export AI_RAG_ENABLED=false
```

### 2. provider=vertex 로 재기동

```bash
export AI_RAG_EMB_PROVIDER=vertex
# 인스턴스 재시작 → VertexEmbeddingAutoConfiguration 활성, EmbeddingModel 빈 생성
# 부팅 로그에서 "SpringAiEmbeddingClient configured: dim=768 ..." 확인
```

### 3. 코퍼스별 재색인 (v1 → v2)

```bash
BASE=http://localhost:8089
AUTH=ai-admin:$AI_ADMIN_PASSWORD
for C in policy_regulation similar_cases internal_faq; do
  curl -fsS -u "$AUTH" -X POST "$BASE/admin/rag/reindex/$C"
done
# 응답: {"corpus":...,"sourceIndex":"kb_*_v1","targetIndex":"kb_*_v2","reembedded":N}
```

- 멱등 — 중단 시 재실행 가능(동일 `_id` 덮어쓰기). `reembedded` 가 소스 건수와 일치하는지 확인.

### 4. 검증 후 alias 원자 스왑 (promote)

```bash
for C in policy_regulation similar_cases internal_faq; do
  curl -fsS -u "$AUTH" -X POST "$BASE/admin/rag/promote/$C"
done
# tgtCount >= srcCount 게이트 통과 시에만 스왑. 미달이면 400/500 → 3번 재색인 재시도.
```

### 5. RAG 재개 + 품질 검증

```bash
export AI_RAG_ENABLED=true   # (필요 시 재기동)
```

- 게이트 기준(§단계별 계획 Step 4): agreementRate ≥ 0.95, citationMissRate ≤ 0.05,
  p99 search < 500ms.
- Grafana(B2 `ai-agent-overview.json`)에 임베딩 패널 추가:
  `ai.embedding.latency.seconds`(p50/p99), `ai.embedding.calls.total{status}`(에러율),
  `ai.embedding.chars.total`(비용 proxy 추이).

### 6. 롤백

| 시점 | 절차 |
|---|---|
| **promote 전** | alias 는 아직 v1 → `AI_RAG_EMB_PROVIDER=stub` 로 되돌리고 재기동, `AI_RAG_ENABLED=true`. v2 인덱스는 방치(다음 시도 시 덮어씀). |
| **promote 후** | ① alias 를 v1 로 역스왑(ES `_aliases` 수동: remove v2 / add v1) ② `AI_RAG_EMB_PROVIDER=stub` 재기동 ③ `AI_RAG_ENABLED=true`. v1(stub) 벡터 ↔ stub 쿼리로 정합성 복구. |

> 역스왑 전용 엔드포인트는 미구현(현재 promote 는 v1→v2 단방향). 운영 롤백 자동화가 필요하면
> 별도 `POST /admin/rag/rollback/{corpus}` 추가를 검토(B5 admin 엔드포인트 묶음).
