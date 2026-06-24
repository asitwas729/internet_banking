"""OnnxShapExplainer 단위 테스트."""

import numpy as np
import pandas as pd


def _explainer(hmda_bundle, top_k):
    from app.shap_explainer import OnnxShapExplainer

    return OnnxShapExplainer(hmda_bundle, top_k=top_k, background_n=60, budget=256)


def test_explain_returns_top_k_features(hmda_bundle, hmda_feature):
    ex = _explainer(hmda_bundle, top_k=3)
    out = ex.explain_top_k(pd.DataFrame([hmda_feature]))
    assert len(out) == 1
    assert len(out[0]) == 3
    for entry in out[0]:
        assert set(entry) == {"feature", "shap_value"}
        assert entry["feature"] in hmda_bundle.schema.all_features


def test_explain_zero_top_k_returns_empty(hmda_bundle, hmda_feature):
    ex = _explainer(hmda_bundle, top_k=0)
    out = ex.explain_top_k(pd.DataFrame([hmda_feature]))
    assert out == [[]]


def test_shap_values_sum_approx_to_pred(hmda_bundle, hmda_feature):
    ex = _explainer(hmda_bundle, top_k=3)
    df = pd.DataFrame([hmda_feature])
    matrix = hmda_bundle.to_matrix(df)
    values = ex.explain_values(matrix[0])
    baseline = ex.baseline_value(matrix[0])
    pred = float(hmda_bundle.predict_pd_proba(df)[0])
    # SHAP efficiency: sum(values) + baseline ≈ prediction
    assert abs((float(values.sum()) + baseline) - pred) < 0.05
