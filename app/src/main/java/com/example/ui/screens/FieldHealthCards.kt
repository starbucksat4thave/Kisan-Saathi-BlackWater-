package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.Dates
import com.example.engine.KisanAnswers
import com.example.engine.KisanEngine
import com.example.engine.Snapshot
import com.example.ui.theme.FieldGreen
import com.example.ui.theme.NotoNastaliqUrdu

@Composable
internal fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Today's real conditions for the field. Shows a loading line until the engine is ready. */
@Composable
internal fun FieldConditionsCard(
    engine: KisanEngine?,
    snap: Snapshot?,
    isUrdu: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFE8F5E9),
        border = BorderStroke(1.5.dp, FieldGreen.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = FieldGreen, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isUrdu) "کھیت کی آج کی صورتحال" else "Field conditions today",
                    fontFamily = if (isUrdu) NotoNastaliqUrdu else null,
                    fontSize = if (isUrdu) 18.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = FieldGreen
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (engine == null || snap == null) {
                Text(
                    text = if (isUrdu) "کھیت کا ڈیٹا لوڈ ہو رہا ہے..." else "Loading field data...",
                    fontSize = 13.sp,
                    color = Color(0xFF1B5E20)
                )
            } else {
                val moisture = Math.round(snap.soilMoisture * 100.0).toInt()
                val moistureNote = when {
                    snap.soilMoisture < 0.20 -> if (isUrdu) "خشک" else "dry"
                    snap.soilMoisture <= 0.35 -> if (isUrdu) "بوائی کے لیے موزوں" else "right for sowing"
                    else -> if (isUrdu) "زیادہ گیلی" else "too wet"
                }
                val in7 = snap.soilMoistureIn7d
                val tmax = snap.fcTmax5d
                val tmin = snap.fcTmin3d
                val unitMm = if (isUrdu) "ملی میٹر" else "mm"

                FactRow(
                    label = if (isUrdu) "زمین کی اوپری نمی (اندازہ)" else "Surface soil moisture (estimate)",
                    value = "$moisture% · $moistureNote"
                )
                if (in7 != null) {
                    FactRow(
                        label = if (isUrdu) "سات دن بعد نمی کا اندازہ" else "Moisture estimate in 7 days",
                        value = "${Math.round(in7 * 100.0).toInt()}%"
                    )
                }
                FactRow(
                    label = if (isUrdu) "پچھلے سات دن کی بارش" else "Rain, last 7 days",
                    value = "${Math.round(snap.rain7d).toInt()} $unitMm"
                )
                FactRow(
                    label = if (isUrdu) "اگلے سات دن کی متوقع بارش" else "Rain expected, next 7 days",
                    value = "${Math.round(snap.fcRain7d).toInt()} $unitMm"
                )
                FactRow(
                    label = if (isUrdu) "ہفتے کا اوسط درجہ حرارت" else "Mean temperature this week",
                    value = "${Math.round(snap.tmean7d).toInt()} °C"
                )
                FactRow(
                    label = if (isUrdu) "فصل کا مرحلہ" else "Crop stage",
                    value = KisanAnswers.stageLabel(snap.stage, isUrdu)
                )
                if (snap.stage != "not_sown") {
                    FactRow(
                        label = if (isUrdu) "جمع شدہ حرارت (GDD)" else "Accumulated warmth (GDD)",
                        value = "${Math.round(snap.gdd).toInt()}"
                    )
                }
                if (tmax != null && tmax >= 32.0) {
                    FactRow(
                        label = if (isUrdu) "گرمی کا انتباہ" else "Heat warning",
                        value = if (isUrdu) "اگلے پانچ دن میں ${Math.round(tmax).toInt()} °C تک" else "up to ${Math.round(tmax).toInt()} °C in 5 days"
                    )
                }
                if (tmin != null && tmin < 2.0) {
                    FactRow(
                        label = if (isUrdu) "کہر کا انتباہ" else "Frost warning",
                        value = if (isUrdu) "اگلی تین راتوں میں ${Math.round(tmin).toInt()} °C تک" else "down to ${Math.round(tmin).toInt()} °C in 3 nights"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isUrdu) "ڈیٹا ${Dates.format(snap.dataAsOf)} تک۔ نمی بارش اور بخارات سے لگایا گیا اندازہ ہے، سینسر کی پیمائش نہیں۔ ماخذ: NASA GPM IMERG، ERA5، NASA POWER۔"
                        else "Data up to ${Dates.format(snap.dataAsOf)}. Moisture is an estimate from rainfall and evaporation, not a sensor reading. Sources: NASA GPM IMERG, ERA5, NASA POWER.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFF1B5E20)
                )
            }
        }
    }
}

/** Soil texture at the field from SoilGrids, read from the engine's bundled data. */
@Composable
internal fun SoilCard(engine: KisanEngine?, isUrdu: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Map, contentDescription = null, tint = FieldGreen)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isUrdu) "کھیت کی مٹی" else "Soil at the field",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            val meta = engine?.meta
            if (meta == null) {
                Text(
                    text = if (isUrdu) "مٹی کا ڈیٹا لوڈ ہو رہا ہے..." else "Loading soil data...",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FactRow(label = if (isUrdu) "چکنی مٹی (Clay)" else "Clay", value = (meta["clay_pct"] ?: "-") + "%")
                FactRow(label = if (isUrdu) "ریت (Sand)" else "Sand", value = (meta["sand_pct"] ?: "-") + "%")
                FactRow(label = if (isUrdu) "گاد (Silt)" else "Silt", value = (meta["silt_pct"] ?: "-") + "%")
                FactRow(label = "pH", value = meta["soil_ph"] ?: "-")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isUrdu) "ڈھڈیال، چکوال کے علاقے کے لیے، اوپری 15 سینٹی میٹر۔ ماخذ: SoilGrids (250 میٹر)۔ یہ علاقے کا نقشہ ہے، آپ کے کھیت کا لیبارٹری ٹیسٹ نہیں۔"
                        else "For the Dhudial area, Chakwal, top 15 cm. Source: SoilGrids (250 m). This is a regional map value, not a lab test of your field.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
