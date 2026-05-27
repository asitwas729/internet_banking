# AuditFairnessAgent Tool-Use 도입 계획

> **상태**: 계획 수립 완료 / 미착수
> **작성일**: 2026-05-27
> **연관 문서**: [loan_review_advisory_progress.md](./loan_review_advisory_progress.md)

---

## 배경

현재 `AuditFairnessAgent`는 단방향 파이프라인 구조다.

```
AuditFairnessAgent
  ① RAG 사전 조회 (PolicyCitationRetriever)
  ② AiGatewayClient → review-ai-gateway
       prompt 조립 → Claude API 1회 호출 → JSON 파싱
  ③ 감사 의견 저장 → 격리 처리
```

Claude는 주어진 컨텍스트만 보고 판단하며, 추가 정보가 필요해도 요청할 수 없다.

---

## 목표 흐름 (agentic loop)

```
AuditFairnessAgent
  ① (RAG 사전 조회 제거)
  ② AiGatewayClient → review-ai-gateway
       AgenticLoop (최대 5턴):
         Claude API 호출 (tools 정의 포함)
         ├─ stop_reason=tool_use → ToolExecutor 실행 → tool_result 추가 → 재호출
         └─ stop_reason=end_turn → 최종 감사 의견 파싱
  ③ 감사 의견 저장 → 격리 처리
```

---

## Tool 4종

| Tool | 파라미터 | 데이터 출처 | 용도 |
|---|---|---|---|
| `get_policy_citation` | `query: string` | advisory-service `PolicyCitationRetriever` | 판단 근거 정책 문서 검색 |
| `get_similar_cases` | `rev_id: long` | advisory-service `SimilarCaseRetriever` | 유사 신청자 선례 비교 |
| `get_reviewer_history` | `reviewer_id: long, days: int` | advisory-service 새 쿼리 (LoanReview 집계) | 심사관 결정 이력 패턴 |
| `get_cohort_stats` | `dimension: string, value: string` | `ReviewerDecisionSnapshot` 테이블 | 코호트 편향 통계 수치 |

---

## 구현 범위

### Step 1 — `review-ai-gateway`: tool-use 코어

**신설 모델** (5종):
- `ToolDefinition` — Claude tool 스키마 (name, description, input_schema)
- `ToolCall` — Claude가 요청한 tool 호출 정보 (id, name, input JSON)
- `ToolResult` — tool 실행 결과 (tool_use_id, content, isError)
- `AgenticLlmRequest` — messages + tools 목록
- `AgenticLlmResponse` — 최종 텍스트 + 토큰 사용량

**`ClaudeLlmClient`에 추가**:
```java
AgenticLlmResponse completeAgentic(AgenticLlmRequest request);
```
내부: `stop_reason=tool_use` → `ToolCall` 목록 추출 → caller에 반환

**`AgenticLoop`** (신설):
```
while turns < maxTurns:
  response = claude.completeAgentic(messages, tools)
  if stop_reason == end_turn → return text
  for each tool_call:
    result = executor.execute(tool_call)  // 실패 시 빈 결과, 루프 계속
    messages.add(tool_result)
  turns++
maxTurns 초과 시 → INSUFFICIENT_DATA 결론으로 폴백
```

안전장치:
- `maxTurns = 5` (무한 루프 방지)
- tool 실행 실패 → 빈 결과 반환 후 계속 (best-effort)
- 전체 루프 타임아웃은 `ClaudeProperties.timeoutMs` 공유

---

### Step 2 — `review-ai-gateway`: ToolExecutor 4종 + AgenticAuditAnalysisService

**`ToolExecutor` 인터페이스**:
```java
interface ToolExecutor {
    String toolName();
    ToolDefinition definition();
    String execute(JsonNode input);  // 실패 시 "" 반환
}
```

**구현체 4종** (advisory-service `RestClient` 호출):

| 클래스 | Tool | HTTP |
|---|---|---|
| `PolicyCitationToolExecutor` | `get_policy_citation(query)` | `GET /api/internal/advisory/policy-citations?query=` |
| `SimilarCasesToolExecutor` | `get_similar_cases(rev_id)` | `GET /api/advisory/reports/{revId}/similar-cases` |
| `ReviewerHistoryToolExecutor` | `get_reviewer_history(reviewer_id, days)` | `GET /api/internal/advisory/reviewer-history?reviewerId=&days=` |
| `CohortStatsToolExecutor` | `get_cohort_stats(dimension, value)` | `GET /api/internal/advisory/cohort-stats?dimension=&value=` |

**`ToolRegistry`** — tool name → executor map, `execute(ToolCall)` 위임

**`AgenticAuditAnalysisService`** — 기존 `AuditAnalysisService` 삭제 후 대체:
```
buildTools() → [4 ToolDefinition]
buildInitialMessages(req) → system + user message
AgenticLoop.run(messages, tools)
→ AuditResponseParser.parse(finalText) → AuditAnalysisResponse
```

설정 추가 (`application.yml`):
```yaml
advisory:
  base-url: http://advisory-service:8080
```

---

### Step 3 — `advisory-service`: internal endpoint 3종 신설 + Agent 단순화

**신설 endpoint**:

| 경로 | 구현 | 설명 |
|---|---|---|
| `GET /api/internal/advisory/policy-citations?query=` | `PolicyCitationRetriever` 텍스트 쿼리 경로 | 정책 문서 청크 검색 |
| `GET /api/internal/advisory/reviewer-history?reviewerId=&days=` | `LoanReview` 집계 쿼리 | 심사관 결정 이력 (건수·코호트 분포) |
| `GET /api/internal/advisory/cohort-stats?dimension=&value=` | `ReviewerDecisionSnapshot` 조회 | 코호트 편향 통계 |

**`AuditFairnessAgent` 단순화**:
- `fetchRagChunks()` 메서드 제거
- `PolicyCitationRetriever` 의존성 제거
- `GatewayAnalysisRequest`에서 `ragChunks` 필드 제거

---

## 커밋 순서

```
feat(review-ai-gateway): tool-use 코어 모델·AgenticLoop 구현
test(review-ai-gateway): AgenticLoop 단위 테스트 추가

feat(review-ai-gateway): ToolExecutor 4종·AgenticAuditAnalysisService 구현
test(review-ai-gateway): ToolExecutor·AgenticAuditAnalysisService 단위 테스트 추가

feat(advisory): internal tool endpoint 3종 신설·AuditFairnessAgent 단순화
test(advisory): agentic 흐름 통합 테스트 추가
```

---

## 설계 결정사항

| 항목 | 결정 | 사유 |
|---|---|---|
| Tool 실행 실패 | 빈 결과 반환 후 루프 계속 | 감사 흐름이 tool 실패로 중단되지 않도록 |
| 기존 `AuditAnalysisService` | 삭제 | `AgenticAuditAnalysisService`로 완전 대체, 코드 분기 제거 |
| ToolExecutor 실행 위치 | gateway 내부에서 advisory-service HTTP 호출 | gateway가 이미 advisory URL 알고 있음, 콜백 복잡도 회피 |
| maxTurns | 5 | 4 tool × 1회 + 최종 응답 기준, 실측 후 조정 |
