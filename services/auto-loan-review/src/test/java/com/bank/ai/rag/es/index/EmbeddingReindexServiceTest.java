package com.bank.ai.rag.es.index;

import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EmbeddingReindexService 단위 테스트 — ES 미연결, EsIndexAdminService·EmbeddingClient 모킹.
 *
 * <p>검증: 재임베딩이 v2 에만 기록 · 소스 불변 · id 보존(멱등) · embedding_model 태깅 ·
 * promote 건수 게이트.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingReindexServiceTest {

    @Mock
    EsIndexAdminService admin;
    @Mock
    EmbeddingClient embeddingClient;

    private static final EsProperties ES_PROPS = new EsProperties(
            "http://localhost:9200", "", Duration.ofSeconds(2), Duration.ofSeconds(5),
            new EsProperties.EsIndexNames("kb_policy", "kb_similar_cases", "kb_internal_faq"),
            new EsProperties.EsSearchConfig(100, 50, 60));

    private EmbeddingReindexService service() {
        return new EmbeddingReindexService(admin, embeddingClient, ES_PROPS);
    }

    private static ReindexDoc doc(String id, String text) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_id", id.split("_")[0]);
        source.put("chunk_seq", 0);
        source.put("chunk_text", text);
        source.put("embedding", List.of(0.0f, 0.0f));       // 기존 stub 벡터
        source.put("embedding_model", "stub");
        return new ReindexDoc(id, source);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reembed_는_v2에만_새임베딩과_모델로_기록하고_id를_보존() throws Exception {
        when(admin.currentIndexForAlias("kb_policy")).thenReturn("kb_policy_v1");
        when(admin.readAllDocs("kb_policy_v1"))
                .thenReturn(List.of(doc("P1_0", "DSR 한도"), doc("P1_1", "신용점수 기준")));
        when(embeddingClient.embedAll(anyList()))
                .thenReturn(List.of(new float[]{1f, 2f}, new float[]{3f, 4f}));

        var report = service().reembed("policy_regulation");

        // v2 생성 + refresh
        verify(admin).createIfAbsent("kb_policy_v2", "es/mappings/kb_policy_v2.json");
        verify(admin).refresh("kb_policy_v2");

        ArgumentCaptor<String> idxCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> srcCap = ArgumentCaptor.forClass(Map.class);
        verify(admin, times(2)).indexDoc(idxCap.capture(), idCap.capture(), srcCap.capture());

        // 전부 v2 에만 기록 (소스 불변)
        assertThat(idxCap.getAllValues()).containsOnly("kb_policy_v2");
        verify(admin, never()).indexDoc(eq("kb_policy_v1"), any(), any());

        // id 보존(멱등 덮어쓰기)
        assertThat(idCap.getAllValues()).containsExactly("P1_0", "P1_1");

        // 새 임베딩 + 모델 태깅
        Map<String, Object> first = srcCap.getAllValues().get(0);
        assertThat(first.get("embedding")).isEqualTo(List.of(1f, 2f));
        assertThat(first.get("embedding_model")).isEqualTo("text-embedding-005");

        assertThat(report.reembedded()).isEqualTo(2);
        assertThat(report.sourceIndex()).isEqualTo("kb_policy_v1");
        assertThat(report.targetIndex()).isEqualTo("kb_policy_v2");
    }

    @Test
    void reembed_빈소스는_건수0_반환하고_기록없음() throws Exception {
        when(admin.currentIndexForAlias("kb_internal_faq")).thenReturn("kb_internal_faq_v1");
        when(admin.readAllDocs("kb_internal_faq_v1")).thenReturn(List.of());

        var report = service().reembed("internal_faq");

        assertThat(report.reembedded()).isZero();
        verify(admin, never()).indexDoc(any(), any(), any());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void reembed_이미_v2활성이면_예외_재색인안함() throws Exception {
        when(admin.currentIndexForAlias("kb_policy")).thenReturn("kb_policy_v2");

        assertThatThrownBy(() -> service().reembed("policy_regulation"))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(embeddingClient);
        verify(admin, never()).indexDoc(any(), any(), any());
    }

    @Test
    void reembed_미지원_corpus는_IllegalArgument() {
        assertThatThrownBy(() -> service().reembed("unknown_corpus"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(admin, embeddingClient);
    }

    @Test
    void promote_건수게이트_통과시_alias스왑() throws Exception {
        when(admin.currentIndexForAlias("kb_policy")).thenReturn("kb_policy_v1");
        when(admin.docCount("kb_policy_v1")).thenReturn(10L);
        when(admin.docCount("kb_policy_v2")).thenReturn(10L);

        var report = service().promote("policy_regulation");

        verify(admin).swapAlias("kb_policy", "kb_policy_v1", "kb_policy_v2");
        assertThat(report.sourceCount()).isEqualTo(10L);
        assertThat(report.targetCount()).isEqualTo(10L);
        assertThat(report.newIndex()).isEqualTo("kb_policy_v2");
    }

    @Test
    void promote_재색인누락이면_차단하고_스왑안함() throws Exception {
        when(admin.currentIndexForAlias("kb_policy")).thenReturn("kb_policy_v1");
        when(admin.docCount("kb_policy_v1")).thenReturn(10L);
        when(admin.docCount("kb_policy_v2")).thenReturn(7L);

        assertThatThrownBy(() -> service().promote("policy_regulation"))
                .isInstanceOf(IllegalStateException.class);

        verify(admin, never()).swapAlias(any(), any(), any());
    }
}
