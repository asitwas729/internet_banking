"""homecredit_kr_v1 PD 학습·calibration·ONNX export 단위 테스트 (in-memory fixture)."""

import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))
warnings.filterwarnings("ignore")

from training.dataset import Splits
from training.features import fit_categories, prepare_features
from training.features_pd import pd_feature_schema, prepare_pd_labels
from training.train_pd import (
    PdTrainConfig,
    apply_class_weight,
    calibrate_pd,
    evaluate_pd,
    export_pd_onnx,
    pd_onnx_smoke_check,
    train_pd,
)

_CAT = [
    "sex", "marital_status", "housing_type", "education_level", "occupation",
    "industry_cd", "province", "applicant_segment", "product_code", "purpose_cd",
]
_NUMX = [
    "age", "employment_years", "n_children", "income_quintile", "annual_income_kw",
    "total_asset_kw", "total_debt_kw", "collateral_debt_kw", "credit_debt_kw",
    "monthly_cashflow_mean_kw", "monthly_cashflow_std_kw", "requested_amount_kw",
    "requested_period_mo", "region_risk_band", "bureau_n_active",
    "bureau_overdue_amt_kw", "bureau_max_status_24m",
]


def _risk(cs, dsr, deli, noise):
    return -(cs - 600) / 120 + (dsr - 0.3) * 3 + deli * 0.5 + noise


def make_pd_df(n: int, seed: int, positive_rate: float = 0.08) -> pd.DataFrame:
    """credit_score·dsr·delinquency 가 디폴트를 구동, 양성률 ≈ positive_rate."""
    rng = np.random.default_rng(seed)
    cs = rng.integers(350, 950, n)
    dsr = rng.uniform(0.05, 0.7, n)
    deli = rng.integers(0, 4, n)
    risk = _risk(cs, dsr, deli, rng.normal(0, 0.5, n))
    y = (risk > np.quantile(risk, 1.0 - positive_rate)).astype(int)
    base = {c: rng.choice(["a", "b", "c"], n) for c in _CAT}
    for col in _NUMX:
        base[col] = rng.integers(0, 100, n)
    base.update({
        "dsr": dsr, "ltv": rng.uniform(0, 0.9, n), "delinquency_history_24m": deli,
        "credit_score_proxy": cs, "purpose_red_flag": rng.integers(0, 2, n).astype(bool),
        "bureau_has_record": rng.integers(0, 2, n).astype(bool), "default_within_12m": y,
    })
    return pd.DataFrame(base)


@pytest.fixture(scope="module")
def splits() -> Splits:
    return Splits(
        train=make_pd_df(4000, 0),
        valid=make_pd_df(900, 1),
        holdout=make_pd_df(900, 2),
    )


@pytest.fixture(scope="module")
def trained(splits):
    return train_pd(splits, PdTrainConfig(num_boost_round=300), desired_positive_rate=0.03)


# ── 데이터/리웨이트 ────────────────────────────────────────────────────────────

def test_pd_dataset_positive_rate(splits):
    """holdout 양성률 ≈ 8% (±1.5%)."""
    rate = float(prepare_pd_labels(splits.holdout).mean())
    assert 0.065 <= rate <= 0.095


def test_class_weight_reduces_base_rate(splits):
    """Kamiran reweight 후 실효 양성률 ≈ 2~4%."""
    w = apply_class_weight(splits.train, desired_positive_rate=0.03).to_numpy()
    y = prepare_pd_labels(splits.train).to_numpy()
    eff = float((w * y).sum() / w.sum())
    assert 0.02 <= eff <= 0.04


# ── 학습/평가 ──────────────────────────────────────────────────────────────────

def test_train_pd_returns_booster(trained):
    import lightgbm as lgb

    assert isinstance(trained.booster, lgb.Booster)
    assert trained.best_iteration >= 1


def test_evaluate_gini_above_threshold(trained):
    assert trained.holdout.gini >= 0.64


def test_evaluate_ks_above_threshold(trained):
    assert trained.holdout.ks >= 0.41


def test_lift_decile1_highest(trained):
    """상위 10% 디폴트 포획률 > 전체 양성률 × 3 (lift > 3)."""
    assert trained.holdout.lift_decile1 > 3.0


def test_pd_threshold_persisted(trained):
    """metadata 직렬화에 pd_threshold 키 존재."""
    assert "pd_threshold" in trained.holdout.to_dict()


def test_calibration_reduces_brier():
    """미스캘리브레이션 booster 점수를 calibrate_pd 가 교정 → Brier 감소.

    base rate 이동을 배제하기 위해 target_base_rate=valid 실제 양성률 사용(순수 calibration).
    """
    valid = make_pd_df(2000, 10)
    holdout = make_pd_df(2000, 11)
    schema = fit_categories(valid, pd_feature_schema())

    class StubBooster:
        best_iteration = 0

        def predict(self, X, num_iteration=None):
            risk = _risk(
                X["credit_score_proxy"].to_numpy(),
                X["dsr"].to_numpy(),
                X["delinquency_history_24m"].to_numpy(),
                0.0,
            )
            p = 1.0 / (1.0 + np.exp(-risk))
            return p ** 3  # 단조이나 overconfident-low 왜곡

    booster = StubBooster()
    base = float(prepare_pd_labels(valid).mean())
    calibrator = calibrate_pd(booster, valid, schema, target_base_rate=base)
    raw = evaluate_pd(booster, holdout, schema)
    cal = evaluate_pd(booster, holdout, schema, calibrator)
    assert cal.brier < raw.brier


# ── ONNX export ────────────────────────────────────────────────────────────────

def test_onnx_pd_smoke(trained, splits, tmp_path):
    """LightGBM vs ONNX P(default) 100행 max_diff < 1e-4."""
    onnx_path = tmp_path / "pd.onnx"
    export_pd_onnx(trained.booster, trained.schema, onnx_path)
    max_diff = pd_onnx_smoke_check(
        trained.booster, onnx_path, splits.holdout.head(100), trained.schema, atol=1e-4
    )
    assert max_diff < 1e-4
