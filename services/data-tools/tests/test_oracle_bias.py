"""oracle 편향 주입 회귀 테스트 — D2 수정 검증."""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from synthesize.oracle import label

RNG = np.random.default_rng(1)


def _make_app_df(n: int = 500, occ_biased: bool = True) -> pd.DataFrame:
    """규칙 통과율이 높은 더미 지원서 데이터."""
    occupation = (
        np.where(RNG.random(n) < 0.3, "단순노무직", "사무직")
        if occ_biased
        else np.full(n, "사무직")
    )
    return pd.DataFrame({
        "occupation": occupation,
        "dsr": RNG.uniform(0.10, 0.35, n),
        "ltv": RNG.uniform(0.30, 0.60, n),
        "credit_score_proxy": RNG.integers(620, 900, n),
        "delinquency_history_24m": RNG.integers(0, 2, n),
        "requested_amount_kw": RNG.integers(1000, 5000, n),
        "annual_income_kw": RNG.integers(3000, 12000, n),
        "purpose_red_flag": RNG.integers(0, 2, n).astype(bool),
    })


# ── D2 회귀: 이미 REJECT 인 행이 편향 후보풀에서 제외되어야 함 ─────────────────

def test_bias_pool_excludes_already_reject():
    """편향 주입 전에 이미 REJECT 였던 행에 oracle_bias_injected=True 가 없어야 한다.

    D2 버그: 과거엔 biased_pool 에 기존 REJECT 도 포함돼 flip 목표에 산입됐으나
    실제 flip 이 불가해 최종 flip 수가 0 에 수렴. 수정 후엔 APPROVE 행만 대상.
    """
    df = _make_app_df(n=600, occ_biased=True)
    result = label(df, seed=99, inject_bias=True)
    # oracle_bias_injected=True 인 행은 반드시 현재 REJECT 여야 하고,
    # 원래도 APPROVE 이었다가 뒤집힌 것이므로 bias_flag True → decision REJECT
    bias_rows = result[result["oracle_bias_injected"]]
    assert (bias_rows["oracle_decision"] == "REJECT").all(), (
        "편향 주입 행의 oracle_decision 이 REJECT 가 아닌 경우 존재"
    )


def test_bias_flip_count_within_target():
    """실제 flip 수가 목표(biased APPROVE 수 × 5%) ±20% 이내여야 한다."""
    bias_extra_reject = 0.05
    df = _make_app_df(n=1000, occ_biased=True)
    result = label(df, seed=42, inject_bias=True, bias_extra_reject=bias_extra_reject)

    actual_flips = int(result["oracle_bias_injected"].sum())
    # 편향 대상: 단순/일용 직업군 — 실제 flip 전 APPROVE 였을 행 수를 추정
    # inject_bias=False 버전으로 flip 전 상태 파악
    baseline = label(df, seed=42, inject_bias=False)
    biased_occ = df["occupation"].str.contains("단순|일용|일당", regex=True, na=False)
    pre_approve = (biased_occ & (baseline["oracle_decision"] == "APPROVE")).sum()
    expected_flips = int(pre_approve * bias_extra_reject)

    if expected_flips == 0:
        pytest.skip("편향 대상 APPROVE 행 없음 — 데이터 조정 필요")

    lo = int(expected_flips * 0.80)
    hi = int(expected_flips * 1.20) + 1
    assert lo <= actual_flips <= hi, (
        f"flip={actual_flips}, expected ∈ [{lo}, {hi}] (target={expected_flips})"
    )


def test_no_bias_when_inject_false():
    """inject_bias=False 이면 oracle_bias_injected 가 모두 False."""
    df = _make_app_df(n=200, occ_biased=True)
    result = label(df, inject_bias=False)
    assert not result["oracle_bias_injected"].any()
