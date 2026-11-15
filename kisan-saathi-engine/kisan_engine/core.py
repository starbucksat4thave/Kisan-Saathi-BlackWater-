"""Crop-stage arithmetic and soil-moisture features shared by training, rules and backend."""
from __future__ import annotations
import datetime as dt
import math

STAGES = [("emergence", 150), ("tillering", 450), ("jointing", 750),
          ("heading", 1200), ("anthesis", 1350), ("maturity", 2000)]  # DRAFT, from thresholds.yaml
GDD_BASE_C = 0.0
SM_FEATURES = ["rain1", "rain3", "rain7", "rain14", "rain30", "api", "et7", "et14",
               "tmean7", "dry_days", "doy_sin", "doy_cos"]
API_DECAY = 0.90


def stage_name(gdd: float) -> str:
    """Stage the crop is in for a given accumulated GDD since sowing."""
    name = "pre_emergence"
    for s, g in STAGES:
        if gdd >= g:
            name = s
    return name


def gdd_since(rows, sowing: dt.date, upto: dt.date) -> float:
    """rows: dict date -> row dict with 'tmean'. Sum of max(tmean - base, 0)."""
    total, d = 0.0, sowing
    while d <= upto:
        r = rows.get(d)
        if r is not None:
            total += max(r["tmean"] - GDD_BASE_C, 0.0)
        d += dt.timedelta(days=1)
    return total


def stage_dates(rows, sowing: dt.date, last: dt.date):
    """Date each stage threshold is first reached, or None if not reached by `last`."""
    out, total, d, i = {}, 0.0, sowing, 0
    while d <= last and i < len(STAGES):
        r = rows.get(d)
        if r is not None:
            total += max(r["tmean"] - GDD_BASE_C, 0.0)
        while i < len(STAGES) and total >= STAGES[i][1]:
            out[STAGES[i][0]] = d
            i += 1
        d += dt.timedelta(days=1)
    for s, _ in STAGES:
        out.setdefault(s, None)
    return out


def sm_features(history):
    """Soil-moisture model inputs from a list of the most recent days, oldest first.

    Each item needs 'date', 'rain', 'et0', 'tmean'. At least 30 days are required.
    """
    h = history[-30:]
    rain = [x["rain"] for x in h]
    et = [x["et0"] for x in h]
    api = 0.0
    for v in rain:
        api = api * API_DECAY + v
    dry = 0
    for v in reversed(rain):
        if v >= 5.0:
            break
        dry += 1
    doy = h[-1]["date"].timetuple().tm_yday
    return {
        "rain1": rain[-1], "rain3": sum(rain[-3:]), "rain7": sum(rain[-7:]), "rain14": sum(rain[-14:]),
        "rain30": sum(rain), "api": api, "et7": sum(et[-7:]), "et14": sum(et[-14:]),
        "tmean7": sum(x["tmean"] for x in h[-7:]) / 7.0, "dry_days": float(dry),
        "doy_sin": math.sin(2 * math.pi * doy / 365.25), "doy_cos": math.cos(2 * math.pi * doy / 365.25),
    }
