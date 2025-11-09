package org.example.project.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.Filters
import org.example.project.ProcessInfo
import org.example.project.ProcState
import org.example.project.SystemSummary
import org.example.project.data.Providers
import org.example.project.ui.components.CpuChip
import org.example.project.ui.components.MemChip
import org.example.project.ui.components.StateBadge
import org.example.project.ui.components.UsageChartCard
import org.example.project.ui.theme.BluePrimary
import org.example.project.ui.theme.OutlineDark
import org.example.project.ui.theme.YellowAccent
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.SwingUtilities

/* ==== Dimensiones de columnas ==== */
private val COL_PID: Dp   = 64.dp
private val COL_PROC: Dp  = 120.dp
private val COL_USER: Dp  = 120.dp
private val COL_CPU: Dp   = 80.dp
private val COL_MEM: Dp   = 80.dp
private val COL_STATE: Dp = 120.dp
private val COL_CMD: Dp   = 300.dp

/* ==== Espaciados comunes ==== */
private val COL_GAP = 12.dp
private val ROW_HP  = 16.dp
private val ROW_VP  = 8.dp

private val Pill = RoundedCornerShape(28.dp)

/* ==== Ordenación ==== */
private enum class SortKey { PID, NAME, USER, CPU, MEM, STATE }
private data class SortState(val key: SortKey = SortKey.NAME, val ascending: Boolean = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessListScreen() {
    // Filtros
    var nameFilter by remember { mutableStateOf("") }
    var userFilter by remember { mutableStateOf("") }
    var stateFilter by remember { mutableStateOf<ProcState?>(null) }

    // Datos (lista y resumen)
    var processes by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var summary by remember { mutableStateOf(SystemSummary(0.0, 0.0)) }

    // Selección
    var selected by remember { mutableStateOf<ProcessInfo?>(null) }

    // Orden
    var sort by remember { mutableStateOf(SortState()) }

    // Gatillo: forzar scroll arriba tras cambiar el orden
    var scrollTopPending by remember { mutableStateOf(false) }

    // Trigger manual de refresco
    var refreshTick by remember { mutableStateOf(0) }

    // Snackbar & scope
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Providers
    val processProvider = remember { Providers.processProvider() }
    val sysProvider     = remember { Providers.systemInfoProvider() }

    // Estado de lista
    val listState = rememberLazyListState()

    // Pausar auto-refresco mientras hay scroll
    var pauseAuto by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling -> pauseAuto = scrolling }
    }

    // Overlay de primera carga
    var firstLoad by remember { mutableStateOf(true) }

    // Exportación
    var exporting by remember { mutableStateOf(false) }

    // Carga con filtros (con protección de “no re-pintar si nada cambió”)
    suspend fun fetchOnce(f: Filters) {
        val newProcesses = withContext(Dispatchers.IO) { processProvider.listProcesses(f) }
            .getOrElse { emptyList() }
        val newSummary = withContext(Dispatchers.IO) { sysProvider.summary() }
            .getOrElse { SystemSummary(0.0, 0.0) }

        val sameList =
            processes.size == newProcesses.size &&
                    processes.zip(newProcesses).all { (a, b) ->
                        a.pid == b.pid &&
                                a.cpuPercent == b.cpuPercent &&
                                a.memPercent == b.memPercent &&
                                a.state == b.state
                    }

        if (!sameList) {
            processes = newProcesses
            selected = selected?.let { sel -> newProcesses.find { it.pid == sel.pid } }
        }
        if (summary != newSummary) summary = newSummary

        if (firstLoad) firstLoad = false
    }

    /* ===== Series para la gráfica (throttle ligero) ===== */
    val cpuSeries: SnapshotStateList<Float> = remember { mutableStateListOf() }
    val memSeries: SnapshotStateList<Float> = remember { mutableStateListOf() }

    LaunchedEffect(Unit) {
        // muestreo cada 700ms para no saturar la UI
        while (true) {
            val s = Providers.systemInfoProvider()
                .summary()
                .getOrElse { SystemSummary(0.0, 0.0) }

            val lastCpu = cpuSeries.lastOrNull()
            val lastMem = memSeries.lastOrNull()
            if (lastCpu == null || kotlin.math.abs(lastCpu - s.totalCpuPercent.toFloat()) >= 0.25f) {
                cpuSeries += s.totalCpuPercent.toFloat()
                if (cpuSeries.size > 60) cpuSeries.removeAt(0)
            }
            if (lastMem == null || kotlin.math.abs(lastMem - s.totalMemPercent.toFloat()) >= 0.25f) {
                memSeries += s.totalMemPercent.toFloat()
                if (memSeries.size > 60) memSeries.removeAt(0)
            }
            delay(700)
        }
    }

    // Refresco por cambio de filtros (debounce)
    LaunchedEffect(nameFilter, userFilter, stateFilter) {
        snapshotFlow { Triple(nameFilter, userFilter, stateFilter) }
            .debounce(350)
            .distinctUntilChanged()
            .collectLatest { (n, u, s) ->
                val f = Filters(
                    name = n.takeIf { it.isNotBlank() },
                    user = u.takeIf { it.isNotBlank() },
                    state = s
                )
                fetchOnce(f)
            }
    }

    // Auto-refresco periódico cuando no hay scroll
    LaunchedEffect(pauseAuto, refreshTick) {
        if (pauseAuto) return@LaunchedEffect
        while (!pauseAuto) {
            val f = Filters(
                name = nameFilter.takeIf { it.isNotBlank() },
                user = userFilter.takeIf { it.isNotBlank() },
                state = stateFilter
            )
            fetchOnce(f)
            delay(2000L)
        }
    }

    // Al parar el scroll, refresco inmediato
    LaunchedEffect(pauseAuto) {
        if (!pauseAuto) refreshTick++
    }

    // Diálogos
    var askKill by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    // Lista ordenada (se recalcula solo si cambian datos u orden)
    val rows = remember(processes, sort) { sortRows(processes, sort) }

    // >>> Forzar scroll al inicio cuando cambie el orden (solución al “salto”)
    LaunchedEffect(sort, rows, scrollTopPending) {
        if (scrollTopPending) {
            listState.scrollToItem(0) // o animateScrollToItem(0) si prefieres animación
            scrollTopPending = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SnackbarHost(hostState = snackbar)

        /* ====== Cabecera + KPIs + filtros + gráfica ====== */
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
                        value = nameFilter,
                        onValueChange = { nameFilter = it },
                        label = { Text("Proceso") },
                        singleLine = true,
                        shape = Pill
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = userFilter,
                        onValueChange = { userFilter = it },
                        label = { Text("Usuario") },
                        singleLine = true,
                        shape = Pill
                    )
                    FilterStateMenu(
                        modifier = Modifier.width(200.dp),
                        stateFilter = stateFilter,
                        onChange = { newState -> stateFilter = newState }
                    )
                }
            }

            UsageChartCard(
                cpuSeries = cpuSeries,
                memSeries = memSeries,
                modifier = Modifier
                    .weight(1f)
                    .height(150.dp)
            )
        }

        /* ====== Tabla ====== */
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ROW_HP, vertical = ROW_VP),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Procesos (${rows.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.width(12.dp))

                        val btnColors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                            contentColor = YellowAccent
                        )
                        val btnPad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

                        OutlinedButton(
                            onClick = { askKill = true },
                            enabled = selected != null,
                            colors = btnColors, contentPadding = btnPad, shape = Pill
                        ) { Text("Finalizar", color = YellowAccent) }

                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { showDetails = true },
                            enabled = selected != null,
                            colors = btnColors, contentPadding = btnPad, shape = Pill
                        ) { Text("Detalles", color = YellowAccent) }

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
                        OutlinedButton(
                            onClick = {
                                val target = chooseCsvFileOnEDT() ?: return@OutlinedButton
                                scope.launch {
                                    exporting = true
                                    try {
                                        writeCsvFile(target, rows)
                                        snackbar.showSnackbar("CSV exportado: ${target.absolutePath}")
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar("Error al exportar: ${e.message ?: "desconocido"}")
                                    } finally {
                                        exporting = false
                                    }
                                }
                            },
                            enabled = rows.isNotEmpty() && !exporting,
                            colors = btnColors, contentPadding = btnPad, shape = Pill
                        ) {
                            Text(if (exporting) "Exportando…" else "Exportar CSV", color = YellowAccent)
                        }

                        Spacer(Modifier.weight(1f))
                    }
                    HorizontalDivider(color = OutlineDark)

                    HeaderRow(
                        sort = sort,
                        onHeaderClick = { key ->
                            sort = if (sort.key == key) sort.copy(ascending = !sort.ascending)
                            else SortState(key, true)
                            // activar gatillo para volver al inicio tras ordenar
                            scrollTopPending = true
                        }
                    )
                    HorizontalDivider(color = OutlineDark.copy(alpha = 0.6f))

                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 10.dp),
                            state = listState,
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            items(items = rows, key = { it.pid }) { p ->
                                val selectedNow = selected?.pid == p.pid

                                val rowBg = if (selectedNow)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                else
                                    Color.Transparent

                                val borderColor = if (selectedNow)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                else
                                    Color.Transparent

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                        .background(rowBg, RoundedCornerShape(8.dp))
                                        .clickable { selected = if (selectedNow) null else p }
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
                                HorizontalDivider(color = OutlineDark.copy(alpha = 0.35f))
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

                if (firstLoad) {
                    LoadingOverlay(
                        text = "Cargando procesos…",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // Detalles
    if (showDetails && selected != null) {
        BasicAlertDialog(onDismissRequest = { showDetails = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 360.dp)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Detalles del proceso",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    HorizontalDivider(color = OutlineDark.copy(alpha = 0.4f))
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PID: ${selected!!.pid}")
                            Text("Proceso: ${selected!!.name}")
                            Text("Usuario: ${selected!!.user}")
                            Text("CPU%: ${"%.1f".format(selected!!.cpuPercent)}")
                            Text("MEM%: ${"%.1f".format(selected!!.memPercent)}")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Estado: "); Spacer(Modifier.width(6.dp)); StateBadge(selected!!.state)
                            }
                            Text("Ruta:"); Text(selected!!.command ?: "—")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val btnColors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                            contentColor = YellowAccent
                        )
                        val btnPad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        OutlinedButton(
                            onClick = { showDetails = false },
                            colors = btnColors, contentPadding = btnPad, shape = Pill
                        ) { Text("Cerrar", color = YellowAccent) }
                    }
                }
            }
        }
    }

    // Confirmación “Finalizar”
    if (askKill && selected != null) {
        AlertDialog(
            onDismissRequest = { askKill = false },
            title = { Text("Finalizar proceso") },
            text = { Text("¿Seguro que quieres finalizar el proceso ${selected!!.name} (PID ${selected!!.pid})?") },
            confirmButton = {
                TextButton(onClick = {
                    askKill = false
                    scope.launch {
                        val pid = selected!!.pid
                        val res = Providers.processProvider().kill(pid)
                        if (res.isSuccess) {
                            snackbar.showSnackbar("Proceso $pid finalizado.")
                            selected = null
                            refreshTick++
                        } else {
                            val msg = res.exceptionOrNull()?.message ?: "Error desconocido"
                            snackbar.showSnackbar("No se pudo finalizar ($pid): $msg")
                        }
                    }
                }) { Text("Sí, finalizar") }
            },
            dismissButton = { TextButton(onClick = { askKill = false }) { Text("Cancelar") } }
        )
    }
}

