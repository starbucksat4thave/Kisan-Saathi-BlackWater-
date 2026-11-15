"""Train and evaluate the small models. Run from project root: python models/train.py

1. Soil wetness estimator: rainfall + evaporation history -> NASA POWER surface wetness.
   Trained only on 2005-2019, because POWER wetness (like POWER rainfall) shifts upward
   from 2020. The model therefore gives a consistent estimate across all years, and
   with forecast rain it gives a 3- and 7-day outlook.
2. Heat risk by sowing date: a climatology table, 21 seasons.
3. A seasonal heat classifier is attempted and reported, and kept only if it beats climatology.
"""
import datetime as dt, json, os, sys
import numpy as np, pandas as pd
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import mean_absolute_error, r2_score
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from kisan_engine import core
from kisan_engine.treemodel import TreeModel, export_sklearn_gbr
P = os.path.join(ROOT, "data", "processed")
CELLS = ["dhudial", "chakwal_city", "bhaun"]  # kallar_kahar shares bhaun's POWER grid cell
HEAT_C = 32.0


def load(loc):
    return pd.read_csv(os.path.join(P, f"daily_{loc}.csv"), parse_dates=["date"]).set_index("date")


def feature_frame(d):
    recs = [{"date": i.date(), "rain": r.rain, "et0": r.et0, "tmean": r.tmean} for i, r in d.iterrows()]
    rows = []
    for k in range(29, len(recs)):
        f = core.sm_features(recs[k - 29:k + 1])
        f["date"] = recs[k]["date"]
        rows.append(f)
    X = pd.DataFrame(rows).set_index("date")
    X.index = pd.to_datetime(X.index)
    X["wetness"] = d.wetness.reindex(X.index)
    return X.dropna()


def train_soil():
    frames = {c: feature_frame(load(c)) for c in CELLS}
    al = pd.concat(frames.values())
    tr, te = al[al.index.year <= 2015], al[(al.index.year >= 2016) & (al.index.year <= 2019)]
    m = GradientBoostingRegressor(n_estimators=120, max_depth=3, learning_rate=0.08, subsample=0.8, random_state=0)
    m.fit(tr[core.SM_FEATURES], tr.wetness)
    pred = m.predict(te[core.SM_FEATURES])
    clim = tr.groupby(tr.index.dayofyear).wetness.mean()
    base = clim.reindex(te.index.dayofyear).fillna(tr.wetness.mean()).values
    path = os.path.join(ROOT, "models", "soil_wetness.txt")
    export_sklearn_gbr(m, core.SM_FEATURES, path)
    tm = TreeModel.load(path)
    chk = te.iloc[::50]
    mine = np.array([tm.predict(r[core.SM_FEATURES].to_dict()) for _, r in chk.iterrows()])
    parity = float(np.abs(mine - m.predict(chk[core.SM_FEATURES])).max())
    rabi = te.index.month.isin([10, 11, 12, 1, 2, 3, 4])
    res = {"train_rows": len(tr), "test_rows": len(te), "test_years": "2016-2019",
           "mae_model": round(mean_absolute_error(te.wetness, pred), 4), "mae_day_of_year_average": round(mean_absolute_error(te.wetness, base), 4),
           "r2_model": round(r2_score(te.wetness, pred), 3), "r2_day_of_year_average": round(r2_score(te.wetness, base), 3),
           "mae_model_oct_to_apr": round(mean_absolute_error(te.wetness[rabi], pred[rabi]), 4),
           "export_parity_max_abs_diff": parity,
           "feature_importance": dict(sorted(zip(core.SM_FEATURES, m.feature_importances_.round(3).tolist()), key=lambda kv: -kv[1]))}
    return res


def seasons_table(d, loc):
    rows = {i.date(): {"tmean": r.tmean} for i, r in d.iterrows()}
    last = d.index.max().date()
    out = []
    for y in range(2005, last.year + 1):
        sow = dt.date(y, 11, 10)
        if sow > last:
            break
        st = core.stage_dates(rows, sow, min(last, dt.date(y + 1, 6, 30)))
        s = d.loc[f"{y}-11-01":f"{y+1}-04-30"]
        win = d.loc[f"{y}-10-20":f"{y}-12-10"]
        rec = {"season": f"{y}-{str(y+1)[2:]}", "location": loc, "rain_nov_apr_mm": round(float(s.rain.sum()), 0),
               "rain_oct_nov_mm": round(float(d.loc[f'{y}-10-01':f'{y}-11-30'].rain.sum()), 0),
               "frost_days": int((d.loc[f"{y}-12-01":f"{y+1}-02-28"].tmin < 2.0).sum()),
               "anthesis_date": str(st["anthesis"]) if st["anthesis"] else "", "maturity_date": str(st["maturity"]) if st["maturity"] else "",
               "complete": bool(st["maturity"])}
        if st["anthesis"] and st["maturity"]:
            gf = d.loc[str(st["anthesis"]):str(st["maturity"])]
            rec["grain_fill_heat_days"] = int((gf.tmax >= HEAT_C).sum())
            rec["grain_fill_tmax_mean"] = round(float(gf.tmax.mean()), 1)
        out.append(rec)
    return pd.DataFrame(out)


