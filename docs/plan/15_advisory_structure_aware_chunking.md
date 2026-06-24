# 15. 구조-인지 청킹 + 멀티포맷 파싱 개편 (advisory RAG)

> 브랜치: `fix/advisory-chunk`
> 대상: advisory-service RAG 인입(`DocumentIngestionService`), inference-server 사이드카, auto-loan-review `PolicyCorpusChunkProvider`

## Context

현재 RAG 청킹은 `DocumentIngestionService.splitChunks()` 의 **800자/100자 오버랩 고정 슬라이딩 윈도우**가 전부다. 문서 구조(목차·머리말·꼬리말·표·조항)를 전혀 인식하지 못해 표가 본문과 섞여 잘리고, `section_path` 는 `char:{pos}` 뿐이며, 엔티티에 `chunk_meta(jsonb)` 컬럼이 있는데도 **INSERT에서 채우지 않는다**(미사용). 입력은 `content` 문자열 직접 제공만 가능하고 PDF/Word/HWP 파일 파싱 경로가 없다.

**목표**: ① 청킹 전에 문서를 구조 블록으로 나누고(목차/머리말/꼬리말 제거, 표·섹션 분리, 블록 내에서만 길이 맞춤), ② 청크 메타데이터를 채우고, ③ PDF·Word·HWP를 공통 파이프라인으로 파싱한다. 표 중첩·스캔 PDF 폴백·긴 조항 분할까지 이번 브랜치에서 처리한다.

**확정 방향**: 포맷 파싱은 기존 Python 사이드카(`inference-server`) 확장, 구조 인식은 조항+헤딩 휴리스틱 폴백, 범위는 전부 한 번에.

## 탐색으로 확정한 핵심 제약 (설계에 결정적)

1. **advisory-service 는 독립 Gradle 모듈이 아니다.** `services/loan-service/build.gradle` 의 `sourceSets` 가 `../advisory-service/src/main/{java,resources}` 와 `src/test/java` 를 srcDir 로 흡수한다. 신규 advisory 클래스의 런타임 의존성은 **loan-service 클래스패스** 기준.
2. **resilience4j 는 loan-service 에 없다**(doc-agent 전용). advisory 사이드카 클라이언트는 doc-agent 의 `@CircuitBreaker` 패턴을 **재사용할 수 없고**, 같은 모듈 `AdvisoryOpenAiEmbeddingClient` 처럼 try/catch + 수동 재시도 폴백을 쓴다.
3. `spring-boot-starter-web`(RestClient 포함)은 root `build.gradle` subprojects 블록 전 모듈 적용 → `RestClient.Builder` 주입 가능.
4. **WireMock 3.9.1 이 loan-service `testImplementation` 에 존재** → 사이드카 스텁 테스트 그대로 사용.
5. **DB 마이그레이션 불필요.** V21 에 `chunk_meta jsonb`, `section_path varchar(500)`, `chunk_token_count` 모두 존재. 단 `section_path` 500자 한계 가드 필요.
6. AI_GUIDELINES: 외부 호출(embed)은 트랜잭션 진입 전 선계산, INSERT/activate 만 트랜잭션 안. 사이드카 파싱도 동일 위치.

---

## A. 사이드카(inference-server) 문서 파싱 엔드포인트

신규 파일 `inference-server/app/parse_router.py`, `main.py` 에 `include_router` 추가(기존 ocr/extract/forgery 와 동일 방식).

**엔드포인트** `POST /parse/document`
- 입력: `{ document_b64, filename, doc_format(PDF|DOCX|HWP|HWPX|AUTO), ocr_fallback=true, submission_id }`
- 출력: `{ submission_id, doc_format, page_count, blocks: [DocumentBlock], degraded: bool, engine }`
- `DocumentBlock`: `{ block_type(heading|paragraph|table|toc|header|footer|list), text, page, level, block_seq, table: {rows, html, nested:[...]} | null, bbox|null }`

