package com.bank.ai.review.client;

import com.bank.ai.review.client.dto.InferenceRequest;
import com.bank.ai.review.client.dto.InferenceResponse;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * InferenceClient 의 SHAP 역직렬화 · 오류코드 세분화 · explain 직렬화 검증.
 * MockRestServiceServer 로 inference-server 응답을 가짜로 주입.
 */
class InferenceClientShapTest {

    private static final List<Map<String, Object>> FEATURES =
            List.of(Map.of("credit_score_proxy", 720, "dsr", 0.28));

    private MockRestServiceServer server;
    private InferenceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InferenceClient(builder, "http://inference");
    }

    @Test
    void shouldDeserializeShapTop3FromResponse() {
        String json = """
                {"model_version":"hmda_v1","predictions":[
                  {"decision":"APPROVE","score":0.82,"proba":{"APPROVE":0.82,"REJECT":0.18},
                   "shap_top3":[{"feature":"credit_score_proxy","shap_value":0.31},
                                {"feature":"dsr","shap_value":-0.18}]}]}""";
        server.expect(requestTo("http://inference/predict"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        InferenceResponse.Prediction pred =
                client.predict(InferenceRequest.of(FEATURES)).predictions().get(0);

        assertThat(pred.shapTop3()).hasSize(2);
        assertThat(pred.shapTop3().get(0).feature()).isEqualTo("credit_score_proxy");
        assertThat(pred.shapTop3().get(0).shapValue()).isEqualTo(0.31);
        assertThat(pred.shapTop3().get(1).shapValue()).isEqualTo(-0.18);
        server.verify();
    }

    @Test
    void shouldReturnEmptyShapWhenNull() {
        String json = """
                {"model_version":"hmda_v1","predictions":[
                  {"decision":"APPROVE","score":0.82,"proba":{"APPROVE":0.82,"REJECT":0.18}}]}""";
        server.expect(requestTo("http://inference/predict"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        InferenceResponse.Prediction pred =
                client.predict(InferenceRequest.of(FEATURES)).predictions().get(0);

        assertThat(pred.shapTop3()).isNull();
        assertThat(pred.shapTop3OrEmpty()).isEmpty();
    }

    @Test
    void shouldThrowInvalidFeatureOn422() {
        server.expect(requestTo("http://inference/predict"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        Throwable t = catchThrowable(() -> client.predict(InferenceRequest.of(FEATURES)));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getErrorCode())
                .isEqualTo(AiErrorCode.INFERENCE_INVALID_FEATURE);
    }

    @Test
    void shouldThrowUnavailableOn503() {
        server.expect(requestTo("http://inference/predict"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        Throwable t = catchThrowable(() -> client.predict(InferenceRequest.of(FEATURES)));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getErrorCode())
                .isEqualTo(AiErrorCode.INFERENCE_UNAVAILABLE);
    }

    @Test
    void shouldIncludeExplainFieldInRequest() {
        String json = """
                {"model_version":"hmda_v1","predictions":[
                  {"decision":"APPROVE","score":0.82,"proba":{"APPROVE":0.82,"REJECT":0.18}}]}""";
        server.expect(requestTo("http://inference/predict"))
                .andExpect(jsonPath("$.explain").value(true))
                .andExpect(jsonPath("$.features").isArray())
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        client.predict(InferenceRequest.of(FEATURES));
        server.verify();
    }
}
