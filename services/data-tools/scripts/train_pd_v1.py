"""homecredit_kr_v1 PD 모델 실학습 + ONNX 번들 저장.

Home Credit 실 TARGET 으로 학습해 data/models/homecredit_kr_v1/ 에 추론 번들 저장.

사용:
    python -m scripts.train_pd_v1                    # 전체
    python -m scripts.train_pd_v1 --sample 100000 --tune
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from loaders.config import PROJECT_ROOT  # noqa: E402
from synthesize.home_credit_pd import build_pd_dataset  # noqa: E402
from training.dataset import split  # noqa: E402
from training.io import save_onnx_bundle  # noqa: E402
from training.onnx_export import onnx_smoke_check  # noqa: E402
from training.train_pd import PdTrainConfig, pd_calibrator_to_dict, train_pd  # noqa: E402

MODEL_DIR = PROJECT_ROOT / "data" / "models" / "homecredit_kr_v1"


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample", type=int, default=None, help="행 샘플 수(기본 전체)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--tune", action="store_true")
    parser.add_argument("--n-trials", type=int, default=25)
    parser.add_argument("--desired-rate", type=float, default=0.03)
    parser.add_argument("--out", default=str(MODEL_DIR))
    args = parser.parse_args()

    log = logging.getLogger("train_pd_v1")
    df = build_pd_dataset(seed=args.seed, sample=args.sample)
    splits = split(df)
    log.info("splits: %s", splits.summary())

    result = train_pd(splits, PdTrainConfig(seed=args.seed), tune=args.tune,
                      n_trials=args.n_trials, desired_positive_rate=args.desired_rate)
    log.info("holdout: %s", result.holdout.to_dict())

    calibrator_json = pd_calibrator_to_dict(result.calibrator) if result.calibrator else None
    model_dir = save_onnx_bundle(
        Path(args.out), result.booster, result.schema, splits.train,
        model_version="homecredit_kr_v1", holdout_metrics=result.holdout.to_dict(),
        calibrator_json=calibrator_json,
        extra_meta={"pd_threshold": result.holdout.pd_threshold,
                    "desired_positive_rate": args.desired_rate,
                    "best_iteration": result.best_iteration},
    )

    max_diff = onnx_smoke_check(result.booster, model_dir / "model.onnx",
                                splits.holdout.head(200), result.schema, atol=1e-4)
    log.info("ONNX smoke max_diff=%.2e", max_diff)
    log.info("=== homecredit_kr_v1 saved: Gini=%.4f KS=%.4f lift1=%.2f → %s ===",
             result.holdout.gini, result.holdout.ks, result.holdout.lift_decile1, model_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