**라이브러리 매핑**(requirements.txt 추가):
- PDF 텍스트 레이어: **PyMuPDF(fitz)** — 블록/스팬 좌표 + 폰트 크기로 heading 추정. 표는 **pdfplumber** `find_tables`, 중첩표는 bbox 포함관계로 outer/inner 판정해 `nested` 채움.
- PDF 텍스트 없음(스캔): 페이지 이미지 렌더 → **기존 `_get_engine()`(PaddleOCR) + `_get_structure_engine()`(PP-StructureV2) 재사용**, `degraded=true`. OCR 미설치면 빈 blocks + `degraded=true`(예외 대신 명확 신호 — `ocr_router` 의 503/fallback 패턴 그대로).
- DOCX: **python-docx** — 문단 style(Heading 1..N)→heading/level, `w:tbl`→table, 셀 내부 table 재귀로 nested.
- HWP/HWPX: **§A-1 다단계 폴백** 참조.
- TOC/header/footer 는 사이드카가 1차 태깅(반복 텍스트·"목차"·페이지번호 패턴), **최종 제거는 Java 청커**가 담당.

기존 `ocr_router.py` 의 지연 로드(`_get_*`), b64 디코드 가드, degraded 처리 컨벤션을 그대로 따른다.

### A-1. HWP 파싱 보완 (다단계 폴백) — 신규 보강

순수 HWP를 단순 `degraded` 처리하지 않고, 단계적으로 품질을 끌어올린다(상위 실패 시 다음 단계로 폴백):

| 순위 | 대상 | 방법 | 표 추출 | 비고 |
|---|---|---|---|---|
| 1 | **HWPX** (ZIP+OWPML) | `Contents/section*.xml` 직접 파싱(lxml) | ✅ `<hp:tbl>` 재귀로 중첩표 | 최상 품질, 표준 포맷 |
| 2 | **바이너리 HWP v5** (OLE) | pyhwp `hwp5html` → 표 보존 XHTML | ✅ HTML 표 → PP-Structure HTML 파싱 경로 재사용 | 텍스트+표 확보 |
| 3 | **변환 폴백** | LibreOffice `soffice --headless --convert-to pdf`(H2Orestart/HWP import 필터) → HWP→PDF | ✅ PDF 경로(PyMuPDF+pdfplumber+OCR) 재활용 | pyhwp 실패본 구제, 스캔성도 OCR로 커버 |
| 4 | **최후** | `hwp5txt` 텍스트만 | ❌ | `degraded=true` |

- **포맷 판별**: 파일 시그니처로 자동 — HWPX = ZIP(`PK\x03\x04`, `mimetype`에 `application/hwp+zip`), 바이너리 HWP v5 = OLE 복합문서(`D0 CF 11 E0`).
- **의존성 추가**: `lxml`(HWPX), `pyhwp`(hwp5html/hwp5txt), inference-server 이미지에 **LibreOffice + H2Orestart 확장** 설치.
- **이미지 용량/기동 완화**: LibreOffice 변환기는 지연 로드(요청 시 subprocess), Docker 멀티스테이지로 빌드 의존성 분리. 변환 폴백은 타임아웃(예 30s) + 실패 시 4단계로 강등.
- **테스트**: HWPX 샘플(표·중첩표) 파싱, 바이너리 HWP hwp5html 표 추출, 변환 폴백 경로(LibreOffice 미설치 시 graceful degrade)를 pytest로 검증.

---

## B. advisory 사이드카 클라이언트 + 블록 모델 + 구조-인지 청커

신규 패키지 `com.bank.loan.advisory.rag.chunk` (`services/advisory-service/src/main/java/com/bank/loan/advisory/rag/chunk/`):

