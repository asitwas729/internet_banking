package com.bank.ai.review.client;

import com.bank.ai.review.client.dto.InferenceRequest;
import com.bank.ai.review.client.dto.InferenceResponse;
import com.bank.ai.review.client.dto.PdInferenceResponse;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class InferenceClient {

    private final RestClient restClient;

    public InferenceClient(
            RestClient.Builder builder,
            @Value("${ai.inference.base-url:http://localhost:8090}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        log.info("InferenceClient configured: baseUrl={}", baseUrl);
    }

    /** decision 모델 — HMDA APPROVE/REJECT 분류. Phase 1.10 호환 alias `/predict`. */
    public InferenceResponse predict(InferenceRequest req) {
        try {
            return restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .onStatus(status -> status.value() == 503, (request, response) -> {
                        log.error("inference /predict unavailable: status=503");
                        throw new BusinessException(AiErrorCode.INFERENCE_UNAVAILABLE);
                    })
                    .onStatus(status -> status.value() == 422, (request, response) -> {
                        log.error("inference /predict invalid feature: status=422");
                        throw new BusinessException(AiErrorCode.INFERENCE_INVALID_FEATURE);
                    })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        log.error("inference /predict error: status={}", response.getStatusCode());
                        throw new BusinessException(AiErrorCode.INFERENCE_FAILED);
                    })
                    .body(InferenceResponse.class);
        } catch (ResourceAccessException e) {
            log.error("inference server unreachable: {}", e.getMessage());
            throw new BusinessException(AiErrorCode.INFERENCE_UNAVAILABLE);
        }
    }

    /**
     * PD 모델 — homecredit binary:logistic + isotonic 캘리브레이션.
     *
     * <p>503(PD 모델 미배포)→INFERENCE_UNAVAILABLE, 422(피처 오류)→INFERENCE_INVALID_FEATURE,
     * 그 외 4xx/5xx→INFERENCE_FAILED. 모두 {@link BusinessException} 이므로 호출 측이 catch 해
     * decision-only fallback 으로 진행한다.
     */
    public PdInferenceResponse predictPd(InferenceRequest req) {
        try {
            return restClient.post()
                    .uri("/predict/pd")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .onStatus(status -> status.value() == 503, (request, response) -> {
                        log.warn("inference /predict/pd unavailable: status=503");
                        throw new BusinessException(AiErrorCode.INFERENCE_UNAVAILABLE);
                    })
                    .onStatus(status -> status.value() == 422, (request, response) -> {
                        log.warn("inference /predict/pd invalid feature: status=422");
                        throw new BusinessException(AiErrorCode.INFERENCE_INVALID_FEATURE);
                    })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        log.warn("inference /predict/pd error: status={}", response.getStatusCode());
                        throw new BusinessException(AiErrorCode.INFERENCE_FAILED);
                    })
                    .body(PdInferenceResponse.class);
        } catch (ResourceAccessException e) {
            log.error("inference server unreachable (PD): {}", e.getMessage());
            throw new BusinessException(AiErrorCode.INFERENCE_UNAVAILABLE);
        }
    }
}
