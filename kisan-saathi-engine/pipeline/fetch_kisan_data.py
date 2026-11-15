"""
Kisan Saathi - Step 1 data download (no login needed).

Downloads, for each location below:
  - NASA POWER daily weather and soil wetness, 2005 to today
  - Open-Meteo daily weather history (ERA5-based), 2005 to last week
  - Open-Meteo 7-day forecast
  - SoilGrids soil texture, organic carbon and pH

Run:   python fetch_kisan_data.py
Output: a folder "step1_output" and a file "kisan_step1.zip" next to this script.
Uses only the Python standard library, so nothing needs to be installed.
"""
import csv, datetime, json, os, sys, time, urllib.parse, urllib.request, zipfile

# name: (latitude, longitude). Edit or add locations here.
LOCATIONS = {
    "chakwal_city": (32.93, 72.86),
    "bhaun": (32.86, 72.77),
    "kallar_kahar": (32.78, 72.70),
    "dhudial": (33.00, 72.97),
    # District points, added so a wheat yield model can pair each district's
    # reported yield with its own weather. Approximate district centres.
    "district_attock": (33.60, 72.40),
    "district_rawalpindi": (33.45, 73.20),
    "district_jhelum": (32.90, 73.50),
    "district_talagang": (32.93, 72.42),
}
START_YEAR = 2005
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "step1_output")
TODAY = datetime.date.today()

POWER_PARAMS = ["T2M_MAX", "T2M_MIN", "T2M", "T2MDEW", "RH2M", "WS2M", "PRECTOTCORR",
                "ALLSKY_SFC_SW_DWN", "GWETTOP", "GWETROOT", "GWETPROF"]
OM_DAILY = ["temperature_2m_max", "temperature_2m_min", "precipitation_sum",
            "et0_fao_evapotranspiration", "wind_speed_10m_max", "shortwave_radiation_sum"]
OM_FORECAST = ["temperature_2m_max", "temperature_2m_min", "precipitation_sum",
               "precipitation_probability_max", "wind_speed_10m_max", "et0_fao_evapotranspiration"]
SOIL_PROPS = ["clay", "sand", "silt", "soc", "phh2o", "bdod", "cec"]
SOIL_DEPTHS = ["0-5cm", "5-15cm", "15-30cm", "30-60cm"]


def get_json(url, tries=4, timeout=120):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "kisan-saathi-fetch/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # network errors, timeouts, bad JSON
            last = e
            time.sleep(5 * (i + 1))
    raise RuntimeError(f"failed after {tries} tries: {last}")


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)
    return len(rows)


def fetch_power(name, lat, lon):
    q = urllib.parse.urlencode({
        "parameters": ",".join(POWER_PARAMS), "community": "AG",
        "latitude": lat, "longitude": lon,
        "start": f"{START_YEAR}0101", "end": TODAY.strftime("%Y%m%d"), "format": "JSON"})
    data = get_json("https://power.larc.nasa.gov/api/temporal/daily/point?" + q)
    p = data["properties"]["parameter"]
    dates = sorted(p[POWER_PARAMS[0]].keys())
    rows = []
    for d in dates:
        vals = [p[k].get(d, "") for k in POWER_PARAMS]
        vals = ["" if v in (-999, -999.0) else v for v in vals]  # -999 means "no data yet"
        rows.append([f"{d[:4]}-{d[4:6]}-{d[6:]}"] + vals)
    return write_csv(os.path.join(OUT, f"power_{name}.csv"), ["date"] + POWER_PARAMS, rows)


def fetch_openmeteo_history(name, lat, lon):
    end = TODAY - datetime.timedelta(days=7)
    q = urllib.parse.urlencode({
        "latitude": lat, "longitude": lon, "start_date": f"{START_YEAR}-01-01",
        "end_date": end.isoformat(), "daily": ",".join(OM_DAILY), "timezone": "Asia/Karachi"})
    d = get_json("https://archive-api.open-meteo.com/v1/archive?" + q)["daily"]
    rows = [[d["time"][i]] + [d[k][i] for k in OM_DAILY] for i in range(len(d["time"]))]
    return write_csv(os.path.join(OUT, f"openmeteo_history_{name}.csv"), ["date"] + OM_DAILY, rows)


def fetch_openmeteo_forecast(name, lat, lon):
    q = urllib.parse.urlencode({
        "latitude": lat, "longitude": lon, "daily": ",".join(OM_FORECAST),
        "forecast_days": 7, "timezone": "Asia/Karachi"})
    d = get_json("https://api.open-meteo.com/v1/forecast?" + q)["daily"]
    rows = [[d["time"][i]] + [d[k][i] for k in OM_FORECAST] for i in range(len(d["time"]))]
    return write_csv(os.path.join(OUT, f"openmeteo_forecast_{name}.csv"), ["date"] + OM_FORECAST, rows)


def fetch_soil(name, lat, lon):
    q = [("lon", lon), ("lat", lat), ("value", "mean")]
    q += [("property", p) for p in SOIL_PROPS] + [("depth", d) for d in SOIL_DEPTHS]
    data = get_json("https://rest.isric.org/soilgrids/v2.0/properties/query?" + urllib.parse.urlencode(q))
    with open(os.path.join(OUT, f"soilgrids_{name}.json"), "w", encoding="utf-8") as f:
        json.dump(data, f)
    return len(data.get("properties", {}).get("layers", []))


def main():
    os.makedirs(OUT, exist_ok=True)
    steps = [("NASA POWER", fetch_power), ("Open-Meteo history", fetch_openmeteo_history),
             ("Open-Meteo forecast", fetch_openmeteo_forecast), ("SoilGrids", fetch_soil)]
    report = []
    for name, (lat, lon) in LOCATIONS.items():
        for label, fn in steps:
            try:
                n = fn(name, lat, lon)
                line = f"OK    {label:20s} {name:14s} {n} rows"
            except Exception as e:
                line = f"FAIL  {label:20s} {name:14s} {e}"
            print(line, flush=True)
            report.append(line)
            time.sleep(1)  # be polite to the free services
    with open(os.path.join(OUT, "download_report.txt"), "w", encoding="utf-8") as f:
        f.write(f"run date: {TODAY.isoformat()}\nlocations: {json.dumps(LOCATIONS)}\n\n" + "\n".join(report) + "\n")
    zpath = os.path.join(HERE, "kisan_step1.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for fn in sorted(os.listdir(OUT)):
            z.write(os.path.join(OUT, fn), fn)
    fails = sum(1 for r in report if r.startswith("FAIL"))
    print(f"\nDone. {len(report) - fails} of {len(report)} downloads succeeded.")
    print(f"Upload this file: {zpath}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