/* ====== Header de tabla con ordenación ====== */
@Composable
private fun HeaderRow(
    sort: SortState,
    onHeaderClick: (SortKey) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BluePrimary.copy(alpha = 0.12f))
            .padding(horizontal = ROW_HP, vertical = ROW_VP),
        horizontalArrangement = Arrangement.spacedBy(COL_GAP)
    ) {
        HeadCell("PID", COL_PID, SortKey.PID, sort, onHeaderClick)
        HeadCell("Proceso", COL_PROC, SortKey.NAME, sort, onHeaderClick)
        HeadCell("Usuario", COL_USER, SortKey.USER, sort, onHeaderClick)
        HeadCell("CPU%", COL_CPU, SortKey.CPU, sort, onHeaderClick)
        HeadCell("MEM%", COL_MEM, SortKey.MEM, sort, onHeaderClick)
        HeadCell("Estado", COL_STATE, SortKey.STATE, sort, onHeaderClick)
        Spacer(Modifier.weight(1f))
        Text(
            "Ruta",
            modifier = Modifier.width(COL_CMD),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun HeadCell(
    text: String,
    width: Dp,
    key: SortKey,
    sort: SortState,
    onClick: (SortKey) -> Unit
) {
    val isActive = sort.key == key
    val arrow = when {
        !isActive -> ""
        sort.ascending -> " ▲"
        else -> " ▼"
    }
    Text(
        text = text + arrow,
        modifier = Modifier
            .width(width)
            .clickable { onClick(key) },
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold),
        color = if (isActive) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
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
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            value = stateFilter?.name ?: "Estado",
            onValueChange = {},
            label = { Text("Estado") },
            shape = Pill
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Todos") }, onClick = { onChange(null); open = false })
            listOf(ProcState.RUNNING, ProcState.OTHER).forEach { st ->
                DropdownMenuItem(
                    text = { Text(st.name.lowercase().replaceFirstChar { it.titlecase() }) },
                    onClick = { onChange(st); open = false }
                )
            }
        }
    }
}

