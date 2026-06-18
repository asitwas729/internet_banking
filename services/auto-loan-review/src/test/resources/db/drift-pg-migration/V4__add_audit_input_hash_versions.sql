-- ============================================================
-- drift 통합테스트 전용 (PostgreSQL Testcontainers): agent_audit_log 재현성 컬럼 추가
-- 운영 db/migration/V8 과 동일 컬럼. drift 로케이션 버전 체계상 V4.
-- ============================================================

ALTER TABLE agent_audit_log
    ADD COLUMN input_hash     CHAR(64),
    ADD COLUMN model_version  VARCHAR(64),
    ADD COLUMN prompt_version VARCHAR(32);

CREATE INDEX idx_aal_input_hash ON agent_audit_log(input_hash);
