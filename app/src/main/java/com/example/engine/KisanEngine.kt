package com.example.engine

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Kisan Saathi on-device engine. Pure Kotlin with no Android imports, so it also runs on a
 * plain JVM for testing. It mirrors kisan_engine/rules.py in the backend and reads the same
 * text files, so phone and server give the same answers.
 *
 * Every agronomy threshold here is DRAFT (copied from config/thresholds.yaml).
 */

/** Dates are stored as days since 1970-01-01 so the engine works on Android API 24 without java.time. */
object Dates {
    fun of(y0: Int, m: Int, d: Int): Int {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Parses "YYYY-MM-DD". */
    fun parse(s: String): Int = of(s.substring(0, 4).toInt(), s.substring(5, 7).toInt(), s.substring(8, 10).toInt())

    /** Returns [year, month, day]. */
    fun ymd(date: Int): IntArray {
        val z = date + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return intArrayOf(if (m <= 2) y + 1 else y, m, d)
    }

    fun format(date: Int): String {
        val a = ymd(date)
        return String.format(Locale.US, "%04d-%02d-%02d", a[0], a[1], a[2])
    }

    fun dayOfYear(date: Int): Int = date - of(ymd(date)[0], 1, 1) + 1
}

class Day(
    val date: Int, val tmax: Double, val tmin: Double, val rh: Double,
    val windKmh: Double, val rain: Double, val et0: Double, val rainProb: Double?
) {
    val tmean: Double get() = (tmax + tmin) / 2.0
}

/** Gradient-boosted trees in the plain-text format written by kisan_engine/treemodel.py. */
class TreeModel private constructor(
    val features: List<String>, private val init: Double, private val lr: Double,
    private val feat: List<IntArray>, private val thr: List<DoubleArray>,
    private val left: List<IntArray>, private val right: List<IntArray>, private val value: List<DoubleArray>
) {
    fun predict(row: DoubleArray): Double {
        var out = init
        for (t in feat.indices) {
            var i = 0
            while (feat[t][i] >= 0) {
                i = if (row[feat[t][i]] <= thr[t][i]) left[t][i] else right[t][i]
            }
            out += lr * value[t][i]
        }
        return out
    }

    companion object {
        fun parse(text: String): TreeModel {
            var features = emptyList<String>()
            var init = 0.0
            var lr = 1.0
            val feat = ArrayList<IntArray>(); val thr = ArrayList<DoubleArray>()
            val left = ArrayList<IntArray>(); val right = ArrayList<IntArray>(); val value = ArrayList<DoubleArray>()
            for (line in text.lineSequence()) {
                val p = line.trim().split(' ').filter { it.isNotEmpty() }
                if (p.isEmpty()) continue
                val rest = p.subList(1, p.size)
                when (p[0]) {
                    "features" -> features = rest[0].split(',')
                    "init" -> init = rest[0].toDouble()
                    "lr" -> lr = rest[0].toDouble()
                    "feat" -> feat.add(IntArray(rest.size) { rest[it].toInt() })
                    "left" -> left.add(IntArray(rest.size) { rest[it].toInt() })
                    "right" -> right.add(IntArray(rest.size) { rest[it].toInt() })
                    "thr" -> thr.add(DoubleArray(rest.size) { rest[it].toDouble() })
                    "val" -> value.add(DoubleArray(rest.size) { rest[it].toDouble() })
                }
            }
            return TreeModel(features, init, lr, feat, thr, left, right, value)
        }
    }
}

class HeatRisk(val month: Int, val day: Int, val meanHeatDays: Double, val p5Plus: Double, val p10Plus: Double, val worst: Int)

class Snapshot(
    val date: Int, val dataAsOf: Int, val forecastSource: String, val forecast: List<Day>,
    val soilMoisture: Double, val soilMoistureIn3d: Double?, val soilMoistureIn7d: Double?,
    val rain7d: Double, val rain2d: Double, val tmean7d: Double, val rh7d: Double,
    val fcRain48h: Double, val fcRain3d: Double, val fcRain7d: Double,
    val fcTmin3d: Double?, val fcTmax5d: Double?,
    val sowingDate: Int?, val gdd: Double, val stage: String, val sprayOkToday: Boolean
)

class Advisory(
    val id: String, val severity: String, val actBy: Int?, val lossFactorId: String?,
    val titleEn: String, val titleUr: String, val bodyEn: String, val bodyUr: String,
    val evidence: Map<String, String>
) {
    val thresholdStatus: String get() = "DRAFT"
}

class KisanEngine(dailyCsv: String, forecastCsv: String, modelText: String, heatCsv: String, metaText: String) {

