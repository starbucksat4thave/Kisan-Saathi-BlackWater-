"""Advisory rule engine. Mirrors android/kotlin/KisanEngine.kt line for line in its logic.

Every threshold below is DRAFT (copied from data/thresholds.yaml) until verified by
local agronomists. The engine never prescribes a pesticide; pest rules only ask the
farmer to scout.
"""
from __future__ import annotations
import csv, datetime as dt, os
from . import core
from .treemodel import TreeModel

# --- thresholds (DRAFT, from thresholds.yaml) ---
SM_MIN, SM_MAX = 0.20, 0.35           # sowing moisture window, m3/m3
SOW_HEAVY_RAIN_48H = 20.0             # mm
UREA_RAIN_48H_LIMIT = 10.0            # mm
UREA_LIGHT_RAIN_MIN, UREA_LIGHT_RAIN_MAX = 4.0, 20.0
UREA_SM_MIN = 0.22
FROST_TMIN = 2.0
APHID_T = (15.0, 28.0); APHID_RH = 60.0
RUST_T = (10.0, 22.0); RUST_RH = 75.0
HEAT_TMAX = 32.0
SPRAY_WIND_MAX, SPRAY_RAINPROB_MAX, SPRAY_TMAX = 15.0, 40.0, 30.0
HARVEST_DRY_MM = 1.0
HARVEST_GDD_LIMIT = 2600.0           # stop harvest reminders about 3 weeks after maturity
SOW_WINDOW = ((10, 20), (12, 15))     # 20 Oct to 15 Dec


def _d(s):
    return dt.date(int(s[0:4]), int(s[5:7]), int(s[8:10]))


def _f(s):
    return float(s) if s not in ("", None) else None


