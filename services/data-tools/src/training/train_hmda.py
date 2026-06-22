"""hmda_v1 Decision Score — LightGBM 이진 분류 학습 + Optuna 튜닝.

타겟: APPROVE(1) / REJECT(0). LightGBM native categorical 사용.
완료 기준: holdout AUC-ROC ≥ 0.87, KS ≥ 0.38.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

import numpy as np
import pandas as pd
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    f1_score,
    roc_auc_score,
    roc_curve,
)

from .dataset import Splits
from .features import (
    FeatureSchema,
    fit_categories,
    hmda_feature_schema,
    prepare_features,
    prepare_hmda_labels,
)

log = logging.getLogger(__name__)

# Optuna 가 탐색하지 않는 고정 파라미터
LGBM_BASE_PARAMS: dict[str, Any] = {
    "objective": "binary",
    "metric": ["auc", "binary_logloss"],
    "boosting_type": "gbdt",
    "seed": 42,
    "n_jobs": -1,
    "verbose": -1,
}


@dataclass
class HmdaTrainConfig:
    num_leaves: int = 127
    learning_rate: float = 0.05
    min_child_samples: int = 50
    subsample: float = 0.8
    colsample_bytree: float = 0.8
    reg_alpha: float = 0.1
    reg_lambda: float = 5.0
    num_boost_round: int = 1000
    early_stopping_rounds: int = 50
    seed: int = 42

    def to_lgb_params(self) -> dict[str, Any]:
        return {
            **LGBM_BASE_PARAMS,
            "num_leaves": self.num_leaves,
            "learning_rate": self.learning_rate,
            "min_child_samples": self.min_child_samples,
            "bagging_fraction": self.subsample,
            "bagging_freq": 1,
            "feature_fraction": self.colsample_bytree,
            "lambda_l1": self.reg_alpha,
            "lambda_l2": self.reg_lambda,
            "seed": self.seed,
        }


@dataclass
class HmdaEvalResult:
    auc: float
    ks: float
    pr_auc: float
    brier: float
    threshold: float
    n_samples: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "auc": round(self.auc, 6),
            "ks": round(self.ks, 6),
            "pr_auc": round(self.pr_auc, 6),
            "brier": round(self.brier, 6),
            "threshold": round(self.threshold, 6),
            "n_samples": self.n_samples,
        }


@dataclass
class HmdaTrainResult:
    booster: Any  # lightgbm.Booster
    schema: FeatureSchema
    config: HmdaTrainConfig
    best_iteration: int
    holdout: HmdaEvalResult
    valid: HmdaEvalResult
    best_params: dict[str, Any] | None = None


def build_lgbm_dataset(
    df: pd.DataFrame,
    schema: FeatureSchema,
    reference=None,
):
    """원본 DataFrame → lgb.Dataset. categorical 은 schema.categorical 로 지정."""
    import lightgbm as lgb

    X = prepare_features(df, schema)
    y = prepare_hmda_labels(df)
    return lgb.Dataset(
        X,
        label=y,
        categorical_feature=list(schema.categorical),
        reference=reference,
        free_raw_data=False,
    )


def _ks_statistic(y_true: np.ndarray, y_score: np.ndarray) -> float:
    """KS = max(TPR - FPR)."""
    fpr, tpr, _ = roc_curve(y_true, y_score)
    return float(np.max(tpr - fpr))


def _threshold_at_f1_max(y_true: np.ndarray, y_score: np.ndarray) -> float:
    """F1 을 최대화하는 분류 임계값."""
    thresholds = np.unique(np.quantile(y_score, np.linspace(0.05, 0.95, 19)))
    best_t, best_f1 = 0.5, -1.0
    for t in thresholds:
        f1 = f1_score(y_true, (y_score >= t).astype(int), zero_division=0)
        if f1 > best_f1:
            best_f1, best_t = f1, float(t)
    return best_t


def evaluate_booster(
    booster,
    df: pd.DataFrame,
    schema: FeatureSchema,
) -> HmdaEvalResult:
    """AUC-ROC, KS, PR-AUC, Brier, threshold@F1-max 산출."""
    X = prepare_features(df, schema)
    y = prepare_hmda_labels(df).to_numpy()
    score = booster.predict(X, num_iteration=booster.best_iteration)
    return HmdaEvalResult(
        auc=float(roc_auc_score(y, score)),
        ks=_ks_statistic(y, score),
        pr_auc=float(average_precision_score(y, score)),
        brier=float(brier_score_loss(y, score)),
        threshold=_threshold_at_f1_max(y, score),
        n_samples=int(len(y)),
    )


def train_booster(
    splits: Splits,
    schema: FeatureSchema,
    config: HmdaTrainConfig,
):
    """early_stopping + log_evaluation 콜백으로 booster 학습."""
    import lightgbm as lgb

    train_ds = build_lgbm_dataset(splits.train, schema)
    valid_ds = build_lgbm_dataset(splits.valid, schema, reference=train_ds)

    booster = lgb.train(
        params=config.to_lgb_params(),
        train_set=train_ds,
        num_boost_round=config.num_boost_round,
        valid_sets=[train_ds, valid_ds],
        valid_names=["train", "valid"],
        callbacks=[
            lgb.early_stopping(config.early_stopping_rounds, verbose=False),
            lgb.log_evaluation(period=100),
        ],
    )
    return booster


def tune_hyperparams(
    splits: Splits,
    schema: FeatureSchema,
    n_trials: int = 20,
    seed: int = 42,
) -> dict[str, Any]:
    """Optuna TPE 로 valid AUC 최대화. best_params(dict) 반환."""
    import lightgbm as lgb
    import optuna

    train_ds = build_lgbm_dataset(splits.train, schema)
    valid_ds = build_lgbm_dataset(splits.valid, schema, reference=train_ds)

    def objective(trial: "optuna.Trial") -> float:
        params = {
            **LGBM_BASE_PARAMS,
            "num_leaves": trial.suggest_categorical("num_leaves", [63, 127, 255]),
            "learning_rate": trial.suggest_categorical("learning_rate", [0.03, 0.05, 0.10]),
            "min_child_samples": trial.suggest_categorical("min_child_samples", [20, 50, 100]),
            "bagging_fraction": trial.suggest_categorical("bagging_fraction", [0.7, 0.8, 0.9]),
            "bagging_freq": 1,
            "feature_fraction": trial.suggest_categorical("feature_fraction", [0.7, 0.8, 0.9]),
            "lambda_l1": trial.suggest_categorical("lambda_l1", [0.0, 0.1, 0.5]),
            "lambda_l2": trial.suggest_categorical("lambda_l2", [1, 5, 10]),
        }
        booster = lgb.train(
            params=params,
            train_set=train_ds,
            num_boost_round=600,
            valid_sets=[valid_ds],
            valid_names=["valid"],
            callbacks=[lgb.early_stopping(50, verbose=False)],
        )
        X_valid = prepare_features(splits.valid, schema)
        y_valid = prepare_hmda_labels(splits.valid).to_numpy()
        score = booster.predict(X_valid, num_iteration=booster.best_iteration)
        return float(roc_auc_score(y_valid, score))

    study = optuna.create_study(
        direction="maximize",
        sampler=optuna.samplers.TPESampler(seed=seed),
        pruner=optuna.pruners.MedianPruner(),
    )
    study.optimize(objective, n_trials=n_trials, show_progress_bar=False)
    log.info("optuna best AUC=%.4f params=%s", study.best_value, study.best_params)
    return study.best_params


def train_hmda(
    splits: Splits,
    config: HmdaTrainConfig | None = None,
    tune: bool = False,
    n_trials: int = 20,
) -> HmdaTrainResult:
    """HMDA 학습 엔드투엔드. tune=True 면 Optuna 로 config 덮어씀."""
    schema = fit_categories(splits.train, hmda_feature_schema())
    log.info("fit_categories: %s", {k: len(v) for k, v in schema.category_codes.items()})

    best_params: dict[str, Any] | None = None
    config = config or HmdaTrainConfig()
    if tune:
        best_params = tune_hyperparams(splits, schema, n_trials=n_trials, seed=config.seed)
        config = HmdaTrainConfig(
            num_leaves=best_params["num_leaves"],
            learning_rate=best_params["learning_rate"],
            min_child_samples=best_params["min_child_samples"],
            subsample=best_params["bagging_fraction"],
            colsample_bytree=best_params["feature_fraction"],
            reg_alpha=best_params["lambda_l1"],
            reg_lambda=best_params["lambda_l2"],
            seed=config.seed,
        )

    booster = train_booster(splits, schema, config)
    valid_eval = evaluate_booster(booster, splits.valid, schema)
    holdout_eval = evaluate_booster(booster, splits.holdout, schema)
    log.info("valid:   AUC=%.4f KS=%.4f", valid_eval.auc, valid_eval.ks)
    log.info("holdout: AUC=%.4f KS=%.4f", holdout_eval.auc, holdout_eval.ks)

    return HmdaTrainResult(
        booster=booster,
        schema=schema,
        config=config,
        best_iteration=int(booster.best_iteration),
        holdout=holdout_eval,
        valid=valid_eval,
        best_params=best_params,
    )
