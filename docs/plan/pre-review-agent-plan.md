# 사전 심사 에이전트 (Pre-Review Agent) — 실행 계획

> Last updated: 2026-05-26
> Status: A0 — 계획 확정. A1~A10 실행 대기.
> 호스팅: **Google AI Studio (Gemini 2.5 Flash) 무료 API** (OpenAI 호환 endpoint)
> 선행 문서: `banking-review-llm.md` §5·§11, `llm-pipeline.md`

## 목표

대출 신청 단계에서 고객의 신용·상환 능력을 종합적으로 분석하여 **위험 수준 평가 및 심사 의사결정 보조 정보**를 생성. 최종 판단이 아닌 **심사 전 단계의 정량/정성 리스크 분석 계층**.

핵심 원칙 (기존 `banking-review-llm.md` §5 계승):
- LLM은 의사결정권 없음 — 승인/거절·트랙 분기는 `RuleEngine` 단독
- 모든 LLM 호출 전·후 `PiiAwareChatClient` (마스킹) 통과
- 결정론 테스트 = `StubLlmClient` + Stub 도구 응답
- RAG 미도입 (Phase 1.7 보류) — `ai.policy.inline.*` 사용, `Citation.sourceId` swap-ready
- 운영 ON/OFF 토글 `ai.agent.enabled`

학습 목적·비상업·합성 데이터 기준. 무료 API 한도 내에서 동작.

---

## 입력 / 출력

### 입력 (기존 `AutoReviewRequest` + Track 결정 결과)
- `AutoReviewRequest` 59필드 (Layer 1~4, persona/financial/application/PD)
- `TrackDecision` (track, hardFails, pd, decisionScore, pdThreshold, safetyMarginThreshold)
- `PurposeAnalysis` (기존 — 신청 사유 plausibility)

### 출력 — `AgentOpinion` (record)
```json
{
  "decision_score": 0.72,
  "pd_score": 0.18,
  "risk_level": "MEDIUM",
  "policy_flags": ["DSR_THRESHOLD_WARNING"],
  "reasoning_summary": "상환 부담 및 부채 비율 증가로 인해 위험도가 상승",
  "simulation_results": [
    {
      "scenario": "loan_amount_reduction_20pct",
      "mutated_amount_kw": 80000000,
      "mutated_period_mo": 60,
      "new_decision_score": 0.84,
      "new_pd_score": 0.12,
      "result": "risk_reduced",
      "suggestion": "대출 금액 20% 감소 시 승인 가능성 증가"
    }
  ],
  "disagreement": false,
  "fallback_reason": null
}
```

`risk_level` 매핑 (외부 노출 X, 내부 분류용):
- LOW ← Track 1
- MEDIUM ← Track 3
- HIGH ← Track 2 (hard fail) 또는 Track 3 + decisionScore < 0.4 보정

---

## 처리 구조

### 1. 정책 룰 평가 (재사용 — `HardConstraintEvaluator`)
즉시 탈락 5종: DSR_EXCEEDED · LTV_EXCEEDED · CREDIT_SCORE_BELOW_MIN · DELINQUENCY_24M_PRESENT · AGE_BELOW_MIN

### 2. 이중 리스크 모델 (재사용 — `InferenceClient.review`)
- `decision_score`: HMDA 기반 P(APPROVE) — 운영 관행 학습
- `pd_score`: Home Credit 기반 P(default 12m) — 절대 신용 리스크
- PD 모델 장애 시 `P(REJECT)` 폴백

### 3. 위험 융합 (재사용 — `TrackClassifier` + `PolicyMatrix`)
| 조합 | 결과 |
|------|------|
| decision↑ + pd↓ | Track 1 (LOW) |
| decision↓ + pd↓ | Track 2 (Policy reject 가능성) |
| decision↑ + pd↑ | Track 3 (Approval but High Risk) |
| decision↓ + pd↑ | Track 2 (Reject candidate, HIGH) |

