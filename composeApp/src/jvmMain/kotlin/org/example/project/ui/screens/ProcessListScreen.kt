package org.example.project.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.project.Filters
import org.example.project.ProcessInfo
import org.example.project.ProcState
import org.example.project.SystemSummary
import org.example.project.data.Providers   // <-- este es el bueno
import org.example.project.ui.components.CpuChip
import org.example.project.ui.components.MemChip
import org.example.project.ui.components.StateBadge
import org.example.project.ui.theme.BluePrimary
import org.example.project.ui.theme.OutlineDark
import org.example.project.ui.theme.YellowAccent
// import sun.security.jca.Providers          // <-- ELIMINADO

/* ==== Anchos de columnas (header = filas) ==== */
private val COL_PID: Dp   = 64.dp
private val COL_PROC: Dp  = 120.dp
private val COL_USER: Dp  = 120.dp
private val COL_CPU: Dp   = 80.dp
private val COL_MEM: Dp   = 80.dp
private val COL_STATE: Dp = 120.dp
private val COL_CMD: Dp   = 300.dp

/* ==== Espaciados comunes para alinear header/filas ==== */
private val COL_GAP = 12.dp
private val ROW_HP  = 16.dp
private val ROW_VP  = 8.dp

private val Pill = RoundedCornerShape(28.dp)

@Composable
fun ProcessListScreen() {
    // Filtros UI
    var nameFilter by remember { mutableStateOf("") }
    var userFilter by remember { mutableStateOf("") }
    var stateFilter by remember { mutableStateOf<ProcState?>(null) }

    // Datos reales
    var processes by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var summary   by remember { mutableStateOf(SystemSummary(0.0, 0.0)) }

    // Trigger de recarga manual
    var refreshTick by remember { mutableStateOf(0) }

    // Providers de SO
    val processProvider = remember { Providers.processProvider() }
    val sysProvider     = remember { Providers.systemInfoProvider() }

    // Carga y autorefresco cada 2s (se cancela al cambiar filtros o refreshTick)
    LaunchedEffect(nameFilter, userFilter, stateFilter, refreshTick) {
        val f = Filters(
            name = nameFilter.takeIf { it.isNotBlank() },
            user = userFilter.takeIf { it.isNotBlank() },
            state = stateFilter
        )
        while (true) {
            processes = processProvider.listProcesses(f).getOrElse { emptyList() }
            summary   = sysProvider.summary().getOrElse { SystemSummary(0.0, 0.0) }
            delay(2000L) // <-- corregido a Long
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        /* ====== Cabecera + KPIs + filtros ====== */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Monitor de Procesos",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold)
                )
                Text(
                    "Interfaz procesos • selección múltiple • acciones seguras",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CpuChip("${"%.0f".format(summary.totalCpuPercent)}%")
                    MemChip("${"%.0f".format(summary.totalMemPercent)}%")
                    Spacer(Modifier.weight(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = nameFilter, onValueChange = { nameFilter = it },
                        label = { Text("Proceso") }, singleLine = true, shape = Pill
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = userFilter, onValueChange = { userFilter = it },
                        label = { Text("Usuario") }, singleLine = true, shape = Pill
                    )
                    FilterStateMenu(
                        modifier = Modifier.width(200.dp),
                        stateFilter = stateFilter,
                        onChange = { stateFilter = it }
                    )
                }
            }
        }

        /* ====== Tarjeta con tabla ====== */
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {

                // Título + acciones
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ROW_HP, vertical = ROW_VP),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Procesos (${processes.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.width(12.dp))
                    val btnColors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                        contentColor = YellowAccent
                    )
                    val btnPad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

                    OutlinedButton(onClick = {}, enabled = false, colors = btnColors, contentPadding = btnPad, shape = Pill) {
                        Text("Finalizar", color = YellowAccent)
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {}, enabled = false, colors = btnColors, contentPadding = btnPad, shape = Pill) {
                        Text("Detalles", color = YellowAccent)
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { refreshTick++ },
                        colors = btnColors, contentPadding = btnPad, shape = Pill
                    ) { Text("Refrescar", color = YellowAccent) }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { nameFilter = ""; userFilter = ""; stateFilter = null },
                        colors = btnColors, contentPadding = btnPad, shape = Pill
                    ) { Text("Limpiar filtros", color = YellowAccent) }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {}, enabled = false, colors = btnColors, contentPadding = btnPad, shape = Pill) {
                        Text("Exportar CSV", color = YellowAccent)
                    }
                    Spacer(Modifier.weight(1f))
                }
                Divider(color = OutlineDark)

                HeaderRow()
                Divider(color = OutlineDark.copy(alpha = 0.6f))

                // Lista + barra vertical
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(processes) { p ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ROW_HP, vertical = ROW_VP),
                                horizontalArrangement = Arrangement.spacedBy(COL_GAP),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Cell(p.pid.toString(), COL_PID)
                                Cell(p.name, COL_PROC)
                                Cell(p.user, COL_USER)
                                Cell("${"%.1f".format(p.cpuPercent)}%", COL_CPU)
                                Cell("${"%.1f".format(p.memPercent)}%", COL_MEM)
                                Box(Modifier.width(COL_STATE)) { StateBadge(p.state) }
                                Spacer(Modifier.weight(1f))
                                Cell(p.command ?: "", COL_CMD, maxLines = 1)
                            }
                            Divider(color = OutlineDark.copy(alpha = 0.35f))
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(listState)
                    )
                }
            }
        }
    }
}

/* ====== Encabezados (mismos paddings + gap que filas) ====== */
@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BluePrimary.copy(alpha = 0.12f))
            .padding(horizontal = ROW_HP, vertical = ROW_VP),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP)
    ) {
        HeadCell("PID", COL_PID)
        HeadCell("Proceso", COL_PROC)
        HeadCell("Usuario", COL_USER)
        HeadCell("CPU%", COL_CPU)
        HeadCell("MEM%", COL_MEM)
        HeadCell("Estado", COL_STATE)
        Spacer(Modifier.weight(1f))
        HeadCell("Comando", COL_CMD)
    }
}

@Composable private fun HeadCell(text: String, width: Dp) {
    Text(
        text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable private fun Cell(text: String, width: Dp, maxLines: Int = 1) {
    Text(
        text,
        modifier = Modifier.width(width),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterStateMenu(
    modifier: Modifier = Modifier,
    stateFilter: ProcState?,
    onChange: (ProcState?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = !open },
        modifier = modifier
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor(),
            readOnly = true,
            value = stateFilter?.name ?: "Estado",
            onValueChange = {},
            label = { Text("Estado") },
            shape = Pill
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Todos") }, onClick = { onChange(null); open = false })
            ProcState.entries.forEach { st ->
                DropdownMenuItem(
                    text = { Text(st.name.lowercase().replaceFirstChar { it.titlecase() }) },
                    onClick = { onChange(st); open = false }
                )
            }
        }
    }
}
