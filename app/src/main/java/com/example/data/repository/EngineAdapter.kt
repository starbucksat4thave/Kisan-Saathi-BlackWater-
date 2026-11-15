package com.example.data.repository

import com.example.data.model.AdvisoryCard
import com.example.data.model.AdvisorySeverity
import com.example.data.model.BarkatEstimate
import com.example.engine.Advisory
import com.example.engine.Dates
import com.example.engine.KisanEngine
import java.util.Locale

/**
 * Converts the engine's output into the app's existing [AdvisoryCard] model, so the Home screen
 * needs no changes. See INTEGRATION.md for the three lines to add to KisanRepository.
 */
object EngineAdapter {

    /** Loss fractions from config/loss_factors.yaml. All are placeholders. */
    private val LOSS_FRACTION = mapOf(
        "sowing_delay_heat_avoidance" to 0.15, "wattar_germination_failure" to 0.20,
        "urea_washout_prevention" to 0.10, "scouting_unnecessary_spray_saved" to 0.08, "harvest_rain_wheat" to 0.25
    )

    private val SOURCES = listOf(
        "NASA GPM IMERG rainfall", "ERA5 temperature (Open-Meteo)",
        "Soil moisture model (trained on NASA POWER)", "Forecast (Open-Meteo)"
    )

    /**
     * The engine only has data up to its last observed day. For a later date (the demo's 2026-27
     * season) this returns the same calendar day one or more years earlier, so the demo replays
     * real weather from the most recent season that has data.
     */
    fun resolveDate(engine: KisanEngine, date: Int): Int {
        var d = date
        while (d > engine.lastObserved + 3) {
            val a = Dates.ymd(d)
            d = Dates.of(a[0] - 1, a[1], if (a[1] == 2 && a[2] == 29) 28 else a[2])
        }
        return d
    }

    /** Demo assumption: the farmer sows on 10 November of the season the date falls in. */
    fun defaultSowingFor(date: Int): Int? {
        val a = Dates.ymd(date)
        val seasonYear = if (a[1] >= 10) a[0] else a[0] - 1
        val sowing = Dates.of(seasonYear, 11, 10)
        val seasonEnd = Dates.of(seasonYear + 1, 6, 30)
        return if (date >= sowing && date <= seasonEnd) sowing else null
    }

    /**
     * Real-data advisories for [simulatedDate] ("YYYY-MM-DD"), in the app's own model.
     * Throws IllegalArgumentException if the engine has no data for the date.
     */
    fun advisoriesFor(engine: KisanEngine, simulatedDate: String, areaAcre: Double = 4.0): List<AdvisoryCard> {
        val day = resolveDate(engine, Dates.parse(simulatedDate))
        val snap = engine.snapshot(day, defaultSowingFor(day))
        return engine.advisories(snap).map { a ->
            val fraction = if (a.severity == "action") LOSS_FRACTION[a.lossFactorId] else null
            AdvisoryCard(
                id = "eng_${a.id}_${Dates.format(day)}",
                key = a.id,
                nodeKey = nodeKeyFor(a),
                severity = when (a.severity) {
                    "action" -> AdvisorySeverity.ACT
                    "watch" -> AdvisorySeverity.ALERT
                    else -> AdvisorySeverity.INFO
                },
                titleUr = a.titleUr, titleEn = a.titleEn, bodyUr = a.bodyUr, bodyEn = a.bodyEn,
                actBy = a.actBy?.let { Dates.format(it) } ?: "",
                sources = SOURCES,
                confidence = "medium",
                memoryRef = null,
                barkatIfDone = fraction?.let {
                    BarkatEstimate(
                        maundEstimate = Math.round(engine.barkatMaunds(areaAcre, it) * 10.0) / 10.0,
                        basis = String.format(
                            Locale.US, "%s: %.0f%% loss avoided x %.1f maund/acre district rainfed average x %.0f acres (estimate)",
                            a.lossFactorId, it * 100, engine.expectedYieldMaundPerAcre, areaAcre
                        ),
                        placeholder = true
                    )
                },
                gddValue = snap.gdd,
                smapMoisture = Math.round(snap.soilMoisture * 100.0) / 100.0,
                rainForecastMm = Math.round(snap.fcRain48h * 10.0) / 10.0
            )
        }
    }

    private fun nodeKeyFor(a: Advisory): String = when {
        a.id.startsWith("sow") -> "sowing"
        a.id.startsWith("urea") -> "tillering_fertilizer"
        a.id.startsWith("frost") -> "frost_monitoring"
        a.id.startsWith("aphid") || a.id.startsWith("rust") -> "pest_scouting"
        a.id.startsWith("heat") -> "grain_filling_heat"
        else -> "harvest"
    }
}
