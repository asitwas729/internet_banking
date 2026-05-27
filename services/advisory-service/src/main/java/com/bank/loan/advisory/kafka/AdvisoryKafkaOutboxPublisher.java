package com.bank.loan.advisory.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Transactional Outbox 워커.
 *
 * advisory_kafka_outbox.status=PENDING 레코드를 5초마다 폴링해 Kafka로 발행한다.
 * @Transactional 금지: Kafka send()가 DB 트랜잭션 안에 들어가면 send 지연이 락 보유 시간이 됨.
 * DB 상태 갱신은 AdvisoryKafkaOutboxTransactionHelper(별도 Bean)에 위임한다.
 */
@Slf4j
@Component
public class AdvisoryKafkaOutboxPublisher {

    private final AdvisoryKafkaOutboxRepository repository;
    private final AdvisoryKafkaOutboxTransactionHelper transactionHelper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public AdvisoryKafkaOutboxPublisher(
            AdvisoryKafkaOutboxRepository repository,
            AdvisoryKafkaOutboxTransactionHelper transactionHelper,
            @Qualifier("advisoryKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.transactionHelper = transactionHelper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<AdvisoryKafkaOutboxMessage> pending = repository.findPending();
        if (pending.isEmpty()) {
            return;
        }
        log.debug("[advisory-outbox] 발행 대상: {}건", pending.size());
        for (AdvisoryKafkaOutboxMessage message : pending) {
            publishOne(message);
        }
    }

    private void publishOne(AdvisoryKafkaOutboxMessage message) {
        try {
            kafkaTemplate.send(message.getTopic(), message.getRecordKey(), message.getPayloadJson()).get();
            transactionHelper.markPublished(message);
            log.debug("[advisory-outbox] 발행 완료 — id={} topic={} key={}",
                    message.getId(), message.getTopic(), message.getRecordKey());
        } catch (ExecutionException e) {
            log.error("[advisory-outbox] 발행 실패 — id={} topic={} error={}",
                    message.getId(), message.getTopic(), e.getCause().getMessage(), e);
            transactionHelper.markFailed(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[advisory-outbox] 인터럽트 — id={}", message.getId(), e);
            transactionHelper.markFailed(message);
        }
    }
}
