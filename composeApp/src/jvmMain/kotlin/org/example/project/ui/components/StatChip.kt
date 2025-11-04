package org.example.project.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.YellowAccent

@Composable
fun KpiChip(
    prefix: String? = null,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (prefix != null) {
                Text(prefix)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = YellowAccent
            )
        }
    }
}

@Composable
fun CpuChip(value: String, modifier: Modifier = Modifier) =
    KpiChip(prefix = "⚡", label = "CPU total", value = value, modifier = modifier)

@Composable
fun MemChip(value: String, modifier: Modifier = Modifier) =
    KpiChip(prefix = "🧠", label = "Memoria", value = value, modifier = modifier)