    val days: List<Day> = parseDays(dailyCsv, hasRainProb = false)
    val forecast: List<Day> = parseDays(forecastCsv, hasRainProb = true)
    val model: TreeModel = TreeModel.parse(modelText)
    val heat: List<HeatRisk> = parseHeat(heatCsv)
    val meta: Map<String, String> = metaText.lineSequence().filter { it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    val porosity: Double = meta.getValue("porosity").toDouble()
    val expectedYieldMaundPerAcre: Double = meta.getValue("expected_rainfed_yield_maund_per_acre").toDouble()
    val lastObserved: Int get() = days.last().date

    // ---- crop stages ----

    fun stageName(gdd: Double): String {
        var name = "pre_emergence"
        for (i in STAGE_NAMES.indices) if (gdd >= STAGE_GDD[i]) name = STAGE_NAMES[i]
        return name
    }

    private fun dayAt(date: Int): Day? {
        val i = date - days[0].date
        return if (i >= 0 && i < days.size) days[i] else null
    }

    fun gddSince(sowing: Int, upto: Int): Double {
        var total = 0.0
        for (d in sowing..upto) dayAt(d)?.let { total += max(it.tmean - GDD_BASE_C, 0.0) }
        return total
    }

    /** Date each stage is first reached, or null if not reached by [last]. Keys follow [STAGE_NAMES]. */
    fun stageDates(sowing: Int, last: Int): Map<String, Int?> {
        val out = LinkedHashMap<String, Int?>()
        var total = 0.0
        var i = 0
        var d = sowing
        while (d <= last && i < STAGE_NAMES.size) {
            dayAt(d)?.let { total += max(it.tmean - GDD_BASE_C, 0.0) }
            while (i < STAGE_NAMES.size && total >= STAGE_GDD[i]) { out[STAGE_NAMES[i]] = d; i++ }
            d++
        }
        for (s in STAGE_NAMES) if (!out.containsKey(s)) out[s] = null
        return out
    }

    // ---- soil moisture model ----

    private fun smFeatures(h: List<Day>): DoubleArray {
        val w = h.subList(h.size - 30, h.size)
        var api = 0.0
        for (x in w) api = api * API_DECAY + x.rain
        var dry = 0
        for (k in w.indices.reversed()) { if (w[k].rain >= 5.0) break; dry++ }
        fun rainSum(n: Int) = w.subList(w.size - n, w.size).sumOf { it.rain }
        fun etSum(n: Int) = w.subList(w.size - n, w.size).sumOf { it.et0 }
        val doy = Dates.dayOfYear(w.last().date)
        val f = mapOf(
            "rain1" to w.last().rain, "rain3" to rainSum(3), "rain7" to rainSum(7), "rain14" to rainSum(14),
            "rain30" to rainSum(30), "api" to api, "et7" to etSum(7), "et14" to etSum(14),
            "tmean7" to w.subList(w.size - 7, w.size).sumOf { it.tmean } / 7.0, "dry_days" to dry.toDouble(),
            "doy_sin" to sin(2 * PI * doy / 365.25), "doy_cos" to cos(2 * PI * doy / 365.25)
        )
        return DoubleArray(model.features.size) { f.getValue(model.features[it]) }
    }

    // ---- snapshot ----

    /**
     * Conditions for the field on [today]. For past dates the "forecast" is the weather that was
     * actually observed over the following 7 days. Throws IllegalArgumentException if no data covers the date.
     */
    fun snapshot(today: Int, sowing: Int?): Snapshot {
        val asOf = min(today, lastObserved)
        val idx = asOf - days[0].date
        require(idx >= 30) { "no data before ${Dates.format(days[0].date + 30)}" }
        require(today <= lastObserved + 10) { "no data after ${Dates.format(lastObserved)}" }
        val hist = days.subList(0, idx + 1)
        val fc: List<Day>
        val source: String
        if (asOf < lastObserved) {
            fc = days.subList(idx + 1, min(idx + 8, days.size)); source = "observed"
        } else {
            fc = forecast.filter { it.date > asOf }.take(7); source = "forecast"
        }
        val wet = model.predict(smFeatures(hist))
        val roll = ArrayList(hist.subList(hist.size - 30, hist.size))
        var sm3: Double? = null
        var sm7: Double? = null
        for ((k, x) in fc.withIndex()) {
            roll.add(x)
            if (k + 1 == 3) sm3 = model.predict(smFeatures(roll)) * porosity
            if (k + 1 == 7) sm7 = model.predict(smFeatures(roll)) * porosity
        }
        val sown = sowing != null && sowing <= asOf
        val gdd = if (sown) gddSince(sowing!!, asOf) else 0.0
        val last7 = hist.subList(hist.size - 7, hist.size)
        return Snapshot(
            date = today, dataAsOf = asOf, forecastSource = source, forecast = fc,
            soilMoisture = wet * porosity, soilMoistureIn3d = sm3, soilMoistureIn7d = sm7,
            rain7d = last7.sumOf { it.rain }, rain2d = hist.subList(hist.size - 2, hist.size).sumOf { it.rain },
            tmean7d = last7.sumOf { it.tmean } / 7.0, rh7d = last7.sumOf { it.rh } / 7.0,
            fcRain48h = fc.take(2).sumOf { it.rain }, fcRain3d = fc.take(3).sumOf { it.rain }, fcRain7d = fc.sumOf { it.rain },
            fcTmin3d = fc.take(3).minOfOrNull { it.tmin }, fcTmax5d = fc.take(5).maxOfOrNull { it.tmax },
            sowingDate = sowing, gdd = gdd, stage = if (sown) stageName(gdd) else "not_sown",
            sprayOkToday = fc.isNotEmpty() && sprayOk(fc[0])
        )
    }

    private fun sprayOk(d: Day): Boolean {
        if (d.windKmh >= SPRAY_WIND_MAX || d.tmax >= SPRAY_TMAX) return false
        return if (d.rainProb == null) d.rain < 1.0 else d.rainProb < SPRAY_RAINPROB_MAX
    }

    // ---- heat risk table ----

    fun heatRiskFor(sowing: Int): HeatRisk {
        val a = Dates.ymd(sowing)
        val key = Dates.of(if (a[1] >= 10) 2001 else 2002, a[1], a[2])
        return heat.minByOrNull { abs(Dates.of(if (it.month >= 10) 2001 else 2002, it.month, it.day) - key) }!!
    }

    // ---- rules ----

    fun advisories(s: Snapshot): List<Advisory> {
        val out = ArrayList<Advisory>()
        val today = s.date
        val sm = s.soilMoisture
        val pct = (sm * 100).roundToInt()
        fun n0(v: Double) = String.format(Locale.US, "%.0f", v)
        fun adv(id: String, sev: String, actBy: Int?, loss: String?, te: String, tu: String, be: String, bu: String) =
            Advisory(id, sev, actBy, loss, te, tu, be, bu, mapOf(
                "soil_moisture_m3m3" to String.format(Locale.US, "%.3f", sm),
                "rain_last_7d_mm" to String.format(Locale.US, "%.1f", s.rain7d),
                "forecast_rain_48h_mm" to String.format(Locale.US, "%.1f", s.fcRain48h),
                "gdd" to n0(s.gdd), "stage" to s.stage, "forecast_source" to s.forecastSource))

        if (s.stage == "not_sown" && inSowWindow(today)) {
            val now = (heatRiskFor(today).p5Plus * 100).roundToInt()
            val later = (heatRiskFor(today + 10).p5Plus * 100).roundToInt()
            val riskEn = " Sowing now has a $now% chance of 5 or more hot days at grain fill; 10 days later it is $later%."
            val riskUr = " ابھی بوائی پر دانہ بھرتے وقت شدید گرمی کا امکان $now٪ ہے، دس دن بعد $later٪۔"
            if (s.fcRain48h >= SOW_HEAVY_RAIN_48H) {
                out.add(adv("sow_wait_rain", "watch", today + 3, "wattar_germination_failure",
                    "Heavy rain coming: wait before sowing", "تیز بارش متوقع ہے: بوائی روک لیں",
                    "About ${n0(s.fcRain48h)} mm of rain is expected in the next 2 days. Sow after it passes and the soil is workable.",
                    "اگلے دو دن میں تقریباً ${n0(s.fcRain48h)} ملی میٹر بارش متوقع ہے۔ بارش گزرنے اور زمین وتر میں آنے پر بوائی کریں۔"))
            } else if (sm >= SM_MIN && sm <= SM_MAX) {
                out.add(adv("sow_now", "action", today + 3, "wattar_germination_failure",
                    "Soil moisture is right: sow wheat within 3 days", "زمین وتر میں ہے: تین دن کے اندر گندم بو دیں",
                    "Estimated surface soil moisture is $pct%, inside the sowing range of 20 to 35%.$riskEn",
                    "زمین کی اوپری نمی کا اندازہ $pct٪ ہے جو بوائی کے لیے موزوں حد (20 تا 35٪) میں ہے۔$riskUr"))
            } else if (sm < SM_MIN) {
                val nxt = s.soilMoistureIn7d
                val moreEn = " Expected rain in the next 7 days: ${n0(s.fcRain7d)} mm." +
                    (if (nxt != null) " Moisture in 7 days is estimated at ${(nxt * 100).roundToInt()}%." else "")
                val moreUr = " اگلے سات دن میں متوقع بارش: ${n0(s.fcRain7d)} ملی میٹر۔"
                out.add(adv("sow_wait_dry", "watch", null, "wattar_germination_failure",
                    "Soil is too dry to sow: wait for rain", "زمین خشک ہے: بوائی کے لیے بارش کا انتظار کریں",
                    "Estimated surface soil moisture is $pct%, below the 20% needed for even germination.$moreEn$riskEn",
                    "زمین کی اوپری نمی کا اندازہ $pct٪ ہے جو اچھے اگاؤ کے لیے درکار 20٪ سے کم ہے۔$moreUr$riskUr"))
            } else {
                out.add(adv("sow_wait_wet", "watch", today + 3, "wattar_germination_failure",
                    "Soil is too wet to sow: wait a few days", "زمین زیادہ گیلی ہے: چند دن انتظار کریں",
                    "Estimated surface soil moisture is $pct%, above the 35% limit. Let the field dry to a workable state.",
                    "زمین کی اوپری نمی کا اندازہ $pct٪ ہے جو 35٪ کی حد سے زیادہ ہے۔ زمین کو وتر میں آنے دیں۔"))
            }
        }

        if (s.stage == "tillering") {
            if (s.fcRain48h >= UREA_RAIN_48H_LIMIT) {
                out.add(adv("urea_hold", "watch", today + 3, "urea_washout_prevention",
                    "Hold the urea: heavy rain expected", "یوریا ابھی نہ ڈالیں: تیز بارش متوقع ہے",
                    "About ${n0(s.fcRain48h)} mm of rain is expected in the next 2 days and could wash the fertiliser away. Apply after it passes.",
                    "اگلے دو دن میں تقریباً ${n0(s.fcRain48h)} ملی میٹر بارش متوقع ہے جو کھاد بہا سکتی ہے۔ بارش کے بعد ڈالیں۔"))
            } else if ((s.rain2d >= UREA_LIGHT_RAIN_MIN && s.rain2d <= UREA_LIGHT_RAIN_MAX) || sm > UREA_SM_MIN) {
                out.add(adv("urea_apply", "action", today + 2, "urea_washout_prevention",
                    "Good time to top-dress urea", "یوریا ڈالنے کا مناسب وقت ہے",
                    "The crop is tillering, the soil is moist ($pct%) and no heavy rain is expected in the next 2 days. Confirm the dose with your extension officer.",
                    "فصل شاخیں نکال رہی ہے، زمین میں نمی ($pct٪) موجود ہے اور اگلے دو دن تیز بارش متوقع نہیں۔ مقدار کے لیے زراعت افسر سے تصدیق کریں۔"))
            } else {
                out.add(adv("urea_wait_moisture", "info", null, "urea_washout_prevention",
                    "Wait for moisture before urea", "یوریا کے لیے نمی کا انتظار کریں",
                    "The crop is tillering but the soil is dry ($pct%). Urea on dry ground is wasted; apply after a light shower.",
                    "فصل شاخیں نکال رہی ہے مگر زمین خشک ($pct٪) ہے۔ خشک زمین میں یوریا ضائع ہوتی ہے؛ ہلکی بارش کے بعد ڈالیں۔"))
            }
        }

        val month = Dates.ymd(today)[1]
        val sown = s.stage != "not_sown"
        val tmin3 = s.fcTmin3d
        if (sown && s.stage != "maturity" && (month == 12 || month == 1 || month == 2) && tmin3 != null && tmin3 < FROST_TMIN) {
            out.add(adv("frost_alert", "watch", today + 1, null,
                "Frost risk in the next 3 nights", "اگلی تین راتوں میں کہر کا خطرہ",
                "Night temperature may fall to ${n0(tmin3)} C. Check low-lying parts of the field in the morning.",
                "رات کا درجہ حرارت ${n0(tmin3)} ڈگری تک گر سکتا ہے۔ صبح کھیت کے نشیبی حصے دیکھیں۔"))
        }

        if ((s.stage == "jointing" || s.stage == "heading" || s.stage == "anthesis") &&
            s.tmean7d >= APHID_T_MIN && s.tmean7d <= APHID_T_MAX && s.rh7d >= APHID_RH) {
            out.add(adv("aphid_scout", "info", today + 3, "scouting_unnecessary_spray_saved",
                "Weather favours aphids: scout before any spray", "موسم تیلے کے لیے سازگار ہے: اسپرے سے پہلے معائنہ کریں",
                "Check 20 plants in 4 spots of the field. Do not spray unless the count is above the threshold your extension officer advises.",
                "کھیت کی چار جگہوں سے بیس پودے دیکھیں۔ جب تک تعداد زراعت افسر کی بتائی حد سے زیادہ نہ ہو، اسپرے نہ کریں۔"))
        }

        if ((s.stage == "tillering" || s.stage == "jointing" || s.stage == "heading" || s.stage == "anthesis") &&
            s.tmean7d >= RUST_T_MIN && s.tmean7d <= RUST_T_MAX && s.rh7d >= RUST_RH) {
            out.add(adv("rust_scout", "info", today + 3, "scouting_unnecessary_spray_saved",
                "Weather favours yellow rust: inspect lower leaves", "موسم زرد کنگی کے لیے سازگار ہے: نچلے پتے دیکھیں",
                "Look for yellow striped pustules on lower leaves on a damp morning. Report what you find before deciding on any spray.",
                "نم صبح میں نچلے پتوں پر زرد دھاری دار دانے تلاش کریں۔ اسپرے کا فیصلہ کرنے سے پہلے زراعت افسر کو بتائیں۔"))
        }

        val tmax5 = s.fcTmax5d
        if ((s.stage == "heading" || s.stage == "anthesis") && tmax5 != null && tmax5 >= HEAT_TMAX) {
            out.add(adv("heat_alert", "watch", today + 2, "sowing_delay_heat_avoidance",
                "Heat stress expected during grain fill", "دانہ بھرتے وقت گرمی کا دباؤ متوقع ہے",
                "Temperature may reach ${n0(tmax5)} C in the next 5 days while grain is filling. Note this season for next year's sowing date.",
                "اگلے پانچ دن میں درجہ حرارت ${n0(tmax5)} ڈگری تک جا سکتا ہے جب دانہ بھر رہا ہے۔ اگلے سال بوائی کی تاریخ کے لیے یاد رکھیں۔"))
        }

        if (s.stage == "maturity" && s.gdd <= HARVEST_GDD_LIMIT) {
            if (s.fcRain3d < HARVEST_DRY_MM) {
                out.add(adv("harvest_now", "action", today + 3, "harvest_rain_wheat",
                    "Crop is ready and 3 dry days are ahead: harvest", "فصل تیار ہے اور تین دن خشک ہیں: کٹائی کریں",
                    "The crop has reached maturity and no rain is expected for 3 days.",
                    "فصل پک چکی ہے اور تین دن بارش متوقع نہیں۔"))
            } else {
                out.add(adv("harvest_rain_risk", "action", today + 1, "harvest_rain_wheat",
                    "Crop is ready but rain is coming: protect the harvest", "فصل تیار ہے مگر بارش آ رہی ہے: فصل محفوظ کریں",
                    "About ${n0(s.fcRain3d)} mm of rain is expected in the next 3 days. Harvest what you can today and cover cut wheat.",
                    "اگلے تین دن میں تقریباً ${n0(s.fcRain3d)} ملی میٹر بارش متوقع ہے۔ آج جتنی ہو سکے کٹائی کریں اور کٹی گندم ڈھانپ دیں۔"))
            }
        }

        val order = mapOf("action" to 0, "watch" to 1, "info" to 2)
        return out.sortedBy { order.getValue(it.severity) }
    }

    /** Barkat: maunds protected = expected rainfed yield x area x loss fraction (loss fractions are placeholders). */
    fun barkatMaunds(areaAcre: Double, lossFraction: Double): Double = expectedYieldMaundPerAcre * areaAcre * lossFraction

    private fun inSowWindow(date: Int): Boolean {
        val a = Dates.ymd(date)
        val v = a[1] * 100 + a[2]
        return v >= 1020 && v <= 1215
    }

    private fun parseDays(csv: String, hasRainProb: Boolean): List<Day> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val head = lines[0].split(',')
        fun col(name: String) = head.indexOf(name)
        val iDate = col("date"); val iTmax = col("tmax"); val iTmin = col("tmin"); val iRh = col("rh")
        val iWind = col("wind_kmh"); val iRain = col("rain"); val iEt = col("et0"); val iProb = col("rain_prob")
        return lines.drop(1).map { line ->
            val c = line.split(',')
            Day(
                date = Dates.parse(c[iDate]), tmax = c[iTmax].toDouble(), tmin = c[iTmin].toDouble(),
                rh = if (iRh >= 0) c[iRh].toDouble() else 0.0, windKmh = c[iWind].toDouble(),
                rain = c[iRain].toDouble(), et0 = c[iEt].toDouble(),
                rainProb = if (hasRainProb && iProb >= 0 && c[iProb].isNotEmpty()) c[iProb].toDouble() else null
            )
        }
    }

