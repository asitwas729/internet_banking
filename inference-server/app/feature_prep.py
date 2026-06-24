"""요청 dict / DataFrame → ONNX 입력 numpy 행렬 변환.

학습 측 training.onnx_export.to_numeric_matrix 와 동일 규칙:
컬럼 순서는 schema.all_features([categorical, numeric, boolean]),
categorical 은 schema.category_codes 순서의 정수 code(미등록/누락=-1)를 float 로.

schema 는 duck-typed — categorical/numeric/boolean/category_codes/all_features 속성만 요구.
"""

from __future__ import annotations

from typing import Any, Protocol

import numpy as np
import pandas as pd


class SchemaLike(Protocol):
    categorical: list[str]
    numeric: list[str]
    boolean: list[str]
    category_codes: dict[str, list[str]]

    @property
    def all_features(self) -> list[str]: ...


def dataframe_to_matrix(df: pd.DataFrame, schema: SchemaLike) -> np.ndarray:
    """DataFrame → float 행렬. 누락 컬럼은 NaN/False 로 안전 처리."""
    n = len(df)
    cols: list[np.ndarray] = []
    for col in schema.categorical:
        series = (df[col].astype("string") if col in df.columns
                  else pd.Series([None] * n, dtype="string"))
        codes = schema.category_codes.get(col)
        cat = pd.Categorical(series, categories=codes)
        cols.append(cat.codes.astype("float32"))
    for col in schema.numeric:
        val = df[col] if col in df.columns else pd.Series([np.nan] * n)
        cols.append(pd.to_numeric(val, errors="coerce").astype("float32").to_numpy())
    for col in schema.boolean:
        val = df[col] if col in df.columns else pd.Series([False] * n)
        cols.append(val.fillna(False).astype("int8").astype("float32").to_numpy())
    return np.column_stack(cols).astype(np.float32)


def records_to_matrix(records: list[dict[str, Any]], schema: SchemaLike) -> np.ndarray:
    """요청 features(list[dict]) → float 행렬."""
    return dataframe_to_matrix(pd.DataFrame(records), schema)
