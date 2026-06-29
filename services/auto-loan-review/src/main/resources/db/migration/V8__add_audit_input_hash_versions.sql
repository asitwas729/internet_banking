-- ============================================================
-- V8: agent_audit_log 재현성 컬럼 추가 (input_hash + 모델/프롬프트 버전)
-- 근거: next-phase-roadmap.md §Phase B B1 — 결정마다 input_hash(SHA-256) 기록,
--       replay 가 동일 모델/프롬프트 버전으로 재현 가능해야 함.
-- ALTER TABLE 은 DDL 이므로 INSERT-ONLY 행 트리거(trg_aal_no_update)에 막히지 않는다.
-- ============================================================

ALTER TABLE agent_audit_log
    ADD COLUMN input_hash     CHAR(64),     -- SHA-256(request_snapshot 정규화) hex
    ADD COLUMN model_version  VARCHAR(64),  -- 결정 시점 LLM/스코어 모델 버전
    ADD COLUMN prompt_version VARCHAR(32);  -- 결정 시점 시스템 프롬프트 버전

-- 동일 입력 재현·중복 조회용 (input_hash 단건 lookup)
CREATE INDEX idx_aal_input_hash ON agent_audit_log(input_hash);
