"""OnnxModelBundle 단위 테스트."""

import pandas as pd


def test_bundle_load_success(hmda_bundle):
    assert hmda_bundle.model_id == "hmda_v1"
    assert hmda_bundle.background_df is not None
    assert len(hmda_bundle.schema.all_features) == 26


def test_predict_proba_shape(hmda_bundle, hmda_feature):
    df = pd.DataFrame([hmda_feature, hmda_feature])
    proba = hmda_bundle.predict_proba(df)
    assert proba.shape == (2, 2)
    # 각 행 확률 합 ≈ 1
    assert abs(float(proba[0].sum()) - 1.0) < 1e-5


def test_warmup_completes_without_error(hmda_bundle):
    hmda_bundle.warm_up(n_rows=5)
