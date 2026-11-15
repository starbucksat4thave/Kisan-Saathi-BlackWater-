package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A plain notice for screens whose numbers are examples and do not yet come from real data. */
@Composable
fun SampleDataBanner(
    isUrdu: Boolean,
    modifier: Modifier = Modifier,
    textUr: String = "یہ اسکرین نمونے کے اعداد دکھا رہی ہے۔ اصل ڈیٹا ابھی شامل نہیں ہوا۔",
    textEn: String = "This screen shows sample figures. Real data has not been connected yet."
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFF4E0),
        border = BorderStroke(1.dp, Color(0xFFB26A00).copy(alpha = 0.5f))
    ) {
        Text(
            text = if (isUrdu) textUr else textEn,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFF5D4037),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
