package com.bank.loan.advisory.rag.chunk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 사이드카(/parse/document)가 반환하는 단일 문서 블록.
 *
 * 사이드카 JSON(snake_case)을 그대로 역직렬화한다. {@link StructureAwareChunker}
 * 가 이 블록 목록을 받아 TOC/HEADER/FOOTER 제거·heading 경로 추적·길이 맞춤 청킹한다.
 *
 * @param type  블록 종류
 * @param text  블록 텍스트(표는 평탄화된 텍스트, 구조는 {@code table} 보존)
 * @param page  페이지 번호(1-base, 없으면 null)
 * @param level heading 깊이(1=최상위, heading 외엔 null)
 * @param seq   문서 내 블록 순번(0-base)
 * @param table 표 구조(TABLE 블록일 때만, 그 외 null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentBlock(
        @JsonProperty("block_type") BlockType type,
        @JsonProperty("text")       String text,
        @JsonProperty("page")       Integer page,
        @JsonProperty("level")      Integer level,
        @JsonProperty("block_seq")  int seq,
        @JsonProperty("table")      TableBlock table
) {
    public DocumentBlock {
        type = type == null ? BlockType.PARAGRAPH : type;
        text = text == null ? "" : text;
    }

    public boolean isTable() {
        return type == BlockType.TABLE;
    }

    public boolean isHeading() {
        return type == BlockType.HEADING;
    }
}
