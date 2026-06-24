package com.bank.loan.advisory.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * RAG 벡터 인덱스 유지보수 — IVFFlat {@code lists} 실측 rows 기준 재산정 + REINDEX.
 *
 * <p>대량 인입·백필 후 호출한다. 구조-인지 청킹으로 청크 수가 늘면 V30 시점의 {@code lists}
 * 가 더 이상 최적이 아니므로, pgvector 권장식으로 다시 계산해 인덱스를 재생성한다.
 *
 * <pre>
 *   rows ≤ 1,000,000 →  lists = max(10, ceil(rows / 1000))
 *   rows >  1,000,000 →  lists = ceil(sqrt(rows))
 * </pre>
 *
 * 테이블·인덱스명은 고정 상수(사용자 입력 아님)라 동적 SQL 주입 위험이 없다.
 * CREATE INDEX 는 빌드 동안 쓰기 락을 잡으므로 트래픽이 적은 시간대 호출을 권장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIndexMaintenanceService {

    private final JdbcTemplate jdbcTemplate;

    public ReindexResult reindex() {
        IndexStat chunk = rebuild("advisory_document_chunk", "idx_advisory_document_chunk_embedding");
        IndexStat cases = rebuild("advisory_case_index", "idx_advisory_case_index_embedding");
        log.info("IVFFlat 재인덱싱 완료 — chunk(rows={} lists={}) case(rows={} lists={})",
                chunk.rows(), chunk.lists(), cases.rows(), cases.lists());
        return new ReindexResult(chunk, cases);
    }

    private IndexStat rebuild(String table, String indexName) {
        long rows = count(table);
        int lists = computeLists(rows);
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + indexName);
        jdbcTemplate.execute(
                "CREATE INDEX " + indexName + " ON " + table
                + " USING ivfflat (embedding vector_cosine_ops) WITH (lists = " + lists + ")");
        return new IndexStat(table, rows, lists);
    }

    private long count(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    /** pgvector 권장식으로 lists 산정. */
    static int computeLists(long rows) {
        if (rows > 1_000_000L) {
            return (int) Math.ceil(Math.sqrt((double) rows));
        }
        return Math.max(10, (int) Math.ceil(rows / 1000.0));
    }

    public record IndexStat(String table, long rows, int lists) {}

    public record ReindexResult(IndexStat chunkIndex, IndexStat caseIndex) {}
}
