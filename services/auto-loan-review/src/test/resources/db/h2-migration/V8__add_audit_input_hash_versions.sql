-- ============================================================
-- V8 (H2 테스트 전용): agent_audit_log 재현성 컬럼 추가
-- 운영 db/migration/V8 과 동일 컬럼. H2 는 ADD COLUMN IF NOT EXISTS 지원.
-- ============================================================

ALTER TABLE agent_audit_log
    ADD COLUMN IF NOT EXISTS input_hash     CHAR(64);
ALTER TABLE agent_audit_log
    ADD COLUMN IF NOT EXISTS model_version  VARCHAR(64);
ALTER TABLE agent_audit_log
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_aal_input_hash ON agent_audit_log(input_hash);
