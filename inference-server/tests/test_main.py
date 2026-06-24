"""FastAPI /health·/predict·/predict/pd 통합 테스트."""


def test_health_returns_200(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "UP"
    assert "hmda_v1" in body["models"]
    assert body["models"]["hmda_v1"]["loaded"] is True


def test_predict_decision_approve(client, hmda_feature):
    r = client.post("/predict", json={"features": [hmda_feature], "explain": False})
    assert r.status_code == 200
    assert r.json()["predictions"][0]["decision"] == "APPROVE"


def test_predict_decision_reject(client, hmda_feature_reject):
    r = client.post("/predict", json={"features": [hmda_feature_reject], "explain": False})
    assert r.status_code == 200
    assert r.json()["predictions"][0]["decision"] == "REJECT"


def test_predict_with_explain_has_shap(client, hmda_feature):
    r = client.post("/predict", json={"features": [hmda_feature], "explain": True})
    assert r.status_code == 200
    assert len(r.json()["predictions"][0]["shap_top3"]) == 3


def test_predict_without_explain_no_shap(client, hmda_feature):
    r = client.post("/predict", json={"features": [hmda_feature], "explain": False})
    assert r.status_code == 200
    assert r.json()["predictions"][0]["shap_top3"] == []


def test_predict_pd_low_risk(client, pd_feature_low):
    r = client.post("/predict/pd", json={"features": [pd_feature_low], "explain": False})
    assert r.status_code == 200
    assert r.json()["predictions"][0]["decision"] == "LOW"


def test_predict_pd_high_risk(client, pd_feature_high):
    r = client.post("/predict/pd", json={"features": [pd_feature_high], "explain": False})
    assert r.status_code == 200
    assert r.json()["predictions"][0]["decision"] == "HIGH"


def test_predict_pd_503_when_not_loaded(make_client, pd_feature_low):
    with make_client(with_pd=False) as c:
        r = c.post("/predict/pd", json={"features": [pd_feature_low], "explain": False})
        assert r.status_code == 503
