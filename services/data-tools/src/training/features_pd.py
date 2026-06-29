"""PD(homecredit_kr_v1) 전용 피처 스키마 + 이진 라벨 분기.

Layer 1~4 전체 + Bureau 집계 포함. 타겟: default_within_12m (1=default).
HMDA(features.py)와 달리 employment_years/bureau_* 등 PD 전용 피처를 포함한다.
"""

from __future__ import annotations

import pandas as pd

from .features import FeatureSchema

PD_CATEGORICAL: list[str] = [
    "sex",
    "marital_status",
    "housing_type",
    "education_level",
    "occupation",
    "industry_cd",
    "province",
    "applicant_segment",
    "product_code",
    "purpose_cd",
]

PD_NUMERIC: list[str] = [
    "age",
    "employment_years",
    "n_children",
    "income_quintile",
    "annual_income_kw",
    "total_asset_kw",
    "total_debt_kw",
    "collateral_debt_kw",
    "credit_debt_kw",
    "dsr",
    "ltv",
    "monthly_cashflow_mean_kw",
    "monthly_cashflow_std_kw",
    "delinquency_history_24m",
    "credit_score_proxy",
    "ext_credit_score_2",
    "ext_credit_score_3",
    "requested_amount_kw",
    "requested_period_mo",
    "region_risk_band",
    "bureau_n_active",
    "bureau_overdue_amt_kw",
    "bureau_max_status_24m",
    "bureau_overdue_cnt",
    "bureau_active_ratio",
    "past_loan_dpd_mean",
    "past_loan_dpd_max",
    "past_loan_pay_ratio",
    "prev_app_refused_ratio",
]

PD_BOOLEAN: list[str] = [
    "purpose_red_flag",
    "bureau_has_record",
]

# 이진 라벨: index 0=NO_DEFAULT, 1=DEFAULT → POSITIVE_CLASS 는 1.
PD_LABEL_CLASSES: list[str] = ["NO_DEFAULT", "DEFAULT"]
PD_LABEL_COL: str = "default_within_12m"
PD_POSITIVE_CLASS: int = 1


def pd_feature_schema() -> FeatureSchema:
    """PD(이진) 전용 FeatureSchema 생성.

    category_codes 는 비어 있으므로 학습 직전 fit_categories() 로 채워야 한다.
    """
    return FeatureSchema(
        categorical=list(PD_CATEGORICAL),
        numeric=list(PD_NUMERIC),
        boolean=list(PD_BOOLEAN),
        label_classes=list(PD_LABEL_CLASSES),
    )


def prepare_pd_labels(df: pd.DataFrame, label_col: str = PD_LABEL_COL) -> pd.Series:
    """default_within_12m(bool/0/1) → PD 이진 라벨(0=NO_DEFAULT, 1=DEFAULT)."""
    if label_col not in df.columns:
        raise KeyError(f"missing label column: {label_col}")
    raw = df[label_col]
    y = raw.astype("int8") if raw.dtype == bool else pd.to_numeric(raw, errors="coerce")
    if y.isna().any():
        unknown = sorted(df.loc[y.isna(), label_col].astype(str).unique().tolist())
        raise ValueError(f"non-numeric label values: {unknown} (expected 0/1/bool)")
    invalid = sorted(set(y.unique().tolist()) - {0, 1})
    if invalid:
        raise ValueError(f"label values out of {{0,1}}: {invalid}")
    return y.astype("int32")
