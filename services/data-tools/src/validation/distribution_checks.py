"""합성 데이터 분포 검증 함수 모음."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from scipy import stats


@dataclass
class CheckResult:
    name: str
    passed: bool
    metric: float | None
    threshold: float | None
    message: str


def check_delinquency_distribution(
    df: pd.DataFrame,
    col: str = "delinquency_history_24m",
    expected_mean: float = 0.35,
    tolerance: float = 0.15,
) -> CheckResult:
    """delinquency_history_24m 평균이 [expected_mean ± tolerance] 구간에 있는지 검사."""
    mean_val = float(df[col].mean())
    lo = expected_mean - tolerance
    hi = expected_mean + tolerance
    passed = lo <= mean_val <= hi
    return CheckResult(
        name="delinquency_distribution",
        passed=passed,
        metric=round(mean_val, 4),
        threshold=hi,
        message=f"mean={mean_val:.4f} {'∈' if passed else '∉'} [{lo:.2f}, {hi:.2f}]",
    )


def check_credit_score_monotone(
    df: pd.DataFrame,
    quintile_col: str = "income_quintile",
    score_col: str = "credit_score_proxy",
    rho_threshold: float = 0.80,
) -> CheckResult:
    """income_quintile 1→5 로 갈수록 credit_score_proxy median 이 단조 비감소인지 확인.

    Spearman ρ ≥ rho_threshold 이어야 PASS.
    """
    medians = (
        df.groupby(quintile_col)[score_col]
        .median()
        .sort_index()
    )
    rho, _ = stats.spearmanr(medians.index, medians.values)
    passed = rho >= rho_threshold
    return CheckResult(
        name="credit_score_monotone",
        passed=passed,
        metric=round(float(rho), 4),
        threshold=rho_threshold,
        message=f"Spearman ρ={rho:.4f} {'≥' if passed else '<'} {rho_threshold}",
    )


def check_label_balance(
    df: pd.DataFrame,
    label_col: str = "oracle_decision",
    min_rare_rate: float = 0.05,
) -> CheckResult:
    """최소 클래스 비율이 min_rare_rate 이상인지 확인."""
    counts = df[label_col].value_counts(normalize=True)
    min_rate = float(counts.min())
    passed = min_rate >= min_rare_rate
    rarest = counts.idxmin()
    return CheckResult(
        name="label_balance",
        passed=passed,
        metric=round(min_rate, 4),
        threshold=min_rare_rate,
        message=f"rarest class={rarest!r} rate={min_rate:.4f} "
                f"{'≥' if passed else '<'} {min_rare_rate}",
    )


def check_4_5ths_rule(
    df: pd.DataFrame,
    pred_col: str,
    group_cols: list[str],
    favorable_label: str = "APPROVE",
) -> CheckResult:
    """그룹별 승인률의 min/max ≥ 0.80 검사 (4/5ths rule)."""
    rates = (
        df.groupby(group_cols)[pred_col]
        .apply(lambda s: (s == favorable_label).mean())
    )
    if rates.max() == 0:
        return CheckResult(
            name=f"4_5ths_{'_'.join(group_cols)}",
            passed=False,
            metric=None,
            threshold=0.80,
            message="no favorable predictions found",
        )
    ratio = float(rates.min() / rates.max())
    passed = ratio >= 0.80
    return CheckResult(
        name=f"4_5ths_{'_'.join(group_cols)}",
        passed=passed,
        metric=round(ratio, 4),
        threshold=0.80,
        message=f"min/max approval ratio={ratio:.4f} {'≥' if passed else '<'} 0.80",
    )
