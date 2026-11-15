"""Add AppEEARS point results (MODIS NDVI, SMAP) to the bundle when they arrive.

Usage:  python pipeline/add_appeears.py path/to/folder_with_appeears_csvs

UNTESTED against real AppEEARS files: it was written before any arrived. It looks for
columns by key word, so small naming differences should not matter. If it reports
"no NDVI column found", send the CSV header line and it can be adjusted.

Writes android/assets/kisan/ndvi_<location>.csv (date, ndvi) for the backend /ndvi endpoint,
and data/processed/smap_<location>.csv (date, soil_moisture) for retraining the soil model.
"""
import glob, os, sys
import pandas as pd
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "android", "assets", "kisan")
ID_TO_LOCATION = {"field_01": "dhudial", "dhudial": "dhudial", "chakwal_city": "chakwal_city", "bhaun": "bhaun", "kallar_kahar": "bhaun"}


def find(cols, *words):
    for c in cols:
        if all(w.lower() in c.lower() for w in words):
            return c
    return None


def main(folder):
    done = []
    for path in sorted(glob.glob(os.path.join(folder, "**", "*.csv"), recursive=True)):
        try:
            df = pd.read_csv(path)
        except Exception as e:
            print("skip", path, e); continue
        idc, datec = find(df.columns, "id"), find(df.columns, "date")
        if not idc or not datec:
            continue
        ndvi = find(df.columns, "NDVI")
        qa = find(df.columns, "pixel_reliability")
        sm = find(df.columns, "soil_moisture")
        for pid, g in df.groupby(idc):
            loc = ID_TO_LOCATION.get(str(pid).strip().lower())
            if not loc:
                continue
            g = g.assign(date=pd.to_datetime(g[datec])).sort_values("date")
            if ndvi and "MOD13" in os.path.basename(path).upper():
                if qa and pd.api.types.is_numeric_dtype(g[qa]):
                    g = g[g[qa].isin([0, 1])]        # keep good and marginal pixels only
                v = g[ndvi].astype(float)
                v = v.where((v >= -0.2) & (v <= 1.0))  # AppEEARS already applies the scale factor
                out = pd.DataFrame({"date": g.date.dt.strftime("%Y-%m-%d"), "ndvi": v.round(4)}).dropna()
                out.to_csv(os.path.join(A, f"ndvi_{loc}.csv"), index=False)
                done.append(f"NDVI {loc}: {len(out)} rows")
            if sm and "SPL" in os.path.basename(path).upper():
                v = g[sm].astype(float)
                v = v.where((v > 0) & (v < 0.7))
                out = pd.DataFrame({"date": g.date.dt.strftime("%Y-%m-%d"), "soil_moisture": v}).dropna().groupby("date").mean().round(4)
                out.to_csv(os.path.join(ROOT, "data", "processed", f"smap_{loc}.csv"))
                done.append(f"SMAP {loc}: {len(out)} rows")
    print("\n".join(done) if done else "nothing recognised: no NDVI or soil moisture column found")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
