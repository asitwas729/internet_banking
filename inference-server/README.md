# inference-server

자동심사 ML 모델 추론 + 문서 OCR/파싱 서버. ai-service·doc-agent·advisory(Java) 게이트웨이가 호출한다.
운영 ML 패턴으로 Python 모델/파서 서빙과 Java 비즈니스 로직을 분리.

## 실행 (로컬)

```bash
cd inference-server
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8090
```

## 실행 (Docker)

```bash
docker build -t inference-server inference-server
# 모델 아티팩트는 볼륨/MODEL_DIR 로 주입 (startup 시 로드)
docker run -p 8090:8090 -e MODEL_DIR=/models/auto_review_v1 \
  -v $PWD/data/models/auto_review_v1:/models/auto_review_v1 inference-server
```

이미지에는 규정문서 파싱 런타임 의존성이 포함된다:
- 파서: PyMuPDF·pdfplumber(PDF), python-docx(DOCX), lxml/pyhwp(HWPX/HWP)
- PaddleOCR·PP-StructureV2 (스캔 PDF·표 구조)
- **LibreOffice + JRE + H2Orestart**: 바이너리 HWP → PDF 변환 폴백
- fonts-nanum: 한글 렌더링/OCR

환경변수:
- `MODEL_VERSION` (기본 `v1`) — `data/models/auto_review_<v>/` 디렉토리 선택
- `MODEL_DIR` — 절대 경로 직지정 (Docker 마운트용)
- `PARSE_SOFFICE_TIMEOUT` (기본 30) / `PARSE_HWP5HTML_TIMEOUT` (기본 20) — HWP 변환 폴백 타임아웃(초)

## API

### `GET /health`
모델 로드 상태 + holdout accuracy.

### `POST /predict`
```json
{
  "features": [
    { "sex": "남자", "age": 35, "occupation": "사무 보조원", "dsr": 0.35, ... }
  ]
}
```
응답:
```json
{
  "model_version": "v1",
  "predictions": [
    {
      "decision": "APPROVE",
      "score": 0.91,
      "proba": { "APPROVE": 0.91, "CONDITIONAL": 0.07, "REJECT": 0.02 }
    }
  ]
}
```

누락된 키는 NaN/null 로 처리되며 XGBoost 가 missing 분기로 라우팅한다.

### `POST /parse/document`
규정문서(PDF/DOCX/HWP/HWPX) → 구조 블록 목록 (advisory RAG 인입용).
```json
{ "document_b64": "<base64>", "filename": "policy.pdf", "doc_format": "AUTO", "ocr_fallback": true }
```
응답: `{ doc_format, page_count, degraded, engine, blocks:[{block_type, text, page, level, block_seq, table}] }`
- `block_type`: heading/paragraph/table/toc/header/footer/list
- HWP 는 다단계 폴백: HWPX 네이티브 → hwp5html → LibreOffice 변환 → hwp5txt
- `degraded=true` 는 저품질 신호(스캔본 OCR·HWP 텍스트-only 등)

### `GET /parse/health`
포맷별 파서·CLI 가용성.
