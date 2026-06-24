"""advisory-service 규정문서 파싱 엔드포인트.

POST /parse/document — PDF/DOCX/HWP/HWPX base64 → 구조 블록 목록(DocumentBlock)
GET  /parse/health   — 포맷별 파서 가용성

블록 단위(heading/paragraph/table/toc/header/footer/list)로 정규화해 반환하면
advisory 측 StructureAwareChunker 가 블록 경계 안에서만 길이를 맞춰 청킹한다.
표는 rows/html/nested 로 구조 보존(중첩표 포함). 목차·머리말·꼬리말은 1차 태깅만 하고
최종 제거는 Java 청커가 담당한다.

설계 원칙(ocr_router 컨벤션 준수):
  - 무거운 파서 라이브러리는 함수 내부에서 지연 import — 미설치 환경에서도 서버 기동 가능
  - 라이브러리 부재/파싱 실패 시 예외 대신 degraded=true 로 명확히 신호
  - HWP 는 다단계 폴백(HWPX 네이티브 → hwp5html → LibreOffice 변환 → hwp5txt)
"""

from __future__ import annotations

import base64
import io
import logging
import os
import re
import subprocess
import tempfile
import zipfile
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger("parse")

router = APIRouter(prefix="/parse", tags=["parse"])

# LibreOffice 변환 폴백 타임아웃(초)
_SOFFICE_TIMEOUT = int(os.getenv("PARSE_SOFFICE_TIMEOUT", "30"))
_HWP5HTML_TIMEOUT = int(os.getenv("PARSE_HWP5HTML_TIMEOUT", "20"))


# ── Pydantic IO ──────────────────────────────────────────────────────────────

class TableModel(BaseModel):
    rows: list[list[str]] = []      # 행×열 셀 텍스트
    html: str = ""                  # 원본 HTML(가능한 경우)
    nested: list["TableModel"] = [] # 셀 안에 중첩된 표


class DocumentBlock(BaseModel):
    block_type: str                 # heading|paragraph|table|toc|header|footer|list
    text: str = ""
    page: int | None = None
    level: int | None = None        # heading 깊이(1=최상위)
    block_seq: int = 0
    table: TableModel | None = None
    bbox: list[float] | None = None


class ParseRequest(BaseModel):
    document_b64: str
    filename: str = ""
    doc_format: str = "AUTO"        # PDF|DOCX|HWP|HWPX|TXT|AUTO
    ocr_fallback: bool = True
    submission_id: str = ""


class ParseResponse(BaseModel):
    submission_id: str
    doc_format: str
    page_count: int
    blocks: list[DocumentBlock]
    degraded: bool
    engine: str


TableModel.model_rebuild()


# ── 포맷 판별 ────────────────────────────────────────────────────────────────

_EXT_FORMAT = {
    "pdf": "PDF", "docx": "DOCX", "doc": "DOCX",
    "hwp": "HWP", "hwpx": "HWPX", "txt": "TXT",
}