### 4. What-if 시뮬레이션 (★ 신규)
`RecomputeWithTermsTool` 가 `AutoReviewRequest.requestedAmountKw`/`requestedPeriodMo` mutate → `InferenceClient.review` 재호출.

시나리오 카탈로그 (에이전트가 risk_level/Track 보고 1~3개 선택):
- `loan_amount_reduction_20pct` — 신청금액 ×0.8
- `loan_amount_reduction_40pct` — 신청금액 ×0.6
- `loan_period_extension_12mo` — 기간 +12개월
- `combined_amount_period` — 금액 ×0.8 + 기간 +12개월

Track 1은 시뮬 생략. Loop guard 가 호출 ≤ 6회.

### 5. 추론 요약 (LLM)
Gemini 2.5 Flash가 위 모든 결과를 받아 1~2 문장 한국어 `reasoning_summary` 생성. 구조화 출력 (`BeanOutputConverter`). 실패 시 `TemplateFallback`.

---

## 아키텍처

### 트리거
```
RuleEngineService.evaluate()
  ↓ AutoReviewEvaluatedEvent
AutoReviewEventListener.handleAutoReviewEvaluated()  [@Async("llmExecutor")]
  ├─ PurposeAnalysisService.analyze()
  ├─ ReviewReportService.generate()
  └─ PreReviewAgentService.run()            ★ 신규
       ├─ Track 3 → P1 full opinion + simulation
       ├─ Track 2 → P3 rejection reason (A8~A9)
       └─ Track 1 → skip
  ↓
LoanServiceClient.updateReport()  (agent_opinion_json 포함)
```

### 신규 패키지
```
com.bank.ai.agent
├── PreReviewAgentService           — orchestrator (Spring AI ChatClient + tool callbacks)
├── AgentOpinion                    — record
├── AgentToolRegistry               — ToolCallback 빈 등록
├── tools/
│   ├── RecomputeWithTermsTool      — what-if 재추론
│   ├── PolicyFlagTool              — soft warning (DSR 임계 80% 도달 등)
│   ├── PurposeAnalysisTool         — PurposeAnalysisService wrap
│   └── PolicyLookupTool            — PolicyIndex 조회
├── guard/
│   ├── AgentLoopGuard              — 도구 ≤ 6, LLM ≤ 2
│   └── DisagreementDetector        — track vs risk_level 불일치
└── rejection/                      — P3
    ├── RejectionReasonAgentService
    └── RejectionDraft
```

### LLM 통합 — Gemini 무료 API
Google AI Studio가 제공하는 **OpenAI 호환 endpoint**:
`https://generativelanguage.googleapis.com/v1beta/openai/`

→ Spring AI `spring-ai-openai-spring-boot-starter` 1개 의존성, base-url 변경만으로 사용. 신규 provider 어댑터 작성 불필요.

```yaml
ai:
  llm:
    provider: gemini-openai-compat    # 신규 enum 값
    base-url: https://generativelanguage.googleapis.com/v1beta/openai/
    model: gemini-2.5-flash
    api-key: ${GEMINI_API_KEY}
    daily-request-cap: 1500           # AI Studio 무료 RPD
    rpm-cap: 15                       # 무료 RPM
  agent:
    enabled: ${AI_AGENT_ENABLED:true}
    max-tool-calls: 6
    max-llm-calls: 2
```

`LlmCostMeter` 의 토큰 cap 자리에 RPD/RPM 카운터 추가 (메서드 시그니처 호환). 초과 시 기존 `TemplateFallback` 우회.

---

## 가드레일

