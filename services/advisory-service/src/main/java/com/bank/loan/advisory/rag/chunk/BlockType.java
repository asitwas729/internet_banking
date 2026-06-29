package com.bank.loan.advisory.rag.chunk;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 사이드카(/parse/document)가 반환하는 문서 블록 종류.
 *
 * 사이드카 응답의 소문자 문자열(heading/paragraph/...)과 1:1 대응한다.
 * TOC/HEADER/FOOTER 는 청킹 전 제거 대상, 나머지는 청크 본문 후보.
 */
public enum BlockType {
    HEADING,
    PARAGRAPH,
    TABLE,
    TOC,
    HEADER,
    FOOTER,
    LIST;

    /** 사이드카 문자열(소문자) → enum (미상/누락 시 PARAGRAPH 로 폴백). */
    @JsonCreator
    public static BlockType from(String raw) {
        if (raw == null || raw.isBlank()) return PARAGRAPH;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PARAGRAPH;
        }
    }
}
