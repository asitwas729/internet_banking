"""inference-server 통합 테스트 픽스처.

학습 측(data-tools)으로 소형 ONNX 번들(hmda_v1 / homecredit_kr_v1)을 만들어
onnxruntime·SHAP·FastAPI 경로를 실제로 검증한다. 학습 의존성(lightgbm/onnxmltools/
shapiq) 부재 시 importorskip 으로 스킵.
"""

import json
import sys
import types
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

_REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO / "services" / "data-tools" / "src"))
sys.path.insert(0, str(_REPO / "inference-server"))

import warnings  # noqa: E402
warnings.filterwarnings("ignore")

_HCAT = ["sex", "marital_status", "family_type", "housing_type", "education_level",
         "occupation", "province", "applicant_segment", "product_code", "purpose_cd"]
_PCAT = ["sex", "marital_status", "housing_type", "education_level", "occupation",
         "industry_cd", "province", "applicant_segment", "product_code", "purpose_cd"]
_PNUM = ["age", "employment_years", "n_children", "income_quintile", "annual_income_kw",
         "total_asset_kw", "total_debt_kw", "collateral_debt_kw", "credit_debt_kw",
         "monthly_cashflow_mean_kw", "monthly_cashflow_std_kw", "requested_amount_kw",
         "requested_period_mo", "region_risk_band", "bureau_n_active",
         "bureau_overdue_amt_kw", "bureau_max_status_24m", "bureau_overdue_cnt",
         "bureau_active_ratio", "past_loan_dpd_mean", "past_loan_dpd_max",
         "past_loan_pay_ratio", "prev_app_refused_ratio",
         "ext_credit_score_2", "ext_credit_score_3"]


def _mk_hmda(n, seed):
    rng = np.random.default_rng(seed)
    cs = rng.integers(350, 950, n); dsr = rng.uniform(0.05, 0.6, n)
    logit = (cs - 600) / 100 - (dsr - 0.3) * 5 + rng.normal(0, 0.6, n)
    dec = np.where(logit > 0.3, "APPROVE", np.where(logit > -0.5, "CONDITIONAL", "REJECT"))
    base = {c: rng.choice(["a", "b", "c"], n) for c in _HCAT}
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
    for col in _PNUM:
        base[col] = rng.integers(0, 100, n)
    base.update({
        "dsr": dsr, "ltv": rng.uniform(0, 0.9, n), "delinquency_history_24m": deli,
        "credit_score_proxy": cs, "purpose_red_flag": rng.integers(0, 2, n).astype(bool),
        "bureau_has_record": rng.integers(0, 2, n).astype(bool), "default_within_12m": y,
    })
    return pd.DataFrame(base)


@pytest.fixture(scope="session")
def bundles(tmp_path_factory):
    """hmda_v1 / homecredit_kr_v1 ONNX 번들 디렉터리 경로 (문자열) 반환."""
    pytest.importorskip("lightgbm")
    pytest.importorskip("onnxmltools")
    pytest.importorskip("shapiq")

    from training.dataset import Splits
    from training.onnx_export import export_lgbm_to_onnx
    from training.shap_background import build_shap_background
    from training.train_hmda import HmdaTrainConfig, train_hmda
    from training.train_pd import (PdTrainConfig, export_pd_onnx, pd_calibrator_to_dict, train_pd)

    # hmda
    sph = Splits(train=_mk_hmda(1500, 0), valid=_mk_hmda(400, 1), holdout=_mk_hmda(400, 2))
    rh = train_hmda(sph, HmdaTrainConfig(num_boost_round=120), calibrate=False)
    dh = tmp_path_factory.mktemp("hmda_v1")
    export_lgbm_to_onnx(rh.booster, rh.schema, dh / "model.onnx")
    (dh / "feature_schema.json").write_text(json.dumps(rh.schema.to_dict()), encoding="utf-8")
    (dh / "metadata.json").write_text(json.dumps({"model_version": "hmda_v1", "holdout": rh.holdout.to_dict()}), encoding="utf-8")
    build_shap_background(sph.train, rh.schema, n=60).to_parquet(dh / "shap_background.parquet")

    # pd
    spp = Splits(train=_mk_pd(3000, 0), valid=_mk_pd(700, 1), holdout=_mk_pd(700, 2))
    rp = train_pd(spp, PdTrainConfig(num_boost_round=120), desired_positive_rate=0.03)
    dp = tmp_path_factory.mktemp("homecredit_kr_v1")
    export_pd_onnx(rp.booster, rp.schema, dp / "model.onnx")
    (dp / "feature_schema.json").write_text(json.dumps(rp.schema.to_dict()), encoding="utf-8")
    (dp / "calibrator.json").write_text(json.dumps(pd_calibrator_to_dict(rp.calibrator)), encoding="utf-8")
    (dp / "metadata.json").write_text(json.dumps({"model_version": "homecredit_kr_v1", "pd_threshold": rp.holdout.pd_threshold, "holdout": rp.holdout.to_dict()}), encoding="utf-8")
    build_shap_background(spp.train, rp.schema, n=60).to_parquet(dp / "shap_background.parquet")

    return str(dh), str(dp)


