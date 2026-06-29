"""LightGBM Booster → ONNX export + onnxruntime smoke 검증.

lightgbm 4.x 에는 built-in to_onnx() 가 없으므로 onnxmltools.convert_lightgbm 사용.
입력은 단일 FloatTensor — categorical 컬럼은 학습 때 사용한 category code(정수)를 float 로 전달.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from .features import FeatureSchema, prepare_features

log = logging.getLogger(__name__)

ONNX_INPUT_NAME = "input"


def to_numeric_matrix(df: pd.DataFrame, schema: FeatureSchema) -> np.ndarray:
    """원본 DataFrame → ONNX 입력 float 행렬.

    컬럼 순서는 schema.all_features([categorical, numeric, boolean]) 와 동일.
    categorical 은 schema.category_codes 순서의 정수 code(미등록=-1)를 float 로.
    """
    cols: list[np.ndarray] = []
    for col in schema.categorical:
        codes = schema.category_codes.get(col)
        cat = pd.Categorical(df[col].astype("string"), categories=codes)
        cols.append(cat.codes.astype("float32"))
    for col in schema.numeric:
        cols.append(pd.to_numeric(df[col], errors="coerce").astype("float32").to_numpy())
    for col in schema.boolean:
        cols.append(df[col].fillna(False).astype("int8").astype("float32").to_numpy())
    return np.column_stack(cols).astype(np.float32)


def export_lgbm_to_onnx(
    booster,
    schema: FeatureSchema,
    out_path: Path,
) -> Any:
    """booster → ONNX 모델. zipmap=False 로 확률을 [N,2] 텐서로 출력."""
    import onnx
    from onnxmltools import convert_lightgbm
    from onnxmltools.convert.common.data_types import FloatTensorType

    n_features = len(schema.all_features)
    initial_types = [(ONNX_INPUT_NAME, FloatTensorType([None, n_features]))]
    onnx_model = convert_lightgbm(booster, initial_types=initial_types, zipmap=False)

    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(onnx_model, str(out_path))
    log.info("ONNX saved to %s (%d features)", out_path, n_features)
    return onnx_model


def _onnx_positive_proba(session, matrix: np.ndarray) -> np.ndarray:
    """onnxruntime 세션 출력에서 P(class=1) 추출."""
    outputs = session.run(None, {ONNX_INPUT_NAME: matrix})
    for arr in outputs:
        a = np.asarray(arr)
        if a.ndim == 2 and a.shape[1] == 2:
            return a[:, 1]
    # fallback: 1D 확률 출력
    return np.asarray(outputs[-1]).ravel()


def onnx_smoke_check(
    booster,
    onnx_path: Path,
    df: pd.DataFrame,
    schema: FeatureSchema,
    atol: float = 1e-4,
) -> float:
    """LightGBM vs ONNX 출력 최대 절대차 반환. atol 초과 시 ValueError."""
    import onnxruntime as ort

    X_cat = prepare_features(df, schema)
    lgb_p = booster.predict(X_cat, num_iteration=booster.best_iteration)

    matrix = to_numeric_matrix(df, schema)
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_p = _onnx_positive_proba(session, matrix)

    max_diff = float(np.max(np.abs(lgb_p - onnx_p)))
    log.info("ONNX smoke: max_diff=%.2e (atol=%.0e, n=%d)", max_diff, atol, len(df))
    if max_diff > atol:
        raise ValueError(f"ONNX smoke 실패: max_diff={max_diff:.3e} > atol={atol:.0e}")
    return max_diff