def heat_by_sowing(d):
    rows = {i.date(): {"tmean": r.tmean} for i, r in d.iterrows()}
    last = d.index.max().date()
    out = []
    for off in range(0, 51, 5):  # Oct 25 .. Dec 14
        counts = []
        for y in range(2005, last.year):
            sow = dt.date(y, 10, 25) + dt.timedelta(days=off)
            st = core.stage_dates(rows, sow, dt.date(y + 1, 6, 30))
            if not (st["anthesis"] and st["maturity"]) or st["maturity"] > last:
                continue
            counts.append(int((d.loc[str(st["anthesis"]):str(st["maturity"])].tmax >= HEAT_C).sum()))
        sow0 = dt.date(2001, 10, 25) + dt.timedelta(days=off)
        c = np.array(counts)
        out.append({"sowing_mmdd": sow0.strftime("%m-%d"), "seasons": len(c), "mean_heat_days": round(float(c.mean()), 1),
                    "p_5plus_heat_days": round(float((c >= 5).mean()), 2), "p_10plus_heat_days": round(float((c >= 10).mean()), 2),
                    "worst_heat_days": int(c.max())})
    return pd.DataFrame(out)


def seasonal_heat_classifier(d, seas):
    """Can mid-February conditions predict a hot grain-fill? Leave-one-out test vs climatology."""
    s = seas[seas.complete & seas.grain_fill_heat_days.notna()].copy()
    feats = []
    for _, r in s.iterrows():
        y = int(r.season[:4])
        w = d.loc[f"{y}-12-01":f"{y+1}-02-15"]
        feats.append([w.tmean.mean(), w.rain.sum(), d.loc[f"{y+1}-02-01":f"{y+1}-02-15"].tmax.mean()])
    X = np.array(feats); X = (X - X.mean(0)) / X.std(0)
    yv = (s.grain_fill_heat_days > s.grain_fill_heat_days.median()).astype(int).values
    if len(set(yv.tolist())) < 2:
        return {"seasons": int(len(yv)), "note": "no variation in target; classifier not applicable"}
    hit = 0
    for i in range(len(yv)):
        m = np.arange(len(yv)) != i
        hit += int(LogisticRegression(C=1.0).fit(X[m], yv[m]).predict(X[i:i + 1])[0] == yv[i])
    return {"seasons": int(len(yv)), "leave_one_out_accuracy": round(hit / len(yv), 2), "coin_flip_baseline": 0.5}


def main():
    res = {"soil_wetness_model": train_soil()}
    d = load("dhudial")
    seas = pd.concat([seasons_table(load(c), c) for c in CELLS])
    yl = pd.read_csv(os.path.join(P, "district_wheat_yield.csv"))
    yl = yl[yl.district == "Chakwal"][["season", "rainfed_yield_kg_per_ha"]]
    seas = seas.merge(yl, on="season", how="left")
    seas["rainfed_yield_maund_per_acre"] = (seas.rainfed_yield_kg_per_ha / 2.4711 / 40).round(1)
    seas.to_csv(os.path.join(P, "seasons.csv"), index=False)
    hb = heat_by_sowing(d); hb.to_csv(os.path.join(P, "heat_risk_by_sowing.csv"), index=False)
    sd = seas[seas.location == "dhudial"]
    res["seasonal_heat_classifier"] = seasonal_heat_classifier(d, sd)
    yy = sd.dropna(subset=["rainfed_yield_kg_per_ha"])
    res["yield_observed"] = {"seasons": yy.season.tolist(), "rainfed_kg_per_ha": yy.rainfed_yield_kg_per_ha.tolist(),
                             "mean_maund_per_acre": round(float(yy.rainfed_yield_maund_per_acre.mean()), 1),
                             "min_maund_per_acre": float(yy.rainfed_yield_maund_per_acre.min()), "max_maund_per_acre": float(yy.rainfed_yield_maund_per_acre.max()),
                             "corr_with_nov_apr_rain": round(float(yy.rainfed_yield_kg_per_ha.corr(yy.rain_nov_apr_mm)), 2),
                             "corr_with_grain_fill_heat_days": round(float(yy.rainfed_yield_kg_per_ha.corr(yy.grain_fill_heat_days)), 2),
                             "note": "5 seasons only: correlations are descriptive, not a model"}
    pw = pd.read_csv(os.path.join(ROOT, "data", "raw", "power_dhudial.csv"), parse_dates=["date"]).set_index("date")
    om = pd.read_csv(os.path.join(ROOT, "data", "raw", "openmeteo_history_dhudial.csv"), parse_dates=["date"]).set_index("date")
    bias = (pw.T2M_MAX - om.temperature_2m_max).dropna()
    res["power_minus_era5_tmax_c"] = {"2005-2019": round(float(bias[:"2019"].mean()), 2), "2020-2026": round(float(bias["2020":].mean()), 2)}
    json.dump(res, open(os.path.join(ROOT, "models", "metrics.json"), "w"), indent=1)
    print(json.dumps(res, indent=1)); print(hb.to_string(index=False)); print(sd.tail(8).to_string(index=False))


if __name__ == "__main__":
    main()
