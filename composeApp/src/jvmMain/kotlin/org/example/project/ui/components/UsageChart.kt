package org.example.project.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/* Colores “fosforito” pedidos */
private val CpuNeonGreen = Color(0xFF00E676) // Green A400
private val MemOrange    = Color(0xFFFF9100) // Orange A400

@Composable
fun UsageChartCard(
    cpuSeries: List<Float>,      // 0..100
    memSeries: List<Float>,      // 0..100
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = modifier,
        color = cs.surface,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Actividad reciente",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface
                    )
                )
                Spacer(Modifier.weight(1f))
                LegendPill("CPU", CpuNeonGreen)
                LegendPill("MEM", MemOrange)
            }

            Spacer(Modifier.height(12.dp))

            MiniUsageChart(
                cpu = cpuSeries,
                mem = memSeries,
                cpuColor = CpuNeonGreen,
                memColor = MemOrange,
                gridColor = cs.onSurface.copy(alpha = 0.12f),
                modifier = Modifier
                    .height(120.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LegendPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        tonalElevation = 0.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MiniUsageChart(
    cpu: List<Float>,
    mem: List<Float>,
    cpuColor: Color,
    memColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val inset = 10.dp.toPx()
        val chart = Rect(
            left = inset,
            top = inset,
            right = size.width - inset,
            bottom = size.height - inset
        )
        val w = chart.width
        val h = chart.height

        val steps = 4
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 10f))
        val gridStroke = Stroke(width = 1f, pathEffect = dash)
        repeat(steps + 1) { i ->
            val y = chart.bottom - (h / steps) * i
            val p = Path().apply { moveTo(chart.left, y); lineTo(chart.right, y) }
            drawPath(p, gridColor, style = gridStroke)
        }

        fun xAt(i: Int, n: Int): Float {
            val m = max(1, n - 1)
            val stepX = w / m
            return chart.left + i * stepX
        }
        fun yFor(value: Float): Float {
            val v = value.coerceIn(0f, 100f)
            return chart.bottom - (v / 100f) * h
        }

        fun buildSmoothPath(series: List<Float>): Path {
            val n = series.size
            val p = Path()
            if (n == 0) return p
            val pts = series.mapIndexed { i, v -> Offset(xAt(i, n), yFor(v)) }
            if (pts.size == 1) { p.moveTo(pts[0].x, pts[0].y); return p }
            p.moveTo(pts.first().x, pts.first().y)
            for (i in 0 until pts.lastIndex) {
                val p0 = pts[max(i - 1, 0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[min(i + 2, pts.lastIndex)]
                val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                p.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
            }
            return p
        }

        fun buildFillPath(line: Path, baselineY: Float): Path =
            Path().apply { addPath(line); lineTo(chart.right, baselineY); lineTo(chart.left, baselineY); close() }

        val memLine = buildSmoothPath(mem)
        val cpuLine = buildSmoothPath(cpu)

        if (mem.isNotEmpty()) {
            val memFill = Brush.verticalGradient(
                colors = listOf(memColor.copy(alpha = 0.22f), Color.Transparent),
                startY = chart.top, endY = chart.bottom
            )
            drawPath(buildFillPath(memLine, chart.bottom), brush = memFill)
        }
        if (cpu.isNotEmpty()) {
            val cpuFill = Brush.verticalGradient(
                colors = listOf(cpuColor.copy(alpha = 0.20f), Color.Transparent),
                startY = chart.top, endY = chart.bottom
            )
            drawPath(buildFillPath(cpuLine, chart.bottom), brush = cpuFill)
        }

        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (mem.isNotEmpty()) drawPath(memLine, memColor, style = stroke)
        if (cpu.isNotEmpty()) drawPath(cpuLine, cpuColor, style = stroke)

        fun drawEndDot(series: List<Float>, color: Color) {
            if (series.isEmpty()) return
            val n = series.size
            val cx = xAt(n - 1, n)
            val cy = yFor(series.last())
            drawCircle(color.copy(alpha = 0.35f), radius = 6.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(cx, cy))
        }
        drawEndDot(mem, memColor)
        drawEndDot(cpu, cpuColor)
    }
}
