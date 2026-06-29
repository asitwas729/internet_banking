"""ONNX 기반 모델 번들 — xgboost.Booster 의존성 제거.

model.onnx + feature_schema.json + calibrator.json + shap_background.parquet +
metadata.json 를 묶어 onnxruntime 으로 추론한다. 학습 측(training.onnx_export)의
to_numeric_matrix 와 동일한 입력 표현(categorical=code float)을 재현한다.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from app.feature_prep import dataframe_to_matrix

log = logging.getLogger("inference")

ONNX_INPUT_NAME = "input"


class FeatureSchema:
    """training.features.FeatureSchema 와 같은 구조의 경량 구현 (의존성 0)."""

    def __init__(self, d: dict[str, Any]) -> None:
        self.categorical: list[str] = list(d["categorical"])
        self.numeric: list[str] = list(d["numeric"])
        self.boolean: list[str] = list(d["boolean"])
        self.label_classes: list[str] = list(d["label_classes"])
        self.category_codes: dict[str, list[str]] = {
            k: list(v) for k, v in d.get("category_codes", {}).items()
        }

    @property
    def all_features(self) -> list[str]:
        return [*self.categorical, *self.numeric, *self.boolean]


class IsotonicCalibrator:
    def __init__(self, x_thresholds, y_thresholds) -> None:
        self.x = np.asarray(x_thresholds, dtype=float)
        self.y = np.asarray(y_thresholds, dtype=float)

    def predict(self, raw: np.ndarray) -> np.ndarray:
        return np.interp(np.asarray(raw, dtype=float), self.x, self.y)


class PlattCalibrator:
    def __init__(self, coef: float, intercept: float) -> None:
        self.coef = float(coef)
        self.intercept = float(intercept)

    def predict(self, raw: np.ndarray) -> np.ndarray:
        z = self.coef * np.asarray(raw, dtype=float) + self.intercept
        return 1.0 / (1.0 + np.exp(-z))


def load_calibrator(d: dict[str, Any]):
    """training 측 직렬화(type 태그) + 레거시(X_thresholds) 모두 지원."""
    t = d.get("type")
    if t == "platt":
        return PlattCalibrator(d["coef"], d["intercept"])
    x = d.get("x_thresholds", d.get("X_thresholds"))
    y = d.get("y_thresholds", d.get("Y_thresholds"))
    if x is None or y is None:
        raise ValueError(f"invalid calibrator json keys: {list(d)}")
    return IsotonicCalibrator(x, y)


class OnnxModelBundle:
    """ONNX 모델 + 스키마 + calibrator + SHAP 배경 묶음."""

    def __init__(self, model_dir: Path) -> None:
        import onnxruntime as ort

        self.model_dir = Path(model_dir)
        schema_path = self.model_dir / "feature_schema.json"
        onnx_path = self.model_dir / "model.onnx"
        if not schema_path.exists() or not onnx_path.exists():
            raise FileNotFoundError(f"incomplete ONNX bundle in {self.model_dir}")

        self.schema = FeatureSchema(json.loads(schema_path.read_text(encoding="utf-8")))
        meta_path = self.model_dir / "metadata.json"
        self.metadata: dict[str, Any] = (
            json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
        )
        self.model_id: str = (
            self.metadata.get("model_id")
            or self.metadata.get("model_version")
            or self.model_dir.name
        )

        self.sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name

        calib_path = self.model_dir / "calibrator.json"
        self.calibrator = (
            load_calibrator(json.loads(calib_path.read_text(encoding="utf-8")))
            if calib_path.exists() else None
        )

        bg_path = self.model_dir / "shap_background.parquet"
        self.background_df: pd.DataFrame | None = (
            pd.read_parquet(bg_path) if bg_path.exists() else None
        )

        log.info("ONNX bundle loaded: %s (calibrated=%s, bg=%s)",
                 self.model_id, self.calibrator is not None, self.background_df is not None)

    def to_matrix(self, df: pd.DataFrame) -> np.ndarray:
        """dict DataFrame → ONNX 입력 float 행렬 (feature_prep 위임)."""
        return dataframe_to_matrix(df, self.schema)

    def _onnx_proba(self, matrix: np.ndarray) -> np.ndarray:
        """ONNX 출력에서 [N, n_classes] 확률 행렬 추출."""
        outputs = self.sess.run(None, {self.input_name: matrix})
        for arr in outputs:
            a = np.asarray(arr)
            if a.ndim == 2 and a.shape[1] == len(self.schema.label_classes):
                return a
        return np.asarray(outputs[-1])

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
        """shape (N, n_classes). label_classes 순서와 동일."""
        return self._onnx_proba(self.to_matrix(df))

    def predict_pd_proba(self, df: pd.DataFrame) -> np.ndarray:
        """shape (N,). P(positive=class1) → calibrator 적용."""
        raw = self._onnx_proba(self.to_matrix(df))[:, 1]
        return self.calibrator.predict(raw) if self.calibrator is not None else raw

    def warm_up(self, n_rows: int = 10) -> None:
        """더미 입력으로 onnxruntime JIT 워밍업."""
        dummy = {}
        for col in self.schema.categorical:
            codes = self.schema.category_codes.get(col)
            dummy[col] = [(codes[0] if codes else "a")] * n_rows
        for col in self.schema.numeric:
            dummy[col] = [0.0] * n_rows
        for col in self.schema.boolean:
            dummy[col] = [False] * n_rows
        self.predict_proba(pd.DataFrame(dummy))
        log.info("warm_up done: %s (%d rows)", self.model_id, n_rows)