1. **RestClient 빈** — doc-agent `InfraConfig` 재사용 불가(다른 모듈). `AdvisoryOpenAiEmbeddingClient` 처럼 생성자에서 `RestClient.Builder` 주입받아 baseUrl/timeout 구성(별도 @Bean 불필요).
2. **`AdvisoryParseProperties`** `@ConfigurationProperties("advisory.rag.parse")`: `baseUrl`(env `INFERENCE_SERVER_URL`, 기본 `localhost:8090`), `connectTimeoutMs`, `readTimeoutMs`(파싱 길게, 예 60000), `maxAttempts`, `ocrFallback`.
3. **클라이언트**
   - `DocumentParseClient`(인터페이스): `ParseResult parse(byte[] bytes, String filename, DocFormat fmt)`
   - `InferenceServerDocumentParseClient`(구현): `AdvisoryOpenAiEmbeddingClient.callWithRetry` 패턴 차용 — 5xx/`ResourceAccessException` 재시도, 4xx 즉시 실패, degraded 노출.
4. **공통 모델 (record/enum)**
   - `DocFormat`(enum PDF/DOCX/HWP/HWPX/TXT/AUTO; `fromFilename(String)`)
   - `DocumentBlock`(record: `BlockType type, String text, Integer page, Integer level, int seq, TableBlock table`)
   - `TableBlock`(record: `List<List<String>> rows, String html, List<TableBlock> nested`)
   - `BlockType`(enum HEADING/PARAGRAPH/TABLE/TOC/HEADER/FOOTER/LIST)
   - `ParseResult`(record: `List<DocumentBlock> blocks, boolean degraded, int pageCount, String engine`)
5. **`StructureAwareChunker`**(핵심): `List<Chunk> chunk(List<DocumentBlock> blocks, DocMeta docMeta)`
   - a. **필터**: TOC/HEADER/FOOTER 제거(사이드카 태그 + Java 휴리스틱: 짧은 반복 텍스트, "목차/차례", 페이지번호 패턴).
   - b. **headingPath 스택**: HEADING 만나면 level 기준 push/pop → 현재 경로(`["제1장","1.2","가."]`).
   - c. **조항 인식 휴리스틱**: 정규식 `^제\s*\d+\s*조`, `^\d+(\.\d+)*\s`, `^[가나다라마]\.` → `article_no` 추출 + 섹션 경계.
   - d. **블록 내 길이 맞춤**: 섹션 본문(PARAGRAPH 누적)이 800자 초과 시 **기존 `splitChunks(text,800,100)` 재사용**(static → 청커로 이동/public 승격). 윈도우는 섹션 경계 불침범.
   - e. **TABLE**: 작은 표는 통째 1청크, 큰 표는 헤더행 prepend + 행묶음 청크. 중첩표 inner 는 `table_id`+`parent_table_id` 메타로 평탄화.
   - `Chunk` record: `{ String text, String sectionPath, Map<String,Object> meta, int tokenCount }`
6. **`ChunkMetaBuilder`**: `chunk_meta` jsonb 키 — `doc_type`(=docCategoryCd), `block_type`, `heading_path`(list), `article_no`, `page`, `table_id`, `parent_table_id`, `nested`(bool).

---

## C. DocumentRegisterRequest / register() 흐름 변경

- **DTO**: `content` 직접 경로 유지 + 멀티파트 경로 추가.
- **컨트롤러** `InternalAdvisoryRagController`: 신규 `@PostMapping(value="/documents/file", consumes=MULTIPART_FORM_DATA)` — `(@RequestPart MultipartFile file, @RequestPart DocumentRegisterRequest meta)`. 기존 JSON `POST /documents` 유지.
- **register() 분기**(`DocumentIngestionService`):
  - content 직접: plain text 를 단일 PARAGRAPH 블록으로 래핑 → 동일 청커 경로로 통일.
  - 파일: (트랜잭션 밖) `parseClient.parse(bytes,filename,fmt)` → blocks → `chunker.chunk` → 임베딩 선계산 → 동일 트랜잭션 INSERT.
  - degraded/실패: `BusinessException(LoanErrorCode.LOAN_xxx, "문서 파싱/스캔본 OCR 불가")` 로 **명확 실패**(부분 적재 금지, content 폴백 없음 — 파일이 원천).
