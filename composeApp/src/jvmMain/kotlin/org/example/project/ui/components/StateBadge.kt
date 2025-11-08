package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import org.example.project.ProcState
import org.example.project.ui.theme.ChipRunning
import org.example.project.ui.theme.ChipSleeping
import org.example.project.ui.theme.ChipZombie

@Composable
fun StateBadge(state: ProcState, modifier: Modifier = Modifier) {

    val (txt, bg) = when (state) {
        ProcState.RUNNING -> "Running" to ChipRunning
        else -> {"Other" to MaterialTheme.colorScheme.outline}
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(txt, fontWeight = FontWeight.Medium)
    }
}
