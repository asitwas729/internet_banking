package com.bank.ai.rag.embedding;

import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 기반 임베딩 클라이언트 — Vertex AI text-embedding-005.
 *
 * <p>{@code ai.rag.embedding.provider=vertex} 시 활성. {@link EmbeddingModel} 빈은
 * {@link VertexEmbeddingAutoConfiguration} 이 provider=vertex 일 때만 등록한다.
 *
 * <p>견고화: 지수 백오프 재시도(일시 오류) · 4xx 등 영구 오류 즉시 중단 · 응답 차원(768) 검증 ·
 * 배치 250 상한 분할. 실패는 모두 {@link BusinessException}({@link AiErrorCode#EMBEDDING_FAILED}
 * / {@link AiErrorCode#EMBEDDING_DIMENSION_MISMATCH}) 으로 변환한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider", havingValue = "vertex")
public class SpringAiEmbeddingClient implements EmbeddingClient {

    /** 메트릭 model 태그 — Vertex 배포 임베딩 모델. */
    static final String MODEL = "text-embedding-005";

    private final EmbeddingModel embeddingModel;
    private final AgentMetricsRecorder metrics;
    private final int dimensions;
    private final int maxAttempts;
    private final long backoffMs;
    private final int batchSize;

    public SpringAiEmbeddingClient(EmbeddingModel embeddingModel, EmbeddingProperties props,
                                   AgentMetricsRecorder metrics) {
        this.embeddingModel = embeddingModel;
        this.metrics = metrics;
        this.dimensions = props.dimensions();
        this.maxAttempts = props.maxAttempts();
        this.backoffMs = props.backoffMs();
        this.batchSize = props.batchSize();
        log.info("SpringAiEmbeddingClient configured: dim={} maxAttempts={} backoffMs={} batchSize={}",
                dimensions, maxAttempts, backoffMs, batchSize);
    }

    @Override
    public float[] embed(String text) {
        return embedBatchWithRetry(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            out.addAll(embedBatchWithRetry(batch));
        }
        return out;
    }

    /** 단일 배치를 재시도·검증과 함께 임베딩. 지연·건수·비용 proxy 를 메트릭으로 기록. */
    private List<float[]> embedBatchWithRetry(List<String> batch) {
        long startNanos = System.nanoTime();
        boolean ok = false;
        try {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    List<float[]> vectors = embeddingModel.embed(batch);
                    validate(vectors, batch.size());
                    ok = true;
                    return vectors;
                } catch (BusinessException e) {
                    // 차원/개수 불일치 등 — 재시도해도 동일, 즉시 전파
                    throw e;
                } catch (RuntimeException e) {
                    if (!isRetryable(e)) {
                        log.error("vertex 임베딩 영구 실패(재시도 안 함): {}", e.toString());
                        throw new BusinessException(AiErrorCode.EMBEDDING_FAILED,
                                "vertex 임베딩 영구 실패: " + e.getMessage());
                    }
                    last = e;
                    log.warn("vertex 임베딩 attempt {}/{} 실패: {}", attempt, maxAttempts, e.toString());
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                    }
                }
            }
            throw new BusinessException(AiErrorCode.EMBEDDING_FAILED,
                    "vertex 임베딩 " + maxAttempts + "회 재시도 실패: "
                            + (last != null ? last.getMessage() : "unknown"));
        } finally {
            metrics.recordEmbedding(MODEL, ok,
                    Duration.ofNanos(System.nanoTime() - startNanos), batch.size(), charCount(batch));
        }
    }

    private static long charCount(List<String> batch) {
        long chars = 0L;
        for (String t : batch) {
            if (t != null) {
                chars += t.length();
            }
        }
        return chars;
    }

    private void validate(List<float[]> vectors, int expectedCount) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new BusinessException(AiErrorCode.EMBEDDING_FAILED,
                    "vertex 응답 개수 불일치: expected=" + expectedCount
                            + " actual=" + (vectors == null ? "null" : vectors.size()));
        }
        for (float[] v : vectors) {
            if (v == null || v.length != dimensions) {
                throw new BusinessException(AiErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "vertex 응답 차원 불일치: expected=" + dimensions
                                + " actual=" + (v == null ? "null" : v.length));
            }
        }
    }

    /**
     * 재시도 가치 판단 — 4xx(요청 오류)·인증/인자 오류는 영구 실패로 보고 즉시 중단.
     * Spring AI EmbeddingModel 추상화는 HTTP 상태를 직접 노출하지 않으므로 원인 체인을
     * best-effort 로 검사한다(google-cloud gax 예외명 포함).
     */
    private boolean isRetryable(Throwable e) {
        for (Throwable t = e; t != null; ) {
            if (t instanceof HttpClientErrorException) {
                return false; // 4xx
            }
            String n = t.getClass().getSimpleName();
            if (n.contains("InvalidArgument") || n.contains("PermissionDenied")
                    || n.contains("Unauthenticated") || n.contains("NotFound")) {
                return false;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return true;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(backoffMs * (1L << (attempt - 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(AiErrorCode.EMBEDDING_FAILED, "임베딩 재시도 대기 중 인터럽트");
        }
    }
}
