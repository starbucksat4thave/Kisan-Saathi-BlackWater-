"""Write the compact bundle used by both the backend and the Android app.

Run from project root:  python pipeline/export_bundle.py
Output: android/assets/kisan/  (copy this folder into app/src/main/assets/kisan)
"""
import json, os, shutil
import pandas as pd
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P, A = os.path.join(ROOT, "data", "processed"), os.path.join(ROOT, "android", "assets", "kisan")
START = "2019-09-01"  # seven seasons of daily data keeps the bundle small
COLS = ["tmax", "tmin", "rh", "wind_kmh", "rain", "et0"]


def main():
    os.makedirs(A, exist_ok=True)
    for loc in ["dhudial", "chakwal_city", "bhaun"]:
        d = pd.read_csv(os.path.join(P, f"daily_{loc}.csv"), parse_dates=["date"]).set_index("date").loc[START:]
        d[COLS].round(2).to_csv(os.path.join(A, f"daily_{loc}.csv"), index_label="date")
        f = pd.read_csv(os.path.join(P, f"forecast_{loc}.csv"))
        f[["date", "tmax", "tmin", "rain", "rain_prob", "wind_kmh", "et0"]].to_csv(os.path.join(A, f"forecast_{loc}.csv"), index=False)
    for name in ["seasons.csv", "heat_risk_by_sowing.csv", "district_wheat_yield.csv"]:
        shutil.copy(os.path.join(P, name), os.path.join(A, name))
    shutil.copy(os.path.join(ROOT, "models", "soil_wetness.txt"), os.path.join(A, "soil_wetness.txt"))
    soil = json.load(open(os.path.join(P, "soil.json")))
    m = json.load(open(os.path.join(ROOT, "models", "metrics.json")))
    with open(os.path.join(A, "meta.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"porosity={soil['porosity']}\nclay_pct={soil.get('clay_pct','')}\nsand_pct={soil.get('sand_pct','')}\n"
                 f"silt_pct={soil.get('silt_pct','')}\nsoil_ph={soil.get('ph','')}\n"
                 f"expected_rainfed_yield_maund_per_acre={m['yield_observed']['mean_maund_per_acre']}\n"
                 f"yield_min_maund_per_acre={m['yield_observed']['min_maund_per_acre']}\n"
                 f"yield_max_maund_per_acre={m['yield_observed']['max_maund_per_acre']}\n"
                 f"soil_model_mae_wetness={m['soil_wetness_model']['mae_model']}\n")
    size = sum(os.path.getsize(os.path.join(A, f)) for f in os.listdir(A))
    print("bundle files:", sorted(os.listdir(A)), f"total {size/1024:.0f} KB")


if __name__ == "__main__":
    main()