- **AI_GUIDELINES**: parse + embed 모두 트랜잭션 진입 전. INSERT/activate 만 트랜잭션 안.

### C-1. multipart 설정 점검 — 신규 보강

- loan-service `application.yml:33-37` 에 multipart 활성 **이미 존재**하나 한도가 작다:
  ```yaml
  spring:
    servlet:
      multipart:
        enabled: true
        max-file-size: 10MB      # 규정 문서(스캔 PDF/HWP)엔 부족 가능
        max-request-size: 20MB
  ```
- 참고: doc-agent 는 `max-file-size: 50MB / max-request-size: 100MB`.
- **조치**: 규정 문서 업로드 대상 크기에 맞춰 상향(예 `30MB / 60MB`). 미상향 시 대용량 PDF/HWP 업로드에서 **413 / multipart 파싱 실패** 발생. advisory 가 loan-service 위에서 동작하므로 설정 위치는 loan-service application.yml.
- 상향과 함께 컨트롤러/문서에 허용 최대 크기·지원 포맷을 명시.

---

## D. chunk_meta INSERT 추가 (현재 누락 버그 수정)

`DocumentIngestionService` 의 `jdbcTemplate.update` INSERT 에 컬럼 추가:
```sql
INSERT INTO advisory_document_chunk
  (doc_id, chunk_seq, chunk_text, section_path, chunk_token_count, chunk_meta,
   embedding_model_cd, embedding, indexed_at, created_at, created_by)
VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS vector), ?, now(), ?)
```
- 값은 `ChunkMetaBuilder` → `ObjectMapper.writeValueAsString`(`PolicyCorpusSeedLoader` 의 `:metadata::jsonb` 패턴 참고).
- `section_path` 는 INSERT 전 500자 truncate 가드.
- `splitChunks` 가 `String[]` 대신 `Chunk` record 반환으로 바뀌므로 루프를 record 필드 접근으로 변경. 직렬화 실패 시 `"{}"` 폴백.

---

## E. PolicyCorpusChunkProvider 긴 조항 분할

- 위치: `services/auto-loan-review/.../rag/seed/PolicyCorpusChunkProvider.java` — **별도 모듈**이라 advisory `StructureAwareChunker` 직접 의존 불가.
- 내부 간이 분할 헬퍼: `chunkText` 길이 > THRESHOLD(예 1200) 시 줄/문장 경계 우선 분할, `chunkSeq` 0,1,2… 증가. metadata 에 `part_index`/`part_total`, summary 는 첫 파트만 원본·나머지 "(이어짐)".
- `ON CONFLICT (corpus, source_id, chunk_seq, embedding_model)` 키이므로 chunkSeq 증가로 **멱등 유지**.
- 모듈 경계 넘는 공유 유틸 추출은 과설계 — 이번 범위 제외.

---

## F. 테스트 전략 (커밋: feat / test 분리)

- **단위** `StructureAwareChunkerTest`: 블록→청크(헤딩스택, 조항분할, 표 행청크, TOC제거, 중첩표 메타). 순수 로직, DB/사이드카 불필요.
- **단위** `ChunkMetaBuilderTest`: jsonb 키 직렬화.
- **통합** 사이드카 = **WireMock** 로 `/parse/document` 스텁 → 클라이언트 재시도/4xx/degraded 경로 검증. 임베딩은 `StubEmbeddingClient`(@Profile test) 자동 로드.
- **통합** `register(file)` E2E: WireMock 파싱 + Stub 임베딩 + 실제 INSERT → `chunk_meta` 채워짐 검증(누락 회귀 방지).
- **PolicyCorpusChunkProvider**: 긴 조항 → chunkSeq 증가/part 메타.
- **Python** `parse_router` pytest: 블록 정규화 + 라이브러리 미설치 degraded + **HWP 다단계 폴백(HWPX/hwp5html/LibreOffice 미설치 강등)**.

