package com.bank.loan.advisory.rag.chunk;

/**
 * 규정문서 파싱 사이드카(inference-server {@code POST /parse/document}) 호출 추상화.
 *
 * 파일 바이트를 사이드카에 보내 구조 블록 목록({@link ParseResult})으로 받는다.
 * 외부 API 호출이므로 트랜잭션 진입 전에 완료해야 한다(AI_GUIDELINES).
 */
public interface DocumentParseClient {

    /**
     * 문서 바이트를 파싱해 구조 블록으로 변환한다.
     *
     * @param bytes    문서 원본 바이트
     * @param filename 원본 파일명(확장자 판별용, null 허용)
     * @param fmt      포맷 힌트(AUTO 면 사이드카가 시그니처로 재판별)
     * @return 파싱 결과(blocks, degraded, engine 등)
     */
    ParseResult parse(byte[] bytes, String filename, DocFormat fmt);
}
