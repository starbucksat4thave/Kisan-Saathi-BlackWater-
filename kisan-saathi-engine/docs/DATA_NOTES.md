# What the data showed

Checked on 15 Nov 2026 against the downloaded files. Numbers are for the Dhudial point unless stated.

## 1. NASA POWER is not consistent across 2020 for this location

| Variable | 2005-2019 | 2020 onward | Compared with |
|---|---|---|---|
| Annual rainfall | 506 to 1,072 mm | 1,155 to 2,949 mm | IMERG and ERA5 stay at 530 to 1,090 mm throughout |
| Max temperature minus ERA5 | +2.6 C | +0.3 C | ERA5 via Open-Meteo |
| Surface wetness, annual mean | 0.38 to 0.55 | 0.53 to 0.73 | follows the rainfall jump |

POWER rainfall roughly doubles from 2020 while both other sources do not, so the jump is in
POWER, not in the weather. The cause was not investigated.

**Decision:** rainfall comes from NASA GPM IMERG (to 30 Sep 2025) and ERA5 after that.
Temperature comes from ERA5, with POWER only for the last few days. POWER still supplies
humidity, wind and sunlight, and its pre-2020 surface wetness is the training target for
the soil model.

## 2. Soil moisture model

Estimates POWER surface wetness from the last 30 days of rainfall and evaporation, then
multiplies by soil porosity (0.468, from SoilGrids bulk density at Dhudial) to get m3/m3.

| | Model | Day-of-year average |
|---|---|---|
| Mean absolute error, wetness (0 to 1 scale) | 0.060 | 0.077 |
| R squared | 0.59 | 0.37 |

Trained on 2005-2015, tested on 2016-2019, three grid cells. It beats the seasonal average
but is modest: an error of 0.06 in wetness is about 0.03 m3/m3, against a sowing window
only 0.15 wide. It has not been checked against SMAP or a field probe. When the AppEEARS
SMAP file arrives, retrain against that.

## 3. Heat risk by sowing date

For each sowing date, the 21 seasons from 2005-06 to 2025-26 were replayed and days of 32 C
or more between flowering and maturity were counted.

| Sown | Mean hot days | Seasons with 5 or more |
|---|---|---|
| 25 Oct | 0.3 | 0% |
| 9 Nov | 1.6 | 14% |
| 19 Nov | 3.1 | 29% |
| 29 Nov | 4.5 | 38% |
| 9 Dec | 6.6 | 48% |

This is a climatology table, not machine learning. A classifier that tried to predict a hot
grain-fill from mid-February conditions scored 38% in leave-one-out testing, worse than a
coin flip, so it was dropped.

## 4. Wheat yield

Chakwal district rainfed wheat, from three Punjab Crop Reporting Service reports:

| Season | kg/ha | maund/acre |
|---|---|---|
| 2019-20 | 1,718 | 17.4 |
| 2020-21 | 1,664 | 16.8 |
| 2021-22 | 1,602 | 16.2 |
| 2023-24 | 2,292 | 23.2 |
| 2024-25 | 1,585 | 16.0 |

Mean 17.9 maund/acre. The app's `crops.yaml` assumes 32, which is close to the district's
irrigated yield, not its rainfed one. The engine uses 17.9 for Barkat.

Five seasons are too few to train a yield model. Yields for Attock, Rawalpindi and Jhelum
are in `data/raw/district_wheat_yield.csv`, but there is no weather download for those
districts yet; the updated `pipeline/fetch_kisan_data.py` adds them.

## 5. Things that look wrong in the thresholds

- A crop sown on 10 Nov reaches the 2,000 GDD "maturity" threshold in late March. Wheat in
  Chakwal is normally harvested from mid April. The maturity GDD is probably too low.
- November 2026 is dry: estimated soil moisture on 12 Nov is 16%, below the 20% sowing
  threshold, and the 7-day forecast shows no rain. The app's demo card says 24% and "sow now".
