package com.bank.loan.advisory.rag.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 구조-인지 청커 — 사이드카 블록 목록을 검색·임베딩용 청크로 변환한다.
 *
 * <p>핵심 원칙: <b>블록 경계 안에서만</b> 길이를 맞춘다. 표는 섹션 본문과 섞지 않고
 * 별도 청크로, heading 경로/조항번호/페이지를 chunk_meta 로 보존한다.
 *
 * 처리 순서:
 * <ol>
 *   <li>TOC/HEADER/FOOTER 블록 제거(사이드카 태그 + 휴리스틱 보강)</li>
 *   <li>HEADING 스택으로 heading_path 추적, 조항(제N조/번호목차) 경계 인식</li>
 *   <li>섹션 본문이 길면 {@link #slidingWindow} 로 1000/50 윈도우 분할(섹션 경계 불침범)</li>
 *   <li>표는 작으면 통째, 크면 헤더행 prepend + 행묶음. 중첩표는 table_id/parent_table_id 로 평탄화</li>
 * </ol>
 *
 * 싱글톤 빈이므로 호출별 상태는 내부 {@link Run} 에 격리해 스레드 안전을 유지한다.
 */
@Component
public class StructureAwareChunker {

    // 청크 크기·overlap: ChunkSizeRetrievalEvalTest 그리드 실측으로 1000/50 채택
    // (800/100 대비 MRR@5 +35%, nDCG@5 +50%, Recall@10 +18pp — docs/plan/16_chunk_size_eval.md).
    static final int CHUNK_SIZE    = 1000;
    static final int CHUNK_OVERLAP = 50;

    /** 표 1청크 허용 최대 문자수(초과 시 행묶음 분할). */
    static final int TABLE_INLINE_LIMIT = 700;

    /** "제3조", "제 12 조" 등 조항 마커. */
    private static final Pattern ARTICLE_JO = Pattern.compile("^제\\s*\\d+\\s*조");
    /** "1.", "1.2", "2.3.1 " 등 번호 목차(섹션 경계). */
    private static final Pattern ARTICLE_NUM = Pattern.compile("^\\d+(\\.\\d+)*[.\\s]");
    /** "가.", "나)" 등 하위 항목(메타만, 경계 아님). */
    private static final Pattern ARTICLE_SUB = Pattern.compile("^[가-힣]\\s*[.)]");
    /** 페이지번호만 있는 라인(꼬리말 휴리스틱). */
    private static final Pattern PAGE_NUM = Pattern.compile("^\\s*[-–]?\\s*\\d{1,4}\\s*[-–]?\\s*$");

    public List<Chunk> chunk(List<DocumentBlock> blocks, DocMeta docMeta) {
        Run run = new Run(docMeta == null ? null : docMeta.docType());
        if (blocks != null) {
            for (DocumentBlock block : blocks) {
                run.consume(block);
            }
        }
        run.flushSection();
        return run.out;
    }

    /**
     * 텍스트를 size 자 단위로 overlap 자 중복 포함해 분할.
     * 기존 DocumentIngestionService.splitChunks 의 윈도우 로직을 승격해 재사용.
     */
    static List<String> slidingWindow(String content, int size, int overlap) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isEmpty()) return result;
        int pos = 0;
        while (pos < content.length()) {
            int end = Math.min(pos + size, content.length());
            result.add(content.substring(pos, end));
            if (end >= content.length()) break;
            pos += size - overlap;
        }
        return result;
    }

    static int estimateTokens(String text) {
        return Math.max(1, (int) (text.length() / 1.5));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 호출별 청킹 상태
    // ──────────────────────────────────────────────────────────────────────

    private static final class Run {

        private final String docType;
        private final List<Chunk> out = new ArrayList<>();
        private final Deque<Heading> headingStack = new ArrayDeque<>();

        private final StringBuilder buffer = new StringBuilder();
        private Integer bufferPage;
        private String currentArticle;
        private int tableCounter = 0;

        Run(String docType) {
            this.docType = docType;
        }

        void consume(DocumentBlock block) {
            BlockType type = block.type();

            // 1. 노이즈 블록 제거 (태그 + 휴리스틱)
            if (type == BlockType.TOC || type == BlockType.HEADER || type == BlockType.FOOTER) {
                return;
            }
            if (type != BlockType.TABLE && isNoise(block.text())) {
                return;
            }

            switch (type) {
                case HEADING -> {
                    flushSection();                        // 이전 섹션 컨텍스트로 먼저 flush
                    pushHeading(block);
                    currentArticle = detectArticle(block.text(), currentArticle);
                    appendLine(block.text(), block.page()); // heading 텍스트도 섹션에 포함(검색성)
                }
                case TABLE -> {
                    flushSection();                        // 표는 본문과 분리
                    if (block.table() != null) {
                        emitTable(block.table(), block.page(), null);
                    }
                }
                case PARAGRAPH, LIST -> {
                    String text = block.text();
                    if (isArticleBoundary(text) && buffer.length() > 0) {
                        flushSection();
                        currentArticle = detectArticle(text, currentArticle);
                    } else {
                        currentArticle = detectArticle(text, currentArticle);
                    }
                    appendLine(text, block.page());
                }
                default -> appendLine(block.text(), block.page());
            }
        }

        private void appendLine(String text, Integer page) {
            if (text == null || text.isBlank()) return;
            if (buffer.length() == 0) bufferPage = page;
            if (buffer.length() > 0) buffer.append('\n');
            buffer.append(text.strip());
        }

        /** 누적된 섹션 본문을 청크로 방출(길면 윈도우 분할). */
        void flushSection() {
            if (buffer.length() == 0) return;
            String body = buffer.toString();
            List<String> windows = body.length() <= CHUNK_SIZE
                    ? List.of(body)
                    : slidingWindow(body, CHUNK_SIZE, CHUNK_OVERLAP);

            List<String> headingPath = headingPath();
            String sectionPath = sectionPath(headingPath);
            int total = windows.size();
            for (int i = 0; i < total; i++) {
                String text = windows.get(i);
                var meta = ChunkMetaBuilder.of(docType, BlockType.PARAGRAPH)
                        .headingPath(headingPath)
                        .articleNo(currentArticle)
                        .page(bufferPage)
                        .part(i, total)
                        .build();
                out.add(new Chunk(text, sectionPath, meta, estimateTokens(text)));
            }
            buffer.setLength(0);
            bufferPage = null;
        }

        // ── 표 청크화 ──────────────────────────────────────────────────────

        private void emitTable(TableBlock table, Integer page, Integer parentTableId) {
            int tableId = ++tableCounter;
            List<List<String>> rows = table.rows();
            boolean nested = parentTableId != null;

            if (!rows.isEmpty()) {
                String full = flattenRows(rows, 0);
                if (full.length() <= TABLE_INLINE_LIMIT) {
                    addTableChunk(full, page, tableId, parentTableId, nested, 0, 1);
                } else {
                    emitLargeTable(rows, page, tableId, parentTableId, nested);
                }
            }

            // 중첩표는 별도 청크로 평탄화(부모 table_id 연결)
            for (TableBlock inner : table.nested()) {
                emitTable(inner, page, tableId);
            }
        }

        private void emitLargeTable(List<List<String>> rows, Integer page,
                                    int tableId, Integer parentTableId, boolean nested) {
            String header = rowToText(rows.get(0));
            List<String> parts = new ArrayList<>();
            StringBuilder grp = new StringBuilder(header);
            for (int r = 1; r < rows.size(); r++) {
                String line = rowToText(rows.get(r));
                if (grp.length() + line.length() + 1 > TABLE_INLINE_LIMIT && grp.length() > header.length()) {
                    parts.add(grp.toString());
                    grp = new StringBuilder(header);     // 그룹마다 헤더행 재첨부(문맥 보존)
                }
                grp.append('\n').append(line);
            }
            if (grp.length() > header.length()) parts.add(grp.toString());

            int total = parts.size();
            for (int i = 0; i < total; i++) {
                addTableChunk(parts.get(i), page, tableId, parentTableId, nested, i, total);
            }
        }

        private void addTableChunk(String text, Integer page, int tableId,
                                   Integer parentTableId, boolean nested, int idx, int total) {
            List<String> headingPath = headingPath();
            var meta = ChunkMetaBuilder.of(docType, BlockType.TABLE)
                    .headingPath(headingPath)
                    .articleNo(currentArticle)
                    .page(page)
                    .tableId(tableId)
                    .parentTableId(parentTableId)
                    .nested(nested)
                    .part(idx, total)
                    .build();
            out.add(new Chunk(text, sectionPath(headingPath), meta, estimateTokens(text)));
        }

        private static String flattenRows(List<List<String>> rows, int from) {
            StringBuilder sb = new StringBuilder();
            for (int r = from; r < rows.size(); r++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(rowToText(rows.get(r)));
            }
            return sb.toString();
        }

        private static String rowToText(List<String> row) {
            List<String> cells = new ArrayList<>();
            for (String c : row) {
                if (c != null && !c.isBlank()) cells.add(c.strip());
            }
            return String.join(" | ", cells);
        }

        // ── heading 스택 ───────────────────────────────────────────────────

        private void pushHeading(DocumentBlock block) {
            int level = block.level() != null ? block.level() : 1;
            while (!headingStack.isEmpty() && headingStack.peekLast().level >= level) {
                headingStack.removeLast();
            }
            headingStack.addLast(new Heading(level, block.text().strip()));
        }

        private List<String> headingPath() {
            List<String> path = new ArrayList<>(headingStack.size());
            for (Heading h : headingStack) path.add(h.text);
            return path;
        }

        private static String sectionPath(List<String> headingPath) {
            return headingPath.isEmpty() ? "doc" : String.join(" > ", headingPath);
        }

        // ── 휴리스틱 ────────────────────────────────────────────────────────

        private static boolean isNoise(String text) {
            if (text == null) return true;
            String t = text.strip();
            return t.isEmpty() || PAGE_NUM.matcher(t).matches();
        }

        private static boolean isArticleBoundary(String text) {
            String t = text.strip();
            return ARTICLE_JO.matcher(t).find() || ARTICLE_NUM.matcher(t).find();
        }

        private static String detectArticle(String text, String fallback) {
            String t = text.strip();
            var mJo = ARTICLE_JO.matcher(t);
            if (mJo.find()) return mJo.group().replaceAll("\\s+", "");
            var mNum = ARTICLE_NUM.matcher(t);
            if (mNum.find()) return mNum.group().strip();
            var mSub = ARTICLE_SUB.matcher(t);
            if (mSub.find()) return mSub.group().strip();
            return fallback;
        }
    }

    private record Heading(int level, String text) {
    }
}
