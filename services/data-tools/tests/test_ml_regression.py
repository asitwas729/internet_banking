"""ML 회귀 검증 (pytest -m ml_regression).

실배포 아티팩트(data/models/<id>/) 부재 시에도 동작하도록 소형 ONNX 번들을
tmp 에 생성해 run_regression_check / PSI / CI 를 end-to-end 검증한다.
"""

import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))
warnings.filterwarnings("ignore")

from evaluation import regression
from evaluation.regression import (
    auc_ci_bootstrap,
    ks_ci_bootstrap,
    run_regression_check,
    track_distribution,
)
from training.dataset import Splits
from training.features import prepare_hmda_labels
from training.features_pd import prepare_pd_labels
from training.onnx_export import export_lgbm_to_onnx, to_numeric_matrix
from training.train_hmda import HmdaTrainConfig, train_hmda
from training.train_pd import PdTrainConfig, export_pd_onnx, pd_calibrator_to_dict, train_pd

pytestmark = pytest.mark.ml_regression

_HCAT = ["sex", "marital_status", "family_type", "housing_type", "education_level",
         "occupation", "province", "applicant_segment", "product_code", "purpose_cd"]
_PCAT = ["sex", "marital_status", "housing_type", "education_level", "occupation",
         "industry_cd", "province", "applicant_segment", "product_code", "purpose_cd"]
_PNUM = ["age", "employment_years", "n_children", "income_quintile", "annual_income_kw",
         "total_asset_kw", "total_debt_kw", "collateral_debt_kw", "credit_debt_kw",
         "monthly_cashflow_mean_kw", "monthly_cashflow_std_kw", "requested_amount_kw",
         "requested_period_mo", "region_risk_band", "bureau_n_active",
         "bureau_overdue_amt_kw", "bureau_max_status_24m"]


def _mk_hmda(n, seed):
    rng = np.random.default_rng(seed)
    cs = rng.integers(350, 950, n); dsr = rng.uniform(0.05, 0.6, n)
    logit = (cs - 600) / 100 - (dsr - 0.3) * 5 + rng.normal(0, 0.6, n)
    dec = np.where(logit > 0.3, "APPROVE", np.where(logit > -0.5, "CONDITIONAL", "REJECT"))
    base = {c: rng.choice(["a", "b", "c"], n) for c in _HCAT}
    base["sex"] = rng.choice(["M", "F"], n)
    base.update({
        "age": rng.integers(20, 70, n), "income_quintile": rng.integers(1, 6, n),
        "annual_income_kw": rng.integers(1000, 12000, n), "total_asset_kw": rng.integers(0, 200000, n),
        "total_debt_kw": rng.integers(0, 30000, n), "collateral_debt_kw": rng.integers(0, 20000, n),
        "credit_debt_kw": rng.integers(0, 10000, n), "dsr": dsr, "ltv": rng.uniform(0, 0.9, n),
        "monthly_cashflow_mean_kw": rng.integers(100, 1000, n), "monthly_cashflow_std_kw": rng.integers(10, 300, n),
        "delinquency_history_24m": rng.integers(0, 3, n), "credit_score_proxy": cs,
        "requested_amount_kw": rng.integers(1000, 5000, n), "requested_period_mo": rng.integers(12, 120, n),
        "purpose_red_flag": rng.integers(0, 2, n).astype(bool), "oracle_decision": dec,
    })
    return pd.DataFrame(base)


def _mk_pd(n, seed):
    rng = np.random.default_rng(seed)
    cs = rng.integers(350, 950, n); dsr = rng.uniform(0.05, 0.7, n); deli = rng.integers(0, 4, n)
    risk = -(cs - 600) / 120 + (dsr - 0.3) * 3 + deli * 0.5 + rng.normal(0, 0.5, n)
    y = (risk > np.quantile(risk, 0.92)).astype(int)
    base = {c: rng.choice(["a", "b", "c"], n) for c in _PCAT}
    base["sex"] = rng.choice(["M", "F"], n)
    for col in _PNUM:
        base[col] = rng.integers(0, 100, n)
    base["age"] = rng.integers(20, 70, n)
    base.update({
        "dsr": dsr, "ltv": rng.uniform(0, 0.9, n), "delinquency_history_24m": deli,
        "credit_score_proxy": cs, "purpose_red_flag": rng.integers(0, 2, n).astype(bool),
        "bureau_has_record": rng.integers(0, 2, n).astype(bool), "default_within_12m": y,
    })
    return pd.DataFrame(base)


