"""
advisory-service RAG 정책문서 시드 업로더.

services/ai-service/seed-data/ 하위 .md / .txt 파일을 읽어
advisory-service POST /api/internal/advisory/documents 로 적재한다.

폴더 → docCategoryCd 매핑:
    law/          → LAW
    supervision/  → SUPERVISION_GUIDE
    internal/     → INTERNAL_RULE
    product/      → PRODUCT_TERMS
    fair-lending/ → FAIR_LENDING

파일 옆에 동일 stem .meta.yml 이 있으면 메타데이터를 덮어쓴다.
없으면 파일명 stem → doc_cd, 오늘 날짜 → effectiveStartDate.

usage:
    pip install requests pyyaml
    python scripts/upload_seed_data.py [--host http://localhost:8083] [--dry-run] [--force]

options:
    --host    advisory-service 주소 (기본: http://localhost:8083)
    --dry-run 실제 API 호출 없이 업로드 목록만 출력
    --force   doc_cd 충돌 시 기존 문서 비활성화 후 재등록
"""

import argparse
import os
import sys
import time
from datetime import date
from pathlib import Path

import requests

try:
    import yaml
    YAML_AVAILABLE = True
except ImportError:
    YAML_AVAILABLE = False

# ── 설정 ───────────────────────────────────────────────────────────────────

SEED_ROOT = Path(__file__).parent.parent / "seed-data"

# 폴더명 → docCategoryCd
FOLDER_TO_CATEGORY = {
    "law":           "LAW",
    "supervision":   "SUPERVISION_GUIDE",
    "internal":      "INTERNAL_RULE",
    "product":       "PRODUCT_TERMS",
    "fair-lending":  "FAIR_LENDING",
}

SUPPORTED_EXTENSIONS = {".md", ".txt"}

REGISTER_URL  = "/api/internal/advisory/documents"
ACTIVATE_URL  = "/api/internal/advisory/documents/{doc_id}/activate"
HEADERS = {"Content-Type": "application/json", "X-Actor-Role": "ADMIN"}

# ── 유틸 ───────────────────────────────────────────────────────────────────

def today_str() -> str:
    return date.today().strftime("%Y%m%d")

def load_meta(file_path: Path) -> dict:
    """동일 stem .meta.yml 이 있으면 읽어서 반환. 없으면 빈 dict."""
    if not YAML_AVAILABLE:
        return {}
    meta_path = file_path.with_suffix(".meta.yml")
    if not meta_path.exists():
        return {}
    with open(meta_path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}

def build_payload(file_path: Path, folder_name: str) -> dict:
    """파일과 메타 정보로 DocumentRegisterRequest payload 구성."""
    meta = load_meta(file_path)
    doc_cd  = meta.get("doc_cd",  file_path.stem.upper())
    content = file_path.read_text(encoding="utf-8")

    return {
        "docCd":              doc_cd,
        "docTitle":           meta.get("title",   doc_cd.replace("_", " ").title()),
        "docCategoryCd":      meta.get("category", FOLDER_TO_CATEGORY.get(folder_name, "POLICY")),
        "docVersion":         meta.get("version", "1.0"),
        "effectiveStartDate": meta.get("effective_start_date", today_str()),
        "effectiveEndDate":   meta.get("effective_end_date", "99991231"),
        "sourceUri":          meta.get("source_url", None),
        "docDesc":            meta.get("notes", f"{doc_cd} 시드 문서"),
        "content":            content,
    }

def collect_files() -> list[tuple[Path, str]]:
    """seed-data/ 하위 지원 확장자 파일을 (path, folder_name) 목록으로 반환."""
    results = []
    if not SEED_ROOT.exists():
        print(f"[ERROR] seed-data 폴더 없음: {SEED_ROOT}", file=sys.stderr)
        sys.exit(1)

    for folder_name in FOLDER_TO_CATEGORY:
        folder = SEED_ROOT / folder_name
        if not folder.exists():
            continue
        for file in sorted(folder.iterdir()):
            if file.suffix.lower() in SUPPORTED_EXTENSIONS and not file.name.startswith("."):
                results.append((file, folder_name))

    return results

