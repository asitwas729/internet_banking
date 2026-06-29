package com.bank.ai.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 임베딩 설정 — application.yml {@code ai.rag.embedding} 섹션.
 *
 * <p>{@code @ConfigurationPropertiesScan("com.bank.ai")} 로 자동 등록.
 *
 * @param provider    임베딩 구현 선택. "stub"(기본, 결정론) / "vertex"(Vertex AI text-embedding-005).
 * @param dimensions  임베딩 차원. text-embedding-005 기본 768. 응답 검증 기준값.
 * @param maxAttempts Vertex 호출 최대 시도 횟수(재시도 포함). 최소 1.
 * @param backoffMs   재시도 지수 백오프 기준(ms). attempt n 의 대기 = backoffMs * 2^(n-1).
 * @param batchSize   배치 임베딩 시 1 요청당 텍스트 수. Vertex 요청당 250 인스턴스 상한으로 클램프.
 */
@ConfigurationProperties(prefix = "ai.rag.embedding")
public record EmbeddingProperties(
        @DefaultValue("stub") String provider,
        @DefaultValue("768") int dimensions,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500") long backoffMs,
        @DefaultValue("250") int batchSize
) {
    /** Vertex text-embedding-005 요청당 인스턴스 상한. */
    public static final int VERTEX_MAX_BATCH = 250;

    public EmbeddingProperties {
        maxAttempts = Math.max(1, maxAttempts);
        backoffMs = Math.max(0, backoffMs);
        batchSize = Math.max(1, Math.min(batchSize, VERTEX_MAX_BATCH));
    }
}
