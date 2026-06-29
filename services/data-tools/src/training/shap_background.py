"""SHAP 배경(background) 샘플 생성.

KMeans 로 train 분포를 대표하는 소수 행을 선택한다. ONNX 추론 서버에서
shapiq/shap explainer 초기화의 reference distribution 으로 사용한다(여기선 SHAP 값
계산을 하지 않으므로 shap 패키지 의존 없음 — sklearn KMeans 만 사용).
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

from .features import FeatureSchema
from .onnx_export import to_numeric_matrix

log = logging.getLogger(__name__)


def build_shap_background(
    train_df: pd.DataFrame,
    schema: FeatureSchema,
    n: int = 500,
    n_clusters: int = 10,
    seed: int = 42,
) -> pd.DataFrame:
    """train 분포를 대표하는 최대 n 행을 KMeans 군집 비례 샘플링으로 선택.

    - 피처는 to_numeric_matrix 표현(categorical=code)으로 표준화 후 군집화
    - 각 군집에서 군집 크기에 비례해 샘플 → 전체 분포 보존
    - 반환은 schema.all_features 원본 컬럼(언어중립 parquet 저장용)
    """
    from sklearn.cluster import KMeans
    from sklearn.preprocessing import StandardScaler

    feat_cols = schema.all_features
    n_rows = len(train_df)
    if n_rows == 0:
        raise ValueError("train_df 가 비어 있음")

    matrix = to_numeric_matrix(train_df, schema)
    matrix = np.nan_to_num(matrix, nan=0.0)
    scaled = StandardScaler().fit_transform(matrix)

    k = max(1, min(n_clusters, n_rows))
    km = KMeans(n_clusters=k, random_state=seed, n_init=10)
    labels = km.fit_predict(scaled)

    rng = np.random.default_rng(seed)
    target = min(n, n_rows)
    selected: list[int] = []
    for c in range(k):
        idx = np.where(labels == c)[0]
        if idx.size == 0:
            continue
        take = max(1, round(target * idx.size / n_rows))
        take = min(take, idx.size)
        selected.extend(rng.choice(idx, size=take, replace=False).tolist())

    selected = selected[:target]
    out = train_df.iloc[selected][feat_cols].reset_index(drop=True)
    log.info("shap background: %d rows from %d clusters (train=%d)", len(out), k, n_rows)
    return out
