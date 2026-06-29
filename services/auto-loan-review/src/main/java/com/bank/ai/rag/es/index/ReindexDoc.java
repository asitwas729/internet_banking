package com.bank.ai.rag.es.index;

import java.util.Map;

/**
 * 재색인 대상 ES 문서 — {@code _id} 와 {@code _source} 원본.
 *
 * <p>재임베딩 시 {@code source} 의 {@code chunk_text} 를 다시 임베딩하고 {@code embedding}·
 * {@code embedding_model} 만 교체한 뒤 동일 {@code id} 로 타깃 인덱스에 기록한다(멱등).
 *
 * @param id     ES 문서 id ({@code sourceId_chunkSeq})
 * @param source ES {@code _source} 맵 (수정 전 원본)
 */
public record ReindexDoc(String id, Map<String, Object> source) {
}
