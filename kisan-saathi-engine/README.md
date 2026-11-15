# Kisan Saathi engine and backend

Real-data advisories for rainfed wheat in Chakwal, built from the files downloaded on 15 Nov 2026.
The same logic runs in two places and gives identical answers:

- **Backend** (`backend/`, Python FastAPI) for a server.
- **On-device engine** (`android/kotlin/`, pure Kotlin) for the app, offline.

Both read one small text bundle (`android/assets/kisan/`, about 400 KB).

## Layout

| Folder | Contents |
|---|---|
| `pipeline/` | `fetch_kisan_data.py` (download), `build_dataset.py` (clean), `export_bundle.py` (write bundle), `add_appeears.py` (add NDVI and SMAP later) |
| `kisan_engine/` | Crop stages, soil-moisture features, tree-model reader, advisory rules |
| `models/` | `train.py`, the trained `soil_wetness.txt`, and `metrics.json` |
| `backend/` | The API |
| `android/` | `assets/kisan/` bundle and Kotlin sources to copy into the app |
| `tests/` | 11 Python tests and a Kotlin-versus-Python comparison |
| `data/` | Raw downloads, processed tables, thresholds |
| `docs/` | `DATA_NOTES.md` (what the data showed), `INTEGRATION.md` (how to wire the app) |

## Run

```
pip install -r requirements.txt
python pipeline/build_dataset.py      # raw -> data/processed
python models/train.py                # trains and writes models/metrics.json
python pipeline/export_bundle.py      # writes android/assets/kisan
python -m pytest tests -q
uvicorn backend.main:app --port 8000  # then open http://localhost:8000/docs
```

## API

| Endpoint | Returns |
|---|---|
| `GET /health` | Status and last day of data |
| `GET /v1/locations` | Locations and their date range |
| `GET /v1/fields/{loc}/conditions?date=&sowing_date=` | Soil moisture now and in 3 and 7 days, rain, crop stage, forecast |
| `GET /v1/fields/{loc}/advisories?date=&sowing_date=` | Advisory cards in English and Urdu with evidence |
| `GET /v1/fields/{loc}/roadmap?sowing_date=&date=` | Stage nodes with the date each was reached |
| `GET /v1/fields/{loc}/history` | Past seasons: rain, frost days, heat days, district yield |
| `GET /v1/fields/{loc}/heat-risk?sowing_date=` | Chance of heat at grain fill by sowing date |
| `GET /v1/fields/{loc}/barkat?area_acre=&loss_fraction=` | Maunds protected |
| `GET /v1/fields/{loc}/ndvi` | NDVI series once AppEEARS data is added |
| `GET /v1/yields` | District wheat yields extracted from the Punjab reports |

`loc` is `dhudial`, `chakwal_city` or `bhaun`. Dates are `YYYY-MM-DD`. Any date from October 2019
to the last day of data works; for past dates the "forecast" is the weather that actually followed.

## Limits to state plainly

- Soil moisture is a model estimate, not a measurement. See `docs/DATA_NOTES.md`.
- Every agronomy threshold is a DRAFT from `data/thresholds.yaml`, and loss fractions are placeholders.
- The engine never names a pesticide or dose; pest rules only ask the farmer to scout.
- There is no login, database or user storage in this backend yet.
