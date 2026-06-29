package com.bank.loan.advisory.rag.chunk;

import java.util.Map;

/**
 * 구조-인지 청킹 결과 단위.
 *
 * @param text        임베딩·저장 대상 본문
 * @param sectionPath 문서 내 위치(heading 경로, "제1장 &gt; 제2절" 형태) — 길면 INSERT 시 truncate
 * @param meta        chunk_meta(jsonb)에 저장할 키-값 (block_type, heading_path, article_no 등)
 * @param tokenCount  추정 토큰 수(한국어 문자 기준 ÷ 1.5)
 */
public record Chunk(
        String text,
        String sectionPath,
        Map<String, Object> meta,
        int tokenCount
) {
}
