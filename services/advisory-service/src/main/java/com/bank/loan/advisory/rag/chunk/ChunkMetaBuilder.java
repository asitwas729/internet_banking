package com.bank.loan.advisory.rag.chunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * chunk_meta(jsonb) 키-값 빌더.
 *
 * null/빈 값은 넣지 않아 jsonb 를 간결하게 유지한다. 키 순서는 삽입 순서 보존.
 */
public final class ChunkMetaBuilder {

    private final Map<String, Object> meta = new LinkedHashMap<>();

    private ChunkMetaBuilder(String docType, BlockType blockType) {
        putIfText("doc_type", docType);
        if (blockType != null) {
            meta.put("block_type", blockType.name().toLowerCase());
        }
    }

    public static ChunkMetaBuilder of(String docType, BlockType blockType) {
        return new ChunkMetaBuilder(docType, blockType);
    }

    public ChunkMetaBuilder headingPath(List<String> headingPath) {
        if (headingPath != null && !headingPath.isEmpty()) {
            meta.put("heading_path", List.copyOf(headingPath));
        }
        return this;
    }

    public ChunkMetaBuilder articleNo(String articleNo) {
        putIfText("article_no", articleNo);
        return this;
    }

    public ChunkMetaBuilder page(Integer page) {
        if (page != null) meta.put("page", page);
        return this;
    }

    public ChunkMetaBuilder tableId(Integer tableId) {
        if (tableId != null) meta.put("table_id", tableId);
        return this;
    }

    public ChunkMetaBuilder parentTableId(Integer parentTableId) {
        if (parentTableId != null) meta.put("parent_table_id", parentTableId);
        return this;
    }

    public ChunkMetaBuilder nested(boolean nested) {
        if (nested) meta.put("nested", true);
        return this;
    }

    /** 다중 윈도우 분할 시 파트 정보(part_index/part_total). */
    public ChunkMetaBuilder part(int index, int total) {
        if (total > 1) {
            meta.put("part_index", index);
            meta.put("part_total", total);
        }
        return this;
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(meta);
    }

    private void putIfText(String key, String value) {
        if (value != null && !value.isBlank()) {
            meta.put(key, value);
        }
    }
}
