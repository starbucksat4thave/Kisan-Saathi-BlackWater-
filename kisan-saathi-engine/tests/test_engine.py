import datetime as dt, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from fastapi.testclient import TestClient
from kisan_engine import core
from kisan_engine.rules import Bundle, advisories, snapshot, heat_risk_for
from backend.main import app

B = Bundle(os.path.join(ROOT, "android", "assets", "kisan"))
client = TestClient(app)


def test_stage_names():
    assert core.stage_name(0) == "pre_emergence"
    assert core.stage_name(150) == "emergence"
    assert core.stage_name(749) == "tillering"
    assert core.stage_name(2500) == "maturity"


def test_gdd_matches_stage_dates():
    rows = {d["date"]: d for d in B.daily("dhudial")}
    sow = dt.date(2024, 11, 10)
    st = core.stage_dates(rows, sow, dt.date(2025, 6, 30))
    assert st["emergence"] < st["tillering"] < st["jointing"] < st["heading"] < st["anthesis"] < st["maturity"]
    assert core.gdd_since(rows, sow, st["maturity"]) >= 2000
    assert core.gdd_since(rows, sow, st["maturity"] - dt.timedelta(days=1)) < 2000


def test_soil_moisture_is_physical_every_day():
    days = B.daily("dhudial")
    for i in range(30, len(days), 9):
        wet = B.model.predict(core.sm_features(days[: i + 1]))
        assert 0.05 <= wet <= 1.0


def test_soil_moisture_rises_after_rain():
    days = [dict(d) for d in B.daily("dhudial")[:60]]
    for d in days[-10:]:
        d["rain"] = 0.0
    dry = B.model.predict(core.sm_features(days))
    for d in days[-3:]:
        d["rain"] = 25.0
    assert B.model.predict(core.sm_features(days)) > dry


def test_no_sowing_advice_outside_window_or_after_sowing():
    s = snapshot(B, "dhudial", dt.date(2025, 9, 1), None)
    assert not [a for a in advisories(B, s) if a["id"].startswith("sow")]
    s = snapshot(B, "dhudial", dt.date(2025, 11, 25), dt.date(2025, 11, 10))
    assert not [a for a in advisories(B, s) if a["id"].startswith("sow")]


def test_exactly_one_sowing_advice_in_window():
    for day in range(1, 30, 4):
        s = snapshot(B, "dhudial", dt.date(2023, 11, day), None)
        assert len([a for a in advisories(B, s) if a["id"].startswith("sow")]) == 1


def test_sow_now_only_when_moisture_in_range():
    seen = set()
    for y in range(2019, 2026):
        for off in range(0, 50):
            s = snapshot(B, "dhudial", dt.date(y, 10, 20) + dt.timedelta(days=off), None)
            for a in advisories(B, s):
                seen.add(a["id"])
                if a["id"] == "sow_now":
                    assert 0.20 <= s["soil_moisture"] <= 0.35 and s["fc_rain_48h"] < 20
                if a["id"] == "sow_wait_dry":
                    assert s["soil_moisture"] < 0.20
    assert {"sow_now", "sow_wait_dry"} <= seen


def test_no_pesticide_prescription_in_any_text():
    banned = ["imidacloprid", "ml per acre", "ml/acre", "gram per acre", "propiconazole", "tebuconazole"]
    for y in range(2019, 2026):
        for off in range(0, 200, 3):
            day = dt.date(y, 11, 1) + dt.timedelta(days=off)
            for a in advisories(B, snapshot(B, "dhudial", day, dt.date(y, 11, 10))):
                assert not any(w in (a["body_en"] + a["title_en"]).lower() for w in banned)
                assert a["threshold_status"] == "DRAFT"


def test_heat_risk_rises_with_later_sowing():
    early, late = heat_risk_for(B, dt.date(2026, 11, 1)), heat_risk_for(B, dt.date(2026, 12, 10))
    assert float(late["mean_heat_days"]) > float(early["mean_heat_days"])


def test_future_date_rejected():
    r = client.get("/v1/fields/dhudial/advisories", params={"date": "2027-03-01"})
    assert r.status_code == 422


def test_api_endpoints():
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/v1/fields/nowhere/conditions").status_code == 404
    c = client.get("/v1/fields/dhudial/conditions", params={"date": "2026-01-18", "sowing_date": "2025-11-10"}).json()
    assert c["stage"] == "jointing" and 0.05 < c["soil_moisture_m3m3"] < 0.47 and len(c["forecast"]) == 7
    a = client.get("/v1/fields/dhudial/advisories", params={"date": "2025-11-06"}).json()
    assert a["advisories"] and a["advisories"][0]["title_ur"]
    rm = client.get("/v1/fields/dhudial/roadmap", params={"sowing_date": "2025-11-10", "date": "2026-01-18"}).json()
    states = [n["state"] for n in rm["nodes"]]
    assert states.count("current") == 1 and states[0] == "done" and states[-1] == "locked"
    h = client.get("/v1/fields/dhudial/history").json()
    assert len(h) >= 6 and any(x["district_rainfed_yield_maund_per_acre"] for x in h)
    hr = client.get("/v1/fields/dhudial/heat-risk", params={"sowing_date": "2026-11-20"}).json()
    assert len(hr["table"]) == 11 and "for_sowing_date" in hr
    bk = client.get("/v1/fields/dhudial/barkat", params={"area_acre": 4, "loss_fraction": 0.15}).json()
    assert abs(bk["maunds_protected"] - 17.9 * 4 * 0.15) < 0.11
    assert client.get("/v1/fields/dhudial/ndvi").json()["available"] is False
    assert len(client.get("/v1/yields").json()) == 20
    today = client.get("/v1/fields/dhudial/advisories").json()
    assert today["data_as_of"] == "2026-11-12"