| 항목 | 방어 |
|------|------|
| Loop | 도구 호출 ≤ 6, LLM 호출 ≤ 2 (`AgentLoopGuard`) |
| 무료 한도 | RPD 1500 / RPM 15 카운터, 초과 시 `TemplateFallback` |
| PII | `PiiAwareChatClient` 통과 (기존 `com.bank.ai.privacy`) |
| 환각 | `GroundingValidator` 확장 — 수치 클레임은 도구 결과와 매칭 |
| 인젝션 | `PromptInjectionDefense` 재사용 |
| Disagreement | track vs risk_level 불일치 시 `disagreement=true` → UI 빨간 배지 (강제 게이트 없음) |
| Kill switch | `ai.agent.enabled` |
| 결정론 테스트 | `StubLlmClient` + Stub 도구 |
| 금지 도구 | 상태 변경(승인/반려/통보), 외부 CB 재조회, PII 비마스킹 출력 |

---

## 단계 (단계별 커밋 + 보고 후 정지)

| # | 산출물 | 커밋 분리 | 상태 |
|---|--------|----------|:----:|
| A0 | 본 문서 + PROGRESS Phase 2 슬롯 갱신 | docs | 🔄 |
| A1 | `LlmProperties` gemini-openai-compat + RPD/RPM 카운터 | feat + test | ⬜ |
| A2 | `com.bank.ai.agent` 패키지 골격 + `AgentOpinion` + `agent_opinion_json` 마이그레이션 | feat + test | ⬜ |
| A3 | 4개 read-only tool + Stub 응답 | feat + test | ⬜ |
| A4 | `PreReviewAgentService` (tool calling) + `AgentLoopGuard` | feat + test | ⬜ |
| A5 | `RiskLevelDeriver` + `DisagreementDetector` + grounding 확장 | feat + test | ⬜ |
| A6 | `AutoReviewEventListener` Track 3 트리거 + `LoanServiceClient` 콜백 확장 | feat + test | ⬜ |
| A7 | E2E smoke 3 케이스 + 회귀 | test | ⬜ |
| **— P1 완료 —** | | | |
| A8 | `RejectionReasonAgentService` (P3) | feat + test | ⬜ |
| A9 | Track 2 트리거 + 준법 검토용 마킹 + smoke | feat + test | ⬜ |
| A10 | 문서 동기화 (banking-review-llm §11, MODEL_CARDS, PROMPT_REGISTRY) | docs | ⬜ |

---

## 재사용 자산

| 기능 | 자산 | 경로 |
|------|------|------|
| LLM provider 추상화 | `LlmClient`, `StubLlmClient` | `llm/client/` |
| PII | `PiiAwareChatClient`, `PiiMaskingFilter` | `llm/support/`, `privacy/` |
| 프롬프트 버저닝 | `PromptRegistry`, `Prompt` | `llm/prompt/` |
| 인젝션 방어 | `PromptInjectionDefense` | `llm/prompt/` |
| 정책 인용 | `PolicyIndex` + `ai.policy.inline.*` | `llm/policy/` + `application.yml` |
| Grounding | `GroundingValidator` | `llm/report/` |
| Fallback | `TemplateFallback` | `llm/report/` |
| 비동기 트리거 | `LlmAsyncConfig`, `AutoReviewEvaluatedEvent`, `AutoReviewEventListener` | `llm/config/`, `review/event/`, `review/listener/` |
| 사유 분석 | `PurposeAnalysisService`, `PurposeAnalysis` | `llm/purpose/` |
| 듀얼 추론 | `InferenceClient.review(AutoReviewRequest)` | `review/client/` |
| 트랙 분기 | `TrackClassifier`, `PolicyMatrix`, `HardConstraintEvaluator` | `rule/service/` |
| 비용 추적 | `LlmCostMeter` | `llm/support/` |

---

## 핵심 책임 (Responsibilities)

- ✅ 위험도 및 심사 참고 정보 생성
- ✅ 정책 기반 1차 필터링 결과 종합
- ✅ 이중 리스크 모델 결과 해석
- ✅ 조건 변경 시 위험도 시뮬레이션
- ✅ 심사자 의사결정 보조 정보 제공
- ❌ 승인/거절 최종 결정
- ❌ 외부 신용정보 재조회
- ❌ 고객 통보 발송
