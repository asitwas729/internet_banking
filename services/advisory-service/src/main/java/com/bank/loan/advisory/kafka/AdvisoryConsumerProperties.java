package com.bank.loan.advisory.kafka;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * advisory consumer 튜닝 파라미터.
 *
 * application.yml의 advisory.consumer.* 값을 바꾸는 것만으로 재컴파일 없이 실험 가능.
 *
 * L7 실험 가이드:
 *   1. fetch.min-bytes: 1 → 10240 변경 후 처리량/지연 비교
 *   2. max-poll-records: 500 → 5000 변경 후 rebalance 빈도 비교
 *   3. experiment-delay-ms: 0 → 60000 설정 시 max.poll.interval.ms 초과 시뮬레이션
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "advisory.consumer")
public class AdvisoryConsumerProperties {

    // fetch 튜닝 (처리량 vs 지연 trade-off)
    private int fetchMinBytes       = 1;      // 낮을수록 즉시 응답(저지연), 높을수록 모아서 응답(고처리량)
    private int fetchMaxWaitMs      = 500;    // fetchMinBytes 못 채울 때 최대 대기
    private int fetchMaxBytes       = 52_428_800;   // 50MB
    private int maxPartitionFetchBytes = 1_048_576; // 1MB

    // 폴링 (rebalance 안정성)
    private int maxPollRecords      = 500;    // 높이면 처리량↑, max.poll.interval.ms 초과 위험
    private int maxPollIntervalMs   = 300_000;
    private int sessionTimeoutMs    = 45_000;
    private int heartbeatIntervalMs = 15_000;

    // 타임아웃
    private int requestTimeoutMs    = 30_000;
    private int defaultApiTimeoutMs = 60_000;

    // 실험용 처리 지연 (ms). 0=정상. 양수 설정 시 max.poll.interval.ms 초과 시뮬레이션
    private long experimentDelayMs  = 0;

    // DLQ 재시도
    private long dlqBackoffIntervalMs = 1_000;
    private long dlqMaxAttempts       = 3;
}