class Bundle:
    """Loads the plain-text bundle (same files the Android app ships in assets/kisan)."""

    def __init__(self, folder):
        self.folder = folder
        self.meta = {}
        with open(os.path.join(folder, "meta.txt"), encoding="utf-8") as f:
            for line in f:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    self.meta[k] = v
        self.porosity = float(self.meta["porosity"])
        self.model = TreeModel.load(os.path.join(folder, "soil_wetness.txt"))
        self.heat = self._csv("heat_risk_by_sowing.csv")
        self.seasons = self._csv("seasons.csv")
        self.yields = self._csv("district_wheat_yield.csv")
        self._daily, self._fc = {}, {}

    def _csv(self, name):
        with open(os.path.join(self.folder, name), encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def locations(self):
        return sorted(n[6:-4] for n in os.listdir(self.folder) if n.startswith("daily_"))

    def daily(self, loc):
        if loc not in self._daily:
            days = []
            for r in self._csv(f"daily_{loc}.csv"):
                tmax, tmin = float(r["tmax"]), float(r["tmin"])
                days.append({"date": _d(r["date"]), "tmax": tmax, "tmin": tmin, "tmean": (tmax + tmin) / 2.0,
                             "rh": float(r["rh"]), "wind_kmh": float(r["wind_kmh"]),
                             "rain": float(r["rain"]), "et0": float(r["et0"])})
            self._daily[loc] = days
        return self._daily[loc]

    def forecast(self, loc):
        if loc not in self._fc:
            days = []
            for r in self._csv(f"forecast_{loc}.csv"):
                tmax, tmin = float(r["tmax"]), float(r["tmin"])
                days.append({"date": _d(r["date"]), "tmax": tmax, "tmin": tmin, "tmean": (tmax + tmin) / 2.0,
                             "rain": float(r["rain"]), "rain_prob": _f(r.get("rain_prob")),
                             "wind_kmh": float(r["wind_kmh"]), "et0": float(r["et0"])})
            self._fc[loc] = days
        return self._fc[loc]


def heat_risk_for(bundle, sowing: dt.date):
    """Row of the heat-risk table nearest to the sowing date (table runs 25 Oct to 14 Dec)."""
    key = (sowing.month, sowing.day)
    best, best_gap = None, 10 ** 9
    for r in bundle.heat:
        m, d = int(r["sowing_mmdd"][:2]), int(r["sowing_mmdd"][3:])
        gap = abs((dt.date(2001 if m >= 10 else 2002, m, d) - dt.date(2001 if key[0] >= 10 else 2002, key[0], key[1])).days)
        if gap < best_gap:
            best, best_gap = r, gap
    return best


def snapshot(bundle: Bundle, loc: str, today: dt.date, sowing: dt.date | None):
    """Everything the rules need for one field on one day."""
    days = bundle.daily(loc)
    last_obs = days[-1]["date"]
    as_of = min(today, last_obs)
    idx = (as_of - days[0]["date"]).days
    if idx < 30:
        raise ValueError(f"no data before {days[0]['date'] + dt.timedelta(days=30)}")
    hist = days[: idx + 1]
    if today > last_obs + dt.timedelta(days=10):
        raise ValueError(f"no data after {last_obs}; latest usable date is {last_obs + dt.timedelta(days=10)}")
    if as_of < last_obs:
        fc, fc_source = [dict(x, rain_prob=None) for x in days[idx + 1: idx + 8]], "observed"
    else:
        fc, fc_source = [x for x in bundle.forecast(loc) if x["date"] > as_of][:7], "forecast"
    wet = bundle.model.predict(core.sm_features(hist))
    roll = [dict(date=x["date"], rain=x["rain"], et0=x["et0"], tmean=x["tmean"]) for x in hist[-30:]]
    outlook = {}
    for k, x in enumerate(fc, start=1):
        roll.append(dict(date=x["date"], rain=x["rain"], et0=x["et0"], tmean=x["tmean"]))
        if k in (3, 7):
            outlook[k] = bundle.model.predict(core.sm_features(roll)) * bundle.porosity
    rows = {x["date"]: x for x in hist}
    gdd = core.gdd_since(rows, sowing, as_of) if sowing and sowing <= as_of else 0.0
    last7 = hist[-7:]
    return {
        "location": loc, "date": today, "data_as_of": as_of, "forecast_source": fc_source, "forecast": fc,
        "soil_moisture": wet * bundle.porosity, "soil_wetness": wet,
        "soil_moisture_in_3d": outlook.get(3), "soil_moisture_in_7d": outlook.get(7),
        "rain_7d": sum(x["rain"] for x in last7), "rain_2d": sum(x["rain"] for x in hist[-2:]),
        "tmean_7d": sum(x["tmean"] for x in last7) / 7.0, "rh_7d": sum(x["rh"] for x in last7) / 7.0,
        "fc_rain_48h": sum(x["rain"] for x in fc[:2]), "fc_rain_3d": sum(x["rain"] for x in fc[:3]),
        "fc_rain_7d": sum(x["rain"] for x in fc),
        "fc_tmin_3d": min([x["tmin"] for x in fc[:3]], default=None),
        "fc_tmax_5d": max([x["tmax"] for x in fc[:5]], default=None),
        "sowing_date": sowing, "gdd": gdd, "stage": core.stage_name(gdd) if sowing and sowing <= as_of else "not_sown",
        "spray_ok_today": _spray_ok(fc[0]) if fc else False,
    }


def _spray_ok(day) -> bool:
    """Calm, cool and dry enough to spray, if scouting has shown a spray is needed."""
    if day["wind_kmh"] >= SPRAY_WIND_MAX or day["tmax"] >= SPRAY_TMAX:
        return False
    if day.get("rain_prob") is None:
        return day["rain"] < 1.0
    return day["rain_prob"] < SPRAY_RAINPROB_MAX


def _in_sow_window(d: dt.date) -> bool:
    a, b = SOW_WINDOW
    return (d.month, d.day) >= a and (d.month, d.day) <= b


def _adv(aid, severity, act_by, loss, s, title_en, title_ur, body_en, body_ur):
    return {"id": aid, "severity": severity, "act_by": act_by, "loss_factor_id": loss, "threshold_status": "DRAFT",
            "title_en": title_en, "title_ur": title_ur, "body_en": body_en, "body_ur": body_ur,
            "evidence": {"soil_moisture_m3m3": round(s["soil_moisture"], 3), "rain_last_7d_mm": round(s["rain_7d"], 1),
                         "forecast_rain_48h_mm": round(s["fc_rain_48h"], 1), "gdd": round(s["gdd"]),
                         "stage": s["stage"], "forecast_source": s["forecast_source"]}}


def advisories(bundle: Bundle, s: dict):
    """Apply the rules to a snapshot. Returns a list of advisory dicts, most urgent first."""
    out, today = [], s["date"]
    sm, pct = s["soil_moisture"], round(s["soil_moisture"] * 100)
    plus = lambda n: today + dt.timedelta(days=n)

    if s["stage"] == "not_sown" and _in_sow_window(today):
        hr = heat_risk_for(bundle, today)
        late = heat_risk_for(bundle, plus(10))
        risk_en = (f" Sowing now has a {round(float(hr['p_5plus_heat_days'])*100)}% chance of 5 or more hot days at grain fill; "
                   f"10 days later it is {round(float(late['p_5plus_heat_days'])*100)}%.")
        risk_ur = (f" ابھی بوائی پر دانہ بھرتے وقت شدید گرمی کا امکان {round(float(hr['p_5plus_heat_days'])*100)}٪ ہے، "
                   f"دس دن بعد {round(float(late['p_5plus_heat_days'])*100)}٪۔")
        if s["fc_rain_48h"] >= SOW_HEAVY_RAIN_48H:
            out.append(_adv("sow_wait_rain", "watch", plus(3), "wattar_germination_failure", s,
                            "Heavy rain coming: wait before sowing", "تیز بارش متوقع ہے: بوائی روک لیں",
                            f"About {s['fc_rain_48h']:.0f} mm of rain is expected in the next 2 days. Sow after it passes and the soil is workable.",
                            f"اگلے دو دن میں تقریباً {s['fc_rain_48h']:.0f} ملی میٹر بارش متوقع ہے۔ بارش گزرنے اور زمین وتر میں آنے پر بوائی کریں۔"))
        elif SM_MIN <= sm <= SM_MAX:
            out.append(_adv("sow_now", "action", plus(3), "wattar_germination_failure", s,
                            "Soil moisture is right: sow wheat within 3 days", "زمین وتر میں ہے: تین دن کے اندر گندم بو دیں",
                            f"Estimated surface soil moisture is {pct}%, inside the sowing range of 20 to 35%." + risk_en,
                            f"زمین کی اوپری نمی کا اندازہ {pct}٪ ہے جو بوائی کے لیے موزوں حد (20 تا 35٪) میں ہے۔" + risk_ur))
        elif sm < SM_MIN:
            nxt = s["soil_moisture_in_7d"]
            more_en = f" Expected rain in the next 7 days: {s['fc_rain_7d']:.0f} mm." + (f" Moisture in 7 days is estimated at {round(nxt*100)}%." if nxt is not None else "")
            more_ur = f" اگلے سات دن میں متوقع بارش: {s['fc_rain_7d']:.0f} ملی میٹر۔"
            out.append(_adv("sow_wait_dry", "watch", None, "wattar_germination_failure", s,
                            "Soil is too dry to sow: wait for rain", "زمین خشک ہے: بوائی کے لیے بارش کا انتظار کریں",
                            f"Estimated surface soil moisture is {pct}%, below the 20% needed for even germination." + more_en + risk_en,
                            f"زمین کی اوپری نمی کا اندازہ {pct}٪ ہے جو اچھے اگاؤ کے لیے درکار 20٪ سے کم ہے۔" + more_ur + risk_ur))
        else:
            out.append(_adv("sow_wait_wet", "watch", plus(3), "wattar_germination_failure", s,
                            "Soil is too wet to sow: wait a few days", "زمین زیادہ گیلی ہے: چند دن انتظار کریں",
                            f"Estimated surface soil moisture is {pct}%, above the 35% limit. Let the field dry to a workable state.",
                            f"زمین کی اوپری نمی کا اندازہ {pct}٪ ہے جو 35٪ کی حد سے زیادہ ہے۔ زمین کو وتر میں آنے دیں۔"))

    if s["stage"] == "tillering":
        if s["fc_rain_48h"] >= UREA_RAIN_48H_LIMIT:
            out.append(_adv("urea_hold", "watch", plus(3), "urea_washout_prevention", s,
                            "Hold the urea: heavy rain expected", "یوریا ابھی نہ ڈالیں: تیز بارش متوقع ہے",
                            f"About {s['fc_rain_48h']:.0f} mm of rain is expected in the next 2 days and could wash the fertiliser away. Apply after it passes.",
                            f"اگلے دو دن میں تقریباً {s['fc_rain_48h']:.0f} ملی میٹر بارش متوقع ہے جو کھاد بہا سکتی ہے۔ بارش کے بعد ڈالیں۔"))
        elif UREA_LIGHT_RAIN_MIN <= s["rain_2d"] <= UREA_LIGHT_RAIN_MAX or sm > UREA_SM_MIN:
            out.append(_adv("urea_apply", "action", plus(2), "urea_washout_prevention", s,
                            "Good time to top-dress urea", "یوریا ڈالنے کا مناسب وقت ہے",
                            f"The crop is tillering, the soil is moist ({pct}%) and no heavy rain is expected in the next 2 days. Confirm the dose with your extension officer.",
                            f"فصل شاخیں نکال رہی ہے، زمین میں نمی ({pct}٪) موجود ہے اور اگلے دو دن تیز بارش متوقع نہیں۔ مقدار کے لیے زراعت افسر سے تصدیق کریں۔"))
        else:
            out.append(_adv("urea_wait_moisture", "info", None, "urea_washout_prevention", s,
                            "Wait for moisture before urea", "یوریا کے لیے نمی کا انتظار کریں",
                            f"The crop is tillering but the soil is dry ({pct}%). Urea on dry ground is wasted; apply after a light shower.",
                            f"فصل شاخیں نکال رہی ہے مگر زمین خشک ({pct}٪) ہے۔ خشک زمین میں یوریا ضائع ہوتی ہے؛ ہلکی بارش کے بعد ڈالیں۔"))

    sown = s["stage"] not in ("not_sown",)
    if sown and s["stage"] != "maturity" and today.month in (12, 1, 2) and s["fc_tmin_3d"] is not None and s["fc_tmin_3d"] < FROST_TMIN:
        out.append(_adv("frost_alert", "watch", plus(1), None, s,
                        "Frost risk in the next 3 nights", "اگلی تین راتوں میں کہر کا خطرہ",
                        f"Night temperature may fall to {s['fc_tmin_3d']:.0f} C. Check low-lying parts of the field in the morning.",
                        f"رات کا درجہ حرارت {s['fc_tmin_3d']:.0f} ڈگری تک گر سکتا ہے۔ صبح کھیت کے نشیبی حصے دیکھیں۔"))

    if s["stage"] in ("jointing", "heading", "anthesis") and APHID_T[0] <= s["tmean_7d"] <= APHID_T[1] and s["rh_7d"] >= APHID_RH:
        out.append(_adv("aphid_scout", "info", plus(3), "scouting_unnecessary_spray_saved", s,
                        "Weather favours aphids: scout before any spray", "موسم تیلے کے لیے سازگار ہے: اسپرے سے پہلے معائنہ کریں",
                        "Check 20 plants in 4 spots of the field. Do not spray unless the count is above the threshold your extension officer advises.",
                        "کھیت کی چار جگہوں سے بیس پودے دیکھیں۔ جب تک تعداد زراعت افسر کی بتائی حد سے زیادہ نہ ہو، اسپرے نہ کریں۔"))

    if s["stage"] in ("tillering", "jointing", "heading", "anthesis") and RUST_T[0] <= s["tmean_7d"] <= RUST_T[1] and s["rh_7d"] >= RUST_RH:
        out.append(_adv("rust_scout", "info", plus(3), "scouting_unnecessary_spray_saved", s,
                        "Weather favours yellow rust: inspect lower leaves", "موسم زرد کنگی کے لیے سازگار ہے: نچلے پتے دیکھیں",
                        "Look for yellow striped pustules on lower leaves on a damp morning. Report what you find before deciding on any spray.",
                        "نم صبح میں نچلے پتوں پر زرد دھاری دار دانے تلاش کریں۔ اسپرے کا فیصلہ کرنے سے پہلے زراعت افسر کو بتائیں۔"))

    if s["stage"] in ("heading", "anthesis") and s["fc_tmax_5d"] is not None and s["fc_tmax_5d"] >= HEAT_TMAX:
        out.append(_adv("heat_alert", "watch", plus(2), "sowing_delay_heat_avoidance", s,
                        "Heat stress expected during grain fill", "دانہ بھرتے وقت گرمی کا دباؤ متوقع ہے",
                        f"Temperature may reach {s['fc_tmax_5d']:.0f} C in the next 5 days while grain is filling. Note this season for next year's sowing date.",
                        f"اگلے پانچ دن میں درجہ حرارت {s['fc_tmax_5d']:.0f} ڈگری تک جا سکتا ہے جب دانہ بھر رہا ہے۔ اگلے سال بوائی کی تاریخ کے لیے یاد رکھیں۔"))

    if s["stage"] == "maturity" and s["gdd"] <= HARVEST_GDD_LIMIT:
        if s["fc_rain_3d"] < HARVEST_DRY_MM:
            out.append(_adv("harvest_now", "action", plus(3), "harvest_rain_wheat", s,
                            "Crop is ready and 3 dry days are ahead: harvest", "فصل تیار ہے اور تین دن خشک ہیں: کٹائی کریں",
                            "The crop has reached maturity and no rain is expected for 3 days.",
                            "فصل پک چکی ہے اور تین دن بارش متوقع نہیں۔"))
        else:
            out.append(_adv("harvest_rain_risk", "action", plus(1), "harvest_rain_wheat", s,
                            "Crop is ready but rain is coming: protect the harvest", "فصل تیار ہے مگر بارش آ رہی ہے: فصل محفوظ کریں",
                            f"About {s['fc_rain_3d']:.0f} mm of rain is expected in the next 3 days. Harvest what you can today and cover cut wheat.",
                            f"اگلے تین دن میں تقریباً {s['fc_rain_3d']:.0f} ملی میٹر بارش متوقع ہے۔ آج جتنی ہو سکے کٹائی کریں اور کٹی گندم ڈھانپ دیں۔"))

    order = {"action": 0, "watch": 1, "info": 2}
    out.sort(key=lambda a: order[a["severity"]])
    return out
