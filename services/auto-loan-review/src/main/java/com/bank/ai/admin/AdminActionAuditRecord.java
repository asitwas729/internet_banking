package com.bank.ai.admin;

/**
 * admin_action_audit 테이블 단건 레코드.
 *
 * @param adminUser       요청 주체 식별자 (인증 도입 전: "anonymous")
 * @param action          수행 동작 코드 (DB CHECK 제약과 동일 값)
 * @param targetRevId     조작 대상 revId (상태 조회 등 대상 없는 동작은 null)
 * @param requestBodyJson 요청 본문 JSON (없으면 null)
 * @param result          SUCCESS / FAILURE
 * @param failureReason   실패 시 사유 메시지 (성공 시 null)
 */
public record AdminActionAuditRecord(
        String adminUser,
        String action,
        Long targetRevId,
        String requestBodyJson,
        String result,
        String failureReason
) {
    public static AdminActionAuditRecord success(String adminUser, String action, Long targetRevId) {
        return new AdminActionAuditRecord(adminUser, action, targetRevId, null, "SUCCESS", null);
    }

    public static AdminActionAuditRecord failure(String adminUser, String action,
                                                  Long targetRevId, String reason) {
        return new AdminActionAuditRecord(adminUser, action, targetRevId, null, "FAILURE", reason);
    }
}
