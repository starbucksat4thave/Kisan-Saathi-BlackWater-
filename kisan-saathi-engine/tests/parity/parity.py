import datetime as dt, os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, ROOT)
from kisan_engine import core
from kisan_engine.rules import Bundle, advisories, snapshot
b = Bundle(sys.argv[1])
f = lambda v: "NA" if v is None else "%.6f" % v
for line in open(sys.argv[2]).read().split():
    d, sw = line.split(",")
    s = snapshot(b, "dhudial", dt.date.fromisoformat(d), dt.date.fromisoformat(sw) if sw else None)
    a = advisories(b, s)
    print("|".join([d, f(s["soil_moisture"]), f(s["soil_moisture_in_3d"]), f(s["soil_moisture_in_7d"]), f(s["gdd"]), s["stage"], s["forecast_source"],
                    f(s["fc_rain_48h"]), f(s["fc_tmin_3d"]), str(s["spray_ok_today"]).lower(),
                    ";".join(x["id"] + ":" + x["severity"] + ":" + (x["act_by"].isoformat() if x["act_by"] else "NA") for x in a)]))
rows = {x["date"]: x for x in b.daily("dhudial")}
st = core.stage_dates(rows, dt.date(2024, 11, 10), dt.date(2025, 6, 30))
print("STAGES|" + ";".join(k + "=" + (v.isoformat() if v else "NA") for k, v in st.items()))
print("TEXT|" + advisories(b, snapshot(b, "dhudial", dt.date(2024, 11, 10), None))[0]["body_en"])
