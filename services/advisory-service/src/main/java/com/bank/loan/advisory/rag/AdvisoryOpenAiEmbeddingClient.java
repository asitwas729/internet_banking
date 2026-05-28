package com.bank.loan.advisory.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * OpenAI /v1/embeddings 어댑터.
 *
 * 활성 조건: advisory.rag.embed.provider=openai
 * 모델코드: OPENAI_3S (text-embedding-3-small, 1536 차원)
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "advisory.rag.embed.provider", havingValue = "openai")
public class AdvisoryOpenAiEmbeddingClient implements EmbeddingClient {

    static final String MODEL_CD  = "OPENAI_3S";
    private static final String EMBED_PATH = "/v1/embeddings";

    private final RestClient restClient;
    private final String     model;
    private final int        dimension;
    private final int        maxAttempts;
    private final long       retryBackoffMs;

    public AdvisoryOpenAiEmbeddingClient(RestClient.Builder builder,
                                         AdvisoryRagProperties props) {
        AdvisoryRagProperties.OpenAi openai = props.openai();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (openai.connectTimeoutMs() > 0) factory.setConnectTimeout(openai.connectTimeoutMs());
        if (openai.readTimeoutMs()    > 0) factory.setReadTimeout(openai.readTimeoutMs());

        RestClient.Builder b = builder
                .requestFactory(factory)
                .baseUrl(openai.baseUrl());

        if (openai.apiKey() != null && !openai.apiKey().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openai.apiKey());
        }

        this.restClient     = b.build();
        this.model          = props.model();
        this.dimension      = props.dimension();
        this.maxAttempts    = openai.maxAttempts();
        this.retryBackoffMs = openai.retryBackoffMs();
    }

    @Override
    public String defaultModelCd() {
        return MODEL_CD;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        EmbedResponse response = callWithRetry(new EmbedRequest(model, List.of(text), dimension));
        if (response.data() == null || response.data().isEmpty()) {
            throw new EmbeddingCallException("openai 응답 본문 비어 있음");
        }
        List<Double> values = response.data().get(0).embedding();
        if (values.size() != dimension) {
            throw new EmbeddingCallException(
                    "openai 응답 차원 불일치: expected=" + dimension + " actual=" + values.size());
        }
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) v[i] = values.get(i).floatValue();
        return v;
    }

    private EmbedResponse callWithRetry(EmbedRequest request) {
        Throwable lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                EmbedResponse body = restClient.post()
                        .uri(EMBED_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(EmbedResponse.class);
                if (body == null) throw new EmbeddingCallException("openai 응답 null");
                return body;
            } catch (HttpClientErrorException e) {
                // 4xx — 재시도 무의미
                throw new EmbeddingCallException(
                        "openai 4xx " + e.getStatusCode() + " — 요청 형식 점검 필요", e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastEx = e;
                log.warn("[advisory-embed] attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) sleepBackoff(attempt);
            }
        }
        throw new EmbeddingCallException("openai 임베딩 " + maxAttempts + "회 재시도 실패", lastEx);
    }

    private void sleepBackoff(int attempt) {
        long delay = retryBackoffMs * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new EmbeddingCallException("재시도 대기 중 인터럽트", ie);
        }
    }

    // commit 3 에서 BusinessException 으로 교체 예정
    static class EmbeddingCallException extends RuntimeException {
        EmbeddingCallException(String msg) { super(msg); }
        EmbeddingCallException(String msg, Throwable cause) { super(msg, cause); }
    }

    record EmbedRequest(String model, List<String> input, int dimensions) {}

    record EmbedResponse(List<Item> data) {
        record Item(int index, List<Double> embedding) {}
    }
}
