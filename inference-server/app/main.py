"""자동심사 모델 추론 서버 (FastAPI).

학습 산출물(data/models/auto_review_<v>/) 을 로드해 HTTP /predict 엔드포인트로 노출한다.
ai-service(Java) 가 이 서버를 게이트웨이 패턴으로 호출.

엔드포인트
- GET  /health       헬스체크 (모델 로드 여부)
- POST /predict      단건 또는 배치 추론
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

import pandas as pd
import xgboost as xgb
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

log = logging.getLogger("inference")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)

MODEL_VERSION = os.getenv("MODEL_VERSION", "v1")
MODEL_DIR_OVERRIDE = os.getenv("MODEL_DIR")  # 절대 경로 직지정 (Docker 마운트용)


def _resolve_model_dir() -> Path:
    if MODEL_DIR_OVERRIDE:
        return Path(MODEL_DIR_OVERRIDE)
    here = Path(__file__).resolve()
    # repo-root/data/models/auto_review_<v>/
    for parent in here.parents:
        candidate = parent / "data" / "models" / f"auto_review_{MODEL_VERSION}"
        if candidate.exists():
            return candidate
    raise FileNotFoundError(
        f"model dir not found for version={MODEL_VERSION}; set MODEL_DIR env"
    )


class FeatureSchema:
    """학습 측 training.features.FeatureSchema 와 같은 구조의 경량 구현 (의존성 0)."""

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


class ModelBundle:
    def __init__(self, model_dir: Path) -> None:
        self.model_dir = model_dir
        schema_path = model_dir / "feature_schema.json"
        model_path = model_dir / "model.json"
        metadata_path = model_dir / "metadata.json"
        if not schema_path.exists() or not model_path.exists():
            raise FileNotFoundError(f"incomplete model bundle in {model_dir}")

        self.schema = FeatureSchema(json.loads(schema_path.read_text(encoding="utf-8")))
        self.metadata = (
            json.loads(metadata_path.read_text(encoding="utf-8"))
            if metadata_path.exists() else {}
        )
        self.booster = xgb.Booster()
        self.booster.load_model(str(model_path))
        self.best_iteration: int | None = self.metadata.get("best_iteration")

        log.info("model loaded: %s (best_iter=%s)", model_dir, self.best_iteration)

    def predict_proba(self, df: pd.DataFrame) -> list[list[float]]:
        X = self._prepare(df)
        dmat = xgb.DMatrix(X, enable_categorical=True)
        if self.best_iteration is not None:
            proba = self.booster.predict(dmat, iteration_range=(0, self.best_iteration + 1))
        else:
            proba = self.booster.predict(dmat)
        return proba.tolist()

    def _prepare(self, df: pd.DataFrame) -> pd.DataFrame:
        out = pd.DataFrame(index=df.index)
        for col in self.schema.categorical:
            series = df[col].astype("string") if col in df.columns else pd.Series([None] * len(df), dtype="string")
            codes = self.schema.category_codes.get(col)
            out[col] = pd.Categorical(series, categories=codes) if codes else series.astype("category")
        for col in self.schema.numeric:
            val = df[col] if col in df.columns else pd.Series([None] * len(df))
            out[col] = pd.to_numeric(val, errors="coerce").astype("float32")
        for col in self.schema.boolean:
            val = df[col] if col in df.columns else pd.Series([False] * len(df))
            out[col] = val.fillna(False).astype("int8")
        return out[self.schema.all_features]


# --- Pydantic IO ----------------------------------------------------------

class PredictRequest(BaseModel):
    """단건 또는 배치. features 는 raw 키-값 dict 의 리스트.

    누락 키는 NaN/None 처리.
    """
    features: list[dict[str, Any]] = Field(..., min_length=1, max_length=10_000)


class Prediction(BaseModel):
    decision: str
    score: float = Field(description="예측 결정 클래스의 확률(0~1)")
    proba: dict[str, float]


class PredictResponse(BaseModel):
    model_version: str
    predictions: list[Prediction]


class HealthResponse(BaseModel):
    status: str
    model_version: str
    best_iteration: int | None
    holdout_accuracy: float | None
    n_features: int


# --- App lifecycle --------------------------------------------------------

_bundle: ModelBundle | None = None
app = FastAPI(title="auto-review inference", version="1.0.0")


@app.on_event("startup")
def _load() -> None:
    global _bundle
    _bundle = ModelBundle(_resolve_model_dir())


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    if _bundle is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    holdout_acc = None
    try:
        holdout_acc = float(_bundle.metadata["holdout_metrics"]["accuracy"])
    except (KeyError, TypeError):
        pass
    return HealthResponse(
        status="UP",
        model_version=MODEL_VERSION,
        best_iteration=_bundle.best_iteration,
        holdout_accuracy=holdout_acc,
        n_features=len(_bundle.schema.all_features),
    )


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest) -> PredictResponse:
    if _bundle is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    df = pd.DataFrame(req.features)
    proba = _bundle.predict_proba(df)
    classes = _bundle.schema.label_classes

    preds: list[Prediction] = []
    for row in proba:
        top = max(range(len(row)), key=lambda i: row[i])
        preds.append(Prediction(
            decision=classes[top],
            score=float(row[top]),
            proba={cls: float(p) for cls, p in zip(classes, row)},
        ))
    return PredictResponse(model_version=MODEL_VERSION, predictions=preds)
