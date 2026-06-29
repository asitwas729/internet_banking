package com.bank.ai.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * {@code admin_action_audit} 테이블 접근 — INSERT 전용 JdbcTemplate 기반.
 */
@Repository
@RequiredArgsConstructor
class AdminActionAuditRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String INSERT_SQL = """
            INSERT INTO admin_action_audit
              (admin_user, action, target_rev_id, request_body, result, failure_reason, created_at)
            VALUES
              (:adminUser, :action, :targetRevId,
               CAST(:requestBody AS VARCHAR), :result, :failureReason, :createdAt)
            """;

    void insert(AdminActionAuditRecord record) {
        var params = new MapSqlParameterSource()
                .addValue("adminUser", record.adminUser())
                .addValue("action", record.action())
                .addValue("targetRevId", record.targetRevId())
                .addValue("requestBody", record.requestBodyJson())
                .addValue("result", record.result())
                .addValue("failureReason", record.failureReason())
                .addValue("createdAt", Timestamp.from(Instant.now()));
        jdbc.update(INSERT_SQL, params);
    }
}
