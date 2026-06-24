package com.bank.loan.advisory.rag.chunk;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InferenceServerDocumentParseClientTest {

    private static WireMockServer server;
    private static InferenceServerDocumentParseClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        var props = new AdvisoryParseProperties(
                "http://localhost:" + server.port(),
                1000, 2000, 2, 1L, true);
        client = new InferenceServerDocumentParseClient(RestClient.builder(), props);
    }

    @AfterEach
    void reset() {
        server.resetAll();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void 정상_파싱_응답을_역직렬화한다() {
        server.stubFor(post(urlEqualTo("/parse/document")).willReturn(okJson("""
                {
                  "submission_id": "",
                  "doc_format": "PDF",
                  "page_count": 2,
                  "degraded": false,
                  "engine": "pymupdf",
                  "blocks": [
                    {"block_type": "heading", "text": "제1장", "level": 1, "block_seq": 0},
                    {"block_type": "paragraph", "text": "본문", "block_seq": 1}
                  ]
                }
                """)));

        ParseResult result = client.parse("data".getBytes(), "reg.pdf", DocFormat.AUTO);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().get(0).type()).isEqualTo(BlockType.HEADING);
        assertThat(result.blocks().get(1).text()).isEqualTo("본문");
        assertThat(result.degraded()).isFalse();
        assertThat(result.engine()).isEqualTo("pymupdf");
    }

    @Test
    void degraded_신호를_그대로_노출한다() {
        server.stubFor(post(urlEqualTo("/parse/document")).willReturn(okJson("""
                {"blocks": [{"block_type":"paragraph","text":"OCR 추출","block_seq":0}],
                 "degraded": true, "page_count": 1, "engine": "paddleocr-ko", "doc_format": "PDF"}
                """)));

        ParseResult result = client.parse("scan".getBytes(), "scan.pdf", DocFormat.PDF);

        assertThat(result.degraded()).isTrue();
        assertThat(result.blocks()).hasSize(1);
    }

    @Test
    void 빈_파일은_HTTP_호출_없이_LOAN_213() {
        assertThatThrownBy(() -> client.parse(new byte[0], "x.pdf", DocFormat.AUTO))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_213);
        server.verify(0, postRequestedFor(urlEqualTo("/parse/document")));
    }

    @Test
    void 사xx_는_즉시_실패_LOAN_213() {
        server.stubFor(post(urlEqualTo("/parse/document"))
                .willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.parse("d".getBytes(), "x.pdf", DocFormat.AUTO))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_213);
        server.verify(1, postRequestedFor(urlEqualTo("/parse/document")));
    }

    @Test
    void 오xx_는_maxAttempts_재시도_후_LOAN_212() {
        server.stubFor(post(urlEqualTo("/parse/document"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.parse("d".getBytes(), "x.pdf", DocFormat.AUTO))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_212);
        server.verify(2, postRequestedFor(urlEqualTo("/parse/document")));
    }
}
