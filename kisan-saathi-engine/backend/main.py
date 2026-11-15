"""Kisan Saathi backend API.

Run from the project root:
    pip install fastapi uvicorn
    uvicorn backend.main:app --host 0.0.0.0 --port 8000
Docs appear at http://localhost:8000/docs

Every response is computed from the data bundle in android/assets/kisan, the same
files the Android app ships, so the phone (offline) and the server agree.
"""
from __future__ import annotations
import datetime as dt, os, sys
from fastapi import FastAPI, HTTPException, Query

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from kisan_engine import core
from kisan_engine.rules import Bundle, advisories, heat_risk_for, snapshot

BUNDLE = Bundle(os.environ.get("KISAN_BUNDLE", os.path.join(ROOT, "android", "assets", "kisan")))
app = FastAPI(title="Kisan Saathi API", version="0.1.0",
              description="Advisories for rainfed wheat in Pothohar, computed from NASA and ERA5 data. All agronomy thresholds are DRAFT.")

STAGE_LABELS = {"emergence": ("Emergence", "اگاؤ"), "tillering": ("Tillering", "شاخیں نکلنا"), "jointing": ("Jointing", "گانٹھیں بننا"),
                "heading": ("Heading", "سٹے نکلنا"), "anthesis": ("Flowering", "پھول آنا"), "maturity": ("Maturity", "پکنا")}


def _loc(loc):
    if loc not in BUNDLE.locations():
        raise HTTPException(404, f"unknown location '{loc}'. Available: {BUNDLE.locations()}")
    return loc


def _date(s, default=None):
    if s is None:
        return default
    try:
        return dt.date.fromisoformat(s)
    except ValueError:
        raise HTTPException(422, f"'{s}' is not a date in YYYY-MM-DD form")


def _snap(loc, date, sowing):
    today = _date(date, BUNDLE.daily(loc)[-1]["date"])
    try:
        return snapshot(BUNDLE, loc, today, _date(sowing))
    except ValueError as e:
        raise HTTPException(422, str(e))


def _iso(x):
    return x.isoformat() if isinstance(x, dt.date) else x


@app.get("/health")
def health():
    return {"status": "ok", "locations": BUNDLE.locations(), "data_through": BUNDLE.daily("dhudial")[-1]["date"].isoformat()}


@app.get("/v1/locations")
def locations():
    return [{"id": l, "data_from": BUNDLE.daily(l)[30]["date"].isoformat(), "data_through": BUNDLE.daily(l)[-1]["date"].isoformat()}
            for l in BUNDLE.locations()]


@app.get("/v1/fields/{loc}/conditions")
def conditions(loc: str, date: str | None = Query(None, description="YYYY-MM-DD; defaults to the latest day with data"),
               sowing_date: str | None = None):
    s = _snap(_loc(loc), date, sowing_date)
    r = lambda v, n=3: None if v is None else round(v, n)
    return {"location": loc, "date": _iso(s["date"]), "data_as_of": _iso(s["data_as_of"]), "forecast_source": s["forecast_source"],
            "soil_moisture_m3m3": r(s["soil_moisture"]), "soil_moisture_in_3d": r(s["soil_moisture_in_3d"]),
            "soil_moisture_in_7d": r(s["soil_moisture_in_7d"]), "soil_moisture_method": "model estimate from rainfall and evaporation; not a direct measurement",
            "rain_last_7d_mm": r(s["rain_7d"], 1), "forecast_rain_48h_mm": r(s["fc_rain_48h"], 1), "forecast_rain_7d_mm": r(s["fc_rain_7d"], 1),
            "forecast_tmin_3d_c": r(s["fc_tmin_3d"], 1), "forecast_tmax_5d_c": r(s["fc_tmax_5d"], 1),
            "gdd": round(s["gdd"]), "stage": s["stage"], "spray_ok_today": s["spray_ok_today"],
            "forecast": [{k: _iso(v) for k, v in d.items() if k != "tmean"} for d in s["forecast"]]}


@app.get("/v1/fields/{loc}/advisories")
def get_advisories(loc: str, date: str | None = None, sowing_date: str | None = None):
    s = _snap(_loc(loc), date, sowing_date)
    out = advisories(BUNDLE, s)
    for a in out:
        a["act_by"] = _iso(a["act_by"])
    return {"location": loc, "date": _iso(s["date"]), "data_as_of": _iso(s["data_as_of"]), "stage": s["stage"], "advisories": out}


