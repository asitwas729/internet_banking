"""외부 시드 — Kaggle Home Credit Default Risk → parquet.

application_train (EXT_SOURCE + TARGET + 인구통계/금융) 과 bureau (신용조회 이력) 를
data/external/credit/home-credit-default/ 아래 parquet 으로 저장한다.

- application_train: financial_profile 의 EXT_SOURCE 시드 + PD 모델 실 TARGET
- bureau: PD 모델 bureau_* 집계 피처 원천

사전 요건: ~/.kaggle/kaggle.json (또는 KAGGLE_USERNAME/KAGGLE_KEY) + 대회 규칙 동의.
사용:
    python -m scripts.fetch_home_credit
"""

from __future__ import annotations

import argparse
import logging
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import pandas as pd  # noqa: E402

from loaders.config import PROJECT_ROOT  # noqa: E402

COMPETITION = "home-credit-default-risk"
BASE = PROJECT_ROOT / "data" / "external" / "credit" / "home-credit-default"

# 파일명 → 저장 서브디렉터리
FILES = {
    "application_train.csv": "application_train",
    "bureau.csv": "bureau",
}


def _download_file(name: str, tmp: Path) -> Path:
    import kaggle

    kaggle.api.competition_download_file(COMPETITION, name, path=str(tmp))
    # kaggle 은 .zip 으로 받기도 함
    zipped = tmp / f"{name}.zip"
    if zipped.exists():
        with zipfile.ZipFile(zipped) as z:
            z.extractall(tmp)
        return tmp / name
    return tmp / name


def fetch(log: logging.Logger) -> None:
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        for csv_name, subdir in FILES.items():
            log.info("downloading %s ...", csv_name)
            csv_path = _download_file(csv_name, tmp)
            df = pd.read_csv(csv_path)
            out_dir = BASE / subdir
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / f"{subdir}.parquet"
            df.to_parquet(out_path, index=False)
            log.info("saved %s: %d rows × %d cols → %s", csv_name, len(df), df.shape[1], out_path)
            if csv_name == "application_train.csv":
                rate = float(df["TARGET"].mean())
                ext = df[["EXT_SOURCE_1", "EXT_SOURCE_2", "EXT_SOURCE_3"]].mean(axis=1).dropna()
                log.info("  TARGET default rate=%.4f | EXT_SOURCE valid=%d", rate, len(ext))


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.parse_args()
    log = logging.getLogger("fetch_home_credit")
    try:
        fetch(log)
    except Exception as e:
        log.error("Home Credit 다운로드 실패: %s", e)
        log.error("→ https://www.kaggle.com/c/home-credit-default-risk/rules 에서 규칙 동의 후 재시도")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
