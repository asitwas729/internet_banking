package com.bank.loan.advisory.rag.chunk;

/**
 * 청킹 시 모든 청크에 공통으로 붙는 문서 수준 메타.
 *
 * @param docType 문서 분류 코드(advisory_document.doc_category_cd) — chunk_meta.doc_type
 */
public record DocMeta(String docType) {
}
