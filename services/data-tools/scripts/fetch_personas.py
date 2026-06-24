"""Layer 1 시드 — NVIDIA Nemotron-Personas-Korea → persona slim parquet.

HuggingFace `nvidia/Nemotron-Personas-Korea` (CC-BY-4.0, 한국 인구통계 합성 페르소나)
에서 인구통계 13개 컬럼만 추려 `data/synthetic/personas/slim/personas.parquet` 로 저장한다.
persona_sampler.sample() 이 이 풀에서 reweighting 샘플링(replace=False) 하므로
--max-rows 는 합성 목표 건수보다 충분히 커야 한다.

사용:
    python -m scripts.fetch_personas                 # 기본 200k
    python -m scripts.fetch_personas --max-rows 50000
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import pandas as pd  # noqa: E402

from loaders.config import PROJECT_ROOT  # noqa: E402

HF_DATASET = "nvidia/Nemotron-Personas-Korea"
PERSONA_DIR = PROJECT_ROOT / "data" / "synthetic" / "personas" / "slim"

# persona_sampler / Layer 1 모델 피처가 요구하는 인구통계 컬럼만 유지.
SLIM_COLS = [
    "uuid",
    "sex",
    "age",
    "marital_status",
    "military_status",
    "family_type",
    "housing_type",
    "education_level",
    "bachelors_field",
    "occupation",
    "district",
    "province",
    "country",
]


def fetch(max_rows: int, out_dir: Path) -> Path:
    from datasets import load_dataset

    log = logging.getLogger("fetch_personas")
    log.info("streaming %s (max_rows=%d)", HF_DATASET, max_rows)

    # streaming 으로 필요한 만큼만 수집 — 거대한 persona 텍스트 컬럼 전체 다운로드 회피
    stream = load_dataset(HF_DATASET, split="train", streaming=True)
    rows: list[dict] = []
    for i, rec in enumerate(stream):
        rows.append({c: rec.get(c) for c in SLIM_COLS})
        if len(rows) >= max_rows:
            break
        if (i + 1) % 20000 == 0:
            log.info("  collected %d rows", len(rows))

    df = pd.DataFrame(rows, columns=SLIM_COLS)
    # 타입 정규화 — age 정수, 나머지 문자열
    df["age"] = pd.to_numeric(df["age"], errors="coerce").astype("Int64")
    df = df.dropna(subset=["age", "occupation"]).reset_index(drop=True)
    df["age"] = df["age"].astype(int)

    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "personas.parquet"
    df.to_parquet(out_path, index=False)
    log.info("saved %d personas → %s", len(df), out_path)
    log.info("sex=%s | top occupations=%s",
             df["sex"].value_counts().to_dict(),
             df["occupation"].value_counts().head(5).to_dict())
    return out_path


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-rows", type=int, default=200_000)
    parser.add_argument("--out", default=str(PERSONA_DIR))
    args = parser.parse_args()

    fetch(args.max_rows, Path(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