/*** Overlay reutilizable para primera carga ***/
@Composable
private fun LoadingOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Column {
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "Esto puede tardar unos segundos la primera vez.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        ),
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

/* ======================= Exportación CSV (segura) ======================= */

private fun chooseCsvFileOnEDT(): File? {
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val suggested = "procesos_$ts.csv"

    val box = arrayOfNulls<File>(1)
    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        box[0] = showSaveDialog(suggested)
    } else {
        SwingUtilities.invokeAndWait { box[0] = showSaveDialog(suggested) }
    }
    return box[0]
}

private fun showSaveDialog(suggested: String): File? {
    val dlg = FileDialog(null as Frame?, "Guardar CSV", FileDialog.SAVE).apply {
        file = suggested
        isVisible = true
    }
    val dir = dlg.directory ?: return null
    val name = dlg.file ?: return null
    val f = File(dir, name)
    return if (f.name.lowercase().endsWith(".csv")) f else File(f.parentFile, f.name + ".csv")
}

private suspend fun writeCsvFile(target: File, rows: List<ProcessInfo>) {
    require(rows.isNotEmpty()) { "No hay datos que exportar." }

    val bom = "\uFEFF"
    val header = listOf("PID","Proceso","Usuario","CPU%","MEM%","Estado","Ruta")
        .joinToString(",") { csvEscape(it) }

    val content = buildString(rows.size * 64) {
        append(bom)
        appendLine(header)
        rows.forEach { p ->
            appendLine(listOf(
                p.pid.toString(),
                p.name,
                p.user,
                String.format("%.1f", p.cpuPercent),
                String.format("%.1f", p.memPercent),
                p.state.name,
                p.command ?: ""
            ).joinToString(",") { csvEscape(it) })
        }
    }

    withContext(Dispatchers.IO) {
        Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8).use { it.write(content) }
    }
}

private fun csvEscape(s: String): String {
    val needsQuotes = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return s
    return "\"" + s.replace("\"", "\"\"") + "\""
}

/* ====== Util ordenación ====== */
private fun sortRows(rows: List<ProcessInfo>, s: SortState): List<ProcessInfo> {
    val base = when (s.key) {
        SortKey.PID   -> rows.sortedBy { it.pid }
        SortKey.NAME  -> rows.sortedBy { it.name.lowercase() }
        SortKey.USER  -> rows.sortedBy { it.user.lowercase() }
        SortKey.CPU   -> rows.sortedBy { it.cpuPercent }
        SortKey.MEM   -> rows.sortedBy { it.memPercent }
        SortKey.STATE -> rows.sortedBy { it.state.name }
    }
    return if (s.ascending) base else base.asReversed()
}
