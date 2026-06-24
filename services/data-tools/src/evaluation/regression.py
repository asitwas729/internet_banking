"""ML 회귀 검증 — PSI drift, AUC/KS bootstrap CI, 4/5ths parity, RegressionReport.

모델 재학습·데이터 변경 시 성능/공정성 지표가 기준치 이상인지 자동 검증한다.
CI 에서 `pytest -m ml_regression` 으로 호출.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
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


def to_age_band(age: int) -> str:
    """연령 → 밴드 라벨 (4/5ths 교차 그룹용)."""
    if age < 30:
        return "20s"
    if age < 40:
        return "30s"
    if age < 50:
        return "40s"
    if age < 60:
        return "50s"
    return "60+"


@dataclass
class FourFifthsResult:
    group_col: str
    rates: dict[str, float]
    ratio: float
    passed: bool
    min_group: str
    max_group: str

    def to_dict(self) -> dict:
        return {
            "group_col": self.group_col,
            "rates": {k: round(v, 6) for k, v in self.rates.items()},
            "ratio": round(self.ratio, 6),
            "passed": self.passed,
            "min_group": self.min_group,
            "max_group": self.max_group,
        }


def check_approval_parity(
    y_pred: np.ndarray,
    group_df: pd.DataFrame,
    group_cols: list[str] | None = None,
    min_ratio: float = 0.80,
    favorable: int = 1,
) -> dict[str, FourFifthsResult]:
    """그룹 컬럼별 favorable(=APPROVE/HIGH) 비율의 min/max 비율 검사.

    ratio = min_rate / max_rate ≥ min_ratio 이면 passed. 컬럼명 → 결과 dict.
    """
    y_pred = np.asarray(y_pred)
    cols = group_cols if group_cols is not None else list(group_df.columns)
    out: dict[str, FourFifthsResult] = {}
    for col in cols:
        groups = group_df[col].astype(str).to_numpy()
        rates: dict[str, float] = {}
        for g in np.unique(groups):
            mask = groups == g
            if mask.sum() == 0:
                continue
            rates[g] = float((y_pred[mask] == favorable).mean())
        if not rates:
            continue
        max_group = max(rates, key=rates.get)
        min_group = min(rates, key=rates.get)
        max_rate = rates[max_group]
        ratio = (rates[min_group] / max_rate) if max_rate > 0 else 0.0
        out[col] = FourFifthsResult(
            group_col=col,
            rates=rates,
            ratio=ratio,
            passed=ratio >= min_ratio,
            min_group=min_group,
            max_group=max_group,
        )
    return out
