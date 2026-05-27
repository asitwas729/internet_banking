# Kafka 학습 로드맵 (advisory-service 기반)

> 1금융권 은행 서비스 가정. 각 Phase는 구현 → 관찰 → 학습 결론 3단으로 마무리.
> 결과 노트: `services/advisory-service/docs/learning/`

## 진행 순서

| Phase | 내용 | 상태 |
|---|---|---|
| L1 | 토픽 거버넌스 명시화 | 🔲 |
| L2 | Producer 고급 설정 | 🔲 |
| L6 | Consumer 기본 도입 | 🔲 |
| L7 | Consumer 성능/장애 튜닝 | 🔲 |
| L4 | 커스텀 파티셔너 | 🔲 |
| L8 | Partition Assignment + Static Membership | 🔲 |
| L3 | Avro + Schema Registry | 🔲 |
| L5 | Quota / Throttling | 🔲 |

---

## L1 — 토픽 거버넌스 명시화

**왜 첫 단계인가**: 1금융권은 운영 토픽 auto-create 금지가 일반적. 감사 대상.

### 구현
- `docker-compose.yml`: `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`
- `AdvisoryTopicInitializer` (`@PostConstruct` + `KafkaAdmin`)
  - `advisory.quarantine.triggered.v1`: partitions=3, RF=1(dev), retention.ms=2592000000(30일)
  - `advisory.report.published.v1`: partitions=3, RF=1(dev), retention.ms=604800000(7일)
  - DLQ: `advisory.quarantine.triggered.v1.dlq`, `advisory.report.published.v1.dlq`

### 관찰
- `kafka-topics.sh --describe` 로 partition/RF/config 확인
- auto-create 끄고 토픽 없는 상태에서 발행 시도 → 오류 확인

### 학습 결론
- 토픽 retention이 도메인마다 다른 이유 (quarantine=30일: 감사 보존 / report=7일: 알림성)
- RF=1(dev) vs RF=3(prd)의 의미

---

## L2 — Producer 고급 설정

**왜**: auto-config 공유 KafkaTemplate → advisory 전용 ProducerFactory 분리. 각 설정 실측.

### 구현 (`AdvisoryKafkaProducerConfig`)

| 설정 | 값 | 이유 |
|---|---|---|
| `acks` | `all` | 무손실 |
| `enable.idempotence` | `true` | 재시도 중복 방지 |
| `max.in.flight.requests.per.connection` | `5` | idempotence와 함께 순서 보장 상한 |
| `retries` | `Integer.MAX_VALUE` | delivery.timeout.ms로 상한 제어 |
| `delivery.timeout.ms` | `120000` | 2분 초과 시 실패 처리 |
| `compression.type` | `lz4` | 저비용 압축. snappy/zstd 비교 실험 |
| `batch.size` | `32768` (32KB) | 기본 16KB의 2배 |
| `linger.ms` | `20` | 0(즉시) vs 20ms(배치) 처리량 비교 |
| `buffer.memory` | `33554432` (32MB) | 발행 속도 > 브로커 수용 속도 시 한계 |
| `max.request.size` | `1048576` (1MB) | |
| `send.buffer.bytes` | `131072` | TCP 소켓 버퍼 |
| `receive.buffer.bytes` | `65536` | TCP 소켓 버퍼 |
| `client.id` | `advisory-producer` | quota/모니터링 식별 (L5에서 활용) |

### 관찰
- `linger.ms=0` vs `20`: 처리량/지연 비교
- `compression.type` 4종: JSON 페이로드 압축률 비교
- Producer Metrics: `record-send-rate`, `batch-size-avg`, `compression-rate-avg`

### 학습 결론
- 노트: `docs/learning/L2_producer_tuning_notes.md`

---

## L6 — Consumer 기본 도입

**왜**: 발행만 하던 advisory에 실제 구독자 추가. at-least-once 보장 패턴 실습.

### 구현 (`AdvisoryQuarantineKafkaConsumer`)
- 토픽: `advisory.quarantine.triggered.v1`
- 수동 ack 모드 (`ack-mode: manual`)

| 설정 | 값 | 이유 |
|---|---|---|
| `group.id` | `advisory-quarantine-notifier` | |
| `enable.auto.commit` | `false` | at-least-once 보장 |
| `auto.offset.reset` | `earliest` | 최초 구독 시 과거 메시지 전부 읽기 |
| `isolation.level` | `read_committed` | 트랜잭션 producer 도입 시 대비 |
| `client.id` | `advisory-quarantine-consumer` | |

