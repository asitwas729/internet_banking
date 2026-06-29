package com.bank.ai.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin 행위 감사 기록 서비스.
 *
 * <p>{@link Propagation#REQUIRES_NEW} — 주 트랜잭션 롤백과 무관하게 행위 로그를 커밋한다.
 * 실패 시 예외를 전파하지 않아 감사 기록 오류가 API 응답을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminActionAuditService {

    private final AdminActionAuditRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminActionAuditRecord record) {
        try {
            repository.insert(record);
            log.debug("[AdminAudit] user={} action={} revId={} result={}",
                    record.adminUser(), record.action(), record.targetRevId(), record.result());
        } catch (Exception e) {
            log.error("[AdminAudit] 행위 감사 기록 실패 action={} revId={}",
                    record.action(), record.targetRevId(), e);
        }
    }
}
