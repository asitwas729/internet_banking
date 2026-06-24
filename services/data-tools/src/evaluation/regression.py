"""ML 회귀 검증 — PSI drift, AUC/KS bootstrap CI, 4/5ths parity, RegressionReport.

모델 재학습·데이터 변경 시 성능/공정성 지표가 기준치 이상인지 자동 검증한다.
CI 에서 `pytest -m ml_regression` 으로 호출.
"""

from __future__ import annotations

import logging
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import ks_2samp
from sklearn.metrics import roc_auc_score

log = logging.getLogger(__name__)

_EPS = 1e-6


def population_stability_index(
    expected: np.ndarray,
    actual: np.ndarray,
    bins: int = 10,
) -> float:
    """PSI = Σ (actual_% - expected_%) · ln(actual_% / expected_%).

    expected 분포의 동일빈도 분위로 bin 경계를 잡고 양쪽 비율 비교.
    """
    expected = np.asarray(expected, dtype=float)
    actual = np.asarray(actual, dtype=float)
    edges = np.unique(np.quantile(expected, np.linspace(0.0, 1.0, bins + 1)))
    if edges.size < 2:
        return 0.0
    edges[0], edges[-1] = -np.inf, np.inf
    e_counts, _ = np.histogram(expected, bins=edges)
    a_counts, _ = np.histogram(actual, bins=edges)
    e_rate = np.clip(e_counts / max(e_counts.sum(), 1), _EPS, None)
    a_rate = np.clip(a_counts / max(a_counts.sum(), 1), _EPS, None)
    return float(np.sum((a_rate - e_rate) * np.log(a_rate / e_rate)))


def track_distribution(
    current_df: pd.DataFrame,
    reference_parquet: Path,
    feature_cols: list[str],
    psi_threshold: float = 0.20,
) -> dict[str, float]:
    """피처별 PSI 계산. PSI ≥ psi_threshold 면 drift 경보 로깅."""
    reference = pd.read_parquet(reference_parquet)
    result: dict[str, float] = {}
    for col in feature_cols:
        if col not in current_df.columns or col not in reference.columns:
            continue
        exp = pd.to_numeric(reference[col], errors="coerce").dropna().to_numpy()
        act = pd.to_numeric(current_df[col], errors="coerce").dropna().to_numpy()
        if exp.size == 0 or act.size == 0:
            continue
        psi = population_stability_index(exp, act)
        result[col] = psi
        if psi >= psi_threshold:
            log.warning("PSI drift: %s = %.4f (>= %.2f)", col, psi, psi_threshold)
    return result


def _ks_statistic(y_true: np.ndarray, score: np.ndarray) -> float:
    pos = score[y_true == 1]
    neg = score[y_true == 0]
    if pos.size == 0 or neg.size == 0:
        return 0.0
    return float(ks_2samp(pos, neg).statistic)


def auc_ci_bootstrap(
    y_true: np.ndarray,
    proba: np.ndarray,
    n_boot: int = 1000,
    alpha: float = 0.05,
    seed: int = 42,
) -> tuple[float, float]:
    """bootstrap resample AUC 분포의 [alpha/2, 1-alpha/2] 백분위 CI."""
    y_true = np.asarray(y_true)
    proba = np.asarray(proba)
    n = len(y_true)
    rng = np.random.default_rng(seed)
    aucs: list[float] = []
    for _ in range(n_boot):
        idx = rng.integers(0, n, n)
        yt = y_true[idx]
        if yt.min() == yt.max():
            continue
        aucs.append(roc_auc_score(yt, proba[idx]))
    if not aucs:
        return (0.0, 0.0)
    lo = float(np.percentile(aucs, 100 * alpha / 2))
    hi = float(np.percentile(aucs, 100 * (1 - alpha / 2)))
    return lo, hi


def ks_ci_bootstrap(
    y_true: np.ndarray,
    proba: np.ndarray,
    n_boot: int = 1000,
    alpha: float = 0.05,
    seed: int = 42,
) -> tuple[float, float]:
    """bootstrap resample KS statistic 분포의 [alpha/2, 1-alpha/2] 백분위 CI."""
    y_true = np.asarray(y_true)
    proba = np.asarray(proba)
    n = len(y_true)
    rng = np.random.default_rng(seed)
    kss: list[float] = []
    for _ in range(n_boot):
        idx = rng.integers(0, n, n)
        yt = y_true[idx]
        if yt.min() == yt.max():
            continue
        kss.append(_ks_statistic(yt, proba[idx]))
    if not kss:
        return (0.0, 0.0)
    lo = float(np.percentile(kss, 100 * alpha / 2))
    hi = float(np.percentile(kss, 100 * (1 - alpha / 2)))
    return lo, hi
