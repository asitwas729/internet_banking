package com.bank.loan.advisory.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagIndexMaintenanceServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private RagIndexMaintenanceService service;

    @Test
    void computeLists_는_pgvector_권장식을_따른다() {
        assertThat(RagIndexMaintenanceService.computeLists(0)).isEqualTo(10);      // 하한 10
        assertThat(RagIndexMaintenanceService.computeLists(5_000)).isEqualTo(10);  // ceil(5000/1000)=5 → max(10,5)=10
        assertThat(RagIndexMaintenanceService.computeLists(15_000)).isEqualTo(15); // ceil(15000/1000)
        assertThat(RagIndexMaintenanceService.computeLists(4_000_000))
                .isEqualTo((int) Math.ceil(Math.sqrt(4_000_000)));                 // 2000
    }

    @Test
    void reindex_는_실측_rows_로_lists_를_정해_DROP_CREATE_한다() {
        when(jdbcTemplate.queryForObject(eq("SELECT count(*) FROM advisory_document_chunk"), eq(Long.class)))
                .thenReturn(15_000L);
        when(jdbcTemplate.queryForObject(eq("SELECT count(*) FROM advisory_case_index"), eq(Long.class)))
                .thenReturn(800L);

        var result = service.reindex();

        assertThat(result.chunkIndex().lists()).isEqualTo(15);   // 15000 rows
        assertThat(result.caseIndex().lists()).isEqualTo(10);    // 800 rows → 하한 10

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(4)).execute(sql.capture());
        List<String> stmts = sql.getAllValues();
        assertThat(stmts).anyMatch(s -> s.contains("DROP INDEX IF EXISTS idx_advisory_document_chunk_embedding"));
        assertThat(stmts).anyMatch(s ->
                s.contains("CREATE INDEX idx_advisory_document_chunk_embedding")
                && s.contains("lists = 15"));
        assertThat(stmts).anyMatch(s ->
                s.contains("CREATE INDEX idx_advisory_case_index_embedding")
                && s.contains("lists = 10"));
    }
}
