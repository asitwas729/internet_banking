"""homecredit_kr_v1 PD 모델 — LightGBM 이진 디폴트 분류 + Kamiran reweight.

타겟: default_within_12m (1=default). Home Credit 양성률(~8%)을 한국 추정
디폴트율(1~3%)로 sample reweighting 해 실효 base rate 를 보정한다.
완료 기준: holdout Gini ≥ 0.64, KS ≥ 0.41.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd
from scipy.stats import ks_2samp
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss, f1_score, roc_auc_score

from .dataset import Splits
from .features import FeatureSchema, fit_categories, prepare_features
from .features_pd import PD_LABEL_COL, pd_feature_schema, prepare_pd_labels
from .onnx_export import export_lgbm_to_onnx, onnx_smoke_check

log = logging.getLogger(__name__)

LGBM_PD_BASE_PARAMS: dict[str, Any] = {
    "objective": "binary",
    "metric": ["auc", "binary_logloss"],
    "boosting_type": "gbdt",
    "seed": 42,
    "n_jobs": -1,
    "verbose": -1,
}


@dataclass
class PdTrainConfig:
    num_leaves: int = 63
    learning_rate: float = 0.05
    min_child_samples: int = 100
    subsample: float = 0.7
    colsample_bytree: float = 0.7
    scale_pos_weight: float = 1.0
    reg_alpha: float = 0.5
    reg_lambda: float = 10.0
    num_boost_round: int = 1000
    early_stopping_rounds: int = 50
    seed: int = 42

    def to_lgb_params(self) -> dict[str, Any]:
        return {
            **LGBM_PD_BASE_PARAMS,
            "num_leaves": self.num_leaves,
            "learning_rate": self.learning_rate,
            "min_child_samples": self.min_child_samples,
            "bagging_fraction": self.subsample,
            "bagging_freq": 1,
            "feature_fraction": self.colsample_bytree,
            "scale_pos_weight": self.scale_pos_weight,
            "lambda_l1": self.reg_alpha,
            "lambda_l2": self.reg_lambda,
            "seed": self.seed,
        }


@dataclass
class PdEvalResult:
    auc: float
    gini: float
    ks: float
    brier: float
    threshold: float
    pd_threshold: float
    lift_decile1: float
    n_samples: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "auc": round(self.auc, 6),
            "gini": round(self.gini, 6),
            "ks": round(self.ks, 6),
            "brier": round(self.brier, 6),
            "threshold": round(self.threshold, 6),
            "pd_threshold": round(self.pd_threshold, 6),
            "lift_decile1": round(self.lift_decile1, 6),
            "n_samples": self.n_samples,
        }


class PlattCalibrator:
    """1D 로지스틱(Platt) 스케일러 — IsotonicRegression 과 동일한 predict 인터페이스."""

    def __init__(self, lr: LogisticRegression) -> None:
        self._lr = lr

    def predict(self, score) -> np.ndarray:
        x = np.asarray(score, dtype=float).reshape(-1, 1)
        return self._lr.predict_proba(x)[:, 1]


@dataclass
class PdTrainResult:
    booster: Any  # lightgbm.Booster
    schema: FeatureSchema
    config: PdTrainConfig
    best_iteration: int
    holdout: PdEvalResult
    valid: PdEvalResult
    best_params: dict[str, Any] | None = None
    desired_positive_rate: float = 0.03
    calibrator: Any = None
    holdout_raw: PdEvalResult | None = None


def apply_class_weight(
    train_df: pd.DataFrame,
    label_col: str = PD_LABEL_COL,
    desired_positive_rate: float = 0.03,
    observed_positive_rate: float | None = None,
) -> pd.Series:
    """Kamiran-Calders sample reweighting — 실효 양성률을 desired 로 맞춤.

    음성 가중치=1.0, 양성 가중치 w = d(1-p) / (p(1-d)) (p=관측 양성률, d=목표).
    p > d 이면 w < 1 → 양성 다운웨이트(음성 클래스 중시).
    """
    y = prepare_pd_labels(train_df, label_col).to_numpy()
    p = observed_positive_rate if observed_positive_rate is not None else float(y.mean())
    d = desired_positive_rate
    if not 0.0 < p < 1.0:
        return pd.Series(np.ones(len(y)), index=train_df.index)
    w_pos = d * (1.0 - p) / (p * (1.0 - d))
    weights = np.where(y == 1, w_pos, 1.0)
    return pd.Series(weights, index=train_df.index)


def build_pd_dataset(
    df: pd.DataFrame,
    schema: FeatureSchema,
    weight: pd.Series | None = None,
    reference=None,
):
    """원본 DataFrame → lgb.Dataset (PD 라벨 + 선택적 sample weight)."""
    import lightgbm as lgb

    X = prepare_features(df, schema)
    y = prepare_pd_labels(df)
    return lgb.Dataset(
        X,
        label=y,
        weight=weight.to_numpy() if weight is not None else None,
        categorical_feature=list(schema.categorical),
        reference=reference,
        free_raw_data=False,
    )


def _ks_statistic(y_true: np.ndarray, score: np.ndarray) -> float:
    """KS = max|CDF_pos - CDF_neg| (scipy ks_2samp)."""
    pos = score[y_true == 1]
    neg = score[y_true == 0]
    if pos.size == 0 or neg.size == 0:
        return 0.0
    return float(ks_2samp(pos, neg).statistic)


def _lift_decile1(y_true: np.ndarray, score: np.ndarray) -> float:
    """상위 10% 점수 구간의 양성률 / 전체 양성률 (lift)."""
    overall = float(y_true.mean())
    if overall == 0.0:
        return 0.0
    k = max(1, int(len(score) * 0.1))
    top_idx = np.argsort(score)[::-1][:k]
    top_rate = float(y_true[top_idx].mean())
    return top_rate / overall


def _threshold_at_f1_max(y_true: np.ndarray, score: np.ndarray) -> float:
    thresholds = np.unique(np.quantile(score, np.linspace(0.05, 0.95, 19)))
    best_t, best_f1 = 0.5, -1.0
    for t in thresholds:
        f1 = f1_score(y_true, (score >= t).astype(int), zero_division=0)
        if f1 > best_f1:
            best_f1, best_t = f1, float(t)
    return best_t


def evaluate_pd(
    booster,
    df: pd.DataFrame,
    schema: FeatureSchema,
    calibrator=None,
) -> PdEvalResult:
    """AUC, Gini(=2·AUC-1), KS, Brier, lift, threshold@F1max, pd_threshold(80th pct)."""
    X = prepare_features(df, schema)
    y = prepare_pd_labels(df).to_numpy()
    score = booster.predict(X, num_iteration=booster.best_iteration)
    if calibrator is not None:
        score = calibrator.predict(score)
    auc = float(roc_auc_score(y, score))
    return PdEvalResult(
        auc=auc,
        gini=2.0 * auc - 1.0,
        ks=_ks_statistic(y, score),
        brier=float(brier_score_loss(y, score)),
        threshold=_threshold_at_f1_max(y, score),
        pd_threshold=float(np.quantile(score, 0.80)),
        lift_decile1=_lift_decile1(y, score),
        n_samples=int(len(y)),
    )


def calibrate_pd(
    booster,
    valid_df: pd.DataFrame,
    schema: FeatureSchema,
    target_base_rate: float = 0.02,
):
    """valid 셋 isotonic / Platt 중 Brier 낮은 쪽 선택.

    valid 는 reweight 되지 않아 양성률이 원본(~8%) 이므로, calibrator fit 시
    target_base_rate 로 sample reweighting 해 출력 분포를 한국 추정 디폴트율로 이동.
    """
    X = prepare_features(valid_df, schema)
    y = prepare_pd_labels(valid_df).to_numpy()
    raw = booster.predict(X, num_iteration=booster.best_iteration)
    w = apply_class_weight(valid_df, desired_positive_rate=target_base_rate).to_numpy()

    iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
    iso.fit(raw, y, sample_weight=w)
    platt = PlattCalibrator(
        LogisticRegression(max_iter=1000).fit(raw.reshape(-1, 1), y, sample_weight=w)
    )

    b_iso = brier_score_loss(y, iso.predict(raw), sample_weight=w)
    b_platt = brier_score_loss(y, platt.predict(raw), sample_weight=w)
    chosen, name = (iso, "isotonic") if b_iso <= b_platt else (platt, "platt")
    log.info("calibrate_pd: weighted brier iso=%.5f platt=%.5f -> %s", b_iso, b_platt, name)
    return chosen


def pd_calibrator_to_dict(calibrator) -> dict[str, Any]:
    """isotonic/Platt calibrator 직렬화 — 언어중립 추론용."""
    if isinstance(calibrator, IsotonicRegression):
        return {
            "type": "isotonic",
            "x_thresholds": [float(x) for x in calibrator.X_thresholds_],
            "y_thresholds": [float(y) for y in calibrator.y_thresholds_],
        }
    if isinstance(calibrator, PlattCalibrator):
        lr = calibrator._lr
        return {
            "type": "platt",
            "coef": float(lr.coef_.ravel()[0]),
            "intercept": float(lr.intercept_.ravel()[0]),
        }
    raise TypeError(f"unsupported calibrator: {type(calibrator)}")


def export_pd_onnx(booster, schema: FeatureSchema, out_path) -> Any:
    """PD booster → ONNX. 출력 [N,2] 의 [:,1] = P(default). 일반 export 재사용."""
    return export_lgbm_to_onnx(booster, schema, out_path)


def pd_onnx_smoke_check(booster, onnx_path, df: pd.DataFrame, schema: FeatureSchema, atol: float = 1e-4) -> float:
    """LightGBM vs ONNX P(default) 최대 절대차. atol 초과 시 ValueError."""
    return onnx_smoke_check(booster, onnx_path, df, schema, atol=atol)


def train_pd_booster(
    splits: Splits,
    schema: FeatureSchema,
    config: PdTrainConfig,
    weight: pd.Series | None,
):
    """early_stopping 으로 PD booster 학습 (train 에 sample weight 적용)."""
    import lightgbm as lgb

    train_ds = build_pd_dataset(splits.train, schema, weight=weight)
    valid_ds = build_pd_dataset(splits.valid, schema, reference=train_ds)
    return lgb.train(
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


def tune_pd_hyperparams(
    splits: Splits,
    schema: FeatureSchema,
    weight: pd.Series | None,
    n_trials: int = 25,
    seed: int = 42,
    ks_floor: float = 0.38,
) -> dict[str, Any]:
    """Optuna TPE 로 valid AUC 최대화. KS < ks_floor 면 trial pruned."""
    import lightgbm as lgb
    import optuna

    train_ds = build_pd_dataset(splits.train, schema, weight=weight)
    valid_ds = build_pd_dataset(splits.valid, schema, reference=train_ds)
    X_valid = prepare_features(splits.valid, schema)
    y_valid = prepare_pd_labels(splits.valid).to_numpy()

    def objective(trial: "optuna.Trial") -> float:
        params = {
            **LGBM_PD_BASE_PARAMS,
            "num_leaves": trial.suggest_categorical("num_leaves", [31, 63, 127]),
            "learning_rate": trial.suggest_categorical("learning_rate", [0.02, 0.05, 0.10]),
            "min_child_samples": trial.suggest_categorical("min_child_samples", [50, 100, 200]),
            "bagging_fraction": trial.suggest_categorical("bagging_fraction", [0.6, 0.7, 0.8]),
            "bagging_freq": 1,
            "feature_fraction": trial.suggest_categorical("feature_fraction", [0.6, 0.7, 0.8]),
            "scale_pos_weight": trial.suggest_categorical("scale_pos_weight", [1.0, 2.0, 5.0]),
            "lambda_l1": trial.suggest_categorical("lambda_l1", [0.0, 0.5, 1.0]),
            "lambda_l2": trial.suggest_categorical("lambda_l2", [5, 10, 20]),
        }
        booster = lgb.train(
            params=params,
            train_set=train_ds,
            num_boost_round=600,
            valid_sets=[valid_ds],
            valid_names=["valid"],
            callbacks=[lgb.early_stopping(50, verbose=False)],
        )
        score = booster.predict(X_valid, num_iteration=booster.best_iteration)
        if _ks_statistic(y_valid, score) < ks_floor:
            raise optuna.TrialPruned()
        return float(roc_auc_score(y_valid, score))

    study = optuna.create_study(
        direction="maximize",
        sampler=optuna.samplers.TPESampler(seed=seed),
        pruner=optuna.pruners.MedianPruner(),
    )
    study.optimize(objective, n_trials=n_trials, show_progress_bar=False)
    log.info("optuna PD best AUC=%.4f params=%s", study.best_value, study.best_params)
    return study.best_params


def train_pd(
    splits: Splits,
    config: PdTrainConfig | None = None,
    tune: bool = False,
    n_trials: int = 25,
    reweight: bool = True,
    desired_positive_rate: float = 0.03,
    calibrate: bool = True,
) -> PdTrainResult:
    """PD 학습 엔드투엔드. reweight=True 면 Kamiran sample weight 로 base rate 보정.

    calibrate=True 면 valid 셋으로 isotonic/Platt calibrator 를 학습해 holdout 평가에 적용.
    """
    schema = fit_categories(splits.train, pd_feature_schema())
    log.info("fit_categories: %s", {k: len(v) for k, v in schema.category_codes.items()})

    weight = (
        apply_class_weight(splits.train, desired_positive_rate=desired_positive_rate)
        if reweight else None
    )

    config = config or PdTrainConfig()
    best_params: dict[str, Any] | None = None
    if tune:
        best_params = tune_pd_hyperparams(splits, schema, weight, n_trials=n_trials, seed=config.seed)
        config = PdTrainConfig(
            num_leaves=best_params["num_leaves"],
            learning_rate=best_params["learning_rate"],
            min_child_samples=best_params["min_child_samples"],
            subsample=best_params["bagging_fraction"],
            colsample_bytree=best_params["feature_fraction"],
            scale_pos_weight=best_params["scale_pos_weight"],
            reg_alpha=best_params["lambda_l1"],
            reg_lambda=best_params["lambda_l2"],
            seed=config.seed,
        )

    booster = train_pd_booster(splits, schema, config, weight)

    calibrator = (
        calibrate_pd(booster, splits.valid, schema, target_base_rate=desired_positive_rate)
        if calibrate else None
    )
    holdout_raw = evaluate_pd(booster, splits.holdout, schema)
    valid_eval = evaluate_pd(booster, splits.valid, schema, calibrator)
    holdout_eval = evaluate_pd(booster, splits.holdout, schema, calibrator)
    log.info("valid:   AUC=%.4f Gini=%.4f KS=%.4f", valid_eval.auc, valid_eval.gini, valid_eval.ks)
    log.info("holdout: AUC=%.4f Gini=%.4f KS=%.4f lift1=%.2f pd_thr=%.4f",
             holdout_eval.auc, holdout_eval.gini, holdout_eval.ks,
             holdout_eval.lift_decile1, holdout_eval.pd_threshold)

    return PdTrainResult(
        booster=booster,
        schema=schema,
        config=config,
        best_iteration=int(booster.best_iteration),
        holdout=holdout_eval,
        valid=valid_eval,
        best_params=best_params,
        desired_positive_rate=desired_positive_rate,
        calibrator=calibrator,
        holdout_raw=holdout_raw,
    )
