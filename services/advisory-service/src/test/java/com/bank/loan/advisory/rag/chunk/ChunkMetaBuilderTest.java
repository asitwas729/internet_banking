package com.bank.loan.advisory.rag.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkMetaBuilderTest {

    @Test
    void doc_type_와_block_type_을_담는다() {
        Map<String, Object> meta = ChunkMetaBuilder.of("CREDIT_POLICY", BlockType.PARAGRAPH).build();

        assertThat(meta).containsEntry("doc_type", "CREDIT_POLICY");
        assertThat(meta).containsEntry("block_type", "paragraph");
    }

    @Test
    void null_빈값_키는_제외한다() {
        Map<String, Object> meta = ChunkMetaBuilder.of(null, BlockType.TABLE)
                .articleNo("  ")
                .headingPath(List.of())
                .page(null)
                .build();

        assertThat(meta).doesNotContainKeys("doc_type", "article_no", "heading_path", "page");
        assertThat(meta).containsEntry("block_type", "table");
    }

    @Test
    void heading_path_와_article_no_page_를_담는다() {
        Map<String, Object> meta = ChunkMetaBuilder.of("REG", BlockType.PARAGRAPH)
                .headingPath(List.of("제1장", "제1절"))
                .articleNo("제3조")
                .page(5)
                .build();

        assertThat(meta).containsEntry("heading_path", List.of("제1장", "제1절"));
        assertThat(meta).containsEntry("article_no", "제3조");
        assertThat(meta).containsEntry("page", 5);
    }

    @Test
    void 표_메타_table_id_parent_nested_를_담는다() {
        Map<String, Object> meta = ChunkMetaBuilder.of("REG", BlockType.TABLE)
                .tableId(2)
                .parentTableId(1)
                .nested(true)
                .build();

        assertThat(meta).containsEntry("table_id", 2);
        assertThat(meta).containsEntry("parent_table_id", 1);
        assertThat(meta).containsEntry("nested", true);
    }

    @Test
    void nested_false_와_part_total_1_은_키를_넣지_않는다() {
        Map<String, Object> meta = ChunkMetaBuilder.of("REG", BlockType.TABLE)
                .nested(false)
                .part(0, 1)
                .build();

        assertThat(meta).doesNotContainKeys("nested", "part_index", "part_total");
    }

    @Test
    void part_total_2이상이면_part_키를_넣는다() {
        Map<String, Object> meta = ChunkMetaBuilder.of("REG", BlockType.PARAGRAPH)
                .part(1, 3)
                .build();

        assertThat(meta).containsEntry("part_index", 1);
        assertThat(meta).containsEntry("part_total", 3);
    }
}
