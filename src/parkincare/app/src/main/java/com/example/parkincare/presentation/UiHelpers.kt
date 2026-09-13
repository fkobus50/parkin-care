package com.example.parkincare.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun SensorSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Obsługa specjalna etykiet, które chcemy łamać na wiele linii
        if (label == "Natężenie światła") {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.Center
            ) {
                val lines = listOf("Natężenie", "światła")
                lines.forEach {
                    AutoResizeText(
                        text = it,
                        color = Color.White,
                        maxFontSize = 14.sp
                    )
                }
            }
        } else if (label.contains("Nagrywanie", ignoreCase = true)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.Center
            ) {
                val parts = label.split("\\s+".toRegex()).filter { it.isNotBlank() }
                parts.forEach { part ->
                    AutoResizeText(
                        text = part,
                        color = Color.White,
                        maxFontSize = 14.sp
                    )
                }
            }
        } else {
            AutoResizeText(
                text = label,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxFontSize = 14.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF0D1A4A)
            )
        )
    }
}

@Composable
fun AutoResizeText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 10.sp,
    maxFontSize: TextUnit = 18.sp,
    fontStyle: FontStyle = FontStyle.Normal
) {
    val fontSize = remember { mutableStateOf(maxFontSize) }
    Box(modifier = modifier) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize.value,
            maxLines = 1,
            softWrap = false,
            fontStyle = fontStyle,
            onTextLayout = { result ->
                if (result.didOverflowWidth && fontSize.value > minFontSize) {
                    fontSize.value = (fontSize.value.value - 1).coerceAtLeast(minFontSize.value).sp
                }
            }
        )
    }
}

fun humanReadableSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
