package com.bank.ai.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AdminActionAuditService 통합 테스트 — phase-b-operational.md §B5.
 *
 * <p>H2 인메모리 DB 사용. REQUIRES_NEW 트랜잭션으로 INSERT 후 행 수를 직접 조회해 검증.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:adminauditdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.flyway.locations=classpath:db/h2-migration",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                "org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration",
        "ai.llm.provider=stub",
        "ai.audit.enabled=true"
})
class AdminActionAuditServiceTest {

    @Autowired
    private AdminActionAuditService adminActionAuditService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    // ── TC1: SUCCESS 행 INSERT ────────────────────────────────────────────────

    @Test
    void record_success_INSERT되고_행_조회됨() {
        var record = AdminActionAuditRecord.success("testuser", "QUERY_AUDIT_LOG", 2001L);

        adminActionAuditService.record(record);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_action_audit WHERE target_rev_id = 2001 AND result = 'SUCCESS'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── TC2: FAILURE 행 + failureReason 저장 ─────────────────────────────────

    @Test
    void record_failure_failureReason_저장됨() {
        var record = AdminActionAuditRecord.failure("testuser", "REPLAY_DRY_RUN", 2002L, "감사 로그 없음 revId=2002");

        adminActionAuditService.record(record);

        String reason = jdbc.queryForObject(
                "SELECT failure_reason FROM admin_action_audit WHERE target_rev_id = 2002 AND result = 'FAILURE'",
                new MapSqlParameterSource(), String.class);
        assertThat(reason).contains("감사 로그 없음");
    }

    // ── TC3: adminUser·action 컬럼 정확히 저장 ────────────────────────────────

    @Test
    void record_adminUser와_action_정확히_저장됨() {
        var record = AdminActionAuditRecord.success("audit-admin", "QUERY_AUDIT_LOG", 2003L);

        adminActionAuditService.record(record);

        var params = new MapSqlParameterSource("revId", 2003L);
        String adminUser = jdbc.queryForObject(
                "SELECT admin_user FROM admin_action_audit WHERE target_rev_id = :revId",
                params, String.class);
        String action = jdbc.queryForObject(
                "SELECT action FROM admin_action_audit WHERE target_rev_id = :revId",
                params, String.class);

        assertThat(adminUser).isEqualTo("audit-admin");
        assertThat(action).isEqualTo("QUERY_AUDIT_LOG");
    }

    // ── TC4: 예외가 발생해도 호출자에게 전파되지 않음 ─────────────────────────

    @Test
    void record_내부예외_호출자에게_전파되지_않음() {
        // 유효하지 않은 action 값으로 DB CHECK 제약 위반을 유도 → service 는 catch 후 log 처리
        var invalidRecord = new AdminActionAuditRecord(
                "testuser", "INVALID_ACTION", null, null, "SUCCESS", null);

        assertThatCode(() -> adminActionAuditService.record(invalidRecord))
                .doesNotThrowAnyException();
    }
}