# ── 업로드 ─────────────────────────────────────────────────────────────────

def register_document(host: str, payload: dict, force: bool) -> str:
    """
    문서 등록. 반환값: 'created' | 'skipped' | 'forced' | 'error:<msg>'
    """
    url = host + REGISTER_URL
    resp = requests.post(url, json=payload, headers=HEADERS, timeout=30)

    if resp.status_code == 201:
        return "created"

    if resp.status_code == 409:
        # doc_cd 중복
        if not force:
            return "skipped"
        # --force: 기존 문서 비활성화 후 재등록
        doc_id = _find_doc_id_by_code(host, payload["docCd"])
        if doc_id:
            deactivate_url = host + ACTIVATE_URL.format(doc_id=doc_id) + "?active=false"
            requests.put(deactivate_url, headers=HEADERS, timeout=10)
            time.sleep(0.1)
        resp2 = requests.post(url, json=payload, headers=HEADERS, timeout=30)
        if resp2.status_code == 201:
            return "forced"
        return f"error:{resp2.status_code} {resp2.text[:100]}"

    return f"error:{resp.status_code} {resp.text[:100]}"

def _find_doc_id_by_code(host: str, doc_cd: str) -> int | None:
    """활성 문서 목록에서 doc_cd 로 docId 를 찾는다. (간단 구현: 없으면 None)"""
    try:
        resp = requests.get(
            host + "/api/internal/advisory/documents",
            params={"docCd": doc_cd, "size": 1},
            headers=HEADERS,
            timeout=10,
        )
        if resp.status_code == 200:
            data = resp.json().get("data", {})
            content = data.get("content", [])
            if content:
                return content[0].get("docId")
    except Exception:
        pass
    return None

# ── 메인 ───────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="advisory-service RAG 정책문서 시드 업로더")
    parser.add_argument("--host",    default="http://localhost:8083",
                        help="advisory-service 주소 (기본: http://localhost:8083)")
    parser.add_argument("--dry-run", action="store_true",
                        help="실제 API 호출 없이 업로드 예정 목록만 출력")
    parser.add_argument("--force",   action="store_true",
                        help="doc_cd 충돌 시 기존 문서 비활성화 후 재등록")
    args = parser.parse_args()

    files = collect_files()
    if not files:
        print("[INFO] 업로드할 파일 없음. seed-data/ 하위 .md / .txt 파일을 추가하세요.")
        return

    print(f"[INFO] 발견된 파일: {len(files)}건  host={args.host}  dry-run={args.dry_run}  force={args.force}")
    print()

    counts = {"created": 0, "skipped": 0, "forced": 0, "error": 0}

    for file_path, folder_name in files:
        payload = build_payload(file_path, folder_name)
        rel = file_path.relative_to(SEED_ROOT)

        if args.dry_run:
            print(f"  [DRY-RUN] {rel}  →  doc_cd={payload['docCd']}  category={payload['docCategoryCd']}"
                  f"  chars={len(payload['content'])}")
            counts["created"] += 1
            continue

        result = register_document(args.host, payload, args.force)
        icon = {"created": "✅", "skipped": "⏭ ", "forced": "🔄"}.get(result.split(":")[0], "❌")
        print(f"  {icon} {rel}  →  {result}")

        key = result.split(":")[0] if result.startswith("error") else result
        counts[key] = counts.get(key, 0) + 1

        time.sleep(0.05)  # rate limit 방지

    print()
    print(f"[완료] 등록={counts['created']}  건너뜀={counts['skipped']}"
          f"  재등록={counts['forced']}  오류={counts.get('error', 0)}")

if __name__ == "__main__":
    main()
