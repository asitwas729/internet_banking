package com.bank.ai.review.scorer;

import com.bank.ai.review.client.FeatureMapper;
import com.bank.ai.review.client.InferenceClient;
import com.bank.ai.review.client.dto.InferenceRequest;
import com.bank.ai.review.client.dto.InferenceResponse;
import com.bank.ai.review.client.dto.PdInferenceResponse;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * inference-server(Python/XGBoost) HTTP 호출 스코어러.
 *
 * <p>모델 아티팩트({@code data/models/auto_review_v1/}) 가 배포된 환경에서만 사용.
 * 로컬·개발 환경에서는 {@link HeuristicDecisionScorer} 를 기본으로 사용한다.
 *
 * <p>활성화: {@code ai.scoring.engine=inference}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.scoring.engine", havingValue = "inference")
@RequiredArgsConstructor
public class InferenceDecisionScorer implements DecisionScorer {

    private static final String POSITIVE_CLASS = "REJECT";

    private final InferenceClient inferenceClient;
    private final FeatureMapper featureMapper;

    @Override
    public AutoReviewResponse score(AutoReviewRequest req) {
        InferenceRequest payload = InferenceRequest.of(List.of(featureMapper.toDecisionFeatures(req)));

        InferenceResponse decisionRes = inferenceClient.predict(payload);
        if (decisionRes == null || decisionRes.predictions() == null || decisionRes.predictions().isEmpty()) {
            log.error("inference /predict returned empty predictions");
            throw new BusinessException(AiErrorCode.INFERENCE_FAILED);
        }
        InferenceResponse.Prediction dp = decisionRes.predictions().get(0);

        Double pdScore = null;
        String pdModelVersion = null;
        try {
            InferenceRequest pdPayload = InferenceRequest.of(List.of(featureMapper.toPdFeatures(req)));
            PdInferenceResponse pdRes = inferenceClient.predictPd(pdPayload);
            if (pdRes != null && pdRes.predictions() != null && !pdRes.predictions().isEmpty()) {
                pdScore = pdRes.predictions().get(0).pdScore();
                pdModelVersion = pdRes.modelVersion();
            }
        } catch (BusinessException e) {
            log.warn("PD inference unavailable, falling back to decision-only: {}", e.getMessage());
        }

        return new AutoReviewResponse(
                decisionRes.modelVersion(),
                dp.decision(),
                dp.score(),
                dp.proba(),
                pdScore,
                pdModelVersion
        );
    }
}
