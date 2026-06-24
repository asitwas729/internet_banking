package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.AdvisoryDocument;
import com.bank.loan.advisory.dto.DocumentRegisterRequest;
import com.bank.loan.advisory.rag.chunk.BlockType;
import com.bank.loan.advisory.rag.chunk.DocumentBlock;
import com.bank.loan.advisory.rag.chunk.DocumentParseClient;
import com.bank.loan.advisory.rag.chunk.ParseResult;
import com.bank.loan.advisory.rag.chunk.StructureAwareChunker;
import com.bank.loan.advisory.repository.AdvisoryDocumentChunkRepository;
import com.bank.loan.advisory.repository.AdvisoryDocumentRepository;
import com.bank.loan.support.LoanErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock private AdvisoryDocumentRepository      docRepo;
    @Mock private AdvisoryDocumentChunkRepository chunkRepo;
    @Mock private EmbeddingClient                 embeddingClient;
    @Mock private JdbcTemplate                    jdbcTemplate;
    @Mock private TransactionTemplate             transactionTemplate;
    @Mock private DocumentParseClient             parseClient;

    private final StructureAwareChunker chunker = new StructureAwareChunker();
    private final ObjectMapper          objectMapper = new ObjectMapper();
    private DocumentIngestionService    service;

    private static DocumentRegisterRequest req(String content) {
        return new DocumentRegisterRequest(
                "DSR_1", "DSR 기준", "CREDIT_POLICY", "v1",
                "20300101", "20301231", null, null, content);
    }

    @BeforeEach
    void setUp() {
        service = new DocumentIngestionService(docRepo, chunkRepo, embeddingClient,
                jdbcTemplate, transactionTemplate, chunker, parseClient, objectMapper);

        lenient().when(embeddingClient.defaultModelCd()).thenReturn("STUB");
        lenient().when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        lenient().when(docRepo.findByDocCdAndDocVersionAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(docRepo.save(any())).thenAnswer(inv -> {
            AdvisoryDocument arg = inv.getArgument(0);
            return AdvisoryDocument.builder()
                    .docId(1L)
                    .docCd(arg.getDocCd())
                    .docTitle(arg.getDocTitle())
                    .docCategoryCd(arg.getDocCategoryCd())
                    .docVersion(arg.getDocVersion())
                    .activeYn(arg.getActiveYn())
                    .build();
        });
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void content_인입은_chunk_meta_와_section_path_를_INSERT_에_채운다() {
        service.register(req("DSR 70% 초과 시 신용대출 승인 불가."), 9L);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), captor.capture());
        Object[] args = captor.getValue();

        assertThat(args).hasSize(10);
        assertThat(args[3]).isEqualTo("doc");                         // section_path
        String chunkMeta = (String) args[5];                          // chunk_meta(jsonb)
        assertThat(chunkMeta)
                .contains("\"doc_type\":\"CREDIT_POLICY\"")
                .contains("\"block_type\":\"paragraph\"");
    }

    @Test
    void 파일_파싱_결과가_비면_LOAN_213_으로_명확_실패한다() {
        when(parseClient.parse(any(), any(), any()))
                .thenReturn(new ParseResult(List.of(), false, 0, "none", "PDF"));

        assertThatThrownBy(() -> service.registerFile(req(null), "x".getBytes(), "x.pdf", 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_213);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void degraded_여도_블록이_있으면_적재한다() {
        DocumentBlock block = new DocumentBlock(BlockType.PARAGRAPH, "OCR 추출 본문", 1, null, 0, null);
        when(parseClient.parse(any(), any(), any()))
                .thenReturn(new ParseResult(List.of(block), true, 1, "paddleocr-ko", "PDF"));

        service.registerFile(req(null), "scan".getBytes(), "scan.pdf", 9L);

        verify(jdbcTemplate, atLeastOnce()).update(anyString(), any(Object[].class));
    }
}
