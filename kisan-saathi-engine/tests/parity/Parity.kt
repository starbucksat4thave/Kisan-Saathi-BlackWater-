import com.example.engine.Dates
import com.example.engine.KisanEngine
import java.io.File
import java.util.Locale

fun main(args: Array<String>) {
    val dir = args[0]
    fun read(n: String) = File(dir, n).readText(Charsets.UTF_8)
    val e = KisanEngine(read("daily_dhudial.csv"), read("forecast_dhudial.csv"), read("soil_wetness.txt"), read("heat_risk_by_sowing.csv"), read("meta.txt"))
    for (line in File(args[1]).readLines()) {
        val p = line.split(',')
        val today = Dates.parse(p[0])
        val sow = if (p[1].isEmpty()) null else Dates.parse(p[1])
        val s = e.snapshot(today, sow)
        val a = e.advisories(s)
        fun f(v: Double?) = if (v == null) "NA" else String.format(Locale.US, "%.6f", v)
        println(listOf(p[0], f(s.soilMoisture), f(s.soilMoistureIn3d), f(s.soilMoistureIn7d), f(s.gdd), s.stage, s.forecastSource,
            f(s.fcRain48h), f(s.fcTmin3d), s.sprayOkToday.toString().lowercase(),
            a.joinToString(";") { it.id + ":" + it.severity + ":" + (it.actBy?.let { d -> Dates.format(d) } ?: "NA") }).joinToString("|"))
    }
    val st = e.stageDates(Dates.parse("2024-11-10"), Dates.parse("2025-06-30"))
    println("STAGES|" + st.entries.joinToString(";") { it.key + "=" + (it.value?.let { d -> Dates.format(d) } ?: "NA") })
    println("TEXT|" + e.advisories(e.snapshot(Dates.parse("2024-11-10"), null))[0].bodyEn)
}
