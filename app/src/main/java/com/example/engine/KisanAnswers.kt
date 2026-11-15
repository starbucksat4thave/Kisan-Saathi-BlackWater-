package com.example.engine

import java.util.Locale
import kotlin.math.roundToInt

/** An answer worked out on the phone from the field's own data, with no internet needed. */
class LocalAnswer(val topic: String, val textUr: String, val textEn: String)

/**
 * Answers common farmer questions from today's conditions for the field. Pure Kotlin, no Android imports.
 *
 * It never names a pesticide, a fertiliser dose or a seed rate. Where the question needs a prescription
 * it tells the farmer what the data shows and sends them to the extension officer.
 */
object KisanAnswers {

    private val TOPICS: List<Pair<String, List<String>>> = listOf(
        "urea" to listOf("یوریا", "کھاد", "urea", "fertili", "khad", "khaad", "dap"),
        "sowing" to listOf("بوائی", "بیج", "کاشت", "sow", "seed", "plant", "boai", "bijai", "kasht"),
        "harvest" to listOf("کٹائی", "کاٹ", "گہائی", "harvest", "thresh", "katai", "cut the"),
        "pest" to listOf("تیلہ", "تیلے", "کیڑ", "کنگی", "بیماری", "اسپرے", "سپرے", "aphid", "rust", "pest", "spray", "disease", "insect", "keera"),
        "frost" to listOf("کہر", "کورا", "پالا", "frost", "freez"),
        "heat" to listOf("گرمی", "لو ", "heat", "hot"),
        "moisture" to listOf("وتر", "نمی", "moisture", "wattar", "watar", "soil"),
        "rain" to listOf("بارش", "موسم", "rain", "weather", "forecast", "barish", "mausam"),
        "yield" to listOf("پیداوار", "برکت", "yield", "production", "maund", "paidawar")
    )

    fun topicOf(question: String): String {
        val q = question.lowercase(Locale.US)
        for ((topic, words) in TOPICS) if (words.any { q.contains(it) }) return topic
        return "general"
    }

    private val STAGE_UR = mapOf(
        "not_sown" to "ابھی بوائی نہیں ہوئی", "pre_emergence" to "بیج زمین میں ہے", "emergence" to "اگاؤ",
        "tillering" to "شاخیں نکل رہی ہیں", "jointing" to "گانٹھیں بن رہی ہیں", "heading" to "سٹے نکل رہے ہیں",
        "anthesis" to "پھول اور دانہ بھرائی", "maturity" to "فصل پک چکی ہے"
    )
    private val STAGE_EN = mapOf(
        "not_sown" to "not sown yet", "pre_emergence" to "seed in the ground", "emergence" to "emergence",
        "tillering" to "tillering", "jointing" to "jointing", "heading" to "heading",
        "anthesis" to "flowering and grain fill", "maturity" to "mature"
    )

    fun stageLabel(stage: String, urdu: Boolean): String =
        (if (urdu) STAGE_UR[stage] else STAGE_EN[stage]) ?: stage

