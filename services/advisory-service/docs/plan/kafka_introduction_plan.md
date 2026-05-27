# advisory-service Kafka 도입 계획

## 0. 전제 확인 (진행 전 결정 필요)

| 항목 | 옵션 | 결정 |
|---|---|---|
| **브로커 토폴로지** | (A) docker-compose.yml 기존 단일 broker 공유 / (B) advisory 전용 broker 신설 | **(A)** |
| **메시지 포맷** | JSON String / Avro+Schema Registry | **JSON** (loan/payment 패턴 일치) |
| **신뢰성 모델** | At-most-once / At-least-once + Outbox / Exactly-once | **At-least-once + Outbox** |
| **Kafka 이관 대상** | 3개 이벤트 모두 / 선별 | **2개만** (Quarantine, AdvisoryReportPublished) |

### 선별 근거

- `LoanReviewCompletedEvent` — **이관 불필요.** advisory-service는 loan-service/build.gradle의 `srcDirs`로 합쳐져 동일 JVM에서 빌드/실행됨. Spring ApplicationEventPublisher 인메모리 전달이 올바른 방식.
- `QuarantineTriggeredEvent` — advisory 외부(notification)가 구독해야 하므로 Kafka 이관 대상.
- `AdvisoryReportPublishedEvent` — 동일하게 외부 구독자 존재, Kafka 이관 대상.

### 토픽 네이밍 컨벤션

```
advisory.quarantine.triggered.v1
advisory.report.published.v1
```

---

## Phase 1 — 인프라 & 의존성 (1 commit)

- `services/advisory-service/build.gradle` — `spring-kafka` 추가
- `services/advisory-service/src/main/resources/application.yml` 신설
  - `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
  - producer: `acks=all`, `enable.idempotence=true`, StringSerializer
- docker-compose.yml 기존 broker(9092) 그대로 사용, 토픽 auto-create 허용

커밋: `feat(advisory): Kafka 의존성 및 application.yml 초기 설정`

---

## Phase 2 — Outbox 테이블 + 발행 워커 (1 feat + 1 test)

- `V6__advisory_outbox.sql`

```sql
CREATE TABLE advisory_outbox_message (
  id            BIGSERIAL PRIMARY KEY,
  aggregate_id  VARCHAR(64)  NOT NULL,
  event_type    VARCHAR(128) NOT NULL,
  payload_json  TEXT         NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  published_at  TIMESTAMPTZ
);
CREATE INDEX ON advisory_outbox_message (status, created_at);
```

- `AdvisoryOutboxPublisher` — payment-service `OutboxPublisher` 패턴 차용
  - 스케줄러 polling 5초 간격
  - DB 트랜잭션 안에서 outbox 적재 → 트랜잭션 밖에서 Kafka send
  - 발행 성공 시 `status=PUBLISHED`, `published_at` 기록

커밋:
- `feat(advisory): Outbox 테이블 + AdvisoryOutboxPublisher 구현`
- `test(advisory): AdvisoryOutboxPublisher 단위 테스트`

---

## Phase 3 — QuarantineTriggeredEvent 마이그레이션 (1 feat + 1 test)

현재: `QuarantineNotificationListener`(@EventListener) → 직접 Outbox 적재

변경:
- 인-메모리 이벤트(`QuarantineTriggeredEvent`)는 유지 (도메인 신호)
- `QuarantineNotificationListener`가 Outbox에 `advisory.quarantine.triggered.v1` 페이로드 적재
- 외부 notification 구독자 → `@KafkaListener("advisory.quarantine.triggered.v1")` 전환

커밋:
- `feat(advisory): QuarantineTriggeredEvent → Kafka Outbox 발행 전환`
- `test(advisory): QuarantineNotificationListener Kafka 발행 통합 테스트`

---

## Phase 4 — AdvisoryReportPublishedEvent 마이그레이션 (1 feat + 1 test)

현재: `AdvisoryBatchEvaluationService`가 `ApplicationEventPublisher`로 발행 → notification 모듈 구독

변경:
- Outbox 적재 방식으로 전환, 토픽 `advisory.report.published.v1`
- 외부 notification 모듈 → `@KafkaListener("advisory.report.published.v1")` 전환

커밋:
- `feat(advisory): AdvisoryReportPublishedEvent → Kafka Outbox 발행 전환`
- `test(advisory): AdvisoryBatchEvaluationService Kafka 발행 통합 테스트`

---

## (보류) Phase 5 — Schema Registry / Avro

토픽이 5개 초과하거나 외부 팀이 consumer로 붙기 시작할 때 검토.

---

## 진행 상태

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 전제 확인 (LoanReviewCompletedEvent 전달 메커니즘) | ✅ 동일 JVM, 이관 불필요 |
| 1 | 인프라 & 의존성 | 🔲 |
| 2 | Outbox 테이블 + 발행 워커 | 🔲 |
| 3 | QuarantineTriggeredEvent 마이그레이션 | 🔲 |
| 4 | AdvisoryReportPublishedEvent 마이그레이션 | 🔲 |
| 5 | 운영/관측 | — 모니터링 담당자 별도 진행 |
