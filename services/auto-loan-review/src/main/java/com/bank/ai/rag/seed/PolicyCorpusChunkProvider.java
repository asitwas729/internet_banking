package com.bank.ai.rag.seed;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rule.config.RuleEngineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정책 코퍼스 P1 청크 생성기 — 백엔드 중립.
 *
 * <p>{@code ai.policy.inline} 정책 + {@code ai.rule-engine.pd-threshold-matrix} 셀을
 * {@link PolicyChunk} 목록으로 변환한다. pgvector({@link PolicyCorpusSeedLoader}) 와
 * ES({@code EsPolicyCorpusSeedLoader}) 양쪽이 동일 청크를 공유하도록 적재 sink 와 분리.
 *
 * <p>{@code matrix_coord} 는 ES {@code kb_policy} 매핑의 {@code keyword} 타입과 호환되도록
 * {@code "product/segment"} 문자열로 표현한다.
 */
@Component
@RequiredArgsConstructor
public class PolicyCorpusChunkProvider {

    private final InlinePolicyIndex policyIndex;
    private final RuleEngineProperties ruleEngineProps;

    /** 인라인 정책 + 매트릭스 셀 전체 청크. */
    public List<PolicyChunk> buildChunks() {
        List<PolicyChunk> chunks = new ArrayList<>();
        chunks.addAll(inlinePolicyChunks());
        chunks.addAll(matrixCellChunks());
        return chunks;
    }

    /** 조항 1개가 이 길이를 넘으면 분할 청킹한다. */
    static final int ARTICLE_SPLIT_THRESHOLD = 1200;
    /** 분할 시 파트당 목표 길이. */
    static final int ARTICLE_PART_TARGET = 1000;

    private List<PolicyChunk> inlinePolicyChunks() {
        List<PolicyChunk> chunks = new ArrayList<>();
        for (var entry : policyIndex.inline().entrySet()) {
            String id = entry.getKey();
            PolicyIndex.PolicyEntry policy = entry.getValue();
            String text = policy.text();

            if (text == null || text.length() <= ARTICLE_SPLIT_THRESHOLD) {
                String chunkText = "[정책] %s — %s\n\n%s".formatted(policy.source(), id, text);
                chunks.add(new PolicyChunk(id, 0, chunkText, text, inlineMeta(policy.source(), id, 0, 1)));
                continue;
            }

            // 긴 조항 — 줄/문장 경계 우선 분할. chunkSeq 0,1,2… 로 ON CONFLICT 멱등 유지.
            List<String> parts = splitText(text, ARTICLE_PART_TARGET);
            int total = parts.size();
            for (int i = 0; i < total; i++) {
                String part = parts.get(i);
                String chunkText = "[정책] %s — %s (%d/%d)\n\n%s"
                        .formatted(policy.source(), id, i + 1, total, part);
                String summary = i == 0 ? part : "(이어짐) " + id;
                chunks.add(new PolicyChunk(id, i, chunkText, summary,
                        inlineMeta(policy.source(), id, i, total)));
            }
        }
        return chunks;
    }

    private static Map<String, Object> inlineMeta(String source, String id, int partIndex, int partTotal) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        metadata.put("article_no", id);
        metadata.put("tags", List.of("policy", "inline"));
        if (partTotal > 1) {
            metadata.put("part_index", partIndex);
            metadata.put("part_total", partTotal);
        }
        return metadata;
    }

    /**
     * 긴 텍스트를 target 길이 기준 문장/줄 경계 우선으로 분할한다.
     * 단일 문장이 target 의 1.5배를 넘으면 강제로 잘라 무한 누적을 막는다.
     */
    static List<String> splitText(String text, int target) {
        List<String> parts = new ArrayList<>();
        String[] units = text.split("(?<=[\\n。.!?])");
        StringBuilder cur = new StringBuilder();
        for (String u : units) {
            if (cur.length() > 0 && cur.length() + u.length() > target) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(u);
            while (cur.length() > target * 1.5) {
                parts.add(cur.substring(0, target).strip());
                cur.delete(0, target);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString().strip());
        return parts.isEmpty() ? List.of(text) : parts;
    }

    private List<PolicyChunk> matrixCellChunks() {
        List<PolicyChunk> chunks = new ArrayList<>();
        for (var productEntry : ruleEngineProps.pdThresholdMatrix().entrySet()) {
            String product = productEntry.getKey();
            for (var segmentEntry : productEntry.getValue().entrySet()) {
                String segment = segmentEntry.getKey();
                double threshold = segmentEntry.getValue();

                String sourceId = "MATRIX_%s_%s".formatted(product, segment.toUpperCase());
                String chunkText = """
                        [정책 매트릭스] %s / %s
                        PD 임계치: %.3f
                        상품·세그먼트별 PD 임계치 상한 기준. 신용정책위원회 분기 의결 값.
                        적용: product=%s, segment=%s
                        """.formatted(product, segment, threshold, product, segment).trim();
                String summary = "%s %s PD 임계치 %.3f".formatted(product, segment, threshold);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", "policy_matrix");
                metadata.put("product", product);
                metadata.put("segment", segment);
                metadata.put("matrix_coord", "%s/%s".formatted(product, segment));
                metadata.put("tags", List.of("matrix", "pd_threshold"));

                chunks.add(new PolicyChunk(sourceId, 0, chunkText, summary, metadata));
            }
        }
        return chunks;
    }

    /**
     * 백엔드 중립 정책 청크.
     *
     * @param sourceId  원본 키
     * @param chunkSeq  청크 순번
     * @param chunkText 본문 (검색·임베딩 대상)
     * @param summary   요약 (LLM 입력용)
     * @param metadata  메타데이터 (matrix_coord 는 "product/segment" 문자열)
     */
    public record PolicyChunk(
            String sourceId,
            int chunkSeq,
            String chunkText,
            String summary,
            Map<String, Object> metadata
    ) {}
}
