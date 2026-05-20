"""피처 스키마 정의 + DataFrame → XGBoost 입력 변환.

라벨 누수 방지를 위해 oracle_* 컬럼(타겟·점수·노이즈·편향 플래그)은 모두 제외한다.
applicant_segment 는 Layer 1 sampler 가 occupation/age 에서 도출한 파생값이라 누수 아님.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import pandas as pd

# XGBoost native categorical 사용 (enable_categorical=True)
CATEGORICAL_FEATURES: list[str] = [
    "sex",
    "marital_status",
    "military_status",
    "family_type",
    "housing_type",
    "education_level",
    "bachelors_field",
    "occupation",
    "district",
    "province",
    "applicant_segment",
    "product_code",
    "purpose_cd",
]

NUMERIC_FEATURES: list[str] = [
    "age",
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
    "requested_amount_kw",
    "requested_period_mo",
]

BOOLEAN_FEATURES: list[str] = [
    "purpose_red_flag",
]

TARGET_COLUMN = "oracle_decision"
SPLIT_COLUMN = "split"

# 학습 시 의도적으로 제외하는 컬럼 (라벨 누수 또는 입력 불가)
EXCLUDED_COLUMNS: list[str] = [
    "uuid",
    "country",           # 단일값
    "sample_weight",     # 페르소나 reweighting 메타
    "persona",           # 자유 텍스트 — 본 모델 범위 밖
    "purpose_text",      # 자유 텍스트 — 본 모델 범위 밖
    "generated_at",
    # oracle 부산물 (라벨 누수)
    "oracle_score",
    "oracle_suggested_amount_kw",
    "oracle_suggested_rate_bps",
    "oracle_noise_flipped",
    "oracle_bias_injected",
]

LABEL_CLASSES: list[str] = ["APPROVE", "CONDITIONAL", "REJECT"]


@dataclass(frozen=True)
class FeatureSchema:
    """모델 추론 시 입력 컬럼 순서·타입 재현용. 직렬화 가능."""

    categorical: list[str] = field(default_factory=lambda: list(CATEGORICAL_FEATURES))
    numeric: list[str] = field(default_factory=lambda: list(NUMERIC_FEATURES))
    boolean: list[str] = field(default_factory=lambda: list(BOOLEAN_FEATURES))
    label_classes: list[str] = field(default_factory=lambda: list(LABEL_CLASSES))

    @property
    def all_features(self) -> list[str]:
        return [*self.categorical, *self.numeric, *self.boolean]

    def to_dict(self) -> dict:
        return {
            "categorical": self.categorical,
            "numeric": self.numeric,
            "boolean": self.boolean,
            "label_classes": self.label_classes,
        }

    @classmethod
    def from_dict(cls, d: dict) -> "FeatureSchema":
        return cls(
            categorical=list(d["categorical"]),
            numeric=list(d["numeric"]),
            boolean=list(d["boolean"]),
            label_classes=list(d["label_classes"]),
        )


def prepare_features(df: pd.DataFrame, schema: FeatureSchema | None = None) -> pd.DataFrame:
    """원본 → XGBoost 입력 DataFrame.

    - categorical 은 pandas Categorical 로 변환 (XGBoost native 지원)
    - boolean 은 int8 (0/1)
    - numeric 은 float32
    - 컬럼 순서는 schema.all_features 로 고정 → 추론 시 동일 순서 보장
    """
    schema = schema or FeatureSchema()
    out = pd.DataFrame(index=df.index)

    for col in schema.categorical:
        if col not in df.columns:
            raise KeyError(f"missing categorical column: {col}")
        out[col] = df[col].astype("category")

    for col in schema.numeric:
        if col not in df.columns:
            raise KeyError(f"missing numeric column: {col}")
        out[col] = pd.to_numeric(df[col], errors="coerce").astype("float32")

    for col in schema.boolean:
        if col not in df.columns:
            raise KeyError(f"missing boolean column: {col}")
        out[col] = df[col].fillna(False).astype("int8")

    return out[schema.all_features]


def prepare_labels(df: pd.DataFrame, schema: FeatureSchema | None = None) -> pd.Series:
    """oracle_decision → 0/1/2 정수 라벨 (LABEL_CLASSES 순서 기준)."""
    schema = schema or FeatureSchema()
    if TARGET_COLUMN not in df.columns:
        raise KeyError(f"missing target column: {TARGET_COLUMN}")
    mapping = {c: i for i, c in enumerate(schema.label_classes)}
    y = df[TARGET_COLUMN].map(mapping)
    if y.isna().any():
        unknown = sorted(df.loc[y.isna(), TARGET_COLUMN].unique().tolist())
        raise ValueError(f"unknown label values: {unknown} (expected {schema.label_classes})")
    return y.astype("int32")
