package com.bank.loan.advisory.rag.chunk;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Base64;

/**
 * inference-server {@code POST /parse/document} 어댑터.
 *
 * {@link AdvisoryOpenAiEmbeddingClient} 와 동일한 수동 재시도 폴백 패턴을 쓴다
 * (loan-service 클래스패스에 resilience4j 부재 — doc-agent 의 @CircuitBreaker 재사용 불가):
 *   - 5xx / 연결 오류(ResourceAccessException) → 백오프 후 재시도
 *   - 4xx → 즉시 실패(요청 형식 문제)
 *
 * degraded 신호는 그대로 노출해 호출부(DocumentIngestionService)가 적재 여부를 판단한다.
 */
@Slf4j
@Component
public class InferenceServerDocumentParseClient implements DocumentParseClient {

    private static final String PARSE_PATH = "/parse/document";

    private final RestClient restClient;
    private final int        maxAttempts;
    private final long       retryBackoffMs;
    private final boolean    ocrFallback;

    public InferenceServerDocumentParseClient(RestClient.Builder builder,
                                              AdvisoryParseProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (props.connectTimeoutMs() > 0) factory.setConnectTimeout(props.connectTimeoutMs());
        if (props.readTimeoutMs()    > 0) factory.setReadTimeout(props.readTimeoutMs());

        this.restClient     = builder.requestFactory(factory).baseUrl(props.baseUrl()).build();
        this.maxAttempts    = props.maxAttempts();
        this.retryBackoffMs = props.retryBackoffMs();
        this.ocrFallback    = props.ocrFallback();
    }

    @Override
    public ParseResult parse(byte[] bytes, String filename, DocFormat fmt) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(LoanErrorCode.LOAN_213, "빈 문서");
        }
        String b64 = Base64.getEncoder().encodeToString(bytes);
        DocFormat format = fmt == null ? DocFormat.AUTO : fmt;
        ParseRequest req = new ParseRequest(
                b64,
                filename == null ? "" : filename,
                format.name(),
                ocrFallback,
                "");
        return callWithRetry(req, filename);
    }

    private ParseResult callWithRetry(ParseRequest request, String filename) {
        Throwable lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ParseResult body = restClient.post()
                        .uri(PARSE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(ParseResult.class);
                if (body == null) {
                    throw new BusinessException(LoanErrorCode.LOAN_213, "파싱 사이드카 응답 null");
                }
                return body;
            } catch (BusinessException e) {
                throw e;
            } catch (HttpClientErrorException e) {
                // 4xx — 재시도 무의미(지원하지 않는 포맷·잘못된 요청)
                throw new BusinessException(LoanErrorCode.LOAN_213,
                        "파싱 사이드카 4xx " + e.getStatusCode() + " — file=" + filename);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastEx = e;
                log.warn("[advisory-parse] attempt {}/{} file={}: {}",
                        attempt, maxAttempts, filename, e.getMessage());
                if (attempt < maxAttempts) sleepBackoff(attempt);
            }
        }
        throw new BusinessException(LoanErrorCode.LOAN_212,
                "파싱 사이드카 " + maxAttempts + "회 재시도 실패: "
                        + (lastEx == null ? "원인 미상" : lastEx.getMessage()));
    }

    private void sleepBackoff(int attempt) {
        long delay = retryBackoffMs * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessException(LoanErrorCode.LOAN_212, "재시도 대기 중 인터럽트");
        }
    }

    /** 사이드카 요청 본문(snake_case). */
    record ParseRequest(
            @JsonProperty("document_b64") String documentB64,
            @JsonProperty("filename")     String filename,
            @JsonProperty("doc_format")   String docFormat,
            @JsonProperty("ocr_fallback") boolean ocrFallback,
            @JsonProperty("submission_id") String submissionId
    ) {}
}