@pytest.fixture(scope="module")
def hmda_ctx(tmp_path_factory):
    sp = Splits(train=_mk_hmda(2500, 0), valid=_mk_hmda(600, 1), holdout=_mk_hmda(900, 2))
    res = train_hmda(sp, HmdaTrainConfig(num_boost_round=200), calibrate=False)
    d = tmp_path_factory.mktemp("hmda_v1")
    export_lgbm_to_onnx(res.booster, res.schema, d / "model.onnx")
    hp = d / "holdout.parquet"; sp.holdout.to_parquet(hp)
    ref = d / "train_reference.parquet"; sp.train.to_parquet(ref)

    thresholds = {"auc": 0.87, "ks": 0.38, "fourfiths": 0.80}
    out = d / "hmda_v1_regression_test.json"
    report = run_regression_check(
        d, hp, res.schema, thresholds,
        label_fn=lambda df: prepare_hmda_labels(df).to_numpy(), out_path=out)

    y = prepare_hmda_labels(sp.holdout).to_numpy()
    proba = regression._onnx_positive_proba(d / "model.onnx", to_numeric_matrix(sp.holdout, res.schema))
    return {"dir": d, "report": report, "out": out, "y": y, "proba": proba,
            "schema": res.schema, "holdout": sp.holdout, "ref": ref}


@pytest.fixture(scope="module")
def pd_ctx(tmp_path_factory):
    sp = Splits(train=_mk_pd(4000, 0), valid=_mk_pd(900, 1), holdout=_mk_pd(900, 2))
    res = train_pd(sp, PdTrainConfig(num_boost_round=200), desired_positive_rate=0.03)
    d = tmp_path_factory.mktemp("homecredit_kr_v1")
    export_pd_onnx(res.booster, res.schema, d / "model.onnx")
    (d / "calibrator.json").write_text(__import__("json").dumps(pd_calibrator_to_dict(res.calibrator)), encoding="utf-8")
    hp = d / "holdout.parquet"; sp.holdout.to_parquet(hp)
    ref = d / "train_reference.parquet"; sp.train.to_parquet(ref)

    thresholds = {"gini": 0.64, "ks": 0.41, "lift_decile1": 2.5, "fourfiths": 0.80}
    out = d / "homecredit_kr_v1_regression_test.json"
    report = run_regression_check(
        d, hp, res.schema, thresholds,
        label_fn=lambda df: prepare_pd_labels(df).to_numpy(),
        decision_threshold=res.holdout.pd_threshold, out_path=out)

    y = prepare_pd_labels(sp.holdout).to_numpy()
    raw = regression._onnx_positive_proba(d / "model.onnx", to_numeric_matrix(sp.holdout, res.schema))
    proba = regression._apply_calibrator(
        __import__("json").loads((d / "calibrator.json").read_text(encoding="utf-8")), raw)
    return {"dir": d, "report": report, "out": out, "y": y, "proba": proba,
            "schema": res.schema, "holdout": sp.holdout, "ref": ref}


# ── hmda_v1 ────────────────────────────────────────────────────────────────────

def test_hmda_v1_auc_above_threshold(hmda_ctx):
    assert hmda_ctx["report"].auc_roc >= 0.87


def test_hmda_v1_auc_ci_lower_bound(hmda_ctx):
    lo, _ = auc_ci_bootstrap(hmda_ctx["y"], hmda_ctx["proba"], n_boot=300)
    assert lo >= 0.85


def test_hmda_v1_4_5ths_sex(hmda_ctx):
    assert hmda_ctx["report"].fourfiths_sex >= 0.80


def test_hmda_v1_4_5ths_age_band(hmda_ctx):
    assert hmda_ctx["report"].fourfiths_age_band >= 0.80


def test_hmda_v1_4_5ths_segment(hmda_ctx):
    assert hmda_ctx["report"].fourfiths_segment >= 0.80


def test_hmda_v1_psi_stable(hmda_ctx):
    psi = track_distribution(hmda_ctx["holdout"], hmda_ctx["ref"], hmda_ctx["schema"].numeric)
    assert psi, "PSI 결과 비어있음"
    assert all(v < 0.20 for v in psi.values()), psi


# ── homecredit_kr_v1 ───────────────────────────────────────────────────────────

def test_pd_v1_gini_above_threshold(pd_ctx):
    assert pd_ctx["report"].gini >= 0.64


def test_pd_v1_ks_above_threshold(pd_ctx):
    assert pd_ctx["report"].ks >= 0.41


def test_pd_v1_ks_ci_lower_bound(pd_ctx):
    lo, _ = ks_ci_bootstrap(pd_ctx["y"], pd_ctx["proba"], n_boot=300)
    assert lo >= 0.38


def test_pd_v1_lift_decile1(pd_ctx):
    assert pd_ctx["report"].lift_decile1 >= 2.5


def test_pd_v1_4_5ths_sex(pd_ctx):
    assert pd_ctx["report"].fourfiths_sex >= 0.80


def test_pd_v1_psi_stable(pd_ctx):
    psi = track_distribution(pd_ctx["holdout"], pd_ctx["ref"], pd_ctx["schema"].numeric)
    assert psi
    assert all(v < 0.20 for v in psi.values()), psi


# ── 공통 ────────────────────────────────────────────────────────────────────────

def test_regression_report_persisted(hmda_ctx, pd_ctx):
    assert hmda_ctx["out"].exists()
    assert pd_ctx["out"].exists()
