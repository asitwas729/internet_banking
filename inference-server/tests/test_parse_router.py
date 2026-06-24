"""parse_router 순수-파이썬 경로 테스트.

무거운 파서 라이브러리(PyMuPDF/python-docx/lxml)나 CLI(hwp5html/soffice)가
없는 CI 환경에서도 동작하도록, 포맷 판별·텍스트 분할·표 평탄화·degraded 폴백만 검증한다.
엔드포인트는 HTTP 없이 함수로 직접 호출한다(TestClient/httpx 의존 회피).
"""

import base64

from app.parse_router import (
    ParseRequest,
    TableModel,
    _detect_format,
    _looks_like_toc,
    _parse_txt,
    _table_to_text,
    parse_document,
)


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


# ── 포맷 판별 ─────────────────────────────────────────────────────────────

def test_detect_pdf_by_signature():
    assert _detect_format(b"%PDF-1.7\n...", "x", "AUTO") == "PDF"


def test_detect_hwp_by_ole_signature():
    ole = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 16
    assert _detect_format(ole, "doc.hwp", "AUTO") == "HWP"


def test_detect_by_extension_fallback():
    assert _detect_format(b"plain bytes", "policy.docx", "AUTO") == "DOCX"
    assert _detect_format(b"plain bytes", "unknown.bin", "AUTO") == "TXT"


def test_declared_format_wins():
    assert _detect_format(b"%PDF-", "x", "DOCX") == "DOCX"


# ── 휴리스틱·평탄화 ────────────────────────────────────────────────────────

def test_looks_like_toc():
    assert _looks_like_toc("목차") is True
    assert _looks_like_toc("서론 ............ 12") is True
    assert _looks_like_toc("제1조 본문 내용") is False


def test_table_to_text_with_nested():
    t = TableModel(rows=[["a", "b"], ["c", "d"]], nested=[TableModel(rows=[["x", "y"]])])
    text = _table_to_text(t)
    assert "a | b" in text
    assert "[중첩표]" in text
    assert "x | y" in text


# ── TXT 파싱 ──────────────────────────────────────────────────────────────

def test_parse_txt_blocks_and_toc_tag():
    blocks, pages, degraded, engine = _parse_txt("문단 하나.\n\n목차\n\n문단 둘.".encode())
    assert degraded is False
    assert engine == "text"
    types = {b.block_type for b in blocks}
    assert "paragraph" in types
    assert "toc" in types


# ── 엔드포인트 ────────────────────────────────────────────────────────────

def test_parse_document_txt():
    req = ParseRequest(document_b64=_b64("제1조 본문 내용.".encode()),
                       filename="a.txt", doc_format="AUTO")
    resp = parse_document(req)
    assert resp.doc_format == "TXT"
    assert resp.blocks
    assert resp.degraded is False


def test_parse_document_hwp_degrades_without_tools():
    """바이너리 HWP + 파서/CLI 부재 → 예외 대신 degraded=true, 빈 blocks."""
    ole = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 32
    req = ParseRequest(document_b64=_b64(ole), filename="a.hwp",
                       doc_format="HWP", ocr_fallback=False)
    resp = parse_document(req)
    assert resp.degraded is True
    assert resp.blocks == []
