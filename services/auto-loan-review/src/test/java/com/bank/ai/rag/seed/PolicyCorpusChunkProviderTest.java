package com.bank.ai.rag.seed;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rule.config.RuleEngineProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCorpusChunkProviderTest {

    private static final RuleEngineProperties RULE_ENGINE_PROPS = new RuleEngineProperties(
            new RuleEngineProperties.HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30, 0.50,
            Map.of(),                       // 매트릭스 비움 — inline 청크만 검증
            0.95, 0.20, true, false);

    private PolicyCorpusChunkProvider provider(Map<String, PolicyIndex.PolicyEntry> inline) {
        return new PolicyCorpusChunkProvider(new InlinePolicyIndex(inline), RULE_ENGINE_PROPS);
    }

    @Test
    void splitText_는_짧으면_단일_파트() {
        assertThat(PolicyCorpusChunkProvider.splitText("짧은 본문.", 1000)).hasSize(1);
    }

    @Test
    void splitText_는_경계_기준_여러_파트로_나눈다() {
        String text = "이 조항은 긴 내용을 담는다. ".repeat(120);   // 약 2400자

        List<String> parts = PolicyCorpusChunkProvider.splitText(text, 1000);

        assertThat(parts.size()).isGreaterThan(1);
        assertThat(parts).allMatch(p -> p.length() <= 1000 * 1.5);
    }

    @Test
    void 짧은_조항은_단일_청크_chunkSeq0() {
        var chunks = provider(Map.of(
                "SHORT", new PolicyIndex.PolicyEntry("DSR 40% 이하", "internal_policy"))).buildChunks();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkSeq()).isZero();
        assertThat(chunks.get(0).metadata()).doesNotContainKey("part_total");
    }

    @Test
    void 긴_조항은_chunkSeq_증가와_part_메타로_분할된다() {
        String longText = "이 조항은 매우 긴 규정 내용을 담고 있다. ".repeat(80);   // > 1200
        var chunks = provider(Map.of(
                "LONG", new PolicyIndex.PolicyEntry(longText, "internal_policy"))).buildChunks();

        assertThat(chunks.size()).isGreaterThan(1);
        // chunkSeq 가 0,1,2… 로 증가 (ON CONFLICT 멱등 키)
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkSeq()).isEqualTo(i);
            assertThat(chunks.get(i).metadata()).containsEntry("part_total", chunks.size());
            assertThat(chunks.get(i).metadata()).containsEntry("article_no", "LONG");
        }
        // 첫 파트 summary 는 본문, 이후는 "(이어짐)"
        assertThat(chunks.get(0).summary()).doesNotStartWith("(이어짐)");
        assertThat(chunks.get(1).summary()).startsWith("(이어짐)");
    }
}
