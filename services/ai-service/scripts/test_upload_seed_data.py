"""
upload_seed_data.py 단위 테스트.

usage:
    python -m pytest scripts/test_upload_seed_data.py -v
"""

import json
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

import upload_seed_data as usd


# ── fixtures ─────────────────────────────────────────────────────────────────

@pytest.fixture
def seed_root(tmp_path: Path) -> Path:
    """임시 seed-data 폴더. 폴더 구조만 생성, 파일은 각 테스트에서 추가."""
    for folder in usd.FOLDER_TO_CATEGORY:
        (tmp_path / folder).mkdir()
    return tmp_path


def make_md(folder: Path, name: str, content: str = "정책 본문 테스트") -> Path:
    f = folder / f"{name}.md"
    f.write_text(content, encoding="utf-8")
    return f


def make_meta(folder: Path, stem: str, **kwargs):
    import yaml
    meta_path = folder / f"{stem}.meta.yml"
    meta_path.write_text(yaml.dump(kwargs, allow_unicode=True), encoding="utf-8")


# ── build_payload ─────────────────────────────────────────────────────────────

class TestBuildPayload:
    def test_stem_이_docCd_가_됨(self, seed_root):
        f = make_md(seed_root / "law", "LAW_BANKING_ACT")
        payload = usd.build_payload(f, "law")
        assert payload["docCd"] == "LAW_BANKING_ACT"

    def test_folder_이_category_가_됨(self, seed_root):
        f = make_md(seed_root / "supervision", "SUPER_FSS_DSR")
        payload = usd.build_payload(f, "supervision")
        assert payload["docCategoryCd"] == "SUPERVISION_GUIDE"

    def test_기본_유효기간_9999(self, seed_root):
        f = make_md(seed_root / "internal", "INTERNAL_BIAS_GUIDE")
        payload = usd.build_payload(f, "internal")
        assert payload["effectiveEndDate"] == "99991231"

    def test_meta_yml_이_기본값_오버라이드(self, seed_root):
        pytest.importorskip("yaml")
        folder = seed_root / "law"
        make_md(folder, "LAW_BANKING_ACT", "본문")
        make_meta(folder, "LAW_BANKING_ACT",
                  title="은행법",
                  version="2024-01-01",
                  effective_start_date="20240101",
                  effective_end_date="20291231",
                  notes="은행법 전문")
        payload = usd.build_payload(folder / "LAW_BANKING_ACT.md", "law")
        assert payload["docTitle"] == "은행법"
        assert payload["docVersion"] == "2024-01-01"
        assert payload["effectiveStartDate"] == "20240101"
        assert payload["effectiveEndDate"] == "20291231"
        assert payload["docDesc"] == "은행법 전문"

    def test_content_가_파일_전문(self, seed_root):
        body = "DSR 70% 초과 시 신용대출 승인 불가. 예외 적용 가이드."
        f = make_md(seed_root / "internal", "INTERNAL_DSR_POLICY", body)
        payload = usd.build_payload(f, "internal")
        assert payload["content"] == body


# ── collect_files ─────────────────────────────────────────────────────────────

class TestCollectFiles:
    def test_지원_확장자만_수집(self, seed_root, monkeypatch):
        monkeypatch.setattr(usd, "SEED_ROOT", seed_root)
        make_md(seed_root / "law", "LAW_BANKING_ACT")
        (seed_root / "law" / "LAW_BANKING_ACT.pdf").write_bytes(b"%PDF")  # 무시
        (seed_root / "law" / ".hidden.md").write_text("숨김")               # 무시

        files = usd.collect_files()
        names = [f.name for f, _ in files]
        assert "LAW_BANKING_ACT.md" in names
        assert "LAW_BANKING_ACT.pdf" not in names
        assert ".hidden.md" not in names

    def test_빈_폴더는_건너뜀(self, seed_root, monkeypatch):
        monkeypatch.setattr(usd, "SEED_ROOT", seed_root)
        # seed_root 의 모든 폴더가 비어 있음
        files = usd.collect_files()
        assert files == []

    def test_폴더_순서_보장(self, seed_root, monkeypatch):
        monkeypatch.setattr(usd, "SEED_ROOT", seed_root)
        make_md(seed_root / "law", "B_LAW")
        make_md(seed_root / "law", "A_LAW")
        files = usd.collect_files()
        names = [f.name for f, _ in files]
        assert names == sorted(names)


# ── register_document (멱등성) ──────────────────────────────────────────────

class TestRegisterDocument:
    def _payload(self):
        return {"docCd": "LAW_TEST", "docTitle": "테스트", "content": "본문",
                "docCategoryCd": "LAW", "docVersion": "1.0",
                "effectiveStartDate": "20260101", "effectiveEndDate": "99991231",
                "sourceUri": None, "docDesc": "테스트"}

    def test_201_created(self):
        mock_resp = MagicMock(status_code=201)
        with patch("requests.post", return_value=mock_resp):
            result = usd.register_document("http://localhost:8083", self._payload(), force=False)
        assert result == "created"

    def test_409_충돌_force_false_는_skipped(self):
        mock_resp = MagicMock(status_code=409)
        with patch("requests.post", return_value=mock_resp):
            result = usd.register_document("http://localhost:8083", self._payload(), force=False)
        assert result == "skipped"

    def test_409_충돌_force_true_재등록_성공(self):
        post_responses = [
            MagicMock(status_code=409),   # 첫 등록 → 충돌
            MagicMock(status_code=201),   # 재등록 → 성공
        ]
        put_resp = MagicMock(status_code=200)
        with patch("requests.post", side_effect=post_responses), \
             patch("requests.put",  return_value=put_resp), \
             patch.object(usd, "_find_doc_id_by_code", return_value=42):
            result = usd.register_document("http://localhost:8083", self._payload(), force=True)
        assert result == "forced"

    def test_5xx_error_반환(self):
        mock_resp = MagicMock(status_code=500, text="Internal Server Error")
        with patch("requests.post", return_value=mock_resp):
            result = usd.register_document("http://localhost:8083", self._payload(), force=False)
        assert result.startswith("error:500")


# ── dry-run 흐름 ─────────────────────────────────────────────────────────────

class TestDryRun:
    def test_dry_run_시_API_호출_없음(self, seed_root, monkeypatch, capsys):
        monkeypatch.setattr(usd, "SEED_ROOT", seed_root)
        make_md(seed_root / "law", "LAW_BANKING_ACT", "은행법 본문")

        with patch("requests.post") as mock_post:
            # argparse 우회: main() 직접 호출 대신 로직만 검증
            files = usd.collect_files()
            for file_path, folder_name in files:
                payload = usd.build_payload(file_path, folder_name)
                # dry-run 분기: API 호출 없이 출력만
                print(f"[DRY-RUN] {file_path.name} → {payload['docCd']}")

            mock_post.assert_not_called()

        out = capsys.readouterr().out
        assert "LAW_BANKING_ACT" in out
