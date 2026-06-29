package com.bank.ai.review.client.dto;

import java.util.List;
import java.util.Map;

/**
 * inference-server 의 POST /predict 입력. features 는 raw key-value map 의 리스트.
 * 키는 Python 측 snake_case 컬럼명과 일치해야 한다.
 *
 * <p>explain=true 이면 서버가 SHAP top-k 를 계산해 응답에 포함(단건 latency +150ms 예상).
 * 배치 처리나 캐시 사전 적재 시엔 {@link #withoutShap} 로 비활성화.
 */
public record InferenceRequest(
        List<Map<String, Object>> features,
        boolean explain
) {
    /** SHAP 포함(explain=true). */
    public static InferenceRequest of(List<Map<String, Object>> features) {
        return new InferenceRequest(features, true);
    }

    /** SHAP 제외(explain=false). */
    public static InferenceRequest withoutShap(List<Map<String, Object>> features) {
        return new InferenceRequest(features, false);
    }
}