---

## G. DB 마이그레이션

**불필요.** `chunk_meta(jsonb)`/`section_path(500)`/`chunk_token_count` 모두 V21 존재. 가드만: section_path 500자 truncate, chunk_meta 직렬화 실패 `"{}"` 폴백. heading_path 풀 값은 chunk_meta 에 보관.
> 참고: 구조-인지 청킹/표 행단위 청크로 청크 수가 늘면 IVFFlat `lists` 재산정 필요 → 대량 인입 후 `V30__ivfflat_lists_reindex.sql` 재실행(또는 REINDEX) 트리거 명시 권장.

---

## 단계별 순서 (커밋 분리: feat / test)

1. [feat] 사이드카 `parse_router.py` + requirements.txt + main.py include + **HWP 다단계 폴백**(Python)
2. [feat] advisory 공통 모델(DocFormat/DocumentBlock/TableBlock/BlockType/ParseResult) + AdvisoryParseProperties
3. [feat] DocumentParseClient + InferenceServerDocumentParseClient(수동 재시도 폴백)
4. [feat] StructureAwareChunker + ChunkMetaBuilder(splitChunks 재사용/이동)
5. [feat] DocumentIngestionService register 분기 + chunk_meta INSERT + section_path 가드
6. [feat] 컨트롤러 multipart 엔드포인트 + DTO + **loan-service multipart 한도 상향**
7. [feat] PolicyCorpusChunkProvider 긴 조항 분할
8. [test] 청커/메타/클라이언트(WireMock)/E2E/Provider/Python 테스트

---

## 검증 방법

- `./gradlew :services:loan-service:test` — 청커/메타/클라이언트(WireMock)/E2E 단위·통합.
- `./gradlew :services:auto-loan-review:test` — Provider 긴 조항 분할.
- Python: `cd inference-server && pytest` — parse_router 블록 정규화·degraded·HWP 폴백.
- 수동 E2E(선택): inference-server 기동 후 `curl -F file=@sample.pdf -F meta=...` 로 multipart 등록 → `advisory_document_chunk` 의 chunk_meta/section_path 확인. HWPX·HWP 샘플로 표/중첩표 청크 확인.

---

## 트레이드오프 / 위험요소

- **HWP 파싱**: HWPX/hwp5html/LibreOffice 다단계로 보완(§A-1). 그래도 최후엔 텍스트+degraded. LibreOffice 변환은 이미지 용량·변환 지연 증가 → 지연 로드/타임아웃으로 완화.
- **사이드카 무게 증가**: PaddleOCR/PyMuPDF/python-docx/LibreOffice 추가로 이미지 크기·기동 시간 증가 → 지연 로드·멀티스테이지로 완화.
- **splitChunks static 이동 회귀**: content 직접 경로를 단일 PARAGRAPH 블록 래핑으로 통일 → 기존 출력 동일성 테스트 필수.
- **section_path 500자 truncate**: 디버깅 정보 손실 → heading_path 풀 값은 chunk_meta 에 보존.
- **표 행단위 청크 → 청크 수 급증**: 임베딩 호출/비용 + IVFFlat lists 재산정 → 작은 표 통째/행수 임계 분기, REINDEX 트리거.
- **resilience4j 부재**: doc-agent `@CircuitBreaker` 못 씀 → 수동 재시도 폴백(동일 모듈 검증 패턴).
- **모듈 경계(advisory ↔ auto-loan-review)**: 청커 공유 불가 → Provider 는 간이 분할(소량 중복 감수).
- **multipart 한도**: loan-service 10MB/20MB → 미상향 시 대용량 업로드 413(§C-1).
