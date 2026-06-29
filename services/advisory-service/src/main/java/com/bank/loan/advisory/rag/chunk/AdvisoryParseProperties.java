package com.bank.loan.advisory.rag.chunk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 규정문서 파싱 사이드카(inference-server) 호출 설정.
 *
 * baseUrl 기본값은 doc-agent 와 동일하게 INFERENCE_SERVER_URL 환경변수를 따른다.
 * 파싱(특히 HWP→PDF 변환 폴백)은 임베딩보다 오래 걸릴 수 있어 readTimeout 을 길게 잡는다.
 */
@ConfigurationProperties(prefix = "advisory.rag.parse")
public record AdvisoryParseProperties(
        @DefaultValue("${INFERENCE_SERVER_URL:http://localhost:8090}") String baseUrl,
        @DefaultValue("5000")  int connectTimeoutMs,
        @DefaultValue("60000") int readTimeoutMs,
        @DefaultValue("2")     int maxAttempts,
        @DefaultValue("500")   long retryBackoffMs,
        @DefaultValue("true")  boolean ocrFallback
) {
}
