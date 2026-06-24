"""자동심사 모델 추론 서버 (FastAPI) — LightGBM ONNX 기반.

학습 산출물(data/models/<model_id>/ : model.onnx + feature_schema.json +
calibrator.json + shap_background.parquet + metadata.json) 을 onnxruntime 으로 로드한다.

엔드포인트
- GET  /health       헬스체크 (양 모델 로드 여부)
- POST /predict      decision 모델(hmda_v1) 추론 + 선택적 SHAP
- POST /predict/pd   PD 모델(homecredit_kr_v1) 추론 + 선택적 SHAP
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.model_bundle import OnnxModelBundle
from app.shap_explainer import OnnxShapExplainer

log = logging.getLogger("inference")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)

MODEL_ID = os.getenv("MODEL_ID", "hmda_v1")
MODEL_DIR_OVERRIDE = os.getenv("MODEL_DIR")
PD_MODEL_ID = os.getenv("PD_MODEL_ID", "homecredit_kr_v1")
PD_MODEL_DIR_OVERRIDE = os.getenv("PD_MODEL_DIR")

SHAP_TOP_K = int(os.getenv("SHAP_TOP_K", "3"))
SHAP_BG_N = int(os.getenv("SHAP_BG_N", "500"))
PD_DEFAULT_THRESHOLD = float(os.getenv("PD_THRESHOLD", "0.06"))
MAX_BATCH_SIZE = int(os.getenv("MAX_BATCH_SIZE", "1000"))


def _resolve_dir(model_id: str, override: str | None) -> Path | None:
    if override:
        p = Path(override)
        return p if p.exists() else None
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "data" / "models" / model_id
        if candidate.exists():
            return candidate
    return None


# --- Pydantic IO ----------------------------------------------------------

class PredictRequest(BaseModel):
    """단건/배치. features 는 raw 키-값 dict 리스트. 누락 키는 NaN/None 처리."""
    features: list[dict[str, Any]] = Field(..., min_length=1, max_length=10_000)
    explain: bool = True


class ShapEntry(BaseModel):
    feature: str
    shap_value: float


class Prediction(BaseModel):
    decision: str
    score: float = Field(description="예측 결정 클래스의 확률(0~1)")
    proba: dict[str, float]
    shap_top3: list[ShapEntry] = Field(default_factory=list)


class PredictResponse(BaseModel):
    model_config = {"protected_namespaces": ()}
    model_version: str
    predictions: list[Prediction]


class PdPrediction(BaseModel):
    pd_score: float = Field(description="P(default_within_12m=1) — calibration 후")
    decision: str = Field(description="PD threshold 기준 HIGH/LOW")
    shap_top3: list[ShapEntry] = Field(default_factory=list)


class PdPredictResponse(BaseModel):
    model_config = {"protected_namespaces": ()}
    model_version: str
    threshold: float
    calibrated: bool
    predictions: list[PdPrediction]


class HealthResponse(BaseModel):
    model_config = {"protected_namespaces": ()}
    status: str
    model_version: str
    n_features: int | None = None


# --- App lifecycle --------------------------------------------------------

_bundle: OnnxModelBundle | None = None
_pd_bundle: OnnxModelBundle | None = None
_explainer: OnnxShapExplainer | None = None
_pd_explainer: OnnxShapExplainer | None = None

app = FastAPI(title="auto-review inference", version="2.0.0")

from app.ocr_router import router as ocr_router            # noqa: E402
from app.extract_router import router as extract_router    # noqa: E402
from app.forgery_router import router as forgery_router    # noqa: E402
app.include_router(ocr_router)
app.include_router(extract_router)
app.include_router(forgery_router)


def _make_explainer(bundle: OnnxModelBundle) -> OnnxShapExplainer | None:
    if SHAP_TOP_K <= 0 or bundle.background_df is None:
        return None
    try:
        return OnnxShapExplainer(bundle, top_k=SHAP_TOP_K, background_n=SHAP_BG_N)
    except Exception as e:
        log.warning("SHAP explainer init failed for %s: %s", bundle.model_id, e)
        return None


@app.on_event("startup")
def _load() -> None:
    global _bundle, _pd_bundle, _explainer, _pd_explainer
    mdir = _resolve_dir(MODEL_ID, MODEL_DIR_OVERRIDE)
    if mdir is not None:
        try:
            _bundle = OnnxModelBundle(mdir)
            _bundle.warm_up()
            _explainer = _make_explainer(_bundle)
        except Exception as e:
            log.warning("decision model load failed (/predict → 503): %s", e)
    else:
        log.info("decision model dir not found — /predict will return 503")

    pddir = _resolve_dir(PD_MODEL_ID, PD_MODEL_DIR_OVERRIDE)
    if pddir is not None:
        try:
            _pd_bundle = OnnxModelBundle(pddir)
            _pd_bundle.warm_up()
            _pd_explainer = _make_explainer(_pd_bundle)
        except Exception as e:
            log.warning("PD model load failed (/predict/pd → 503): %s", e)
    else:
        log.info("PD model dir not found — /predict/pd will return 503")


def _check_batch(req: PredictRequest) -> None:
    if len(req.features) > MAX_BATCH_SIZE:
        raise HTTPException(status_code=413, detail=f"batch > MAX_BATCH_SIZE({MAX_BATCH_SIZE})")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    if _bundle is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    return HealthResponse(
        status="UP",
        model_version=_bundle.model_id,
        n_features=len(_bundle.schema.all_features),
    )


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest) -> PredictResponse:
    if _bundle is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    _check_batch(req)
    df = pd.DataFrame(req.features)
    proba = _bundle.predict_proba(df)
    classes = _bundle.schema.label_classes
    shaps = (_explainer.explain_top_k(df)
             if req.explain and _explainer is not None else [[] for _ in range(len(df))])

    preds: list[Prediction] = []
    for row, sh in zip(proba, shaps):
        top = int(np.argmax(row))
        preds.append(Prediction(
            decision=classes[top],
            score=float(row[top]),
            proba={cls: float(p) for cls, p in zip(classes, row)},
            shap_top3=[ShapEntry(**e) for e in sh],
        ))
    return PredictResponse(model_version=_bundle.model_id, predictions=preds)


@app.post("/predict/pd", response_model=PdPredictResponse)
def predict_pd(req: PredictRequest) -> PdPredictResponse:
    if _pd_bundle is None:
        raise HTTPException(status_code=503, detail="PD model not loaded")
    _check_batch(req)
    df = pd.DataFrame(req.features)
    pd_scores = _pd_bundle.predict_pd_proba(df)
    calibrated = _pd_bundle.calibrator is not None
    threshold = float(_pd_bundle.metadata.get("pd_threshold", PD_DEFAULT_THRESHOLD))
    shaps = (_pd_explainer.explain_top_k(df)
             if req.explain and _pd_explainer is not None else [[] for _ in range(len(df))])

    preds: list[PdPrediction] = []
    for score, sh in zip(pd_scores, shaps):
        preds.append(PdPrediction(
            pd_score=float(score),
            decision="HIGH" if float(score) >= threshold else "LOW",
            shap_top3=[ShapEntry(**e) for e in sh],
        ))
    return PdPredictResponse(
        model_version=_pd_bundle.model_id,
        threshold=threshold,
        calibrated=calibrated,
        predictions=preds,
    )
