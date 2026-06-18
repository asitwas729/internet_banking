-- ============================================================
-- V9: Admin 행위 감사 로그 (INSERT-ONLY)
-- 보존 정책: 금융감독원 AI 리스크 관리 가이드라인(2024) §8
-- ============================================================

CREATE TABLE admin_action_audit (
    id              BIGSERIAL       PRIMARY KEY,
    admin_user      VARCHAR(128)    NOT NULL,
    action          VARCHAR(64)     NOT NULL,
    target_rev_id   BIGINT,
    request_body    JSONB,
    result          VARCHAR(32)     NOT NULL,
    failure_reason  TEXT,
    ip_address      INET,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_aaa_action CHECK (action IN (
        'QUERY_AUDIT_LOG',
        'REPLAY_DRY_RUN',
        'REGENERATE_OPINION',
        'QUERY_STATUS',
        'TOGGLE_AGENT',
        'FLUSH_RATE_METER',
        'QUERY_PSI_REPORT',
        'QUERY_FAIRNESS_REPORT',
        'QUERY_SHADOW_DIVERGED'
    )),
    CONSTRAINT chk_aaa_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_aaa_admin_user  ON admin_action_audit(admin_user);
CREATE INDEX idx_aaa_created_at  ON admin_action_audit(created_at DESC);
CREATE INDEX idx_aaa_action      ON admin_action_audit(action);