    fun answer(engine: KisanEngine, s: Snapshot, question: String): LocalAnswer {
        val topic = topicOf(question)
        val cards = engine.advisories(s)
        fun card(vararg prefixes: String): Advisory? = cards.firstOrNull { c -> prefixes.any { c.id.startsWith(it) } }
        fun n0(v: Double) = String.format(Locale.US, "%.0f", v)
        val pct = (s.soilMoisture * 100).roundToInt()
        val asOf = Dates.format(s.dataAsOf)
        val tailUr = " (کھیت کا ڈیٹا $asOf تک۔)"
        val tailEn = " (Field data up to $asOf.)"
        val stageUr = stageLabel(s.stage, true)
        val stageEn = stageLabel(s.stage, false)
        fun fromCard(c: Advisory) = LocalAnswer(topic, c.titleUr + "۔ " + c.bodyUr + tailUr, c.titleEn + ". " + c.bodyEn + tailEn)

        when (topic) {
            "sowing" -> {
                card("sow")?.let { return fromCard(it) }
                return if (s.stage == "not_sown") LocalAnswer(topic,
                    "بوائی کا مناسب وقت 20 اکتوبر سے 15 دسمبر تک ہے۔ اس وقت زمین کی اوپری نمی کا اندازہ $pct٪ ہے۔$tailUr",
                    "The sowing window runs from 20 October to 15 December. Estimated surface soil moisture is now $pct%.$tailEn")
                else LocalAnswer(topic,
                    "ریکارڈ کے مطابق فصل بوئی جا چکی ہے اور اس وقت مرحلہ یہ ہے: $stageUr۔$tailUr",
                    "The crop is recorded as already sown and is now at this stage: $stageEn.$tailEn")
            }
            "urea" -> {
                card("urea")?.let { return fromCard(it) }
                return LocalAnswer(topic,
                    "یوریا شاخیں نکلتے وقت، نمی والی زمین میں اور تیز بارش سے پہلے نہیں ڈالی جاتی۔ اس وقت فصل کا مرحلہ: $stageUr، زمین کی نمی $pct٪، اگلے دو دن میں متوقع بارش ${n0(s.fcRain48h)} ملی میٹر۔ مقدار کے لیے زراعت افسر سے پوچھیں۔$tailUr",
                    "Urea is top-dressed at tillering, on moist soil, and not ahead of heavy rain. Right now the crop stage is: $stageEn, soil moisture is $pct%, and ${n0(s.fcRain48h)} mm of rain is expected in the next 2 days. Ask your extension officer for the dose.$tailEn")
            }
            "harvest" -> {
                card("harvest")?.let { return fromCard(it) }
                return LocalAnswer(topic,
                    "فصل ابھی کٹائی کے مرحلے پر نہیں پہنچی۔ موجودہ مرحلہ: $stageUr۔ اگلے تین دن میں متوقع بارش ${n0(s.fcRain3d)} ملی میٹر۔$tailUr",
                    "The crop has not reached harvest yet. Current stage: $stageEn. Rain expected in the next 3 days: ${n0(s.fcRain3d)} mm.$tailEn")
            }
            "pest" -> {
                val c = card("aphid", "rust")
                val sprayUr = if (s.sprayOkToday) "آج ہوا اور موسم اسپرے کے لیے موزوں ہیں، اگر معائنے سے ضرورت ثابت ہو۔" else "آج ہوا، گرمی یا بارش کی وجہ سے اسپرے مناسب نہیں۔"
                val sprayEn = if (s.sprayOkToday) "Wind and weather today would allow spraying, if scouting shows it is needed." else "Wind, heat or rain make today unsuitable for spraying."
                if (c != null) return LocalAnswer(topic, c.titleUr + "۔ " + c.bodyUr + " " + sprayUr + tailUr, c.titleEn + ". " + c.bodyEn + " " + sprayEn + tailEn)
                return LocalAnswer(topic,
                    "اس ہفتے کا موسم (اوسط درجہ حرارت ${n0(s.tmean7d)} ڈگری، نمی ${n0(s.rh7d)}٪) تیلے یا کنگی کے لیے خاص سازگار نہیں۔ پھر بھی کھیت کی چار جگہوں سے بیس پودے دیکھیں اور اسپرے سے پہلے زراعت افسر سے مشورہ کریں۔ $sprayUr$tailUr",
                    "This week's weather (mean ${n0(s.tmean7d)} C, humidity ${n0(s.rh7d)}%) does not especially favour aphids or rust. Still check 20 plants in 4 spots, and consult your extension officer before any spray. $sprayEn$tailEn")
            }
            "frost" -> {
                card("frost")?.let { return fromCard(it) }
                val t = s.fcTmin3d
                return LocalAnswer(topic,
                    "اگلی تین راتوں میں کہر کا خطرہ ظاہر نہیں ہوتا" + (if (t != null) "؛ کم سے کم درجہ حرارت ${n0(t)} ڈگری متوقع ہے۔" else "۔") + tailUr,
                    "No frost risk shows for the next 3 nights" + (if (t != null) "; the lowest temperature expected is ${n0(t)} C." else ".") + tailEn)
            }
            "heat" -> {
                card("heat")?.let { return fromCard(it) }
                val t = s.fcTmax5d
                return LocalAnswer(topic,
                    "اگلے پانچ دن میں فصل کے لیے نقصان دہ گرمی ظاہر نہیں ہوتی" + (if (t != null) "؛ زیادہ سے زیادہ درجہ حرارت ${n0(t)} ڈگری متوقع ہے۔" else "۔") + " دیر سے بوائی دانہ بھرتے وقت گرمی کا خطرہ بڑھاتی ہے۔" + tailUr,
                    "No damaging heat shows for the next 5 days" + (if (t != null) "; the highest temperature expected is ${n0(t)} C." else ".") + " Later sowing raises the risk of heat at grain fill." + tailEn)
            }
            "moisture" -> {
                val nxt = s.soilMoistureIn7d
                return LocalAnswer(topic,
                    "زمین کی اوپری نمی کا اندازہ $pct٪ ہے (بوائی کے لیے 20 تا 35٪ موزوں ہے)۔" + (if (nxt != null) " سات دن بعد کا اندازہ ${(nxt * 100).roundToInt()}٪۔" else "") + " یہ بارش اور بخارات سے لگایا گیا اندازہ ہے، کھیت میں ہاتھ سے وتر ضرور دیکھیں۔" + tailUr,
                    "Estimated surface soil moisture is $pct% (20 to 35% suits sowing)." + (if (nxt != null) " The estimate for 7 days from now is ${(nxt * 100).roundToInt()}%." else "") + " This is an estimate from rainfall and evaporation, so check the soil by hand in the field." + tailEn)
            }
            "rain" -> return LocalAnswer(topic,
                "پچھلے سات دن میں ${n0(s.rain7d)} ملی میٹر بارش ہوئی۔ اگلے دو دن میں ${n0(s.fcRain48h)} اور اگلے سات دن میں ${n0(s.fcRain7d)} ملی میٹر متوقع ہے۔$tailUr",
                "Rain in the last 7 days: ${n0(s.rain7d)} mm. Expected: ${n0(s.fcRain48h)} mm in the next 2 days and ${n0(s.fcRain7d)} mm in the next 7 days.$tailEn")
            "yield" -> {
                val y = String.format(Locale.US, "%.1f", engine.expectedYieldMaundPerAcre)
                return LocalAnswer(topic,
                    "ضلع چکوال میں بارانی گندم کی اوسط پیداوار پچھلے پانچ رپورٹ شدہ سیزن میں $y من فی ایکڑ رہی۔ یہ ضلعی اوسط ہے، آپ کے کھیت کا ریکارڈ نہیں۔",
                    "Rainfed wheat in Chakwal district averaged $y maund per acre over the last five reported seasons. This is the district average, not a record of your own field.")
            }
        }
        val first = cards.firstOrNull()
        if (first != null) return fromCard(first)
        return LocalAnswer(topic,
            "آج کی صورتحال: فصل کا مرحلہ $stageUr، زمین کی نمی کا اندازہ $pct٪، پچھلے سات دن میں بارش ${n0(s.rain7d)} ملی میٹر، اگلے سات دن میں متوقع ${n0(s.fcRain7d)} ملی میٹر۔ بوائی، کھاد، کیڑے، کہر، گرمی، کٹائی یا بارش کے بارے میں پوچھیں۔$tailUr",
            "Today: crop stage $stageEn, estimated soil moisture $pct%, rain in the last 7 days ${n0(s.rain7d)} mm, expected in the next 7 days ${n0(s.fcRain7d)} mm. Ask about sowing, fertiliser, pests, frost, heat, harvest or rain.$tailEn")
    }
}
