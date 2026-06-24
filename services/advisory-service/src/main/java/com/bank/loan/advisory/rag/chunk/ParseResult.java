package com.bank.loan.advisory.rag.chunk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 사이드카 {@code POST /parse/document} 응답.
 *
 * @param blocks    정규화된 문서 블록 목록
 * @param degraded  품질 저하 신호(라이브러리 부재·스캔본 OCR·HWP 폴백 등) — 운영 판단용
 * @param pageCount 페이지 수
 * @param engine    실제 사용된 파서 엔진(pymupdf/python-docx/hwpx/hwp5html/libreoffice+pymupdf/...)
 * @param docFormat 사이드카가 최종 판별한 포맷
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParseResult(
        @JsonProperty("blocks")     List<DocumentBlock> blocks,
        @JsonProperty("degraded")   boolean degraded,
        @JsonProperty("page_count") int pageCount,
        @JsonProperty("engine")     String engine,
        @JsonProperty("doc_format") String docFormat
) {
    public ParseResult {
        blocks = blocks == null ? List.of() : blocks;
        engine = engine == null ? "none" : engine;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }
}
