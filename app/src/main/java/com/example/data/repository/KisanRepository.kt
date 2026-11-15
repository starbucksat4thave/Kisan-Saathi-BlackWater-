package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.R
import com.example.data.model.AdvisoryCard
import com.example.data.model.AdvisorySeverity
import com.example.data.model.BadgeItem
import com.example.data.model.BarkatEstimate
import com.example.data.model.BarkatLog
import com.example.data.model.CropSeason
import com.example.data.model.DiaryLog
import com.example.data.model.FarmerProfile
import com.example.data.model.FieldPlot
import com.example.data.model.HistoricalSeasonData
import com.example.data.model.MemoryRef
import com.example.data.model.NdviCurvePoint
import com.example.data.model.NodeState
import com.example.data.model.RoadmapNode
import com.example.data.model.SummerPlanOption
import com.example.data.model.VillageLeaderboardFarmer
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class KisanRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    /** The on-device engine. Held as Compose state so screens redraw when it finishes loading. */
    var engine: com.example.engine.KisanEngine? by mutableStateOf<com.example.engine.KisanEngine?>(null)

    /** Conditions for the most recent day that has data, or null until the engine has loaded. */
    fun getLatestSnapshot(): com.example.engine.Snapshot? {
        val e = engine ?: return null
        return runCatching {
            val day = e.lastObserved
            e.snapshot(day, EngineAdapter.defaultSowingFor(day))
        }.getOrNull()
    }

    /**
     * Returns on-device engine Snapshot for a given seasonal date if available.
     */
    fun getSnapshotForDate(simulatedDate: String = "2026-11-06"): com.example.engine.Snapshot? {
        val e = engine ?: return null
        return runCatching {
            val day = EngineAdapter.resolveDate(e, com.example.engine.Dates.parse(simulatedDate))
            e.snapshot(day, EngineAdapter.defaultSowingFor(day))
        }.getOrNull()
    }

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Farmer must be signed in with Google before accessing Firestore.")
    }

    /**
     * Realtime observation of Farmer Profile
     */
    fun observeFarmerProfile(): Flow<FarmerProfile?> = flow {
        val uid = getCurrentUserId()
        if (uid == null) {
            emit(getDemoFarmerProfile("demo_user"))
            return@flow
        }
        val docRef = db.collection("users").document(uid)
        emit(getDemoFarmerProfile(uid)) // initial seed emit
        try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val profile = snapshot.toObject(FarmerProfile::class.java)
                emit(profile ?: getDemoFarmerProfile(uid))
            } else {
                // Initialize default demo profile in firestore
                saveInitialProfile(uid)
                emit(getDemoFarmerProfile(uid))
            }
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            emit(getDemoFarmerProfile(uid))
        }
    }

    private suspend fun saveInitialProfile(uid: String) {
        val docRef = db.collection("users").document(uid)
        val payload = mapOf(
            "userId" to uid,
            "name" to "Rashid Minhas",
            "language" to "ur",
            "village" to "Dhudial",
            "tehsil" to "Chakwal",
            "level" to "mehnati_kisan",
            "barkat_total_maund" to 6.0,
            "leaderboard_opt_in" to true,
            "nickname" to "Rashid Bhai",
            "points" to 340,
            "streak_weeks" to 3,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
        }
    }

    /**
     * Mark an advisory as completed, log in diary, record barkat, update points
     */
    suspend fun markAdvisoryCompleted(
        advisory: AdvisoryCard,
        amountBag: Double? = null,
        detailNote: String? = null
    ): Double {
        val uid = getCurrentUserId() ?: "demo_rashid"
        val maundSaved = advisory.barkatIfDone?.maundEstimate ?: 1.5

        if (auth.currentUser != null) {
            val userRef = db.collection("users").document(uid)
            val diaryRef = userRef.collection("diary").document()
            val barkatRef = userRef.collection("barkat_events").document()
            val leaderboardRef = db.collection("leaderboard").document(uid)

            val diaryPayload = mapOf(
                "userId" to uid,
                "season_id" to "rabi_2026_27",
                "date" to advisory.actBy,
                "type" to advisory.nodeKey,
                "detail" to (detailNote ?: "${advisory.titleUr}: ${amountBag?.let { "$it بوری" } ?: "بروقت کارروائی مکمل"}"),
                "source" to "tap",
                "matched_advisory_id" to advisory.id,
                "createdAt" to FieldValue.serverTimestamp()
            )

            val barkatPayload = mapOf(
                "userId" to uid,
                "advisory_id" to advisory.id,
                "maund_estimate" to maundSaved,
                "loss_factor_id" to (advisory.barkatIfDone?.basis ?: "timely_action"),
                "basis" to (advisory.barkatIfDone?.basis ?: "Field trial loss model (placeholder)"),
                "placeholder" to true,
                "createdAt" to FieldValue.serverTimestamp()
            )

            try {
                diaryRef.set(diaryPayload).await()
                barkatRef.set(barkatPayload).await()
                userRef.update(
                    "barkat_total_maund", FieldValue.increment(maundSaved),
                    "points", FieldValue.increment(20),
                    "updatedAt", FieldValue.serverTimestamp()
                ).await()

                leaderboardRef.set(
                    mapOf(
                        "farmerId" to uid,
                        "nickname" to "Rashid Bhai",
                        "village" to "Dhudial",
                        "score" to 360,
                        "streak_weeks" to 4,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, diaryRef.path)
            }
        }

        return maundSaved
    }

    /**
     * Fallback / Baseline Farmer Profile
     */
    fun getDemoFarmerProfile(uid: String = "demo_rashid"): FarmerProfile {
        return FarmerProfile(
            id = uid,
            userId = uid,
            name = "Rashid Minhas (راشد منہاس)",
            phone = "+92 300 1234567",
            language = "ur",
            village = "Dhudial (ڈھڈیال)",
            tehsil = "Chakwal (چکوال)",
            level = "mehnati_kisan",
            barkat_total_maund = 6.0,
            leaderboard_opt_in = true,
            nickname = "Rashid Bhai (راشد بھائی)",
            points = 340,
            streak_weeks = 3
        )
    }

    /**
     * Roadmap Nodes for the Wheat Crop Cycle in Pothohar, synced with KisanEngine GDD & stages.
     */
    fun getRoadmapNodes(currentSimulatedDate: String = "2026-11-06"): List<RoadmapNode> {
        val snap = getSnapshotForDate(currentSimulatedDate)
        val currentGdd = snap?.gdd

        // Helper to determine node status based on real GDD thresholds or date cutoff
        fun resolveState(stageMinGdd: Double?, dateCutoffStart: String, dateCutoffEnd: String): NodeState {
            if (currentGdd != null && stageMinGdd != null) {
                return when {
                    currentGdd < stageMinGdd -> NodeState.UPCOMING
                    currentGdd < (stageMinGdd + 300.0) -> NodeState.CURRENT
                    else -> NodeState.DONE
                }
            }
            return when {
                currentSimulatedDate < dateCutoffStart -> NodeState.UPCOMING
                currentSimulatedDate <= dateCutoffEnd -> NodeState.CURRENT
                else -> NodeState.DONE
            }
        }

        return listOf(
            RoadmapNode(
                key = "land_prep",
                order = 1,
                titleUr = "زمین کی تیاری اور وتر",
                titleEn = "Land Prep & Wattar",
                windowStart = "2026-10-15",
                windowEnd = "2026-10-31",
                state = if (snap != null && snap.sowingDate != null) NodeState.DONE else if (currentSimulatedDate <= "2026-10-31") NodeState.CURRENT else NodeState.DONE,
                icon = "agriculture",
                advisoryKeys = listOf("prep_deep_plow"),
                gddStage = "Pre-sowing (0 GDD)"
            ),
            RoadmapNode(
                key = "sowing",
                order = 2,
                titleUr = "بوائی (وتر کا سنہری وقت)",
                titleEn = "Sowing (Wattar Window)",
                windowStart = "2026-11-01",
                windowEnd = "2026-11-15",
                state = when {
                    snap != null && snap.sowingDate == null -> NodeState.CURRENT
                    currentGdd != null && currentGdd > 150.0 -> NodeState.DONE
                    currentSimulatedDate <= "2026-11-15" -> NodeState.CURRENT
                    else -> NodeState.DONE
                },
                icon = "grain",
                advisoryKeys = listOf("wattar_ok_sow_now"),
                gddStage = currentGdd?.let { "Emergence (${it.toInt()} GDD)" } ?: "Emergence (0–150 GDD)"
            ),
            RoadmapNode(
                key = "tillering_fertilizer",
                order = 3,
                titleUr = "شاخیں نکلنا اور یوریا کھاد",
                titleEn = "Tillering & Urea Top-Dress",
                windowStart = "2026-12-05",
                windowEnd = "2026-12-25",
                state = resolveState(150.0, "2026-12-01", "2026-12-25"),
                icon = "spa",
                advisoryKeys = listOf("urea_tillering_timing"),
                gddStage = "Tillering (450 GDD)"
            ),
            RoadmapNode(
                key = "frost_monitoring",
                order = 4,
                titleUr = "کورے (پالے) کی نگرانی",
                titleEn = "Frost & Cold Stress",
                windowStart = "2027-01-05",
                windowEnd = "2027-01-25",
                state = resolveState(750.0, "2027-01-01", "2027-01-25"),
                icon = "ac_unit",
                advisoryKeys = listOf("frost_alert_night"),
                gddStage = "Jointing (750 GDD)"
            ),
            RoadmapNode(
                key = "pest_scouting",
                order = 5,
                titleUr = "سست تیلہ معائنہ (بچت اسپرے)",
                titleEn = "Aphid Scouting & Spray",
                windowStart = "2027-02-10",
                windowEnd = "2027-02-28",
                state = resolveState(1200.0, "2027-02-01", "2027-02-28"),
                icon = "bug_report",
                advisoryKeys = listOf("aphid_scout_check"),
                gddStage = "Heading (1200 GDD)"
            ),
            RoadmapNode(
                key = "grain_filling_heat",
                order = 6,
                titleUr = "دانہ بھرائی اور گرمی سے بچاؤ",
                titleEn = "Grain Filling & Heat Wave",
                windowStart = "2027-03-15",
                windowEnd = "2027-03-31",
                state = resolveState(1350.0, "2027-03-01", "2027-03-31"),
                icon = "wb_sunny",
                advisoryKeys = listOf("heat_wave_anthesis"),
                gddStage = "Anthesis / Grain Fill (1350 GDD)"
            ),
            RoadmapNode(
                key = "harvest",
                order = 7,
                titleUr = "بروقت کٹائی (بارش سے پہلے)",
                titleEn = "Harvest Window",
                windowStart = "2027-04-10",
                windowEnd = "2027-04-25",
                state = resolveState(2000.0, "2027-04-01", "2027-04-25"),
                icon = "inventory_2",
                advisoryKeys = listOf("harvest_window_rain_ahead"),
                gddStage = "Maturity (2000 GDD)"
            )
        )
    }

    /**
     * Active Advisory Cards for the selected seasonal date
     */
    fun getAdvisoriesForDate(simulatedDate: String = "2026-11-06"): List<AdvisoryCard> {
            engine?.let { e ->
        runCatching { EngineAdapter.advisoriesFor(e, simulatedDate) }
            .getOrNull()?.let { return it }
    }
        val list = mutableListOf<AdvisoryCard>()

        when {
            simulatedDate <= "2026-11-15" -> {
                list.add(
                    AdvisoryCard(
                        id = "adv_sow_1",
                        key = "wattar_ok_sow_now",
                        nodeKey = "sowing",
                        severity = AdvisorySeverity.ACT,
                        titleUr = "زمین کا وتر بہترین ہے — فوری بوائی کریں",
                        titleEn = "Soil moisture is right — sow wheat within 3 days",
                        bodyUr = "ناسا SMAP سیٹلائٹ کے مطابق آپ کے کھیت میں نمی 24% ہے جو بوائی کے لیے بالکل مناسب ہے۔ جمعرات کو ہلکی بارش کا امکان ہے، اس سے پہلے بوائی مکمل کر لیں۔",
                        bodyEn = "NASA SMAP soil moisture is 0.24 m³/m³ (optimal). Sow before Thursday rain to ensure strong germination without seed rot.",
                        actBy = "2026-11-10",
                        sources = listOf("SMAP (SPL4SMGP v8)", "IMERG (GPM V07)", "Forecast (Open-Meteo)"),
                        confidence = "high",
                        memoryRef = MemoryRef(
                            season = "rabi_2023_24",
                            textUr = "پچھلی بار آپ نے 25 نومبر کو بوائی کی تھی، جس سے مارچ کی گرمی نے دانہ سکیڑ دیا تھا۔ اس بار 10 نومبر سے پہلے بوائی مکمل کریں۔",
                            textEn = "In 2023-24 you sowed on Nov 25 and March heat cut grain; sow before Nov 10."
                        ),
                        barkatIfDone = BarkatEstimate(
                            maundEstimate = 1.5,
                            basis = "sowing_delay_heat_avoidance: 15% terminal heat loss averted on 4 acres (estimate)",
                            placeholder = true
                        ),
                        gddValue = 45.0,
                        smapMoisture = 0.24,
                        rainForecastMm = 12.0
                    )
                )
            }
            simulatedDate in "2026-11-16".."2026-12-31" -> {
                list.add(
                    AdvisoryCard(
                        id = "adv_till_1",
                        key = "urea_tillering_timing",
                        nodeKey = "tillering_fertilizer",
                        severity = AdvisorySeverity.ACT,
                        titleUr = "شاخیں نکلنے کا وقت — کھاد ڈالنے کا سنہری موقع",
                        titleEn = "Tillering stage reached — Apply 1 bag urea",
                        bodyUr = "فصل 450 GDD پر پہنچ چکی ہے۔ کل صبح ہلکی بارش (5 ملی میٹر) متوقع ہے۔ بارش کے فوراً بعد 1 بوری یوریا فی ایکڑ چھٹہ دیں۔",
                        bodyEn = "GDD reached 450. A light 5mm shower is forecast; top-dress 1 bag urea immediately after to maximize root absorption.",
                        actBy = "2026-12-20",
                        sources = listOf("NASA POWER (GDD)", "SMAP (Rootzone)", "Forecast"),
                        confidence = "high",
                        memoryRef = MemoryRef(
                            season = "rabi_2024_25",
                            textUr = "پچھلے سال آپ نے بارش کے بعد کھاد ڈالی تھی اور 36 من پیداوار حاصل ہوئی تھی۔",
                            textEn = "Last year right-time top dressing yielded 36 maunds/acre."
                        ),
                        barkatIfDone = BarkatEstimate(
                            maundEstimate = 2.0,
                            basis = "urea_washout_prevention: 1 bag nitrogen saved from heavy runoff loss (estimate)",
                            placeholder = true
                        ),
                        gddValue = 450.0,
                        smapMoisture = 0.28,
                        rainForecastMm = 5.0
                    )
                )
            }
            simulatedDate in "2027-01-01".."2027-01-31" -> {
                list.add(
                    AdvisoryCard(
                        id = "adv_frost_1",
                        key = "frost_alert_night",
                        nodeKey = "frost_monitoring",
                        severity = AdvisorySeverity.ALERT,
                        titleUr = "کورے (پالے) کی وارننگ — رات کا درجہ حرارت 1.5°C",
                        titleEn = "Frost Alert: Night minimum temperature drops to 1.5°C",
                        bodyUr = "آئندہ دو راتوں میں شدید سرد لہر اور کورے کا خطرہ ہے۔ گندم کے پودوں پر گندھک کا ہلکا دھواں یا نمی برقرار رکھیں۔",
                        bodyEn = "Cold spell forecast with min temp below 2°C in low lying barani swales. Maintain surface humidity.",
                        actBy = "2027-01-18",
                        sources = listOf("NASA POWER (Tmin)", "PMD Advisory", "Forecast"),
                        confidence = "high",
                        barkatIfDone = BarkatEstimate(
                            maundEstimate = 1.0,
                            basis = "frost_damage_mitigation (estimate)",
                            placeholder = true
                        ),
                        gddValue = 820.0,
                        smapMoisture = 0.22,
                        rainForecastMm = 0.0
                    )
                )
            }
            simulatedDate in "2027-02-01".."2027-02-28" -> {
                list.add(
                    AdvisoryCard(
                        id = "adv_pest_1",
                        key = "aphid_scout_check",
                        nodeKey = "pest_scouting",
                        severity = AdvisorySeverity.INFO,
                        titleUr = "سست تیلہ معائنہ کریں — اسپرے کی جلد بازی نہ کریں",
                        titleEn = "Scout for aphids — do not spray prematurely",
                        bodyUr = "ہوا میں نمی اور درجہ حرارت کی وجہ سے تیلے کا خطرہ ہے۔ 4 جگہوں سے 20 پودے چیک کریں۔ اگر فی پودا 10 سے کم کیڑے ہوں تو اسپرے نہ کریں۔",
                        bodyEn = "Temperature and humidity favorable for aphids. Scout 20 plants. If count is below 10/tiller, skip chemical spray to protect ladybird beetles.",
                        actBy = "2027-02-18",
                        sources = listOf("NASA POWER (RH2M/T2M)", "Directorate Pest Warning", "HLS (NDVI)"),
                        confidence = "high",
                        memoryRef = MemoryRef(
                            season = "rabi_2024_25",
                            textUr = "پچھلے سال آپ نے بلاوجہ اسپرے بچا کر 8,000 روپے کی بچت کی تھی۔",
                            textEn = "Saved Rs. 8,000 in unnecessary pesticide in 2024-25."
                        ),
                        barkatIfDone = BarkatEstimate(
                            maundEstimate = 1.2,
                            basis = "scouting_unnecessary_spray_saved: Cost of 2.5 maunds pesticide eliminated (estimate)",
                            placeholder = true
                        ),
                        gddValue = 1250.0,
                        smapMoisture = 0.21,
                        rainForecastMm = 2.0
                    )
                )
            }
            simulatedDate in "2027-03-01".."2027-03-31" -> {
                list.add(
                    AdvisoryCard(
                        id = "adv_heat_1",
                        key = "heat_wave_anthesis",
                        nodeKey = "grain_filling_heat",
                        severity = AdvisorySeverity.ALERT,
                        titleUr = "مارچ کی گرم لہر (34°C) — دانے کی سنبھال",
                        titleEn = "Heat wave during grain fill: Tmax forecast 34°C",
                        bodyUr = "دانہ بھرائی کے مرحلے پر گرم ہوا چلنے کا امکان ہے۔ زمین میں موجود نمی پودے کو گرمی سے محفوظ رکھے گی۔ اگلے سیزن میں 10 دن قبل بوائی ترجیح دیں۔",
                        bodyEn = "Anthesis heat stress forecast. Retained rootzone moisture buffers against premature shriveling.",
                        actBy = "2027-03-25",
                        sources = listOf("NASA POWER (Tmax)", "ECOSTRESS (LST)", "Forecast"),
                        confidence = "high",
                        memoryRef = MemoryRef(
                            season = "rabi_2023_24",
                            textUr = "2023-24 میں اسی گرمی کی لہر نے تاخیر سے بوئی گندم کی پیداوار 18 من تک گرا دی تھی۔",
                            textEn = "March 2024 heat wave caused severe shriveling in late-sown wheat."
                        ),
                        barkatIfDone = BarkatEstimate(
                            maundEstimate = 2.5,
                            basis = "terminal_heat_mitigation: Rootzone moisture preservation (estimate)",
                            placeholder = true
                        ),
                        gddValue = 1550.0,
                        smapMoisture = 0.19,
                        rainForecastMm = 0.0
                    )
                )
            }
            else -> {
                list.add(
                    AdvisoryCard(
                        id = "adv_harv_1",
                        key = "harvest_window_rain_ahead",
                        nodeKey = "harvest",
                        severity = AdvisorySeverity.ALERT,
                        titleUr = "جمعہ کو طوفانی بارش — جمعرات تک کٹائی مکمل کریں",
                        titleEn = "Heavy rain forecast Friday — Harvest wheat by Thursday",
                        bodyUr = "جی پی ایم IMERG سیٹلائٹ اور محکمہ موسمیات کے مطابق جمعہ کی رات 35 ملی میٹر تیز بارش اور ژالہ باری متوقع ہے۔ فصل 2000 GDD پر پک چکی ہے، فوری کٹائی کر کے بھوسہ سنبھال لیں۔",
                        bodyEn = "Severe thunderstorm (35mm + hail) forecast Friday. Crop at 2000 GDD maturity. Harvest and thresh before Thursday sunset.",
                        actBy = "2027-04-18",
                        sources = listOf("IMERG Early Run", "NASA POWER (GDD)", "HLS (Maturity Drop)", "Forecast"),
                        confidence = "high",
                        memoryRef = MemoryRef(
                            season = "rabi_2025_26",
                            textUr = "پچھلے سال بروقت الرٹ نہ ملنے سے 25 فیصد کٹی گندم بھیگ کر داغی ہو گئی تھی۔ اس بار نقصان سے بچیں!",
                            textEn = "In 2025-26 harvest rain damaged 25% of unthreshed wheat."
                        ),
                        barkatIfDone = BarkatEstimate(
                            maundEstimate = 4.0,
                            basis = "harvest_rain_wheat: 25% yield loss prevention on 4-acre crop (estimate)",
                            placeholder = true
                        ),
                        gddValue = 2050.0,
                        smapMoisture = 0.12,
                        rainForecastMm = 35.0
                    )
                )
            }
        }

        return list
    }

    /**
     * 3 Seeded Past Seasons for Rashid Bhai
     */
    /**
     * Past seasons from the real record: weather replayed for a crop sown on 10 November, and the
     * Chakwal district rainfed yield where the Punjab Crop Reporting Service published one.
     * This is a district-level record, not the farmer's own diary. Empty until the engine has loaded.
     */
    fun getHistoricalSeasons(isUrdu: Boolean = true): List<HistoricalSeasonData> {
        val all = engine?.seasons ?: return emptyList()
        return all.takeLast(3).reversed().map { s ->
            val yieldValue = s.districtYieldMaundPerAcre ?: 0.0
            val events = if (isUrdu) listOf(
                "نومبر تا اپریل بارش: ${s.rainNovAprMm.toInt()} ملی میٹر (GPM IMERG)",
                "کہر والی راتیں (2 ڈگری سے کم): ${s.frostDays}",
                "دانہ بھرتے وقت گرم دن (32 ڈگری یا زیادہ): ${s.grainFillHeatDays}"
            ) else listOf(
                "Rain November to April: ${s.rainNovAprMm.toInt()} mm (GPM IMERG)",
                "Frost nights (below 2 C): ${s.frostDays}",
                "Hot days at grain fill (32 C or more): ${s.grainFillHeatDays}"
            )
            val lesson = when {
                s.grainFillHeatDays >= 5 ->
                    if (isUrdu) "دانہ بھرتے وقت ${s.grainFillHeatDays} گرم دن آئے۔ جلد بوائی اس خطرے کو کم کرتی ہے۔"
                    else "Grain fill met ${s.grainFillHeatDays} hot days. Earlier sowing lowers this risk."
                s.rainNovAprMm < 150.0 ->
                    if (isUrdu) "خشک سیزن: نومبر سے اپریل تک صرف ${s.rainNovAprMm.toInt()} ملی میٹر بارش۔ بوائی کے وقت وتر سب سے اہم رہا۔"
                    else "A dry season: only ${s.rainNovAprMm.toInt()} mm from November to April. Moisture at sowing mattered most."
                yieldValue >= 20.0 ->
                    if (isUrdu) "ضلع کے لیے اچھا سال: مناسب بارش اور دانہ بھرتے وقت کم گرمی۔"
                    else "A good year for the district: steady rain and little heat at grain fill."
                else ->
                    if (isUrdu) "اوسط سیزن: ریکارڈ میں شدید گرمی یا خشک سالی کا واضح اثر نہیں۔"
                    else "An average season: no strong heat or drought signal in the record."
            }
            HistoricalSeasonData(
                seasonId = "rabi_" + s.season.replace('-', '_'),
                seasonLabelUr = "ربیع ${s.season}",
                seasonLabelEn = "Rabi ${s.season}",
                sowingDate = if (isUrdu) "10 نومبر (فرض کردہ)" else "10 Nov (assumed)",
                harvestDate = if (isUrdu) "ریکارڈ نہیں" else "not recorded",
                yieldMaundPerAcre = yieldValue,
                eventsUr = events,
                lessonsUr = lesson,
                badgeEarned = null
            )
        }
    }


    /**
     * Summer Rotation Options with AquaCrop 20-Year Weather Replay
     */
    fun getSummerRotationOptions(
        waterWeight: Float = 0.5f,
        incomeWeight: Float = 0.5f,
        soilWeight: Float = 0.5f,
        cattleFeedWeight: Float = 0.5f
    ): List<SummerPlanOption> {
        val baseOptions = listOf(
            SummerPlanOption(
                id = "mungbean",
                nameUr = "مونگ کی دال (پھلی دار نقد آور فصل)",
                nameEn = "Mungbean (Green Gram)",
                descriptionUr = "60 دن میں تیار ہونے والی پھلی دار فصل۔ زمین میں نائٹروجن جذب کر کے آئندہ گندم کی کھاد کا خرچ 30 فیصد کم کرتی ہے۔",
                avgYieldText = "8 من فی ایکڑ",
                avgWaterUseMm = 190.0,
                soilCoverScore = 85,
                grossIncomePkr = 68000,
                inputCostPkr = 18000,
                netMarginPkr = 50000,
                badYearLossPkr = 5000,
                cattleFeedScore = 40,
                erosionRisk = "بہت کم (Very Low)",
                winYearsAgainstFallowPct = 85
            ),
            SummerPlanOption(
                id = "sorghum_fodder",
                nameUr = "جوار کا ہرا چارہ (مویشیوں کی خوراک)",
                nameEn = "Sorghum Fodder (Jowar)",
                descriptionUr = "گرمیوں میں مویشیوں کے لیے بہترین غذائیت بخش سبز چارہ۔ زمین کو گھنا ڈھانپ کر کٹاؤ سے مکمل محفوظ رکھتا ہے۔",
                avgYieldText = "350 من ہرا چارہ",
                avgWaterUseMm = 240.0,
                soilCoverScore = 95,
                grossIncomePkr = 77000,
                inputCostPkr = 22000,
                netMarginPkr = 55000,
                badYearLossPkr = 2000,
                cattleFeedScore = 100,
                erosionRisk = "سب سے کم (Lowest)",
                winYearsAgainstFallowPct = 90
            ),
            SummerPlanOption(
                id = "fallow",
                nameUr = "خالی کھیت (روایتی طریقہ)",
                nameEn = "Summer Fallow (Current Practice)",
                descriptionUr = "گرمیوں میں کھیت بغیر فصل چھوڑنا۔ مونسون کی طوفانی بارشوں میں زرخیز مٹی بہہ جانے کا شدید خطرہ رہتا ہے۔",
                avgYieldText = "کچھ نہیں (صفر)",
                avgWaterUseMm = 80.0,
                soilCoverScore = 20,
                grossIncomePkr = 0,
                inputCostPkr = 0,
                netMarginPkr = 0,
                badYearLossPkr = 0,
                cattleFeedScore = 10,
                erosionRisk = "شدید خطرہ (High Erosion)",
                winYearsAgainstFallowPct = 0
            )
        )

        // Dynamic weighted scoring
        return baseOptions.sortedByDescending { opt ->
            val scoreIncome = (opt.netMarginPkr / 55000.0) * incomeWeight
            val scoreSoil = (opt.soilCoverScore / 100.0) * soilWeight
            val scoreCattle = (opt.cattleFeedScore / 100.0) * cattleFeedWeight
            val scoreWater = (1.0 - (opt.avgWaterUseMm / 250.0)) * waterWeight
            scoreIncome + scoreSoil + scoreCattle + scoreWater
        }
    }

    /**
     * 30m HLS NDVI curve comparison
     */
    fun getNdviCurve(): List<NdviCurvePoint> {
        return listOf(
            NdviCurvePoint("بوائی", "Sowing", 0.12f, 0.11f, 0.13f, 0.12f, 0.12f),
            NdviCurvePoint("کونپلیں", "Emergence", 0.22f, 0.18f, 0.24f, 0.20f, 0.21f),
            NdviCurvePoint("شاخیں", "Tillering", 0.42f, 0.32f, 0.45f, 0.38f, 0.40f),
            NdviCurvePoint("سٹہ نکلنا", "Heading", 0.68f, 0.52f, 0.72f, 0.62f, 0.65f),
            NdviCurvePoint("دانہ بھرائی", "Anthesis", 0.74f, 0.56f, 0.78f, 0.67f, 0.71f),
            NdviCurvePoint("پکائی", "Maturity", 0.40f, 0.25f, 0.38f, 0.32f, 0.35f)
        )
    }

    /**
     * Badges list with earned states
     */
    fun getBadges(): List<BadgeItem> {
        return listOf(
            BadgeItem("wattar_wala", "وتر والا", "Wattar Wala", "زمین کے صحیح وتر پر بروقت بوائی کی", "water_drop", true, "نومبر ۲۰۲۴"),
            BadgeItem("baarish_se_pehle", "بارش سے پہلے", "Baarish Se Pehle", "بارش سے پہلے بروقت کٹائی کر کے فصل بچائی", "umbrella", false),
            BadgeItem("bachat_spray", "بچت اسپرے", "Bachat Spray", "معائنے کے بعد غیر ضروری اسپرے چھوڑ کر خرچ بچایا", "eco", true, "فروری ۲۰۲۵"),
            BadgeItem("khaad_ka_sahi_waqt", "کھاد کا صحیح وقت", "Khaad Ka Sahi Waqt", "ہلکی بارش کے فوراً بعد کھاد ڈالی، ضائع ہونے سے بچائی", "sparkles", true, "دسمبر ۲۰۲۴"),
            BadgeItem("poora_season", "پورا سیزن ڈائری", "Poora Season", "سیزن کے تمام اہم کام روزنامچے میں درج کیے", "menu_book", false),
            BadgeItem("hara_bhara_garmi", "ہرا بھرا گرمی", "Hara Bhara Garmi", "گرمیوں میں خالی کھیت کے بجائے مونگ یا چارہ کاشت کیا", "wb_sunny", false),
            BadgeItem("mentor", "ساتھی رہنما", "Mentor", "گاؤں کے دوسرے کسانوں کو بروقت مشورے سے فائدہ پہنچایا", "group", true, "مارچ ۲۰۲۵")
        )
    }

    /**
     * Village Dhudial Community Leaderboard
     */
    fun getVillageLeaderboard(): List<VillageLeaderboardFarmer> {
        return listOf(
            VillageLeaderboardFarmer("farmer_1", "Aslam Chacha", "Dhudial", 410, 5, 1, 9.5),
            VillageLeaderboardFarmer("farmer_2", "Rashid Bhai (آپ)", "Dhudial", 340, 3, 2, 6.0),
            VillageLeaderboardFarmer("farmer_3", "Tariq Kisaan", "Dhudial", 310, 2, 3, 5.0),
            VillageLeaderboardFarmer("farmer_4", "Bilal Bhai", "Dhudial", 280, 2, 4, 4.5),
            VillageLeaderboardFarmer("farmer_5", "Sikandar", "Dhudial", 220, 1, 5, 3.0)
        )
    }
}
