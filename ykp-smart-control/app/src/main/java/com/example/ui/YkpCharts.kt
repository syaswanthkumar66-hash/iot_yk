package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.HealthEntity

@Composable
fun HealthTelemetryChart(
    history: List<HealthEntity>,
    type: String, // "rssi" | "heap"
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) {
        Box(
            modifier = modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                "No health records available",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        return
    }

    // Take up to last 10 records, reverse to show chronological order (oldest to newest)
    val points = history.take(10).reversed()
    val values = points.map { 
        if (type == "rssi") it.rssi.toDouble() else it.freeHeap.toDouble() / 1024.0 // KB
    }

    val minVal = values.minOrNull() ?: 0.0
    val maxVal = values.maxOrNull() ?: 100.0
    val range = if (maxVal == minVal) 10.0 else maxVal - minVal

    val primaryColor = if (type == "rssi") {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        val labelTitle = if (type == "rssi") "Wi-Fi Strength (dBm)" else "Free System Heap (KB)"
        val suffix = if (type == "rssi") " dBm" else " KB"
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(labelTitle, style = MaterialTheme.typography.labelMedium, color = labelColor)
            Text(
                "Range: ${minVal.toInt()}$suffix - ${maxVal.toInt()}$suffix",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp)
                )
                .padding(top = 16.dp, bottom = 8.dp, start = 8.dp, end = 8.dp)
        ) {
            val width = size.width
            val height = size.height

            if (points.size < 2) return@Canvas

            val stepX = width / (points.size - 1)
            val path = Path()
            val fillPath = Path()

            val graphPoints = mutableListOf<Offset>()

            for (i in points.indices) {
                val value = values[i]
                // Translate values to standard float coordinates in Canvas space (bottom is height)
                val fraction = (value - minVal) / range
                // Clamp fraction safely
                val clampedFraction = fraction.coerceIn(0.0, 1.0)
                val x = i * stepX
                val y = height - (clampedFraction.toFloat() * height)
                
                val currentPoint = Offset(x, y)
                graphPoints.add(currentPoint)

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            // Close area path for ambient gradient glow under curve
            fillPath.lineTo(width, height)
            fillPath.close()

            // Draw Horizontal Grid lines
            val gridLinesCount = 3
            for (g in 0..gridLinesCount) {
                val gridY = height * g / gridLinesCount
                drawLine(
                    color = gridColor,
                    start = Offset(0f, gridY),
                    end = Offset(width, gridY),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Draw gradient glow fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f),
                        primaryColor.copy(alpha = 0.02f)
                    )
                )
            )

            // Draw line curve
            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Draw data nodes
            for (point in graphPoints) {
                // outer glow
                drawCircle(
                    color = primaryColor.copy(alpha = 0.25f),
                    radius = 6.dp.toPx(),
                    center = point
                )
                // inner core
                drawCircle(
                    color = primaryColor,
                    radius = 3.dp.toPx(),
                    center = point
                )
            }
        }
    }
}
