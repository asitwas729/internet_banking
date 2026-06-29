package com.bank.ai.rag.embedding;

import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * SpringAiEmbeddingClient 단위 테스트 — Vertex 미호출, EmbeddingModel 모킹.
 *
 * <p>차원 검증 · 일시 오류 재시도 · 영구(4xx) 즉시 중단 · 배치 분할(250 상한)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiEmbeddingClientTest {

    @Mock
    EmbeddingModel embeddingModel;

    private static final int DIM = 4;

    private final AgentMetricsRecorder metrics = new AgentMetricsRecorder(new SimpleMeterRegistry());

    /** 백오프 0 으로 테스트 지연 제거, 작은 차원/배치로 검증. */
    private SpringAiEmbeddingClient client(int maxAttempts, int batchSize) {
        EmbeddingProperties props = new EmbeddingProperties("vertex", DIM, maxAttempts, 0L, batchSize);
        return new SpringAiEmbeddingClient(embeddingModel, props, metrics);
    }

    private static float[] vec() {
        return new float[DIM];
    }

    @Test
    void embed_정상_단건() {
        when(embeddingModel.embed(anyList())).thenReturn(List.of(vec()));

        float[] result = client(3, 250).embed("주담대 DSR 한도");

        assertThat(result).hasSize(DIM);
        verify(embeddingModel, times(1)).embed(anyList());
    }

    @Test
    void 일시오류_5xx_는_재시도_후_성공() {
        when(embeddingModel.embed(anyList()))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(List.of(vec()));

        float[] result = client(3, 250).embed("재시도 검증");

        assertThat(result).hasSize(DIM);
        verify(embeddingModel, times(3)).embed(anyList());
    }

    @Test
    void 일시오류_재시도_소진시_EMBEDDING_FAILED() {
        when(embeddingModel.embed(anyList()))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client(3, 250).embed("계속 실패"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AiErrorCode.EMBEDDING_FAILED);

        verify(embeddingModel, times(3)).embed(anyList());
    }

    @Test
    void 영구오류_4xx_는_재시도없이_즉시_중단() {
        when(embeddingModel.embed(anyList()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "bad", null, null, null));

        assertThatThrownBy(() -> client(3, 250).embed("형식 오류"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AiErrorCode.EMBEDDING_FAILED);

        verify(embeddingModel, times(1)).embed(anyList());
    }

    @Test
    void 차원_불일치는_DIMENSION_MISMATCH_재시도없음() {
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[DIM + 1]));

        assertThatThrownBy(() -> client(3, 250).embed("차원 검증"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AiErrorCode.EMBEDDING_DIMENSION_MISMATCH);

        verify(embeddingModel, times(1)).embed(anyList());
    }

    @Test
    void embedAll_배치는_batchSize_상한으로_분할() {
        // batchSize=2, 입력 5건 → 2+2+1 = 3 배치 호출
        when(embeddingModel.embed(anyList()))
                .thenAnswer(inv -> {
                    List<String> batch = inv.getArgument(0);
                    return batch.stream().map(t -> vec()).toList();
                });

        List<float[]> result = client(3, 2)
                .embedAll(List.of("a", "b", "c", "d", "e"));

        assertThat(result).hasSize(5);
        verify(embeddingModel, times(3)).embed(anyList());
    }

    @Test
    void embedAll_빈입력은_호출없이_빈리스트() {
        List<float[]> result = client(3, 250).embedAll(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(embeddingModel);
    }
}
