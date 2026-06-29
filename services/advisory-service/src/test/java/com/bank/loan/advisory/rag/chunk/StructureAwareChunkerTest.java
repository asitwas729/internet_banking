package com.bank.loan.advisory.rag.chunk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructureAwareChunkerTest {

    private final StructureAwareChunker chunker = new StructureAwareChunker();
    private final DocMeta docMeta = new DocMeta("CREDIT_POLICY");

    private static DocumentBlock heading(String text, int level) {
        return new DocumentBlock(BlockType.HEADING, text, 1, level, 0, null);
    }

    private static DocumentBlock para(String text) {
        return new DocumentBlock(BlockType.PARAGRAPH, text, 1, null, 0, null);
    }

    private static DocumentBlock block(BlockType type, String text) {
        return new DocumentBlock(type, text, 1, null, 0, null);
    }

    private static DocumentBlock table(TableBlock t) {
        return new DocumentBlock(BlockType.TABLE, "", 1, null, 0, t);
    }

    @Test
    void TOC_HEADER_FOOTER_와_페이지번호_라인은_제거된다() {
        List<DocumentBlock> blocks = List.of(
                block(BlockType.TOC, "목차"),
                block(BlockType.HEADER, "○○은행 내부규정"),
                para("실제 본문 내용입니다."),
                block(BlockType.FOOTER, "기밀"),
                para("- 12 -")            // 페이지번호 휴리스틱
        );

        List<Chunk> chunks = chunker.chunk(blocks, docMeta);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("실제 본문 내용입니다.");
    }

    @Test
    void heading_경로가_chunk_meta_와_section_path_에_반영된다() {
        List<DocumentBlock> blocks = List.of(
                heading("제1장 총칙", 1),
                heading("제1절 목적", 2),
                para("이 규정은 여신 심사 기준을 정한다.")
        );

        List<Chunk> chunks = chunker.chunk(blocks, docMeta);

        Chunk last = chunks.get(chunks.size() - 1);
        assertThat(last.text()).contains("이 규정은 여신 심사 기준을 정한다.");
        assertThat(last.sectionPath()).isEqualTo("제1장 총칙 > 제1절 목적");
        assertThat(last.meta()).containsEntry("heading_path", List.of("제1장 총칙", "제1절 목적"));
        assertThat(last.meta()).containsEntry("doc_type", "CREDIT_POLICY");
    }

    @Test
    void 조항_제N조_경계로_분리되고_article_no_가_기록된다() {
        List<DocumentBlock> blocks = List.of(
                para("제1조 목적. 이 규정은 목적을 정한다."),
                para("제2조 정의. 용어의 뜻은 다음과 같다.")
        );

        List<Chunk> chunks = chunker.chunk(blocks, docMeta);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).meta()).containsEntry("article_no", "제1조");
        assertThat(chunks.get(1).meta()).containsEntry("article_no", "제2조");
    }

    @Test
    void 긴_섹션은_윈도우로_분할되고_part_메타가_붙는다() {
        String longText = "가".repeat(2000);   // 경계 없는 2000자 → 800/100 윈도우
        List<Chunk> chunks = chunker.chunk(List.of(para(longText)), docMeta);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).meta()).containsKey("part_total");
        assertThat(chunks.get(0).text().length()).isLessThanOrEqualTo(StructureAwareChunker.CHUNK_SIZE);
        // 모든 파트가 동일 섹션 경로
        assertThat(chunks).allMatch(c -> c.sectionPath().equals(chunks.get(0).sectionPath()));
    }

    @Test
    void 작은_표는_통째로_table_블록_1청크가_된다() {
        TableBlock t = new TableBlock(
                List.of(List.of("항목", "값"), List.of("DSR", "70%")), "", List.of());

        List<Chunk> chunks = chunker.chunk(List.of(table(t)), docMeta);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).meta()).containsEntry("block_type", "table");
        assertThat(chunks.get(0).meta()).containsEntry("table_id", 1);
        assertThat(chunks.get(0).text()).contains("DSR | 70%");
    }

    @Test
    void 중첩표는_parent_table_id_와_nested_메타로_평탄화된다() {
        TableBlock inner = new TableBlock(List.of(List.of("세부", "내용")), "", List.of());
        TableBlock outer = new TableBlock(
                List.of(List.of("구분", "상세")), "", List.of(inner));

        List<Chunk> chunks = chunker.chunk(List.of(table(outer)), docMeta);

        assertThat(chunks).hasSize(2);
        Chunk innerChunk = chunks.get(1);
        assertThat(innerChunk.meta()).containsEntry("parent_table_id", 1);
        assertThat(innerChunk.meta()).containsEntry("nested", true);
    }

    @Test
    void 큰_표는_헤더행_prepend_하며_행묶음으로_분할된다() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("코드", "설명"));
        for (int i = 0; i < 80; i++) {
            rows.add(List.of("C" + i, "설명".repeat(10)));
        }
        TableBlock big = new TableBlock(rows, "", List.of());

        List<Chunk> chunks = chunker.chunk(List.of(table(big)), docMeta);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allMatch(c -> c.text().startsWith("코드 | 설명"));
    }
}
