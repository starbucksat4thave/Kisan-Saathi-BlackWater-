package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.KisanRepository
import com.example.ui.theme.BarkatGold
import com.example.ui.theme.FieldEmerald
import com.example.ui.theme.FieldGreen
import com.example.ui.theme.NotoNastaliqUrdu
import com.example.ui.theme.WheatGold
import com.example.util.LocalAppLanguage
import com.example.util.isUrdu

@Composable
fun FieldHealthScreen(
    repository: KisanRepository,
    modifier: Modifier = Modifier
) {
    val isUrdu = LocalAppLanguage.current.isUrdu
    val ndviPoints = remember { repository.getNdviCurve() }
    val engine = repository.engine
    val snap = remember(engine) { repository.getLatestSnapshot() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("field_health_screen")
    ) {
        // Title
        Text(
            text = if (isUrdu) "کھیت کی صحت (Satellite Field Health)" else "Satellite Field Health (کھیت کی صحت)",
            fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
            fontSize = if (isUrdu) 24.sp else 21.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = if (isUrdu) "بارش، درجہ حرارت اور مٹی کے ڈیٹا سے کھیت کی آج کی صورتحال۔ سیٹلائٹ ہریالی (NDVI) ابھی شامل نہیں ہوئی۔"
                else "Today's field conditions from rainfall, temperature and soil data. Satellite greenness (NDVI) has not been added yet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Today's conditions, from the on-device engine
        FieldConditionsCard(engine = engine, snap = snap, isUrdu = isUrdu)

        Spacer(modifier = Modifier.height(18.dp))

        // Interactive NDVI Graph Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUrdu) "ہریالی کا تقابلی چارٹ (NDVI Curve)" else "Comparative Greenness (NDVI Curve)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (isUrdu) "نمونہ ڈیٹا" else "Sample data",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Canvas Graph
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val paddingX = 40f
                        val paddingY = 20f
                        val graphW = w - (paddingX * 2)
                        val graphH = h - (paddingY * 2)

                        // Draw Grid lines
                        for (i in 0..4) {
                            val y = paddingY + (graphH * (i / 4f))
                            drawLine(
                                color = Color.LightGray.copy(alpha = 0.4f),
                                start = Offset(paddingX, y),
                                end = Offset(w - paddingX, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        val n = ndviPoints.size
                        fun getX(idx: Int) = paddingX + (graphW * (idx.toFloat() / (n - 1)))
                        fun getY(valNdvi: Float) = paddingY + graphH * (1f - (valNdvi / 1.0f))

                        // Path 1: Regional Median (Dashed Gray)
                        val medianPath = Path()
                        ndviPoints.forEachIndexed { i, p ->
                            val x = getX(i)
                            val y = getY(p.regionalMedian)
                            if (i == 0) medianPath.moveTo(x, y) else medianPath.lineTo(x, y)
                        }
                        drawPath(
                            path = medianPath,
                            color = Color.Gray,
                            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                        )

                        // Path 2: 2023-24 Late Season (Amber/Brown)
                        val path2023 = Path()
                        ndviPoints.forEachIndexed { i, p ->
                            val x = getX(i)
                            val y = getY(p.season2023)
                            if (i == 0) path2023.moveTo(x, y) else path2023.lineTo(x, y)
                        }
                        drawPath(path = path2023, color = Color(0xFFD32F2F).copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx()))

                        // Path 3: 2024-25 Ideal Season (Wheat Gold)
                        val path2024 = Path()
                        ndviPoints.forEachIndexed { i, p ->
                            val x = getX(i)
                            val y = getY(p.season2024)
                            if (i == 0) path2024.moveTo(x, y) else path2024.lineTo(x, y)
                        }
                        drawPath(path = path2024, color = WheatGold, style = Stroke(width = 2.dp.toPx()))

                        // Path 4: Current Season (Thick Vibrant Green)
                        val currentPath = Path()
                        ndviPoints.forEachIndexed { i, p ->
                            val x = getX(i)
                            val y = getY(p.currentSeason)
                            if (i == 0) currentPath.moveTo(x, y) else currentPath.lineTo(x, y)
                        }
                        drawPath(path = currentPath, color = FieldGreen, style = Stroke(width = 4.dp.toPx()))

                        // Current points dots
                        ndviPoints.forEachIndexed { i, p ->
                            drawCircle(color = FieldGreen, radius = 5.dp.toPx(), center = Offset(getX(i), getY(p.currentSeason)))
                            drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(getX(i), getY(p.currentSeason)))
                        }
                    }
                }

                // X-Axis Stage Labels
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ndviPoints.forEach { pt ->
                        Text(
                            text = if (isUrdu) pt.stageUr else pt.stageEn,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Legend
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LegendItem(
                        color = FieldGreen,
                        label = if (isUrdu) "نمونہ: موجودہ سیزن" else "Sample: current season"
                    )
                    LegendItem(
                        color = WheatGold,
                        label = if (isUrdu) "نمونہ: اچھا سیزن" else "Sample: a good season"
                    )
                    LegendItem(
                        color = Color(0xFFD32F2F),
                        label = if (isUrdu) "نمونہ: تاخیر سے بوئی گئی فصل" else "Sample: a late-sown season"
                    )
                    LegendItem(
                        color = Color.Gray,
                        label = if (isUrdu) "نمونہ: علاقائی اوسط" else "Sample: regional median"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Soil at the field, from SoilGrids
        SoilCard(engine = engine, isUrdu = isUrdu)

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