@pytest.fixture(scope="session")
def hmda_bundle(bundles):
    from app.model_bundle import OnnxModelBundle

    return OnnxModelBundle(Path(bundles[0]))


def _stub_routers():
    from fastapi import APIRouter

    for m in ["app.ocr_router", "app.extract_router", "app.forgery_router"]:
        mod = types.ModuleType(m)
        mod.router = APIRouter()
        sys.modules[m] = mod


@pytest.fixture
def make_client(bundles, monkeypatch):
    """env 를 세팅하고 app.main 을 새로 import 하는 TestClient 팩토리."""
    from fastapi.testclient import TestClient

    hmda_dir, pd_dir = bundles

    def _make(with_pd=True, shap_top_k=3):
        monkeypatch.setenv("MODEL_DIR", hmda_dir)
        if with_pd:
            monkeypatch.setenv("PD_MODEL_DIR", pd_dir)
        else:
            # 디스크의 실 data/models/homecredit_kr_v1 자동 탐색을 막기 위해
            # 존재하지 않는 경로를 명시(삭제만 하면 fallback 탐색이 실모델을 찾음).
            monkeypatch.setenv("PD_MODEL_DIR", str(Path(pd_dir).parent / "__no_pd__"))
        monkeypatch.setenv("SHAP_TOP_K", str(shap_top_k))
        monkeypatch.setenv("SHAP_BG_N", "60")
        _stub_routers()
        sys.modules.pop("app.main", None)
        import app.main as M
        return TestClient(M.app)

    return _make


@pytest.fixture
def client(make_client):
    with make_client() as c:
        yield c


@pytest.fixture
def hmda_feature():
    f = {c: "a" for c in _HCAT}
    f.update({
        "age": 35, "income_quintile": 5, "annual_income_kw": 9000, "total_asset_kw": 100000,
        "total_debt_kw": 4000, "collateral_debt_kw": 2000, "credit_debt_kw": 2000,
        "dsr": 0.18, "ltv": 0.3, "monthly_cashflow_mean_kw": 700, "monthly_cashflow_std_kw": 50,
        "delinquency_history_24m": 0, "credit_score_proxy": 900, "requested_amount_kw": 2000,
        "requested_period_mo": 36, "purpose_red_flag": False,
    })
    return f


@pytest.fixture
def hmda_feature_reject(hmda_feature):
    f = dict(hmda_feature)
    f.update({"credit_score_proxy": 360, "dsr": 0.62, "delinquency_history_24m": 2})
    return f


def _pd_feature(**over):
    f = {c: "a" for c in _PCAT}
    for col in _PNUM:
        f[col] = 50
    f.update({"dsr": 0.2, "ltv": 0.3, "delinquency_history_24m": 0,
              "credit_score_proxy": 900, "purpose_red_flag": False, "bureau_has_record": True})
    f.update(over)
    return f


@pytest.fixture
def pd_feature_low():
    return _pd_feature(credit_score_proxy=900, dsr=0.15, delinquency_history_24m=0)


@pytest.fixture
def pd_feature_high():
    return _pd_feature(credit_score_proxy=380, dsr=0.6, delinquency_history_24m=3)
