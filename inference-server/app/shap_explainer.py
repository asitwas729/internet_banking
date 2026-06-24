"""ONNX 모델용 SHAP 설명기 — shapiq KernelSHAP wrapper.

TreeSHAP 은 ONNX booster 에 직접 적용 불가하므로 shapiq.TabularExplainer(KernelSHAP)
로 model-agnostic 근사한다. 입력/배경은 OnnxModelBundle.to_matrix 표현(categorical=code).

성능: top_k=0 이면 비용 0(설명기 미생성). top_k>0 이면 단건 ~수십 ms (배경·budget 의존).
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

log = logging.getLogger("inference")


class OnnxShapExplainer:
    def __init__(
        self,
        bundle,
        top_k: int = 3,
        background_n: int = 500,
        budget: int = 256,
        random_state: int = 42,
    ) -> None:
        self._bundle = bundle
        self._top_k = top_k
        self._budget = budget
        self._features = list(bundle.schema.all_features)
        self._explainer = None

        if top_k > 0:
            import shapiq

            background = self._prepare_background(background_n, random_state)
            self._explainer = shapiq.TabularExplainer(
                model=self._predict_fn,
                data=background,
                index="SV",          # max_order=1 → Shapley values
                max_order=1,
                random_state=random_state,
            )
            log.info("OnnxShapExplainer ready: top_k=%d bg=%d budget=%d",
                     top_k, background.shape[0], budget)

    def _predict_fn(self, X: np.ndarray) -> np.ndarray:
        """shapiq 요구 인터페이스 — ONNX P(positive) 반환."""
        matrix = np.atleast_2d(np.asarray(X, dtype=np.float32))
        return self._bundle._onnx_proba(matrix)[:, 1]

    def _prepare_background(self, n: int, random_state: int) -> np.ndarray:
        bg_df = self._bundle.background_df
        if bg_df is None or len(bg_df) == 0:
            raise ValueError("SHAP background 없음 — shap_background.parquet 필요")
        if len(bg_df) > n:
            bg_df = bg_df.sample(n=n, random_state=random_state)
        return self._bundle.to_matrix(bg_df)

    def explain_top_k(self, df: pd.DataFrame) -> list[list[dict[str, float]]]:
        """행별 top-k SHAP 기여값 [{feature, shap_value}]. top_k=0 이면 빈 리스트."""
        if self._top_k <= 0 or self._explainer is None:
            return [[] for _ in range(len(df))]

        matrix = self._bundle.to_matrix(df)
        results: list[list[dict[str, float]]] = []
        for i in range(matrix.shape[0]):
            values = self.explain_values(matrix[i])
            order = np.argsort(np.abs(values))[::-1][: self._top_k]
            results.append([
                {"feature": self._features[j], "shap_value": float(values[j])}
                for j in order
            ])
        return results

    def explain_values(self, row: np.ndarray) -> np.ndarray:
        """단일 행의 피처별 order-1 Shapley 값 배열 (len = n_features)."""
        iv = self._explainer.explain(np.asarray(row, dtype=np.float32), budget=self._budget)
        return np.asarray(iv.get_n_order_values(1), dtype=float)

    def baseline_value(self, row: np.ndarray) -> float:
        """설명 baseline(기대값). SHAP 합 + baseline ≈ 예측 검증용."""
        iv = self._explainer.explain(np.asarray(row, dtype=np.float32), budget=self._budget)
        return float(iv.baseline_value)