def _detect_format(data: bytes, filename: str, declared: str) -> str:
    """시그니처 우선, 미상이면 확장자로 판별."""
    d = (declared or "AUTO").upper()
    if d != "AUTO":
        return d

    if data[:5] == b"%PDF-":
        return "PDF"
    # OLE 복합문서(D0CF11E0) — 바이너리 HWP v5(구 .doc 도 동일 시그니처지만 본 파이프라인은 HWP 취급)
    if data[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1":
        return "HWP"
    # ZIP 컨테이너 — DOCX vs HWPX 구분
    if data[:4] == b"PK\x03\x04":
        return _zip_format(data)

    ext = filename.lower().rsplit(".", 1)[-1] if "." in filename else ""
    return _EXT_FORMAT.get(ext, "TXT")


def _zip_format(data: bytes) -> str:
    """ZIP 내부 엔트리로 DOCX/HWPX 판별."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            names = set(zf.namelist())
            if "word/document.xml" in names:
                return "DOCX"
            # HWPX: mimetype=application/hwp+zip, Contents/section*.xml
            if "mimetype" in names:
                try:
                    if b"hwp+zip" in zf.read("mimetype"):
                        return "HWPX"
                except Exception:
                    pass
            if any(n.startswith("Contents/section") for n in names):
                return "HWPX"
    except Exception as e:
        log.warning("ZIP 포맷 판별 실패: %s", e)
    return "DOCX"


# ── 블록 누적 헬퍼 ───────────────────────────────────────────────────────────

class _Blocks:
    """block_seq 자동 증가 누적기."""

    def __init__(self) -> None:
        self.items: list[DocumentBlock] = []

    def add(self, block_type: str, text: str = "", *, page: int | None = None,
            level: int | None = None, table: TableModel | None = None,
            bbox: list[float] | None = None) -> None:
        if block_type != "table" and not (text and text.strip()):
            return
        self.items.append(DocumentBlock(
            block_type=block_type,
            text=(text or "").strip(),
            page=page,
            level=level,
            block_seq=len(self.items),
            table=table,
            bbox=bbox,
        ))


_PAGE_NUM_RE = re.compile(r"^\s*[-–]?\s*\d{1,4}\s*[-–]?\s*$")
_TOC_HINT_RE = re.compile(r"(목\s*차|차\s*례|CONTENTS)", re.IGNORECASE)


def _looks_like_toc(text: str) -> bool:
    """'목차/차례' 헤더 또는 점선 + 페이지번호 패턴(……12)."""
    if _TOC_HINT_RE.search(text):
        return True
    return bool(re.search(r"\.{4,}\s*\d{1,4}\s*$", text))


# ── PDF ──────────────────────────────────────────────────────────────────────

def _parse_pdf(data: bytes, ocr_fallback: bool) -> tuple[list[DocumentBlock], int, bool, str]:
    try:
        import fitz  # type: ignore  # PyMuPDF
    except ImportError:
        log.warning("PyMuPDF 미설치 — PDF 파싱 불가")
        return [], 0, True, "none"

    blocks = _Blocks()
    degraded = False
    engine = "pymupdf"
    try:
        doc = fitz.open(stream=data, filetype="pdf")
    except Exception as e:
        log.error("PDF open 실패: %s", e)
        return [], 0, True, "none"

    page_count = doc.page_count
    tables_by_page = _pdf_tables(data)  # pdfplumber 표(페이지별)

    for pno in range(page_count):
        page = doc.load_page(pno)
        page_text = page.get_text("dict")
        spans = _pdf_spans(page_text)

        if not spans:
            # 텍스트 레이어 없음 → 스캔본 추정. OCR 폴백
            if ocr_fallback:
                ocr_blocks, ocr_engine = _ocr_pdf_page(page)
                for b in ocr_blocks:
                    blocks.add(b["block_type"], b["text"], page=pno + 1, level=b.get("level"))
                engine = ocr_engine
                degraded = True
            else:
                degraded = True
            # 표는 텍스트 없는 페이지에서도 pdfplumber 가 잡았을 수 있음
            for tbl in tables_by_page.get(pno, []):
                blocks.add("table", _table_to_text(tbl), page=pno + 1, table=tbl)
            continue

        body_size = _pdf_body_size(spans)
        heading_sizes = _pdf_heading_sizes(spans, body_size)
        for s in spans:
            text = s["text"]
            if not text.strip():
                continue
            block_type = "paragraph"
            level = None
            if _looks_like_toc(text):
                block_type = "toc"
            elif s["size"] in heading_sizes:
                block_type = "heading"
                level = heading_sizes.index(s["size"]) + 1
            blocks.add(block_type, text, page=pno + 1, level=level, bbox=s.get("bbox"))

        for tbl in tables_by_page.get(pno, []):
            blocks.add("table", _table_to_text(tbl), page=pno + 1, table=tbl)

    doc.close()
    return blocks.items, page_count, degraded, engine


def _pdf_spans(page_dict: dict) -> list[dict]:
    """PyMuPDF dict → 라인 단위 텍스트 + 대표 폰트크기."""
    spans: list[dict] = []
    for block in page_dict.get("blocks", []):
        for line in block.get("lines", []):
            parts = [sp.get("text", "") for sp in line.get("spans", [])]
            text = "".join(parts).strip()
            if not text:
                continue
            sizes = [sp.get("size", 0.0) for sp in line.get("spans", []) if sp.get("text", "").strip()]
            size = round(max(sizes), 1) if sizes else 0.0
            spans.append({"text": text, "size": size, "bbox": list(line.get("bbox", []))})
    return spans


def _pdf_body_size(spans: list[dict]) -> float:
    """가장 빈번한 폰트크기 = 본문 크기."""
    freq: dict[float, int] = {}
    for s in spans:
        freq[s["size"]] = freq.get(s["size"], 0) + len(s["text"])
    return max(freq, key=lambda k: freq[k]) if freq else 0.0


def _pdf_heading_sizes(spans: list[dict], body_size: float) -> list[float]:
    """본문보다 큰 폰트크기를 내림차순으로 = heading level 후보."""
    bigger = {s["size"] for s in spans if s["size"] > body_size * 1.12}
    return sorted(bigger, reverse=True)


def _pdf_tables(data: bytes) -> dict[int, list[TableModel]]:
    """pdfplumber 로 페이지별 표 추출(+중첩표 bbox 포함관계)."""
    result: dict[int, list[TableModel]] = {}
    try:
        import pdfplumber  # type: ignore
    except ImportError:
        log.debug("pdfplumber 미설치 — PDF 표 추출 SKIP")
        return result

    try:
        with pdfplumber.open(io.BytesIO(data)) as pdf:
            for pno, page in enumerate(pdf.pages):
                found = page.find_tables() or []
                cells = [(t.bbox, _rows_from_finder(t)) for t in found]
                result[pno] = _nest_tables(cells)
    except Exception as e:
        log.warning("pdfplumber 표 추출 오류: %s", e)
    return result


def _rows_from_finder(table: Any) -> list[list[str]]:
    try:
        extracted = table.extract() or []
        return [[(c or "").strip() for c in row] for row in extracted]
    except Exception:
        return []


def _nest_tables(cells: list[tuple[tuple, list[list[str]]]]) -> list[TableModel]:
    """bbox 포함관계로 outer/inner 판정 — inner 는 outer.nested 로 이동."""
    models = [TableModel(rows=rows) for _, rows in cells]
    boxes = [bbox for bbox, _ in cells]
    is_inner = [False] * len(cells)

    for i in range(len(cells)):
        for j in range(len(cells)):
            if i == j:
                continue
            if _bbox_contains(boxes[j], boxes[i]):
                models[j].nested.append(models[i])
                is_inner[i] = True
                break
    return [m for idx, m in enumerate(models) if not is_inner[idx]]


def _bbox_contains(outer: tuple, inner: tuple) -> bool:
    try:
        ox0, oy0, ox1, oy1 = outer
        ix0, iy0, ix1, iy1 = inner
        return ox0 <= ix0 and oy0 <= iy0 and ox1 >= ix1 and oy1 >= iy1 and (outer != inner)
    except Exception:
        return False


def _ocr_pdf_page(page: Any) -> tuple[list[dict], str]:
    """텍스트 레이어 없는 PDF 페이지 → 이미지 렌더 후 OCR(ocr_router 재사용)."""
    try:
        from app.ocr_router import _get_engine
        import numpy as np  # type: ignore
        from PIL import Image  # type: ignore
    except ImportError:
        return [], "none"

    engine = _get_engine()
    if engine is None:
        return [], "none"

    try:
        pix = page.get_pixmap(dpi=200)
        img = Image.open(io.BytesIO(pix.tobytes("png"))).convert("RGB")
        raw = engine.ocr(np.array(img), cls=True)
    except Exception as e:
        log.warning("PDF 페이지 OCR 실패: %s", e)
        return [], "none"

    out: list[dict] = []
    for pg in (raw or []):
        for line in (pg or []):
            try:
                _, (text, conf) = line
                if conf >= 0.6 and text.strip():
                    out.append({"block_type": "paragraph", "text": text})
            except (TypeError, ValueError, IndexError):
                pass
    return out, "paddleocr-ko"


# ── DOCX ─────────────────────────────────────────────────────────────────────

def _parse_docx(data: bytes) -> tuple[list[DocumentBlock], int, bool, str]:
    try:
        import docx  # type: ignore  # python-docx
    except ImportError:
        log.warning("python-docx 미설치 — DOCX 파싱 불가")
        return [], 0, True, "none"

    try:
        document = docx.Document(io.BytesIO(data))
    except Exception as e:
        log.error("DOCX open 실패: %s", e)
        return [], 0, True, "none"

    blocks = _Blocks()
    # 본문 순서(문단/표 혼재)를 보존하며 순회
    from docx.document import Document as _Doc  # type: ignore
    from docx.oxml.table import CT_Tbl  # type: ignore
    from docx.oxml.text.paragraph import CT_P  # type: ignore
    from docx.table import Table  # type: ignore
    from docx.text.paragraph import Paragraph  # type: ignore

    parent_elm = document.element.body
    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            para = Paragraph(child, document)
            style = (para.style.name or "").lower() if para.style else ""
            text = para.text
            if not text.strip():
                continue
            if style.startswith("heading"):
                level = _docx_heading_level(style)
                blocks.add("heading", text, level=level)
            elif style.startswith("toc") or _looks_like_toc(text):
                blocks.add("toc", text)
            elif style.startswith("list"):
                blocks.add("list", text)
            else:
                blocks.add("paragraph", text)
        elif isinstance(child, CT_Tbl):
            table = Table(child, document)
            tbl_model = _docx_table(table)
            blocks.add("table", _table_to_text(tbl_model), table=tbl_model)

    return blocks.items, 1, False, "python-docx"


def _docx_heading_level(style: str) -> int:
    m = re.search(r"(\d+)", style)
    return int(m.group(1)) if m else 1


def _docx_table(table: Any) -> TableModel:
    """DOCX 표 → rows + 셀 내부 중첩표 재귀."""
    rows: list[list[str]] = []
    nested: list[TableModel] = []
    for row in table.rows:
        cells: list[str] = []
        for cell in row.cells:
            cells.append(cell.text.strip())
            for inner in cell.tables:
                nested.append(_docx_table(inner))
        rows.append(cells)
    return TableModel(rows=rows, nested=nested)


# ── HWPX (ZIP + OWPML) ────────────────────────────────────────────────────────

_HP_NS = {"hp": "http://www.hancom.co.kr/hwpml/2011/paragraph"}


def _parse_hwpx(data: bytes) -> tuple[list[DocumentBlock], int, bool, str]:
    try:
        from lxml import etree  # type: ignore
    except ImportError:
        log.warning("lxml 미설치 — HWPX 파싱 불가")
        return [], 0, True, "none"

    blocks = _Blocks()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            sections = sorted(n for n in zf.namelist()
                              if re.match(r"Contents/section\d+\.xml", n))
            if not sections:
                return [], 0, True, "none"
            for sec in sections:
                root = etree.fromstring(zf.read(sec))
                _hwpx_walk(root, blocks)
    except Exception as e:
        log.error("HWPX 파싱 오류: %s", e)
        return blocks.items, 1, True, "hwpx"

    return blocks.items, 1, False, "hwpx"


def _hwpx_local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def _hwpx_walk(root: Any, blocks: _Blocks) -> None:
    """section XML 을 순회하며 문단(<hp:p>)·표(<hp:tbl>) 블록화."""
    for elem in root.iter():
        local = _hwpx_local(elem.tag)
        if local == "tbl":
            tbl = _hwpx_table(elem)
            blocks.add("table", _table_to_text(tbl), table=tbl)
        elif local == "p":
            # 표 안의 문단은 표 처리에서 흡수되므로 최상위 문단만
            if _hwpx_inside_table(elem):
                continue
            text = _hwpx_text(elem)
            if text.strip():
                blocks.add("toc" if _looks_like_toc(text) else "paragraph", text)


def _hwpx_inside_table(elem: Any) -> bool:
    parent = elem.getparent()
    while parent is not None:
        if _hwpx_local(parent.tag) == "tbl":
            return True
        parent = parent.getparent()
    return False


def _hwpx_text(elem: Any) -> str:
    parts: list[str] = []
    for t in elem.iter():
        if _hwpx_local(t.tag) == "t" and t.text:
            parts.append(t.text)
    return "".join(parts)


def _hwpx_table(tbl_elem: Any) -> TableModel:
    """<hp:tbl> → rows + 셀 내부 중첩표 재귀."""
    rows: list[list[str]] = []
    nested: list[TableModel] = []
    for tr in tbl_elem:
        if _hwpx_local(tr.tag) != "tr":
            continue
        cells: list[str] = []
        for tc in tr:
            if _hwpx_local(tc.tag) != "tc":
                continue
            cells.append(_hwpx_cell_text(tc))
            for sub in tc.iter():
                if _hwpx_local(sub.tag) == "tbl" and sub is not tbl_elem:
                    nested.append(_hwpx_table(sub))
        rows.append(cells)
    return TableModel(rows=rows, nested=nested)


def _hwpx_cell_text(tc: Any) -> str:
    parts: list[str] = []
    for t in tc.iter():
        if _hwpx_local(t.tag) == "t" and t.text:
            parts.append(t.text)
    return "".join(parts).strip()


# ── HWP 바이너리(다단계 폴백) ─────────────────────────────────────────────────

def _parse_hwp_binary(data: bytes, ocr_fallback: bool) -> tuple[list[DocumentBlock], int, bool, str]:
    """순수 HWP v5: hwp5html(표 보존) → LibreOffice 변환(PDF) → hwp5txt(텍스트) 순 폴백."""
    # Tier 2: hwp5html → 표 보존 XHTML
    html = _hwp5html(data)
    if html:
        blocks = _html_to_blocks(html)
        if blocks:
            return blocks, 1, False, "hwp5html"

    # Tier 3: LibreOffice → PDF 변환 후 PDF 경로 재사용
    pdf_bytes = _soffice_to_pdf(data, "hwp")
    if pdf_bytes:
        pdf_blocks, pages, _, _ = _parse_pdf(pdf_bytes, ocr_fallback)
        if pdf_blocks:
            return pdf_blocks, pages, True, "libreoffice+pymupdf"

    # Tier 4: hwp5txt 텍스트만
    text = _hwp5txt(data)
    if text:
        blocks = _Blocks()
        for line in text.splitlines():
            if line.strip():
                blocks.add("paragraph", line)
        return blocks.items, 1, True, "hwp5txt"

    log.warning("HWP 모든 폴백 실패 — degraded")
    return [], 0, True, "none"


def _hwp5html(data: bytes) -> str:
    """pyhwp hwp5html CLI 로 XHTML 생성. 표를 <table> 로 보존."""
    try:
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "in.hwp")
            out = os.path.join(tmp, "out")
            with open(src, "wb") as f:
                f.write(data)
            subprocess.run(
                ["hwp5html", "--output", out, src],
                check=True, capture_output=True, timeout=_HWP5HTML_TIMEOUT,
            )
            index = os.path.join(out, "index.xhtml")
            if os.path.exists(index):
                with open(index, "r", encoding="utf-8", errors="ignore") as f:
                    return f.read()
    except FileNotFoundError:
        log.debug("hwp5html(pyhwp) 미설치 — SKIP")
    except subprocess.TimeoutExpired:
        log.warning("hwp5html 타임아웃")
    except Exception as e:
        log.warning("hwp5html 변환 오류: %s", e)
    return ""


def _hwp5txt(data: bytes) -> str:
    try:
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "in.hwp")
            with open(src, "wb") as f:
                f.write(data)
            res = subprocess.run(
                ["hwp5txt", src],
                check=True, capture_output=True, timeout=_HWP5HTML_TIMEOUT,
            )
            return res.stdout.decode("utf-8", errors="ignore")
    except FileNotFoundError:
        log.debug("hwp5txt(pyhwp) 미설치 — SKIP")
    except Exception as e:
        log.warning("hwp5txt 변환 오류: %s", e)
    return ""


def _soffice_to_pdf(data: bytes, ext: str) -> bytes:
    """LibreOffice headless 로 임의 포맷 → PDF 변환(H2Orestart 필터로 HWP 지원)."""
    try:
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, f"in.{ext}")
            with open(src, "wb") as f:
                f.write(data)
            subprocess.run(
                ["soffice", "--headless", "--convert-to", "pdf", "--outdir", tmp, src],
                check=True, capture_output=True, timeout=_SOFFICE_TIMEOUT,
            )
            pdf_path = os.path.join(tmp, "in.pdf")
            if os.path.exists(pdf_path):
                with open(pdf_path, "rb") as f:
                    return f.read()
    except FileNotFoundError:
        log.debug("soffice(LibreOffice) 미설치 — SKIP")
    except subprocess.TimeoutExpired:
        log.warning("LibreOffice 변환 타임아웃")
    except Exception as e:
        log.warning("LibreOffice 변환 오류: %s", e)
    return b""


def _html_to_blocks(html: str) -> list[DocumentBlock]:
    """XHTML → 블록(표는 rows 보존). lxml 우선, 미설치 시 빈 결과."""
    try:
        from lxml import html as lxml_html  # type: ignore
    except ImportError:
        return []

    blocks = _Blocks()
    try:
        root = lxml_html.fromstring(html)
    except Exception:
        return []

    body = root.find(".//body")
    target = body if body is not None else root
    for elem in target.iter():
        tag = (elem.tag or "").lower() if isinstance(elem.tag, str) else ""
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            blocks.add("heading", elem.text_content(), level=int(tag[1]))
        elif tag == "table":
            tbl = _html_table(elem)
            blocks.add("table", _table_to_text(tbl), table=tbl)
        elif tag == "p":
            if elem.getparent() is not None and (elem.getparent().tag or "") in ("td", "th"):
                continue  # 표 안 문단은 표에서 흡수
            text = elem.text_content()
            if text.strip():
                blocks.add("toc" if _looks_like_toc(text) else "paragraph", text)
    return blocks.items


def _html_table(table_elem: Any) -> TableModel:
    rows: list[list[str]] = []
    nested: list[TableModel] = []
    for tr in table_elem.iter("tr"):
        cells: list[str] = []
        for td in tr:
            if (td.tag or "").lower() not in ("td", "th"):
                continue
            cells.append(td.text_content().strip())
            for inner in td.iter("table"):
                if inner is not table_elem:
                    nested.append(_html_table(inner))
        if cells:
            rows.append(cells)
    return TableModel(rows=rows, nested=nested)


# ── 표 → 텍스트 평탄화(검색·요약용) ──────────────────────────────────────────

def _table_to_text(table: TableModel) -> str:
    lines = [" | ".join(c for c in row if c) for row in table.rows]
    flat = "\n".join(ln for ln in lines if ln.strip())
    for nested in table.nested:
        nested_text = _table_to_text(nested)
        if nested_text:
            flat += "\n[중첩표]\n" + nested_text
    return flat


# ── 엔드포인트 ───────────────────────────────────────────────────────────────

@router.get("/health")
def parse_health() -> dict:
    available: list[str] = []
    for mod, label in (("fitz", "pymupdf"), ("pdfplumber", "pdfplumber"),
                       ("docx", "python-docx"), ("lxml", "lxml")):
        try:
            __import__(mod)
            available.append(label)
        except ImportError:
            pass
    for cli in ("hwp5html", "soffice"):
        if _which(cli):
            available.append(cli)
    return {"status": "UP" if available else "DEGRADED", "parsers": available}


def _which(cmd: str) -> bool:
    from shutil import which
    return which(cmd) is not None


@router.post("/document", response_model=ParseResponse)
def parse_document(req: ParseRequest) -> ParseResponse:
    try:
        data = base64.b64decode(req.document_b64)
    except Exception:
        raise HTTPException(status_code=400, detail="document_b64 디코딩 실패")
    if not data:
        raise HTTPException(status_code=400, detail="빈 문서")

    fmt = _detect_format(data, req.filename, req.doc_format)

    if fmt == "PDF":
        blocks, pages, degraded, engine = _parse_pdf(data, req.ocr_fallback)
    elif fmt == "DOCX":
        blocks, pages, degraded, engine = _parse_docx(data)
    elif fmt == "HWPX":
        blocks, pages, degraded, engine = _parse_hwpx(data)
    elif fmt == "HWP":
        blocks, pages, degraded, engine = _parse_hwp_binary(data, req.ocr_fallback)
    elif fmt == "TXT":
        blocks, pages, degraded, engine = _parse_txt(data)
    else:
        raise HTTPException(status_code=400, detail=f"지원하지 않는 포맷: {fmt}")

    log.info("문서 파싱 완료: submissionId=%s fmt=%s blocks=%d pages=%d degraded=%s engine=%s",
             req.submission_id, fmt, len(blocks), pages, degraded, engine)

    return ParseResponse(
        submission_id=req.submission_id,
        doc_format=fmt,
        page_count=pages,
        blocks=blocks,
        degraded=degraded,
        engine=engine,
    )


def _parse_txt(data: bytes) -> tuple[list[DocumentBlock], int, bool, str]:
    text = data.decode("utf-8", errors="ignore")
    blocks = _Blocks()
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if para:
            blocks.add("toc" if _looks_like_toc(para) else "paragraph", para)
    return blocks.items, 1, False, "text"
