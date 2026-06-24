package com.bank.loan.advisory.rag.chunk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 표 블록 — 사이드카 파싱 결과의 구조 보존 표현.
 *
 * @param rows   행×열 셀 텍스트
 * @param html   원본 HTML(가능한 경우, 없으면 빈 문자열)
 * @param nested 셀 안에 중첩된 표(표 안의 표)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableBlock(
        List<List<String>> rows,
        String html,
        List<TableBlock> nested
) {
    public TableBlock {
        rows = rows == null ? List.of() : rows;
        nested = nested == null ? List.of() : nested;
        html = html == null ? "" : html;
    }

    public boolean hasNested() {
        return nested != null && !nested.isEmpty();
    }

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }
}
