package com.bank.loan.advisory.controller;

import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.dto.DocumentRegisterRequest;
import com.bank.loan.advisory.dto.DocumentRegisterResponse;
import com.bank.loan.advisory.rag.CaseIndexBackfillService;
import com.bank.loan.advisory.rag.CaseIndexingService;
import com.bank.loan.advisory.rag.DocumentIngestionService;
import com.bank.loan.advisory.rag.RagIndexMaintenanceService;
import com.bank.loan.advisory.repository.AdvisoryDocumentChunkRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.bank.loan.advisory.domain.AdvisoryDocument;
import com.bank.loan.advisory.repository.AdvisoryDocumentRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * RAG 내부 관리 API (plan §11.5 — Task 6-8).
 *
 * 권한: admin/system (X-Actor-Role=ADMIN 헤더로 보호 — 현 단계 컨트롤러 레벨 미검증,
 * 운영 환경에서는 API Gateway/서비스 메시 네트워크 정책으로 보호).
 */
@Tag(name = "어드바이저리 RAG 관리", description = "Advisory - 정책문서 인입 / 사례 인덱싱 (internal admin)")
@RestController
@RequestMapping("/api/internal/advisory")
@RequiredArgsConstructor
@Validated
public class InternalAdvisoryRagController {

    private final DocumentIngestionService           ingestionService;
    private final CaseIndexingService                caseIndexingService;
    private final CaseIndexBackfillService           backfillService;
    private final RagIndexMaintenanceService         indexMaintenanceService;
    private final CurrentActorProvider               currentActor;
    private final AdvisoryDocumentRepository         documentRepository;
    private final AdvisoryDocumentChunkRepository    chunkRepository;

    @Operation(summary = "정책문서 적재 통계", description = "전체/활성 문서 수와 embedding_model_cd 별 청크 건수를 반환.")
    @GetMapping("/documents/stats")
    public ApiResponse<DocumentStatsResponse> documentStats() {
        long total  = documentRepository.countByDeletedAtIsNull();
        long active = documentRepository.countByActiveYnAndDeletedAtIsNull("Y");
        List<DocumentStatsResponse.ModelChunkCount> chunks = chunkRepository.countByEmbeddingModelCd()
                .stream()
                .map(s -> new DocumentStatsResponse.ModelChunkCount(s.getEmbeddingModelCd(), s.getCount()))
                .toList();
        return ApiResponse.ok(new DocumentStatsResponse(total, active, chunks));
    }

    public record DocumentStatsResponse(
            long totalDocuments,
            long activeDocuments,
            List<ModelChunkCount> chunksByModel) {
        public record ModelChunkCount(String embeddingModelCd, long count) {}
    }

    @Operation(summary = "정책문서 목록 조회", description = "삭제되지 않은 전체 정책문서를 최신순으로 반환.")
    @GetMapping("/documents")
    public ApiResponse<List<DocumentSummaryResponse>> listDocuments() {
        List<DocumentSummaryResponse> items = documentRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
                .stream().map(DocumentSummaryResponse::of).toList();
        return ApiResponse.ok(items);
    }

    public record DocumentSummaryResponse(
            Long docId, String docCd, String docTitle, String docCategoryCd,
            String docVersion, String activeYn, OffsetDateTime createdAt) {
        static DocumentSummaryResponse of(AdvisoryDocument d) {
            return new DocumentSummaryResponse(
                    d.getDocId(), d.getDocCd(), d.getDocTitle(), d.getDocCategoryCd(),
                    d.getDocVersion(), d.getActiveYn(), d.getCreatedAt());
        }
    }

    @Operation(summary = "정책문서 등록 및 인입",
            description = "정책문서를 등록하고 청크 분할·임베딩·적재 후 자동 활성화한다. " +
                          "content 필드로 문서 본문을 직접 제공 (source_uri fetch 는 현 단계 미지원).")
    @PostMapping("/documents")
    public ResponseEntity<ApiResponse<DocumentRegisterResponse>> registerDocument(
            @Valid @RequestBody DocumentRegisterRequest req,
            @RequestParam(defaultValue = "false") boolean replace) {
        Long actorId = currentActor.currentActorId();
        DocumentRegisterResponse res = ingestionService.register(req, actorId, replace);
        return ResponseEntity.status(201).body(ApiResponse.ok(res));
    }

