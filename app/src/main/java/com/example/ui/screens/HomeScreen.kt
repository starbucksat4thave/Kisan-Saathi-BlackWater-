package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AdvisoryCard
import com.example.data.model.FarmerProfile
import com.example.data.model.RoadmapNode
import com.example.data.repository.KisanRepository
import com.example.ui.components.AdvisoryCardSheet
import com.example.ui.components.BarkatBasisDialog
import com.example.ui.components.BarkatSceneView
import com.example.ui.components.RoadmapView
import com.example.ui.theme.BarkatGold
import com.example.ui.theme.FieldGreen
import com.example.ui.theme.NotoNastaliqUrdu
import com.example.ui.theme.WheatGold
import com.example.util.AudioHelper
import com.example.util.LocalAppLanguage
import com.example.util.isUrdu
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: KisanRepository,
    audioHelper: AudioHelper,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUrdu = LocalAppLanguage.current.isUrdu
    val coroutineScope = rememberCoroutineScope()
    var farmerProfile by remember { mutableStateOf(repository.getDemoFarmerProfile()) }
    var simulatedDate by remember { mutableStateOf("2026-11-06") }
    var simulatedDateLabel by remember { mutableStateOf(if (isUrdu) "نومبر ۶ (بوائی)" else "Nov 6 (Sowing)") }

    var selectedNodeKey by remember { mutableStateOf("sowing") }
    var showBarkatDialog by remember { mutableStateOf(false) }
    var barkatBasisText by remember { mutableStateOf("") }
    var barkatMaundBasis by remember { mutableDoubleStateOf(1.5) }

    var showActionDoneDialog by remember { mutableStateOf(false) }
    var activeAdvisoryToComplete by remember { mutableStateOf<AdvisoryCard?>(null) }
    var showCelebrationBanner by remember { mutableStateOf(false) }

    val snapshot = remember(simulatedDate, repository.engine) { repository.getSnapshotForDate(simulatedDate) }
    val nodes = remember(simulatedDate, repository.engine) { repository.getRoadmapNodes(simulatedDate) }
    val advisories = remember(simulatedDate, repository.engine) { repository.getAdvisoriesForDate(simulatedDate) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // --- 1. Top Farmer Header & Barkat Chip ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isUrdu) "السلام علیکم، ${farmerProfile.nickname}!" else "Welcome, ${farmerProfile.name}!",
                    fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
                    fontSize = if (isUrdu) 22.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isUrdu) "گاؤں ${farmerProfile.village} · بڑا کھیت (۴ ایکڑ بارانی)" else "Village ${farmerProfile.village} · Bara Khet (4-Acre Rainfed)",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Cumulative Barkat Counter Card
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        barkatBasisText = "مجموعی برکت: اس سیزن میں اب تک بچائی گئی متوقع پیداوار برائے ۴ ایکڑ رقبہ۔"
                        barkatMaundBasis = farmerProfile.barkat_total_maund
                        showBarkatDialog = true
                    }
                    .testTag("barkat_badge_chip"),
                color = BarkatGold.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, BarkatGold)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Star, contentDescription = "Barkat", tint = Color(0xFFD48806), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isUrdu) "+${farmerProfile.barkat_total_maund} من برکت" else "+${farmerProfile.barkat_total_maund} Maund",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF874D00)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- 1B. Interactive Canvas Visual Field (Dynamic Barkat Growth) ---
        BarkatSceneView(
            barkatMaunds = farmerProfile.barkat_total_maund,
            onTapDetails = {
                barkatBasisText = "برکت اسکور: فصل کے مختلف مراحل پر بروقت اقدامات سے بچائی گئی پیداوار۔"
                barkatMaundBasis = farmerProfile.barkat_total_maund
                showBarkatDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        // --- 2. Agro-weather Telemetry Strip (from KisanEngine Snapshot) ---
        val smPercent = snapshot?.let { Math.round(it.soilMoisture * 100) } ?: 24
        val rain48h = snapshot?.let { Math.round(it.fcRain48h * 10.0) / 10.0 } ?: 0.0
        val stageLabel = snapshot?.stage?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "Sowing"

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Opacity, contentDescription = null, tint = Color(0xFF0288D1), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUrdu) "مٹی نمی: $smPercent% ($stageLabel)" else "Soil Moisture: $smPercent% ($stageLabel)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF5C6BC0), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUrdu) "پیش گوئی: ${rain48h}mm بارش (48h)" else "Forecast: ${rain48h}mm rain (48h)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- 3. Time-Travel Demonstration Bar (Simulate Crop Cycle) ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFFFF3E0),
            border = androidx.compose.foundation.BorderStroke(1.dp, WheatGold.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.FastForward, contentDescription = null, tint = WheatGold, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isUrdu) "سیزن کا وقت تبدیل کریں (Time Travel Demo):" else "Simulation Date (Time Travel Demo):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF795548)
                        )
                    }
                    Text(
                        text = simulatedDateLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = FieldGreen
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TimeTravelPill("نومبر (بوائی)", "2026-11-06", "نومبر ۶ (بوائی)", "sowing", simulatedDate) { d, l, n ->
                        simulatedDate = d; simulatedDateLabel = l; selectedNodeKey = n
                    }
                    TimeTravelPill("دسمبر (کھاد)", "2026-12-18", "دسمبر ۱۸ (یوریا)", "tillering_fertilizer", simulatedDate) { d, l, n ->
                        simulatedDate = d; simulatedDateLabel = l; selectedNodeKey = n
                    }
                    TimeTravelPill("جنوری (کورا)", "2027-01-15", "جنوری ۱۵ (کورا)", "frost_monitoring", simulatedDate) { d, l, n ->
                        simulatedDate = d; simulatedDateLabel = l; selectedNodeKey = n
                    }
                    TimeTravelPill("فروری (تیلہ)", "2027-02-15", "فروری ۱۵ (تیلہ)", "pest_scouting", simulatedDate) { d, l, n ->
                        simulatedDate = d; simulatedDateLabel = l; selectedNodeKey = n
                    }
                    TimeTravelPill("مارچ (گرمی)", "2027-03-22", "مارچ ۲۲ (گرمی)", "grain_filling_heat", simulatedDate) { d, l, n ->
                        simulatedDate = d; simulatedDateLabel = l; selectedNodeKey = n
                    }
                    TimeTravelPill("اپریل (کٹائی)", "2027-04-16", "اپریل ۱۶ (کٹائی)", "harvest", simulatedDate) { d, l, n ->
                        simulatedDate = d; simulatedDateLabel = l; selectedNodeKey = n
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- 4. Celebration Banner if Action Completed ---
        AnimatedVisibility(
            visible = showCelebrationBanner,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(14.dp),
                color = FieldGreen,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Celebration, contentDescription = null, tint = BarkatGold, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isUrdu) "ماشاءاللہ! اقدام بروقت مکمل ہوا" else "Masha'Allah! Action completed on time",
                            fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
                            color = Color.White,
                            fontSize = if (isUrdu) 17.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isUrdu) "برکت میں اضافہ درج کر لیا گیا اور پوائنٹس مل گئے۔" else "Barkat recorded and points awarded.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // --- 5. Active Advisory Card Sheet for Current Stage ---
        advisories.firstOrNull()?.let { activeCard ->
            AdvisoryCardSheet(
                advisory = activeCard,
                onPlayAudio = {
                    audioHelper.speak(if (isUrdu) activeCard.bodyUr else activeCard.bodyEn)
                },
                onMarkDone = {
                    activeAdvisoryToComplete = activeCard
                    showActionDoneDialog = true
                },
                onTapBarkatEstimate = {
                    activeCard.barkatIfDone?.let { b ->
                        barkatBasisText = b.basis
                        barkatMaundBasis = b.maundEstimate
                        showBarkatDialog = true
                    }
                }
            )
            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 6. Duolingo-style Seasonal Roadmap Path ---
        Text(
            text = if (isUrdu) "فصل کا روڈ میپ (Season Roadmap):" else "Crop Season Roadmap:",
            fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = if (isUrdu) "اگلے مرحلے پر کلک کر کے پیشگی معلومات حاصل کریں" else "Tap any milestone node to view upcoming stage advisory",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        RoadmapView(
            nodes = nodes,
            selectedNodeKey = selectedNodeKey,
            onSelectNode = { node ->
                selectedNodeKey = node.key
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- 7. Past Seasons Quick Navigation Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onNavigateToHistory() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Schedule, contentDescription = null, tint = FieldGreen, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isUrdu) "ماضی کے سیزن اور سبق" else "Past Seasons & Memory",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isUrdu) "۲۰۲۱ سے ۲۰۲۴ کے موسمی واقعات دیکھیں" else "Compare with 2021-2024 seasons",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = FieldGreen)
            }
        }
    }

    // --- Barkat Basis Explainer Dialog ---
    if (showBarkatDialog) {
        BarkatBasisDialog(
            maundEstimate = barkatMaundBasis,
            lossFactorBasis = barkatBasisText,
            onDismiss = { showBarkatDialog = false }
        )
    }

    // --- Action Completion Confirmation Dialog ---
    if (showActionDoneDialog && activeAdvisoryToComplete != null) {
        var noteText by remember { mutableStateOf("") }
        var ureaBags by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showActionDoneDialog = false },
            title = {
                Text(
                    text = if (isUrdu) "اقدام مکمل کرنے کی تصدیق" else "Confirm Action Completed",
                    fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isUrdu) "کیا آپ نے یہ کام اپنے کھیت میں کر لیا ہے؟" else "Have you completed this task in your field?",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (activeAdvisoryToComplete?.nodeKey == "tillering_fertilizer") {
                        OutlinedTextField(
                            value = ureaBags,
                            onValueChange = { ureaBags = it },
                            label = { Text(if (isUrdu) "یوریا کھاد کی بوریاں" else "Urea Bags Applied") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text(if (isUrdu) "اضافی تفصیل یا نوٹ (اختیاری)" else "Field Note / Observation (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val card = activeAdvisoryToComplete!!
                        showActionDoneDialog = false
                        coroutineScope.launch {
                            val saved = repository.markAdvisoryCompleted(
                                advisory = card,
                                amountBag = ureaBags.toDoubleOrNull() ?: 1.0,
                                detailNote = noteText.ifBlank { null }
                            )
                            farmerProfile = farmerProfile.copy(
                                barkat_total_maund = farmerProfile.barkat_total_maund + saved,
                                points = farmerProfile.points + 50
                            )
                            showCelebrationBanner = true
                            delay(4500)
                            showCelebrationBanner = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FieldGreen)
                ) {
                    Text(if (isUrdu) "ہاں، میں نے کر لیا (+۵۰ پوائنٹس)" else "Mark Done (+50 pts)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showActionDoneDialog = false }) {
                    Text(if (isUrdu) "ابھی نہیں" else "Not Yet")
                }
            }
        )
    }
}

@Composable
private fun TimeTravelPill(
    label: String,
    dateValue: String,
    dateLabel: String,
    nodeKey: String,
    currentSimulatedDate: String,
    onSelect: (String, String, String) -> Unit
) {
    val isSelected = currentSimulatedDate == dateValue
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect(dateValue, dateLabel, nodeKey) },
        color = if (isSelected) FieldGreen else Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) FieldGreen else Color.LightGray)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else Color(0xFF424242),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
