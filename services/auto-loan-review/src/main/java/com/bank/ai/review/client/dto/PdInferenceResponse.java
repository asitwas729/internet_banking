package com.bank.ai.review.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * inference-server 의 {@code POST /predict/pd} 응답 (Phase 1.4-PD).
 *
 * <p>PD 모델은 binary:logistic + isotonic 캘리브레이션을 거친 P(default_within_12m=1) 단일 값을 반환.
 * decision 모델과 분리된 엔드포인트라 모델 미배포 환경에선 503 처리.
 */
public record PdInferenceResponse(
        @JsonProperty("model_version") String modelVersion,
        double threshold,
        boolean calibrated,
        List<Prediction> predictions
) {
    public record Prediction(
            @JsonProperty("pd_score") double pdScore,
            String decision,
            @JsonProperty("shap_top3") List<ShapEntry> shapTop3
    ) {
        /** SHAP 미제공 환경(explain=false)에서 null-safe 접근. */
        public List<ShapEntry> shapTop3OrEmpty() {
            return shapTop3 != null ? shapTop3 : List.of();
        }
    }
}