    @Operation(summary = "정책문서 파일 업로드 및 인입",
            description = "PDF/Word/HWP 파일을 사이드카(inference-server)로 파싱해 구조-인지 청킹·임베딩·적재 후 " +
                          "자동 활성화한다. file 파트는 원본 문서, meta 파트(application/json)는 문서 메타.")
    @PostMapping(value = "/documents/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentRegisterResponse>> registerDocumentFile(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") DocumentRegisterRequest meta,
            @RequestParam(defaultValue = "false") boolean replace) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(LoanErrorCode.LOAN_213, "업로드 파일이 비어있습니다.");
        }
        Long actorId = currentActor.currentActorId();
        DocumentRegisterResponse res = ingestionService.registerFile(
                meta, file.getBytes(), file.getOriginalFilename(), actorId, replace);
        return ResponseEntity.status(201).body(ApiResponse.ok(res));
    }

    @Operation(summary = "문서 활성화/비활성화",
            description = "active=true 면 활성화(검색 대상 포함), false 면 비활성화(검색 제외).")
    @PutMapping("/documents/{docId}/activate")
    public ApiResponse<Void> toggleActivate(
            @PathVariable Long docId,
            @RequestParam(defaultValue = "true") boolean active) {
        Long actorId = currentActor.currentActorId();
        if (active) {
            ingestionService.activate(docId, actorId);
        } else {
            ingestionService.deactivate(docId, actorId);
        }
        return ApiResponse.ok();
    }

    @Operation(summary = "심사 사례 인덱싱",
            description = "지정 심사(revId) 또는 전체 COMPLETED 심사를 사례 인덱스에 적재한다. " +
                          "revId 생략 시 일괄 인덱싱 (초기 구동·모델 교체 후 재인덱싱용).")
    @PostMapping("/index/cases")
    public ApiResponse<IndexCasesResult> indexCases(
            @RequestParam(required = false) Long revId,
            @RequestParam(defaultValue = "N") String overturnYn) {
        Long actorId = currentActor.currentActorId();
        if (revId != null) {
            Long caseIdxId = caseIndexingService.index(revId, overturnYn, actorId);
            return ApiResponse.ok(new IndexCasesResult(1, caseIdxId));
        } else {
            int count = caseIndexingService.indexAll(actorId);
            return ApiResponse.ok(new IndexCasesResult(count, null));
        }
    }

    public record IndexCasesResult(int indexedCount, Long lastCaseIdxId) {}

    @Operation(summary = "완료 심사 케이스 일괄 백필",
            description = "COMPLETED 심사를 ADVISORY_CASE_INDEX 에 적재한다. " +
                          "from/to 생략 시 전체 대상. dryRun=true 면 INSERT 없이 대상 건수만 반환. " +
                          "revId 당 1건만 적재 (멱등).")
    @PostMapping("/rag/case-index/backfill")
    public ApiResponse<BackfillResultResponse> backfill(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        Long actorId = currentActor.currentActorId();
        CaseIndexBackfillService.BackfillResult result =
                backfillService.backfill(from, to, dryRun, actorId);
        return ApiResponse.ok(BackfillResultResponse.from(result));
    }

    public record BackfillResultResponse(
            int processed, int skipped, int failed, boolean dryRun) {
        static BackfillResultResponse from(CaseIndexBackfillService.BackfillResult r) {
            return new BackfillResultResponse(r.processed(), r.skipped(), r.failed(), r.dryRun());
        }
    }

    @Operation(summary = "RAG 벡터 인덱스 재산정",
            description = "advisory_document_chunk·advisory_case_index 의 IVFFlat lists 를 실측 rows 기준으로 " +
                          "재계산해 인덱스를 재생성한다. 대량 인입·백필 후 호출. CREATE INDEX 쓰기 락 주의.")
    @PostMapping("/rag/reindex")
    public ApiResponse<RagIndexMaintenanceService.ReindexResult> reindex() {
        return ApiResponse.ok(indexMaintenanceService.reindex());
    }
}
