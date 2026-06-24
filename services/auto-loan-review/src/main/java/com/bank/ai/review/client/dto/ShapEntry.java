package com.bank.ai.review.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * inference-server 의 {@code shap_top3} 배열 원소.
 *
 * <p>feature: 피처명(snake_case), shapValue: SHAP 기여값
 * (decision 모델 기준 양수=positive 클래스 기여, 음수=반대 기여).
 */
public record ShapEntry(
        String feature,
        @JsonProperty("shap_value") double shapValue
) {
}
