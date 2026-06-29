"""hmda_v1 학습·캘리브레이션·ONNX export 단위 테스트 (in-memory fixture)."""

import sys
import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))
warnings.filterwarnings("ignore")

from synthesize.build import _split_assign
from training.dataset import Splits
from training.features import fit_categories, hmda_feature_schema, prepare_hmda_labels
from training.onnx_export import export_lgbm_to_onnx, onnx_smoke_check, to_numeric_matrix
from training.train_hmda import (
    HmdaTrainConfig,
    calibrate_isotonic,
    evaluate_booster,
    train_hmda,
)

_CAT = [
    "sex", "marital_status", "family_type", "housing_type", "education_level",
    "occupation", "province", "applicant_segment", "product_code", "purpose_cd",
]


def make_loan_df(n: int, seed: int, signal: bool = True) -> pd.DataFrame:
    """credit_score·dsr 가 oracle_decision 을 구동하는 학습가능 합성 데이터."""
    rng = np.random.default_rng(seed)
    cs = rng.integers(350, 950, n)
    dsr = rng.uniform(0.05, 0.6, n)
    logit = ((cs - 600) / 100 - (dsr - 0.3) * 5 + rng.normal(0, 0.6, n)
             if signal else rng.normal(0, 1, n))
    dec = np.where(logit > 0.3, "APPROVE", np.where(logit > -0.5, "CONDITIONAL", "REJECT"))
    base = {c: rng.choice(["a", "b", "c"], n) for c in _CAT}
    base.update({
        "age": rng.integers(20, 70, n), "income_quintile": rng.integers(1, 6, n),
        "annual_income_kw": rng.integers(1000, 12000, n), "total_asset_kw": rng.integers(0, 200000, n),
        "total_debt_kw": rng.integers(0, 30000, n), "collateral_debt_kw": rng.integers(0, 20000, n),
        "credit_debt_kw": rng.integers(0, 10000, n), "dsr": dsr, "ltv": rng.uniform(0, 0.9, n),
        "monthly_cashflow_mean_kw": rng.integers(100, 1000, n),
        "monthly_cashflow_std_kw": rng.integers(10, 300, n),
        "delinquency_history_24m": rng.integers(0, 3, n), "credit_score_proxy": cs,
        "requested_amount_kw": rng.integers(1000, 5000, n),
        "requested_period_mo": rng.integers(12, 120, n),
        "purpose_red_flag": rng.integers(0, 2, n).astype(bool), "oracle_decision": dec,
    })
    return pd.DataFrame(base)


@pytest.fixture(scope="module")
def splits() -> Splits:
    return Splits(
        train=make_loan_df(2500, 0),
        valid=make_loan_df(600, 1),
        holdout=make_loan_df(600, 2),
    )


@pytest.fixture(scope="module")
def trained(splits):
    return train_hmda(splits, HmdaTrainConfig(num_boost_round=300), calibrate=True)


# ── 데이터/라벨 ────────────────────────────────────────────────────────────────

def test_dataset_load_split_ratio():
    """train:valid:holdout ≈ 71:14.5:14.5 (±1%)."""
    import collections

    arr = _split_assign(10_000, seed=42)
    c = collections.Counter(arr.tolist())
    assert abs(c["train"] / 10_000 - 0.71) < 0.01
    assert abs(c["valid"] / 10_000 - 0.145) < 0.01
    assert abs(c["holdout"] / 10_000 - 0.145) < 0.01


def test_label_positive_rate():
    """APPROVE+CONDITIONAL=positive 비율이 HMDA 기준 60~80%."""
    df = pd.DataFrame({"oracle_decision": ["APPROVE"] * 60 + ["CONDITIONAL"] * 10 + ["REJECT"] * 30})
    rate = float(prepare_hmda_labels(df).mean())
    assert 0.60 <= rate <= 0.80


# ── 학습/평가 ──────────────────────────────────────────────────────────────────

def test_train_returns_booster(trained):
    import lightgbm as lgb

    assert isinstance(trained.booster, lgb.Booster)
    assert trained.best_iteration >= 1


def test_evaluate_auc_above_threshold(trained):
    """학습가능 fixture 에서 holdout AUC ≥ 0.87."""
    assert trained.holdout.auc >= 0.87
    assert trained.holdout.ks >= 0.38


def test_calibration_reduces_brier():
    """미스캘리브레이션 booster 점수를 isotonic 이 교정 → Brier 감소."""
    valid = make_loan_df(1500, 10)
    holdout = make_loan_df(1500, 11)
    schema = fit_categories(valid, hmda_feature_schema())

    class StubBooster:
        best_iteration = 0

        def predict(self, X, num_iteration=None):
            z = (X["credit_score_proxy"].to_numpy() - 600) / 100
            # 단조이나 overconfident-low 로 왜곡 → 미스캘리브레이션
            return (1.0 / (1.0 + np.exp(-z))) ** 3

    booster = StubBooster()
    calibrator = calibrate_isotonic(booster, valid, schema)
    raw = evaluate_booster(booster, holdout, schema)
    cal = evaluate_booster(booster, holdout, schema, calibrator)
    assert cal.brier < raw.brier


# ── ONNX export ────────────────────────────────────────────────────────────────

def test_onnx_export_smoke(trained, splits, tmp_path):
    """LightGBM vs ONNX 100행 max_diff < 1e-4."""
    onnx_path = tmp_path / "hmda.onnx"
    export_lgbm_to_onnx(trained.booster, trained.schema, onnx_path)
    max_diff = onnx_smoke_check(
        trained.booster, onnx_path, splits.holdout.head(100), trained.schema, atol=1e-4
    )
    assert max_diff < 1e-4


def test_onnx_latency_p99(trained, splits, tmp_path):
    """단건 추론 1000회 p99 ≤ 50ms."""
    import onnxruntime as ort

    onnx_path = tmp_path / "hmda_lat.onnx"
    export_lgbm_to_onnx(trained.booster, trained.schema, onnx_path)
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    mat = to_numeric_matrix(splits.holdout.head(1), trained.schema)

    latencies = []
    for _ in range(1000):
        t0 = time.perf_counter()
        session.run(None, {"input": mat})
        latencies.append((time.perf_counter() - t0) * 1000.0)
    p99 = float(np.percentile(latencies, 99))
    assert p99 <= 50.0, f"p99={p99:.2f}ms > 50ms"
