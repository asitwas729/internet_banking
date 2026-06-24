"""Home Credit → PD(homecredit_kr_v1) 학습 데이터 어댑터.

Kaggle Home Credit application_train + bureau 를 PD 피처 스키마(features_pd) 로 매핑한다.
TARGET=default_within_12m(실 라벨), EXT_SOURCE→credit_score_proxy, bureau 집계→bureau_*.

KR 고유 필드(province/purpose 등)는 Home Credit 에 없어 상수/파생으로 채운다.
범주형 일부는 KR 어휘로 매핑, occupation/industry 는 Home Credit 어휘 유지(프로덕션 통합 시 매핑 필요).
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

from loaders.config import PROJECT_ROOT

log = logging.getLogger(__name__)

BASE = PROJECT_ROOT / "data" / "external" / "credit" / "home-credit-default"

_MARITAL = {
    "Married": "배우자있음", "Single / not married": "미혼", "Civil marriage": "배우자있음",
    "Widow": "사별", "Separated": "이혼",
}
_HOUSING = {
    "House / apartment": "자가", "With parents": "부모동거", "Municipal apartment": "공공임대",
    "Rented apartment": "전월세", "Office apartment": "사택", "Co-op apartment": "조합주택",
}
_EDU = {
    "Secondary / secondary special": "고등학교", "Higher education": "대학교",
    "Incomplete higher": "대학중퇴", "Lower secondary": "중학교", "Academic degree": "대학원",
}


def _applicant_segment(occ: str, age: int) -> str:
    occ = occ or ""
    if "Self-employed" in occ or "Businessman" in occ:
        return "self_employed"
    if age < 30:
        return "young"
    if age >= 60:
        return "senior"
    if occ in ("Low-skill Laborers", "Laborers", "Cleaning staff"):
        return "precarious"
    return "regular"


def _split_assign(n: int, seed: int, train=0.71, valid=0.145) -> np.ndarray:
    rng = np.random.default_rng(seed)
    arr = np.empty(n, dtype=object)
    idx = rng.permutation(n)
    n_tr, n_va = int(n * train), int(n * valid)
    arr[idx[:n_tr]] = "train"
    arr[idx[n_tr:n_tr + n_va]] = "valid"
    arr[idx[n_tr + n_va:]] = "holdout"
    return arr


def build_pd_dataset(seed: int = 42, sample: int | None = None) -> pd.DataFrame:
    """Home Credit → PD 학습 DataFrame (피처 + default_within_12m + split)."""
    app = pd.read_parquet(BASE / "application_train" / "application_train.parquet")
    if sample is not None and sample < len(app):
        app = app.sample(n=sample, random_state=seed).reset_index(drop=True)

    bureau = pd.read_parquet(BASE / "bureau" / "bureau.parquet",
                             columns=["SK_ID_CURR", "SK_ID_BUREAU", "CREDIT_ACTIVE",
                                      "AMT_CREDIT_SUM_OVERDUE", "CREDIT_DAY_OVERDUE"])
    bagg = bureau.groupby("SK_ID_CURR").agg(
        bureau_n_active=("CREDIT_ACTIVE", lambda s: int((s == "Active").sum())),
        bureau_overdue_amt=("AMT_CREDIT_SUM_OVERDUE", "sum"),
        bureau_max_status=("CREDIT_DAY_OVERDUE", "max"),
        bureau_cnt=("SK_ID_BUREAU", "size"),
    )
    df = app.merge(bagg, on="SK_ID_CURR", how="left")

    inc = df["AMT_INCOME_TOTAL"].clip(lower=1)
    ext = df[["EXT_SOURCE_1", "EXT_SOURCE_2", "EXT_SOURCE_3"]].mean(axis=1)
    ext = ext.fillna(ext.mean())
    age = (-df["DAYS_BIRTH"] / 365).astype(int)
    emp = (-df["DAYS_EMPLOYED"] / 365).where(df["DAYS_EMPLOYED"] < 0, 0).clip(0, 50).fillna(0)
    goods = df["AMT_GOODS_PRICE"].replace(0, np.nan)

    out = pd.DataFrame()
    out["default_within_12m"] = df["TARGET"].astype(int)
    # ── 범주형 (KR 어휘 매핑) ──
    out["sex"] = df["CODE_GENDER"].map({"M": "남자", "F": "여자"}).fillna("남자")
    out["marital_status"] = df["NAME_FAMILY_STATUS"].map(_MARITAL).fillna("미혼")
    out["housing_type"] = df["NAME_HOUSING_TYPE"].map(_HOUSING).fillna("기타")
    out["education_level"] = df["NAME_EDUCATION_TYPE"].map(_EDU).fillna("고등학교")
    out["occupation"] = df["OCCUPATION_TYPE"].fillna("기타").astype(str)
    out["industry_cd"] = df["ORGANIZATION_TYPE"].fillna("기타").astype(str)
    out["province"] = "기타"
    out["product_code"] = df["NAME_CONTRACT_TYPE"].map(
        {"Cash loans": "CASH", "Revolving loans": "REVOLVING"}).fillna("CASH")
    out["purpose_cd"] = "OTHER"
    out["applicant_segment"] = [
        _applicant_segment(o, a) for o, a in zip(out["occupation"], age)]
    # ── 수치형 ──
    out["age"] = age
    out["employment_years"] = emp.astype(int)
    out["n_children"] = df["CNT_CHILDREN"].fillna(0).astype(int)
    out["income_quintile"] = pd.qcut(inc.rank(method="first"), 5, labels=[1, 2, 3, 4, 5]).astype(int)
    out["annual_income_kw"] = (inc / 10_000).clip(0, 1_000_000).astype(int)
    out["requested_amount_kw"] = (df["AMT_CREDIT"].fillna(0) / 10_000).astype(int)
    out["collateral_debt_kw"] = (df["AMT_GOODS_PRICE"].fillna(0) / 10_000).astype(int)
    out["total_debt_kw"] = out["requested_amount_kw"]
    out["credit_debt_kw"] = (out["total_debt_kw"] - out["collateral_debt_kw"]).clip(lower=0)
    out["total_asset_kw"] = (out["collateral_debt_kw"] * 1.5).astype(int)
    out["dsr"] = (df["AMT_ANNUITY"] / inc).clip(0, 2).fillna(0.3).round(4)
    out["ltv"] = (df["AMT_CREDIT"] / goods).clip(0, 1.5).fillna(0.5).round(4)
    out["monthly_cashflow_mean_kw"] = (out["annual_income_kw"] / 12).astype(int)
    out["monthly_cashflow_std_kw"] = (out["monthly_cashflow_mean_kw"] * 0.2).astype(int)
    out["requested_period_mo"] = (df["AMT_CREDIT"] / df["AMT_ANNUITY"].replace(0, np.nan) * 12
                                  ).clip(6, 360).fillna(36).astype(int)
    out["credit_score_proxy"] = (300 + ext * 650).clip(300, 950).astype(int)
    out["region_risk_band"] = df["REGION_RATING_CLIENT"].fillna(2).astype(int)
    out["bureau_n_active"] = df["bureau_n_active"].fillna(0).astype(int)
    out["bureau_overdue_amt_kw"] = (df["bureau_overdue_amt"].fillna(0) / 10_000).clip(0).astype(int)
    out["bureau_max_status_24m"] = df["bureau_max_status"].fillna(0).clip(0, 12).astype(int)
    out["delinquency_history_24m"] = (df["bureau_max_status"].fillna(0) > 0).astype(int)
    # ── boolean ──
    out["purpose_red_flag"] = False
    out["bureau_has_record"] = df["bureau_cnt"].fillna(0) > 0

    out["split"] = _split_assign(len(out), seed=seed)
    log.info("home_credit PD dataset: %d rows, default_rate=%.4f, splits=%s",
             len(out), out["default_within_12m"].mean(), out["split"].value_counts().to_dict())
    return out
