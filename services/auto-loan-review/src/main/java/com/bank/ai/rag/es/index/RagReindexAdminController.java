package com.bank.ai.rag.es.index;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 임베딩 재색인 운영 엔드포인트 — Step 2 (Vertex 실 임베딩 전환).
 *
 * <p>{@code /admin/**} 이므로 {@code AdminSecurityConfig}(role AI_ADMIN) 로 보호된다.
 * 컷오버 절차: provider=vertex 적용 → {@code POST /admin/rag/reindex/{corpus}} 로 v2 적재 →
 * 건수 확인 후 {@code POST /admin/rag/promote/{corpus}} 로 alias 스왑.
 *
 * <p>{@code ai.rag.backend=es} 에서만 빈 등록.
 */
@RestController
@RequestMapping("/admin/rag")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
public class RagReindexAdminController {

    private final EmbeddingReindexService reindexService;

    /** 코퍼스를 현재 활성 임베딩으로 재색인해 v2 인덱스에 적재(멱등). */
    @PostMapping("/reindex/{corpus}")
    public EmbeddingReindexService.ReindexReport reindex(@PathVariable String corpus) throws IOException {
        return reindexService.reembed(corpus);
    }

    /** 재색인된 v2 로 alias 를 원자 스왑(건수 게이트 통과 시). */
    @PostMapping("/promote/{corpus}")
    public EmbeddingReindexService.PromoteReport promote(@PathVariable String corpus) throws IOException {
        return reindexService.promote(corpus);
    }
}
