"""distribution_checks 단위 테스트."""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from validation.distribution_checks import (
    CheckResult,
    check_4_5ths_rule,
    check_credit_score_monotone,
    check_delinquency_distribution,
    check_label_balance,
)

RNG = np.random.default_rng(0)


# ── delinquency_distribution ─────────────────────────────────────────────────

def test_delinquency_mean_within_range():
    """NegBin(n=2, p=0.85) 샘플 mean ≈ 0.35 → PASS."""
    counts = RNG.negative_binomial(n=2, p=0.85, size=10_000)
    df = pd.DataFrame({"delinquency_history_24m": counts})
    result = check_delinquency_distribution(df)
    assert isinstance(result, CheckResult)
    assert result.passed, f"expected PASS but got: {result.message}"


def test_delinquency_mean_out_of_range():
    """mean=0.01 데이터는 [0.20, 0.50] 범위 밖 → FAIL."""
    counts = RNG.integers(0, 1, size=5_000)  # almost all zeros
    df = pd.DataFrame({"delinquency_history_24m": counts})
    result = check_delinquency_distribution(df)
    assert not result.passed, f"expected FAIL but got: {result.message}"


# ── credit_score_monotone ─────────────────────────────────────────────────────

def test_credit_score_monotone_pass():
    """분위가 높을수록 score가 높은 단조 증가 데이터 → PASS."""
    rows = []
    base_scores = {1: 450, 2: 550, 3: 650, 4: 750, 5: 850}
    for q, center in base_scores.items():
        scores = RNG.normal(center, 30, size=200)
        rows.append(pd.DataFrame({"income_quintile": q, "credit_score_proxy": scores}))
    df = pd.concat(rows, ignore_index=True)
    result = check_credit_score_monotone(df)
    assert result.passed, f"expected PASS but got: {result.message}"


def test_credit_score_monotone_fail():
    """분위별 score가 역전된 데이터 → FAIL."""
    rows = []
    base_scores = {1: 850, 2: 750, 3: 650, 4: 550, 5: 450}  # reversed
    for q, center in base_scores.items():
        scores = RNG.normal(center, 20, size=200)
        rows.append(pd.DataFrame({"income_quintile": q, "credit_score_proxy": scores}))
    df = pd.concat(rows, ignore_index=True)
    result = check_credit_score_monotone(df)
    assert not result.passed, f"expected FAIL but got: {result.message}"


# ── check_label_balance ───────────────────────────────────────────────────────

def test_label_balance_pass():
    """균등 분포 라벨 → PASS."""
    labels = np.tile(["APPROVE", "CONDITIONAL", "REJECT"], 300)
    df = pd.DataFrame({"oracle_decision": labels})
    result = check_label_balance(df)
    assert result.passed, f"expected PASS but got: {result.message}"


def test_label_balance_fail():
    """극소수 클래스 비율(< 5%) → FAIL."""
    labels = ["REJECT"] * 2 + ["APPROVE"] * 100
    df = pd.DataFrame({"oracle_decision": labels})
    result = check_label_balance(df)
    assert not result.passed, f"expected FAIL but got: {result.message}"


# ── check_4_5ths_rule ─────────────────────────────────────────────────────────

def test_4_5ths_rule_pass():
    """승인률 균일 → ratio=1.0 → PASS."""
    df = pd.DataFrame({
        "sex": ["M", "F"] * 100,
        "pred": ["APPROVE"] * 200,
    })
    result = check_4_5ths_rule(df, pred_col="pred", group_cols=["sex"])
    assert result.passed, f"expected PASS but got: {result.message}"


def test_4_5ths_rule_fail():
    """한 그룹 승인률 50% vs 100% → ratio=0.50 → FAIL."""
    df = pd.DataFrame({
        "sex": ["M"] * 100 + ["F"] * 100,
        "pred": ["APPROVE"] * 100 + ["APPROVE"] * 50 + ["REJECT"] * 50,
    })
    result = check_4_5ths_rule(df, pred_col="pred", group_cols=["sex"])
    assert not result.passed, f"expected FAIL but got: {result.message}"