    private fun parseHeat(csv: String): List<HeatRisk> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val head = lines[0].split(',')
        return lines.drop(1).map { line ->
            val c = line.split(',')
            val mmdd = c[head.indexOf("sowing_mmdd")]
            HeatRisk(
                mmdd.substring(0, 2).toInt(), mmdd.substring(3, 5).toInt(),
                c[head.indexOf("mean_heat_days")].toDouble(), c[head.indexOf("p_5plus_heat_days")].toDouble(),
                c[head.indexOf("p_10plus_heat_days")].toDouble(), c[head.indexOf("worst_heat_days")].toInt()
            )
        }
    }

    companion object {
        val STAGE_NAMES = listOf("emergence", "tillering", "jointing", "heading", "anthesis", "maturity")
        val STAGE_GDD = doubleArrayOf(150.0, 450.0, 750.0, 1200.0, 1350.0, 2000.0)
        const val GDD_BASE_C = 0.0
        const val API_DECAY = 0.90
        const val SM_MIN = 0.20
        const val SM_MAX = 0.35
        const val SOW_HEAVY_RAIN_48H = 20.0
        const val UREA_RAIN_48H_LIMIT = 10.0
        const val UREA_LIGHT_RAIN_MIN = 4.0
        const val UREA_LIGHT_RAIN_MAX = 20.0
        const val UREA_SM_MIN = 0.22
        const val FROST_TMIN = 2.0
        const val APHID_T_MIN = 15.0
        const val APHID_T_MAX = 28.0
        const val APHID_RH = 60.0
        const val RUST_T_MIN = 10.0
        const val RUST_T_MAX = 22.0
        const val RUST_RH = 75.0
        const val HEAT_TMAX = 32.0
        const val SPRAY_WIND_MAX = 15.0
        const val SPRAY_RAINPROB_MAX = 40.0
        const val SPRAY_TMAX = 30.0
        const val HARVEST_DRY_MM = 1.0
        const val HARVEST_GDD_LIMIT = 2600.0
    }
}
