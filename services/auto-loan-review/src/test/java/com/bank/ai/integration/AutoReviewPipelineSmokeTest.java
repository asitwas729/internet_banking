package com.bank.ai.integration;

import com.bank.ai.review.client.LoanServiceClient;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.ai.review.service.AutoReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 파이프라인 E2E 스모크 테스트 — plan/llm-pipeline.md §11 (L11).
 *
 * <p>검증 흐름:
 * POST /api/ai/auto-review/evaluate
 *   → sync 즉시 응답 (PENDING)
 *   → @Async("llmExecutor") 비동기 파이프라인 실행
 *     (StubLlmClient: purpose_analysis + review_report_track1)
 *   → LoanServiceClient.updateReport(revId, DONE) 호출 검증
 *
 * <p>인프라 스터빙:
 * <ul>
 *   <li>LLM provider — {@code ai.llm.provider=stub} (StubLlmClient 자동 활성)</li>
 *   <li>inference-server — {@code @MockBean AutoReviewService} (Track 1 진입 조건)</li>
 *   <li>loan-service 콜백 — {@code @MockBean LoanServiceClient}
 *       (Mockito 검증, HTTP 레이어 제거 — wiremock-standalone 의 Jetty H2 클라이언트 간섭 방지)</li>
 *   <li>Redis — autoconfigure 제외</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ai.llm.provider=stub",
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
class AutoReviewPipelineSmokeTest {

    // ── Mocks ─────────────────────────────────────────────────────────────

    /** inference-server 대체 — 고신뢰 APPROVE 응답 (Track 1 진입 조건 충족). */
    @MockBean
    private AutoReviewService autoReviewService;

    /** loan-service 콜백 클라이언트 — Mockito 로 콜백 파라미터 검증. */
    @MockBean
    private LoanServiceClient loanServiceClient;

    @Autowired
    private TestRestTemplate restTemplate;

    /** 테스트 간 비동기 태스크 유출 방지 — llmExecutor 큐 드레인 대기. */
    @Autowired
    @Qualifier("llmExecutor")
    private ThreadPoolTaskExecutor llmExecutor;

    /** P(APPROVE)=0.97, pdScore=null → PD 폴백 pd=0.03 → Track 1. */
    private static final AutoReviewResponse TRACK1_INFERENCE = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.97,
            Map.of("APPROVE", 0.97, "REJECT", 0.03),
            null, null
    );

    @BeforeEach
    void setUp() {
        when(autoReviewService.review(any())).thenReturn(TRACK1_INFERENCE);
    }

    /**
     * 각 테스트 종료 후 llmExecutor 의 활성 스레드가 0이 될 때까지 대기.
     * 이전 테스트의 비동기 파이프라인이 다음 테스트로 유출되는 레이스 컨디션 방지.
     */
    @AfterEach
    void drainAsyncTasks() {
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> llmExecutor.getThreadPoolExecutor().getActiveCount() == 0
                         && llmExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    /**
     * Track 1 진입 조건을 충족하는 정상 신청 요청.
     * hard constraint 통과: dsr=0.30, ltv=0.50, credit=750, delinq=0, age=35.
     */
    private static AutoReviewRequest track1Request(Long revId) {
        return new AutoReviewRequest(
                revId,
                // Layer 1
                null, 35, null, null, null, null, null, null, null, null, null, "regular",
                // Layer 2
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 750,
                // Layer 3
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                // Layer 4
                null, null, null, null, null, null, null
        );
    }

    // ── TC 1: 동기 즉시 응답 ───────────────────────────────────────────────

    @Test
    void 평가_API_동기_응답은_즉시_PENDING_상태로_반환된다() {
        var response = restTemplate.postForEntity(
                "/api/ai/auto-review/evaluate",
                track1Request(99L),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("PENDING")     // reportStatus
                .contains("TRACK_1");    // track — 고신뢰 APPROVE + Track 1 조건 충족
    }

    // ── TC 2: 비동기 파이프라인 완료 → DONE 콜백 ─────────────────────────

    @Test
    void 비동기_LLM_파이프라인_완료_후_loan_service_에_DONE_콜백_전송() {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(1L), String.class);

        // llmExecutor 스레드풀 완료 대기 + Mockito 호출 검증
        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(1L), captor.capture()));

        ReviewReportUpdateRequest callbackReq = captor.getValue();
        assertThat(callbackReq.status()).isEqualTo("DONE");
        assertThat(callbackReq.report()).isNotNull();
        assertThat(callbackReq.report().track().name()).isEqualTo("TRACK_1"); // StubLlmClient 산출
    }

    // ── TC 3: revId null → 비동기 파이프라인 스킵 ────────────────────────

    @Test
    void revId_null_이면_비동기_파이프라인_스킵_후_콜백_없음() throws InterruptedException {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(null), String.class);

        // 비동기가 실행됐다면 완료될 충분한 시간 대기
        TimeUnit.MILLISECONDS.sleep(600);

        verify(loanServiceClient, never()).updateReport(any(), any());
    }
}
