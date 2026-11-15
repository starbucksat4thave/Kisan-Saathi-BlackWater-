"""Turn the raw downloads into one clean daily table per location.

Run from the project root:  python pipeline/build_dataset.py

Source choices (see docs/DATA_NOTES.md for the evidence):
  temperature : ERA5 via Open-Meteo, with NASA POWER for the most recent days.
                NASA POWER maximum temperature runs about 2.6 C above ERA5 before 2020
                and 0.3 C above it after, so it is not consistent across seasons.
  humidity, wind, sunlight, surface wetness : NASA POWER
  rainfall : NASA GPM IMERG (area average) where available, Open-Meteo/ERA5 otherwise.
             NASA POWER rainfall is NOT used: from 2020 onward it is roughly double
             both IMERG and ERA5 for Chakwal.
"""
import json, os, sys
import numpy as np
import pandas as pd

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW, OUT = os.path.join(ROOT, "data", "raw"), os.path.join(ROOT, "data", "processed")
LOCATIONS = ["dhudial", "chakwal_city", "bhaun", "kallar_kahar"]
PARTICLE_DENSITY = 2.65  # g/cm3, standard mineral soil value


def load_imerg():
    path = os.path.join(RAW, "imerg_chakwal_final.csv")
    if not os.path.exists(path):
        return None
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            a = [s.strip() for s in line.split(",")]
            if len(a) == 2 and len(a[0]) == 10 and a[0][4] == "-":
                v = float(a[1])
                rows.append((a[0], np.nan if v < -9000 else v))
    s = pd.Series(dict(rows), name="rain_imerg")
    s.index = pd.to_datetime(s.index)
    return s


def soil_summary():
    """Porosity and texture from SoilGrids. Uses the first location with real values."""
    for loc in LOCATIONS:
        path = os.path.join(RAW, f"soilgrids_{loc}.json")
        if not os.path.exists(path):
            continue
        layers = {L["name"]: L for L in json.load(open(path))["properties"]["layers"]}
        top = {k: [d["values"]["mean"] for d in v["depths"][:2]] for k, v in layers.items()}
        if any(x is None for vals in top.values() for x in vals):
            continue
        mean = {k: float(np.mean(v)) for k, v in top.items()}
        bd = mean["bdod"] / 100.0  # cg/cm3 -> g/cm3
        return {"source_location": loc, "depth": "0-15cm", "bulk_density_g_cm3": round(bd, 3),
                "porosity": round(1 - bd / PARTICLE_DENSITY, 3),
                "clay_pct": round(mean["clay"] / 10, 1), "sand_pct": round(mean["sand"] / 10, 1),
                "silt_pct": round(mean["silt"] / 10, 1), "ph": round(mean["phh2o"] / 10, 1),
                "organic_carbon_g_kg": round(mean["soc"] / 10, 1)}
    return {"source_location": None, "porosity": 0.45, "note": "default; no SoilGrids values"}


def build(loc, imerg, porosity):
    p = pd.read_csv(os.path.join(RAW, f"power_{loc}.csv"), parse_dates=["date"]).set_index("date")
    o = pd.read_csv(os.path.join(RAW, f"openmeteo_history_{loc}.csv"), parse_dates=["date"]).set_index("date")
    d = pd.DataFrame(index=p.index)
    # ERA5 (Open-Meteo) temperature is consistent across 2005-2026; POWER shifts by about 2 C at 2020.
    om_tmax, om_tmin = o.temperature_2m_max.reindex(p.index), o.temperature_2m_min.reindex(p.index)
    d["tmax"], d["tmin"] = om_tmax.fillna(p.T2M_MAX), om_tmin.fillna(p.T2M_MIN)
    d["tmean"] = (d.tmax + d.tmin) / 2.0
    d["temp_source"] = np.where(om_tmax.notna(), "ERA5", "POWER")
    d["rh"], d["wind_kmh"], d["solar"] = p.RH2M, p.WS2M * 3.6, p.ALLSKY_SFC_SW_DWN
    d["wetness"] = p.GWETTOP
    d["rain_om"] = o.precipitation_sum.reindex(d.index)
    d["et0"] = o.et0_fao_evapotranspiration.reindex(d.index)
    d["rain_imerg"] = imerg.reindex(d.index) if imerg is not None else np.nan
    d["rain"] = d.rain_imerg.fillna(d.rain_om)
    d["rain_source"] = np.where(d.rain_imerg.notna(), "IMERG", np.where(d.rain_om.notna(), "OpenMeteo", "none"))
    d = d[d.tmax.notna() | d.rain.notna()]
    last_obs = d.tmax.last_valid_index()
    d = d.loc[:last_obs].copy()
    d["rain"] = d.rain.fillna(0.0)
    for c in ["tmax", "tmin", "tmean", "rh", "wind_kmh", "solar", "wetness", "et0"]:
        d[c] = d[c].interpolate(limit=5).ffill()
    d["soil_moisture_est"] = (d.wetness * porosity).round(3)  # m3/m3, estimate from wetness x porosity
    return d.round(3)


def main():
    os.makedirs(OUT, exist_ok=True)
    imerg, soil = load_imerg(), soil_summary()
    json.dump(soil, open(os.path.join(OUT, "soil.json"), "w"), indent=1)
    for loc in LOCATIONS:
        d = build(loc, imerg, soil["porosity"])
        d.to_csv(os.path.join(OUT, f"daily_{loc}.csv"), index_label="date")
        f = pd.read_csv(os.path.join(RAW, f"openmeteo_forecast_{loc}.csv"))
        f = f.rename(columns={"temperature_2m_max": "tmax", "temperature_2m_min": "tmin", "precipitation_sum": "rain",
                              "precipitation_probability_max": "rain_prob", "wind_speed_10m_max": "wind_kmh",
                              "et0_fao_evapotranspiration": "et0"})
        f.to_csv(os.path.join(OUT, f"forecast_{loc}.csv"), index=False)
        print(f"{loc}: {len(d)} days {d.index.min().date()} to {d.index.max().date()}, forecast {len(f)} days")
    pd.read_csv(os.path.join(RAW, "district_wheat_yield.csv")).to_csv(os.path.join(OUT, "district_wheat_yield.csv"), index=False)
    print("soil:", soil)


if __name__ == "__main__":
    sys.exit(main())
