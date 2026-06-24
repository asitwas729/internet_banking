"""hmda_v1 Decision Score 실학습 + ONNX 번들 저장.

합성 신청서(persona slim 시드) 를 생성·학습해 data/models/hmda_v1/ 에 추론 번들 저장.

사용:
    python -m scripts.train_hmda_v1                 # n=30000
    python -m scripts.train_hmda_v1 --n 30000 --tune
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from loaders.config import PROJECT_ROOT  # noqa: E402
from synthesize.build import build  # noqa: E402
from training.dataset import split  # noqa: E402
from training.io import save_onnx_bundle  # noqa: E402
from training.onnx_export import onnx_smoke_check  # noqa: E402
from training.train_hmda import HmdaTrainConfig, calibrator_to_dict, train_hmda  # noqa: E402

MODEL_DIR = PROJECT_ROOT / "data" / "models" / "hmda_v1"


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=30_000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--tune", action="store_true")
    parser.add_argument("--n-trials", type=int, default=20)
    parser.add_argument("--out", default=str(MODEL_DIR))
    args = parser.parse_args()

    log = logging.getLogger("train_hmda_v1")
    df = build(n=args.n, seed=args.seed)
    splits = split(df)
    log.info("splits: %s", splits.summary())

    result = train_hmda(splits, HmdaTrainConfig(seed=args.seed),
                        tune=args.tune, n_trials=args.n_trials, calibrate=True)
    log.info("holdout: %s", result.holdout.to_dict())

    calibrator_json = calibrator_to_dict(result.calibrator) if result.calibrator else None
    model_dir = save_onnx_bundle(
        Path(args.out), result.booster, result.schema, splits.train,
        model_version="hmda_v1", holdout_metrics=result.holdout.to_dict(),
        calibrator_json=calibrator_json,
        extra_meta={"data_n": args.n, "best_iteration": result.best_iteration},
    )

    max_diff = onnx_smoke_check(result.booster, model_dir / "model.onnx",
                                splits.holdout.head(200), result.schema, atol=1e-4)
    log.info("ONNX smoke max_diff=%.2e", max_diff)
    log.info("=== hmda_v1 saved: AUC=%.4f KS=%.4f → %s ===",
             result.holdout.auc, result.holdout.ks, model_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
