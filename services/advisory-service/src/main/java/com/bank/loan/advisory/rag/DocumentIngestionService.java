package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.AdvisoryDocument;
import com.bank.loan.advisory.dto.DocumentRegisterRequest;
import com.bank.loan.advisory.dto.DocumentRegisterResponse;
import com.bank.loan.advisory.rag.chunk.BlockType;
import com.bank.loan.advisory.rag.chunk.Chunk;
import com.bank.loan.advisory.rag.chunk.DocFormat;
import com.bank.loan.advisory.rag.chunk.DocMeta;
import com.bank.loan.advisory.rag.chunk.DocumentBlock;
import com.bank.loan.advisory.rag.chunk.DocumentParseClient;
import com.bank.loan.advisory.rag.chunk.ParseResult;
import com.bank.loan.advisory.rag.chunk.StructureAwareChunker;
import com.bank.loan.advisory.repository.AdvisoryDocumentChunkRepository;
import com.bank.loan.advisory.repository.AdvisoryDocumentRepository;
import com.bank.loan.support.LoanErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 정책문서 등록 + 구조-인지 청킹 + 임베딩 적재 (plan §11.4.1 — Task 6-3, §15 개편).
 *
 * 처리 순서:
 *   1. (파일) 사이드카 파싱 → 블록 / (content) 단일 블록 래핑
 *   2. 구조-인지 청킹 + embed 선계산 (트랜잭션 없음 — 외부 API 호출 격리)
 *   3. ADVISORY_DOCUMENT INSERT → ADVISORY_DOCUMENT_CHUNK INSERT(chunk_meta 포함) → activate (단일 트랜잭션)
 *
 * AI_GUIDELINES: 트랜잭션 안에서 외부 API 호출 금지 → parse·embed 는 트랜잭션 진입 전에 완료.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    /** section_path 컬럼 길이 한계(VARCHAR(500)). */
    private static final int SECTION_PATH_MAX = 500;

    private final AdvisoryDocumentRepository     docRepo;
    private final AdvisoryDocumentChunkRepository chunkRepo;
    private final EmbeddingClient                embeddingClient;
    private final JdbcTemplate                   jdbcTemplate;
    private final TransactionTemplate            transactionTemplate;
    private final StructureAwareChunker          chunker;
    private final DocumentParseClient            parseClient;
    private final ObjectMapper                   objectMapper;

    /** content 직접 제공 경로 — 평문을 단일 블록으로 래핑해 동일 청킹 경로로 통일. */
    public DocumentRegisterResponse register(DocumentRegisterRequest req, Long actorId) {
        if (req.content() == null || req.content().isBlank()) {
            throw new BusinessException(LoanErrorCode.LOAN_003,
                    "content 가 비어있습니다. content 직접 제공 또는 파일 업로드가 필요합니다.");
        }
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock(BlockType.PARAGRAPH, req.content(), null, null, 0, null));
        List<Chunk> chunks = chunker.chunk(blocks, new DocMeta(req.docCategoryCd()));
        return ingest(req, chunks, actorId);
    }

    /** 파일 업로드 경로 — 사이드카 파싱 → 구조-인지 청킹. */
    public DocumentRegisterResponse registerFile(DocumentRegisterRequest req, byte[] fileBytes,
                                                  String filename, Long actorId) {
        ParseResult parsed = parseClient.parse(fileBytes, filename, DocFormat.fromFilename(filename));
        List<Chunk> chunks = chunker.chunk(parsed.blocks(), new DocMeta(req.docCategoryCd()));
        if (chunks.isEmpty()) {
            // 파싱은 됐으나 추출 청크 0 — 부분 적재 금지, 명확 실패
            throw new BusinessException(LoanErrorCode.LOAN_213,
                    "문서에서 청크를 추출하지 못했습니다 (지원하지 않는 포맷이거나 스캔본 인식 불가). file=" + filename);
        }
        if (parsed.degraded()) {
            log.warn("문서 파싱 degraded — 저품질 가능: file={} engine={} blocks={} chunks={}",
                     filename, parsed.engine(), parsed.blocks().size(), chunks.size());
        }
        return ingest(req, chunks, actorId);
    }

    // ──────────────────────────────────────────────────
    // 공통 적재 (embed 선계산 → 트랜잭션 INSERT)
    // ──────────────────────────────────────────────────

    private DocumentRegisterResponse ingest(DocumentRegisterRequest req, List<Chunk> chunks, Long actorId) {
        if (chunks.isEmpty()) {
            throw new BusinessException(LoanErrorCode.LOAN_003, "청크가 비어있어 적재할 수 없습니다.");
        }

        String         modelCd   = embeddingClient.defaultModelCd();
        OffsetDateTime indexedAt = OffsetDateTime.now();

        // 1. 임베딩 선계산 (트랜잭션 밖 — 외부 API 호출)
        List<String> vecStrings = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            vecStrings.add(EmbeddingClient.toVectorString(embeddingClient.embed(chunk.text())));
        }

        // 2. 문서 마스터 + 청크 INSERT + 활성화 (단일 트랜잭션)
        DocumentRegisterResponse resp = Objects.requireNonNull(
            transactionTemplate.execute(status -> {
                docRepo.findByDocCdAndDocVersionAndDeletedAtIsNull(req.docCd(), req.docVersion())
                       .ifPresent(d -> { throw new BusinessException(LoanErrorCode.LOAN_001); });

                AdvisoryDocument doc = docRepo.save(AdvisoryDocument.builder()
                        .docCd(req.docCd())
                        .docTitle(req.docTitle())
                        .docCategoryCd(req.docCategoryCd())
                        .docVersion(req.docVersion())
                        .effectiveStartDate(req.effectiveStartDate())
                        .effectiveEndDate(req.effectiveEndDate())
                        .sourceUri(req.sourceUri())
                        .activeYn("N")
                        .docDesc(req.docDesc())
                        .build());

                for (int i = 0; i < chunks.size(); i++) {
                    Chunk chunk = chunks.get(i);
                    jdbcTemplate.update("""
                        INSERT INTO advisory_document_chunk
                          (doc_id, chunk_seq, chunk_text, section_path, chunk_token_count, chunk_meta,
                           embedding_model_cd, embedding, indexed_at, created_at, created_by)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS vector), ?, now(), ?)
                        """,
                            doc.getDocId(), i + 1, chunk.text(),
                            truncateSectionPath(chunk.sectionPath()), chunk.tokenCount(),
                            toJson(chunk.meta()), modelCd, vecStrings.get(i), indexedAt, actorId);
                }

                doc.activate();
                log.info("정책문서 인입 완료 — docId={} docCd={} chunkCount={}",
                         doc.getDocId(), doc.getDocCd(), chunks.size());
                return DocumentRegisterResponse.of(doc, chunks.size());
            })
        );
        return resp;
    }

    /** 문서 비활성화 (재인입 준비 또는 만료 처리). */
    @Transactional
    public void deactivate(Long docId, Long actorId) {
        AdvisoryDocument doc = docRepo.findByDocIdAndDeletedAtIsNull(docId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));
        doc.deactivate();
        log.info("정책문서 비활성화 — docId={}", docId);
    }

    /** 문서 활성화 (비활성 → 활성). */
    @Transactional
    public void activate(Long docId, Long actorId) {
        AdvisoryDocument doc = docRepo.findByDocIdAndDeletedAtIsNull(docId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_002));
        if (chunkRepo.countByDocId(docId) == 0) {
            throw new BusinessException(LoanErrorCode.LOAN_003,
                    "청크가 없는 문서는 활성화할 수 없습니다. 먼저 content 를 인입하세요.");
        }
        doc.activate();
    }

    // ──────────────────────────────────────────────────
    // internal helpers
    // ──────────────────────────────────────────────────

    /** chunk_meta 직렬화 — 실패 시 빈 객체로 폴백(적재는 계속). */
    private String toJson(Object meta) {
        if (meta == null) return "{}";
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("chunk_meta 직렬화 실패 — 빈 객체 폴백: {}", e.getMessage());
            return "{}";
        }
    }

    /** section_path 500자 가드 (heading_path 풀 값은 chunk_meta 에 보존). */
    private static String truncateSectionPath(String sectionPath) {
        if (sectionPath == null) return null;
        return sectionPath.length() <= SECTION_PATH_MAX
                ? sectionPath
                : sectionPath.substring(0, SECTION_PATH_MAX);
    }
}