@app.get("/v1/fields/{loc}/roadmap")
def roadmap(loc: str, sowing_date: str, date: str | None = None):
    _loc(loc)
    days = BUNDLE.daily(loc)
    sow, today = _date(sowing_date), _date(date, days[-1]["date"])
    rows = {x["date"]: x for x in days}
    reached = core.stage_dates(rows, sow, min(today, days[-1]["date"]))
    gdd = core.gdd_since(rows, sow, min(today, days[-1]["date"])) if sow <= today else 0.0
    nodes = [{"id": "sowing", "name_en": "Sowing", "name_ur": "بوائی", "gdd_target": 0, "reached_on": sow.isoformat() if sow <= today else None,
              "state": "done" if sow <= today else "locked"}]
    current_set = False
    for name, target in core.STAGES:
        on = reached[name]
        state = "done" if on else ("current" if not current_set and sow <= today else "locked")
        current_set = current_set or state == "current"
        nodes.append({"id": name, "name_en": STAGE_LABELS[name][0], "name_ur": STAGE_LABELS[name][1], "gdd_target": target,
                      "reached_on": on.isoformat() if on else None, "state": state})
    return {"location": loc, "sowing_date": sow.isoformat(), "date": today.isoformat(), "gdd": round(gdd), "nodes": nodes,
            "threshold_status": "DRAFT"}


@app.get("/v1/fields/{loc}/history")
def history(loc: str):
    cell = "bhaun" if loc == "kallar_kahar" else _loc(loc)
    rows = [r for r in BUNDLE.seasons if r["location"] == cell and r["complete"] == "True"]
    num = lambda v, f=float: f(float(v)) if v not in ("", None) else None
    return [{"season": r["season"], "rain_nov_apr_mm": num(r["rain_nov_apr_mm"]), "rain_oct_nov_mm": num(r["rain_oct_nov_mm"]),
             "frost_days": num(r["frost_days"], int), "grain_fill_heat_days": num(r["grain_fill_heat_days"], int),
             "maturity_date_if_sown_nov_10": r["maturity_date"],
             "district_rainfed_yield_maund_per_acre": num(r["rainfed_yield_maund_per_acre"])} for r in rows]


@app.get("/v1/fields/{loc}/heat-risk")
def heat_risk(loc: str, sowing_date: str | None = None):
    _loc(loc)
    table = [{"sowing_mmdd": r["sowing_mmdd"], "mean_heat_days": float(r["mean_heat_days"]),
              "p_5plus_heat_days": float(r["p_5plus_heat_days"]), "p_10plus_heat_days": float(r["p_10plus_heat_days"]),
              "worst_heat_days": int(r["worst_heat_days"]), "seasons": int(r["seasons"])} for r in BUNDLE.heat]
    out = {"basis": "21 seasons, 2005-06 to 2025-26; heat day = maximum temperature of 32 C or more between flowering and maturity",
           "table": table}
    if sowing_date:
        r = heat_risk_for(BUNDLE, _date(sowing_date))
        out["for_sowing_date"] = {"nearest_table_row": r["sowing_mmdd"], "p_5plus_heat_days": float(r["p_5plus_heat_days"]),
                                  "mean_heat_days": float(r["mean_heat_days"])}
    return out


@app.get("/v1/fields/{loc}/barkat")
def barkat(loc: str, area_acre: float = 4.0, loss_fraction: float = Query(..., gt=0, lt=1)):
    """Maunds protected = expected rainfed yield x area x loss fraction. Loss fractions are placeholders."""
    _loc(loc)
    y = float(BUNDLE.meta["expected_rainfed_yield_maund_per_acre"])
    return {"expected_yield_maund_per_acre": y, "yield_basis": "mean Chakwal district rainfed wheat yield, 5 seasons, Punjab Crop Reporting Service",
            "yield_range_maund_per_acre": [float(BUNDLE.meta["yield_min_maund_per_acre"]), float(BUNDLE.meta["yield_max_maund_per_acre"])],
            "area_acre": area_acre, "loss_fraction": loss_fraction, "loss_fraction_status": "placeholder",
            "maunds_protected": round(y * area_acre * loss_fraction, 1)}


@app.get("/v1/yields")
def yields():
    return BUNDLE.yields


@app.get("/v1/fields/{loc}/ndvi")
def ndvi(loc: str):
    _loc(loc)
    path = os.path.join(BUNDLE.folder, f"ndvi_{loc}.csv")
    if not os.path.exists(path):
        return {"available": False, "reason": "MODIS NDVI from AppEEARS has not been added yet. Run pipeline/add_appeears.py when the files arrive."}
    import csv
    with open(path, encoding="utf-8") as f:
        return {"available": True, "source": "MODIS MOD13Q1, 250 m, 16-day", "series": list(csv.DictReader(f))}
