package com.example.engine

import android.content.Context

/**
 * Loads the engine from app/src/main/assets/kisan. Loading parses about 400 KB of text,
 * so call it once off the main thread (for example inside withContext(Dispatchers.IO)) and keep the result.
 */
object KisanAssets {
    @Volatile private var cached: KisanEngine? = null
    private var cachedLocation: String? = null

    fun load(context: Context, location: String = "dhudial"): KisanEngine {
        cached?.let { if (cachedLocation == location) return it }
        fun read(name: String): String =
            context.applicationContext.assets.open("kisan/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val engine = KisanEngine(
            dailyCsv = read("daily_$location.csv"), forecastCsv = read("forecast_$location.csv"),
            modelText = read("soil_wetness.txt"), heatCsv = read("heat_risk_by_sowing.csv"), metaText = read("meta.txt")
        )
        cached = engine; cachedLocation = location
        return engine
    }
}
