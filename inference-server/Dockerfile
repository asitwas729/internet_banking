# inference-server — 자동심사 ML 추론 + 규정문서 파싱(/parse/document)
#
# 무거운 런타임 의존성을 한 이미지에 담는다:
#   - 파서 라이브러리: PyMuPDF/pdfplumber/python-docx/lxml/pyhwp (requirements.txt)
#   - PaddleOCR/PP-StructureV2 런타임 (libgl/libgomp)
#   - LibreOffice + JRE + H2Orestart: 바이너리 HWP → PDF 변환 폴백 (HWP import 필터)
#   - 한글 폰트(fonts-nanum): LibreOffice 렌더링·OCR 정확도
#
# 주의: startup 훅이 data/models/auto_review_<v>/ 를 로드한다.
#       런타임에 MODEL_DIR 환경변수 또는 볼륨 마운트로 모델 아티팩트를 제공해야 기동된다.
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DEBIAN_FRONTEND=noninteractive

# 시스템 의존성
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-core libreoffice-writer \
        default-jre-headless \
        fonts-nanum \
        libgl1 libglib2.0-0 libgomp1 \
        curl unzip \
    && rm -rf /var/lib/apt/lists/*

# H2Orestart — LibreOffice HWP/HWPX import 확장 (Java 기반 → JRE 필요)
ARG H2O_VERSION=0.7.12
RUN curl -fsSL -o /tmp/H2Orestart.oxt \
        "https://github.com/ebandal/H2Orestart/releases/download/v${H2O_VERSION}/H2Orestart.oxt" \
    && unopkg add --shared /tmp/H2Orestart.oxt \
    && rm -f /tmp/H2Orestart.oxt

WORKDIR /app

COPY requirements.txt ./
RUN pip install -r requirements.txt

COPY app ./app

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
