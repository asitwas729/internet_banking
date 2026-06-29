package com.bank.ai.rag.es.index;

import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 임베딩 재색인(re-embedding) 백필 서비스 — Step 2 (Vertex 실 임베딩 전환).
 *
 * <p>stub 임베딩으로 색인된 기존 코퍼스를 현재 활성 {@link EmbeddingClient}(=Vertex) 로 다시
 * 임베딩해 버전 인덱스(예: {@code kb_policy_v2}) 에 적재한다. policy 외 cases/faq 는 정적 chunk
 * provider 가 없으므로, 코퍼스 무관하게 <b>v1 인덱스를 scroll → {@code chunk_text} 재임베딩 →
 * v2 에 기록</b> 하는 방식으로 통일한다.
 *
 * <p>혼입 방지 원칙:
 * <ul>
 *   <li>기록은 v2(타깃) 에만 — 소스 인덱스는 불변</li>
 *   <li>동일 {@code _id}({@code sourceId_chunkSeq}) 덮어쓰기 → 재실행 멱등</li>
 *   <li>{@code embedding_model} 을 타깃 모델로 태깅</li>
 *   <li>{@link #promote} 는 {@code tgtCount >= srcCount} 게이트 통과 시에만 alias 스왑</li>
 * </ul>
 *
 * <p>{@code ai.rag.backend=es} 에서만 빈 등록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
public class EmbeddingReindexService {

    /** 재색인 후 v2 문서에 기록할 임베딩 모델 식별자. */
    static final String TARGET_MODEL = "text-embedding-005";

    /** 코퍼스 식별자 → v2 인덱스명·매핑 리소스. */
    private static final Map<String, CorpusTarget> TARGETS = Map.of(
            "policy_regulation", new CorpusTarget("kb_policy_v2", "es/mappings/kb_policy_v2.json"),
            "similar_cases", new CorpusTarget("kb_similar_cases_v2", "es/mappings/kb_similar_cases_v2.json"),
            "internal_faq", new CorpusTarget("kb_internal_faq_v2", "es/mappings/kb_internal_faq_v2.json")
    );

    private final EsIndexAdminService admin;
    private final EmbeddingClient embeddingClient;
    private final EsProperties esProps;

    /**
     * 코퍼스의 현재 alias 인덱스를 scroll 해 재임베딩 후 v2 인덱스에 적재.
     *
     * @param corpus 코퍼스 식별자 ("policy_regulation" / "similar_cases" / "internal_faq")
     * @return 재색인 리포트(소스·타깃 인덱스, 재임베딩 건수)
     */
    public ReindexReport reembed(String corpus) throws IOException {
        CorpusTarget target = target(corpus);
        String alias = aliasFor(corpus);
        String srcIndex = admin.currentIndexForAlias(alias);
        if (srcIndex.equals(target.v2Index())) {
            throw new IllegalStateException("이미 v2 가 활성 — 재색인 불필요: alias=" + alias);
        }

        admin.createIfAbsent(target.v2Index(), target.v2Mapping());

        List<ReindexDoc> docs = admin.readAllDocs(srcIndex);
        if (docs.isEmpty()) {
            log.warn("EmbeddingReindexService: 소스 비어 있음 — corpus={} src={}", corpus, srcIndex);
            return new ReindexReport(corpus, srcIndex, target.v2Index(), 0);
        }

        List<String> texts = new ArrayList<>(docs.size());
        for (ReindexDoc doc : docs) {
            texts.add(String.valueOf(doc.source().get("chunk_text")));
        }

        // Vertex 임베딩 — 250 배치 분할은 EmbeddingClient 내부 처리
        List<float[]> vectors = embeddingClient.embedAll(texts);
        if (vectors.size() != docs.size()) {
            throw new IllegalStateException(
                    "임베딩 개수 불일치: docs=" + docs.size() + " vectors=" + vectors.size());
        }

        for (int i = 0; i < docs.size(); i++) {
            ReindexDoc doc = docs.get(i);
            Map<String, Object> newSource = new LinkedHashMap<>(doc.source());
            newSource.put("embedding", toFloatList(vectors.get(i)));
            newSource.put("embedding_model", TARGET_MODEL);
            admin.indexDoc(target.v2Index(), doc.id(), newSource);
        }
        admin.refresh(target.v2Index());

        log.info("EmbeddingReindexService: 재색인 완료 — corpus={} {} → {} ({} 건)",
                corpus, srcIndex, target.v2Index(), docs.size());
        return new ReindexReport(corpus, srcIndex, target.v2Index(), docs.size());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private CorpusTarget target(String corpus) {
        CorpusTarget target = TARGETS.get(corpus);
        if (target == null) {
            throw new IllegalArgumentException("미지원 corpus: " + corpus);
        }
        return target;
    }

    private String aliasFor(String corpus) {
        EsProperties.EsIndexNames idx = esProps.indexes();
        return switch (corpus) {
            case "policy_regulation" -> idx.policy();
            case "similar_cases" -> idx.cases();
            case "internal_faq" -> idx.faq();
            default -> throw new IllegalArgumentException("미지원 corpus: " + corpus);
        };
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) {
            list.add(v);
        }
        return list;
    }

    private record CorpusTarget(String v2Index, String v2Mapping) {}

    /** 재색인 결과 리포트. */
    public record ReindexReport(String corpus, String sourceIndex, String targetIndex, int reembedded) {}
}
