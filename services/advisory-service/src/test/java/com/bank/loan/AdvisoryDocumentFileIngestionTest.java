package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정책문서 파일 업로드 E2E 통합 테스트 (plan §15 — 구조-인지 청킹).
 *
 * 파싱 사이드카(/parse/document)는 WireMock 으로 스텁하고, 그 외(pgvector INSERT,
 * 구조-인지 청킹, Stub 임베딩)는 실제 경로로 흘린다. 검증 포인트:
 *   - multipart 업로드 → registerFile → 자동 활성화(activeYn=Y)
 *   - 구조 블록(heading/paragraph/table) → chunk_meta(jsonb) 실제 적재 (누락 회귀 방지)
 *   - heading_path / block_type=table 메타 보존
 *
 * 테스트 연도 격리: 2071.
 */
class AdvisoryDocumentFileIngestionTest extends AbstractLoanIntegrationTest {

    static final WireMockServer PARSE_MOCK;

    static {
        PARSE_MOCK = new WireMockServer(options().dynamicPort());
        PARSE_MOCK.start();
    }

    @DynamicPropertySource
    static void parseProps(DynamicPropertyRegistry r) {
        r.add("advisory.rag.parse.base-url", () -> "http://localhost:" + PARSE_MOCK.port());
    }

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 파일_업로드_구조인지청킹_chunk_meta_적재() throws Exception {
        PARSE_MOCK.stubFor(post(urlEqualTo("/parse/document")).willReturn(okJson("""
                {
                  "submission_id": "",
                  "doc_format": "PDF",
                  "page_count": 1,
                  "degraded": false,
                  "engine": "pymupdf",
                  "blocks": [
                    {"block_type": "heading", "text": "제1장 총칙", "level": 1, "block_seq": 0},
                    {"block_type": "paragraph", "text": "이 규정은 여신 심사 기준을 정한다.", "block_seq": 1},
                    {"block_type": "table", "text": "", "block_seq": 2,
                     "table": {"rows": [["항목", "값"], ["DSR", "70%"]], "html": "", "nested": []}}
                  ]
                }
                """)));

        String metaJson = """
                {
                  "docCd": "FILE_POLICY_2071",
                  "docTitle": "파일 업로드 정책",
                  "docCategoryCd": "CREDIT_POLICY",
                  "docVersion": "v1.0",
                  "effectiveStartDate": "20710101",
                  "effectiveEndDate": "20711231",
                  "docDesc": "파일 인입 E2E"
                }
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "reg.pdf", "application/pdf", "dummy-pdf-bytes".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile meta = new MockMultipartFile(
                "meta", "", "application/json", metaJson.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/internal/advisory/documents/file")
                        .file(file).file(meta))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docCd").value("FILE_POLICY_2071"))
                .andExpect(jsonPath("$.data.activeYn").value("Y"))
                .andReturn();

        JsonNode data = extractData(result);
        long docId = data.get("docId").asLong();
        assertThat(data.get("chunkCount").asInt()).isGreaterThanOrEqualTo(2);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT chunk_text, section_path, chunk_meta::text AS meta "
                + "FROM advisory_document_chunk WHERE doc_id = ? ORDER BY chunk_seq", docId);

        assertThat(rows).isNotEmpty();
        // chunk_meta 가 비어있지 않고 doc_type 이 채워졌다 (누락 회귀 방지).
        // jsonb::text 는 콜론 뒤 공백을 넣으므로 공백 제거 후 비교.
        assertThat(rows).allMatch(r -> meta(r).contains("\"doc_type\":\"CREDIT_POLICY\""));
        // heading 경로가 섹션 청크에 보존
        assertThat(rows).anyMatch(r -> meta(r).contains("\"heading_path\"")
                && ((String) r.get("section_path")).contains("제1장 총칙"));
        // 표가 별도 table 블록 청크로 적재
        assertThat(rows).anyMatch(r -> meta(r).contains("\"block_type\":\"table\"")
                && ((String) r.get("chunk_text")).contains("DSR | 70%"));
    }

    /** jsonb::text 의 콜론/콤마 뒤 공백을 제거해 비교를 단순화한다. */
    private static String meta(Map<String, Object> row) {
        return ((String) row.get("meta")).replace(" ", "");
    }
}