- 멱등성: 처리 로그 테이블 또는 `aggregate_id` UNIQUE 제약으로 중복 방어
- DLQ 연결: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`

### 관찰
- `auto.offset.reset=earliest` vs `latest` 동작 차이
- 수동 ack 전/후 재시작 시 offset 위치 비교
- DLQ로 실패 메시지 격리 확인

---

## L7 — Consumer 성능/장애 튜닝

### 구현

| 설정 | 실험값 | 학습 포인트 |
|---|---|---|
| `fetch.min.bytes` | `1` → `10240` | 저지연 vs 고처리량 trade-off |
| `fetch.max.wait.ms` | `500` | min.bytes 못 채울 때 대기 상한 |
| `fetch.max.bytes` | `52428800` (50MB) | 응답 전체 상한 |
| `max.partition.fetch.bytes` | `1048576` (1MB) | 파티션별 상한 |
| `max.poll.records` | `500` → 조정 | 너무 크면 max.poll.interval.ms 초과 |
| `max.poll.interval.ms` | `300000` | 초과 시 그룹 추방 → rebalance |
| `session.timeout.ms` | `45000` | heartbeat 없으면 사망 판정 |
| `heartbeat.interval.ms` | `15000` | session.timeout의 1/3 |
| `request.timeout.ms` | `30000` | broker 응답 최대 대기 |
| `default.api.timeout.ms` | `60000` | API 호출 전체 타임아웃 |

### 관찰
- `max.poll.records` 변경 시 rebalance 빈도 비교
- DLQ 격리 시나리오 실행

### 학습 결론
- 노트: `docs/learning/L7_consumer_failure_modes.md`

---

## L4 — 커스텀 파티셔너

**왜**: key=`reviewerId`로 바꾸면 거물 심사관에게 쏠림 → hot partition. 1금융권 VIP 데이터 집중 케이스.

### 구현
- 시뮬레이션 토픽 `advisory.test.skew.v1`에 `reviewerId` 키로 1만 건 발행
- `SkewAwarePartitioner implements Partitioner`
  - 핫키(특정 reviewerId)는 round-robin
  - 나머지는 기본 hash 파티셔닝

### 관찰
- 커스텀 전/후 파티션별 메시지 수 비교 (`GetOffsetShell`)
- 순서 보장 손실 인지 및 허용 여부 판단

### 학습 결론
- 노트: `docs/learning/L4_partition_skew.md`

---

## L8 — Partition Assignment + Static Membership

**왜**: 1금융권 무중단 배포 시 rebalance 폭풍 = consumer lag 폭증 → SLA 위반.

### 구현

| 설정 | 값 | 학습 포인트 |
|---|---|---|
| `partition.assignment.strategy` | `CooperativeStickyAssignor` | rebalance 중에도 영향 없는 파티션은 처리 계속 |
| `group.instance.id` | `advisory-consumer-0`, `-1`, `-2` | static membership. 재시작 시 rebalance 방지 |
| `client.rack` | `az-a`, `az-b` | 같은 rack replica에서 읽기 → 크로스 AZ 대역폭↓ |
| `offsets.retention.minutes` | `10080` (7일) | 그룹 정지 후 offset 보존 기간 |

### 관찰
- 컨슈머 1→2→3 단계적 추가하며 rebalance 로그 (cooperative vs eager)
- static membership 전후 재시작 시 rebalance 발생 여부

---

## L3 — Avro + Schema Registry

**왜**: 기존 String consumer를 Avro로 마이그레이션 — 호환성 문제를 직접 맞닥뜨림.

### 구현
- `docker-compose.yml`: `confluentinc/cp-schema-registry:7.6.0` (포트 8081)
- `src/main/avro/`: `QuarantineTriggered.avsc`, `ReportPublished.avsc`
- gradle: `com.github.davidmc24.gradle.plugin.avro`
- 직렬화기: `KafkaAvroSerializer` / `KafkaAvroDeserializer`
- 호환성 모드: `BACKWARD`

### 관찰
- Schema Registry REST로 스키마 확인
- 호환성 위반 시뮬레이션 (필드 삭제 → 등록 실패)
- JSON vs Avro payload 크기 비교

### 학습 결론
- 노트: `docs/learning/L3_avro_schema_evolution.md`

---

## L5 — Quota / Throttling

**왜**: advisory 배치가 결제계 broker 대역폭 점유 방지.

### 구현
- `kafka-configs.sh`로 `client.id` 기반 quota:
  - `producer_byte_rate=1048576` (1MB/s)
  - `request_percentage=50`
- consumer quota: `consumer_byte_rate`

### 관찰
- 1MB/s 초과 발행 → throttle 지연 (`produce-throttle-time-avg`)
- advisory 배치가 다른 서비스에 미치는 영향 측정
