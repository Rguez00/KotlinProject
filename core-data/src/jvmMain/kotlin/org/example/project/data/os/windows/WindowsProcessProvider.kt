package org.example.project.data.os.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.example.project.*
import org.example.project.data.perf.Perf
import kotlin.math.max
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime

class WindowsProcessProvider : ProcessProvider, SystemInfoProvider {

    /* ==== Flags de rendimiento/log ==== */
    private val FAST_MODE: Boolean = java.lang.Boolean.getBoolean("monitor.fastMode")
    private val NO_LOGS: Boolean = java.lang.Boolean.getBoolean("monitor.noLogs")

    /* ===== Rutas robustas ===== */
    private val SYS_ROOT: String = System.getenv("SystemRoot") ?: "C:\\Windows"
    private val CMD_EXE: String = "$SYS_ROOT\\System32\\cmd.exe"
    private val POWERSHELL_EXE: String = "$SYS_ROOT\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

    // EJECUTABLES con ruta absoluta (evita problemas de PATH en .msi/.exe)
    private val TASKLIST_EXE: String = preferFullPath("$SYS_ROOT\\System32\\tasklist.exe", "tasklist.exe")
    private val WMIC_EXE:     String = preferFullPath("$SYS_ROOT\\System32\\wbem\\wmic.exe", "wmic.exe")
    private val TYPEPERF_EXE: String = preferFullPath("$SYS_ROOT\\System32\\typeperf.exe", "typeperf.exe")

    private fun preferFullPath(path: String, fallback: String): String =
        runCatching { Files.exists(Paths.get(path)) }.getOrDefault(false).let { if (it) path else fallback }

    /* ===== Caches ===== */
    private companion object {
        private const val USER_TTL_MS = 60_000L
        private const val CPU_TTL_MS_BASE  = 1_500L
        private const val CPU_TTL_MS_FAST  = 2_500L

        private const val EXE_TTL_MS = 60_000L   // cache de rutas/WS 60s
    }

    @Volatile private var userCache: Map<Long, String> = emptyMap()
    @Volatile private var statusCache: Map<Long, String> = emptyMap()
    @Volatile private var userCacheAt: Long = 0L

    @Volatile private var cpuCache: Map<Long, Double> = emptyMap()
    @Volatile private var cpuCacheAt: Long = 0L

    // Cache para ExecutablePath/WorkingSet (para mostrar Ruta en FAST_MODE)
    @Volatile private var exeCache: Map<Long, EnrichedRow> = emptyMap()
    @Volatile private var exeCacheAt: Long = 0L

    // --- Suavizado (EMA) para la gráfica/summary ---
    @Volatile private var cpuEma: Double? = null
    @Volatile private var memEma: Double? = null
    private fun ema(curr: Double, last: Double?, alpha: Double = 0.30): Double =
        last?.let { alpha * curr + (1 - alpha) * it } ?: curr

    private fun now() = System.currentTimeMillis()
    private fun userCacheExpired() = (now() - userCacheAt) > USER_TTL_MS
    private fun cpuCacheValid(): Boolean {
        val ttl = if (FAST_MODE) CPU_TTL_MS_FAST else CPU_TTL_MS_BASE
        return (now() - cpuCacheAt) <= ttl && cpuCache.isNotEmpty()
    }
    private fun exeCacheExpired() = (now() - exeCacheAt) > EXE_TTL_MS

    /* ============================ API ============================ */

    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> = runCatching {
        coroutineScope {
            Perf.mark("listProcesses/start")

            val cpuMapDeferred = async(Dispatchers.IO) {
                if (cpuCacheValid()) cpuCache else readCpuSample().also { cpuCache = it; cpuCacheAt = now() }
            }

            val usersDeferred = async(Dispatchers.IO) {
                if (userCacheExpired()) {
                    val (u, s) = readUsersAndStatusFromTasklistV()
                    if (u.isNotEmpty() || s.isNotEmpty()) {
                        userCache = u; statusCache = s; userCacheAt = now()
                    }
                }
                userCache
            }

            val taskRows = withContext(Dispatchers.IO) { readTasklistCsv() }
            if (taskRows.isEmpty()) return@coroutineScope emptyList<ProcessInfo>()

            val totalRam = totalRamBytes().coerceAtLeast(1L).toDouble()

            // Enriquecido:
            // - FAST_MODE: carga/renueva cache cada 60s; el resto del tiempo usa exeCache
            // - Normal: usa WMIC y, si vacío, CIM (PS)
            val enrichMap: Map<Long, EnrichedRow> = withContext(Dispatchers.IO) {
                if (FAST_MODE) {
                    if (exeCacheExpired()) {
                        val m = readWmicCsvExecutablePath()
                        if (m.isNotEmpty()) { exeCache = m; exeCacheAt = now() }
                    }
                    exeCache
                } else {
                    val wmicMap = readWmicCsvExecutablePath()
                    if (wmicMap.isNotEmpty()) wmicMap else readWmiCsvExecutablePath()
                }
            }

            val cpuMap = cpuMapDeferred.await()
            val users  = usersDeferred.await()

            val items = taskRows.map { t ->
                val enrich = enrichMap[t.pid]

                val memPctFromTask = (t.memKb * 1024.0 / totalRam) * 100.0
                val memPctRaw      = enrich?.workingSet?.let { (it / totalRam) * 100.0 } ?: memPctFromTask
                val memPct         = kotlin.math.round(memPctRaw * 10.0) / 10.0

                val cpuPct = cpuMap[t.pid] ?: 0.0
                val user   = users[t.pid] ?: enrich?.user ?: "?"
                val cmd    = normalizeWinPath(enrich?.command)

                val status = statusCache[t.pid]?.lowercase()
                val state  = when {
                    status?.contains("running") == true -> ProcState.RUNNING
                    else -> if (cpuPct > 0.0) ProcState.RUNNING else ProcState.OTHER
                }

                ProcessInfo(
                    pid = t.pid,
                    name = t.name,
                    user = user,
                    cpuPercent = cpuPct,
                    memPercent = memPct,
                    state = state,
                    command = cmd
                )
            }

            applyFilters(items, filters).sortedBy { it.name.lowercase() }
        }
    }

    override suspend fun kill(pid: Long): Result<Unit> = runCatching {
        val cmd = preferFullPath(CMD_EXE, "cmd.exe")
        val p = ProcessBuilder(cmd, "/c", "taskkill /PID $pid /F")
            .redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        if (p.waitFor() != 0) error("taskkill no disponible o denegado")
        Unit
    }

    @Suppress("DEPRECATION")
    override suspend fun summary(): Result<SystemSummary> = runCatching {
        // 1) MXBean cuando está disponible y da un valor válido
        val hasMx = runCatching { Class.forName("java.lang.management.ManagementFactory"); true }
            .getOrElse { false }

        if (hasMx) {
            val mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    as? com.sun.management.OperatingSystemMXBean

            if (mx != null) {
                val cpuMx = (mx.systemCpuLoad ?: -1.0) * 100.0
                if (cpuMx >= 0.0) {
                    val total = mx.totalPhysicalMemorySize.toDouble()
                    val free  = mx.freePhysicalMemorySize.toDouble()
                    val memPct = if (total > 0) ((total - free) / total) * 100.0 else 0.0
                    val cpuSm = ema(cpuMx.coerceIn(0.0, 100.0), cpuEma)
                    val memSm = ema(memPct.coerceIn(0.0, 100.0), memEma, alpha = 0.25)
                    cpuEma = cpuSm; memEma = memSm
                    return@runCatching SystemSummary(
                        totalCpuPercent = cpuSm,
                        totalMemPercent = memSm
                    )
                }
            }
        }

        // 2) typeperf (rápido) / 3) wmic / 4) PowerShell (si no es FAST_MODE)
        val cpuDirect = if (FAST_MODE) {
            readCpuTotalViaTypeperf(samples = 3)       // <-- promedio 3 muestras
        } else {
            readCpuTotalAny()
        }

        // 5) Último recurso: suma de procesos
        val cpuFallback = cpuDirect ?: sumPerProcessCpu()

        val mem = readMemPercentViaWmic() ?: 0.0

        // Suavizado final para UI
        val cpuSm = ema(cpuFallback.coerceIn(0.0, 100.0), cpuEma)
        val memSm = ema(mem.coerceIn(0.0, 100.0), memEma, alpha = 0.25)
        cpuEma = cpuSm; memEma = memSm

        SystemSummary(
            totalCpuPercent = cpuSm,
            totalMemPercent = memSm
        )
    }

    /** Fallback: estima CPU total sumando el % de cada proceso y capando a 100. */
    private fun sumPerProcessCpu(): Double {
        val map = readCpuSample()
        val sum = map.values.sum()
        return when {
            sum.isNaN() -> 0.0
            sum < 0.0   -> 0.0
            else        -> sum.coerceAtMost(100.0)
        }
    }

    /* ===================== Implementación ===================== */

    private data class TaskRow(val pid: Long, val name: String, val memKb: Long)
    private data class EnrichedRow(val pid: Long, val workingSet: Double, val command: String?, val user: String)

    /* ---------- CMD helper robusto ---------- */
    private data class CmdResult(val out: String, val exit: Int)

    private fun runCmdRobust(line: String): CmdResult {
        val cmd = preferFullPath(CMD_EXE, "cmd.exe")

        // En FAST_MODE probamos 1 vez directo (sin chcp) para ahorrar coste
        if (FAST_MODE) {
            val p = ProcessBuilder(cmd, "/c", line).redirectErrorStream(true).start()
            val bytes = p.inputStream.readAllBytes()
            val exit = p.waitFor()
            val text = String(bytes, Charsets.UTF_8).ifBlank { String(bytes) }.trim()
            if (exit == 0 && text.isNotBlank()) return CmdResult(text, 0)
            if (!NO_LOGS) logDiag("CMD (FAST) fallo exit=$exit\nLINE:\n$line\nOUT:\n$text")
            return CmdResult("", 1)
        }

        // Modo normal: 2 intentos (con chcp y sin chcp)
        val attempts = listOf(
            "chcp 65001>nul & $line",
            line
        )

        for (attempt in attempts) {
            val p = ProcessBuilder(cmd, "/c", attempt).redirectErrorStream(true).start()
            val bytes = p.inputStream.readAllBytes()
            val exit = p.waitFor()

            var text = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrElse { "" }
            if (text.isBlank() || text.length < 5) {
                text = String(bytes).trim()
            }
            if (exit == 0 && text.isNotBlank()) return CmdResult(text, 0)

            if (!NO_LOGS) logDiag("CMD intento fallido (exit=$exit)\nLINE:\n$attempt\nOUT:\n$text")
        }
        return CmdResult("", 1)
    }

    /* ---------- Lecturas por comandos ---------- */

    private fun readTasklistCsv(): List<TaskRow> {
        val (text, exit) = runCmdRobust("\"$TASKLIST_EXE\" /FO CSV /NH")
        if (exit != 0 || text.isBlank()) return emptyList()
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { ln ->
            val cols = splitCsvLine(ln, ',')
            if (cols.size < 5) return@mapNotNull null
            val name = cols[0].trim('"')
            val pid  = cols[1].trim('"').toLongOrNull() ?: return@mapNotNull null
            val kb   = cols[4].filter { it.isDigit() }.toLongOrNull() ?: 0L
            TaskRow(pid, name, kb)
        }.toList()
    }

    private fun readUsersAndStatusFromTasklistV(): Pair<Map<Long, String>, Map<Long, String>> {
        val (text, exit) = runCmdRobust("\"$TASKLIST_EXE\" /V /FO CSV /NH")
        if (exit != 0 || text.isBlank()) return emptyMap<Long, String>() to emptyMap()

        val users = HashMap<Long, String>()
        val status = HashMap<Long, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { ln ->
            val c = splitCsvLine(ln, ',')
            if (c.size < 8) return@forEach
            val pid = c[1].trim('"').toLongOrNull() ?: return@forEach
            val st  = c[5].trim('"')
            val usr = c[6].trim('"')
            if (usr.isNotBlank() && !usr.equals("N/A", true)) users[pid] = usr
            if (st.isNotBlank() && !st.equals("N/A", true))   status[pid] = st
        }
        return users to status
    }

    private fun readWmicCsvExecutablePath(): Map<Long, EnrichedRow> {
        val (text, exit) = runCmdRobust("\"$WMIC_EXE\" process get ProcessId,ExecutablePath,WorkingSetSize /FORMAT:CSV")
        if (exit != 0 || text.isBlank()) return emptyMap()

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()
        val header = splitCsvLine(lines.first(), ',')
        val pidIdx  = header.indexOfFirst { it.equals("ProcessId", true) }
        val pathIdx = header.indexOfFirst { it.equals("ExecutablePath", true) }
        val wsIdx   = header.indexOfFirst { it.equals("WorkingSetSize", true) }
        if (pidIdx == -1 || wsIdx == -1) return emptyMap()

        val map = HashMap<Long, EnrichedRow>()
        lines.drop(1).forEach { ln ->
            val c = splitCsvLine(ln, ',')
            val pid = c.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = c.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val exe = c.getOrNull(pathIdx)?.ifBlank { null }
            map[pid] = EnrichedRow(pid, ws, exe, user = "?")
        }
        return map
    }

    /** Fallback CIM (PS) sólo si WMIC no dio datos y NO estamos en FAST_MODE */
    private fun readWmiCsvExecutablePath(): Map<Long, EnrichedRow> {
        if (FAST_MODE) return emptyMap()

        val psBody = """
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
        Get-CimInstance Win32_Process |
          Select-Object ProcessId, ExecutablePath, @{n='WS';e=${'$'}_.WorkingSetSize} |
          ConvertTo-Csv -NoTypeInformation -Delimiter '§'
    """.trimIndent()

        val csv = runPsUtf8(psBody).trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), '§')
        val pidIdx  = header.indexOfFirst { it.equals("ProcessId", true) }
        val wsIdx   = header.indexOfFirst { it.equals("WS", true) }
        val pathIdx = header.indexOfFirst { it.equals("ExecutablePath", true) }
        if (pidIdx == -1 || wsIdx == -1) return emptyMap()

        val map = HashMap<Long, EnrichedRow>()
        lines.drop(1).forEach { ln ->
            val c = splitCsvLine(ln, '§')
            val pid = c.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = c.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val exe = c.getOrNull(pathIdx)?.ifBlank { null }
            map[pid] = EnrichedRow(pid, ws, exe, user = "?")
        }
        return map
    }


    /* ---------- CPU% por proceso ---------- */
    private fun readCpuSample(): Map<Long, Double> {
        val (text, exit) = runCmdRobust("\"$WMIC_EXE\" path Win32_PerfFormattedData_PerfProc_Process get IDProcess,PercentProcessorTime /FORMAT:CSV")
        if (exit != 0 || text.isBlank()) return emptyMap()

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), ',')
        val pidIdx = header.indexOfFirst { it.equals("IDProcess", true) }
        val cpuIdx = header.indexOfFirst { it.equals("PercentProcessorTime", true) }
        if (pidIdx == -1 || cpuIdx == -1) return emptyMap()

        val out = HashMap<Long, Double>()
        lines.drop(1).forEach { ln ->
            val c = splitCsvLine(ln, ',')
            val pid = c.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            if (pid == 0L) return@forEach
            val cpu = c.getOrNull(cpuIdx)?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
            out[pid] = cpu
        }
        return out
    }

    /* ---------- Fallbacks resumen sin MXBean ---------- */
    private fun readMemPercentViaWmic(): Double? {
        val (text, exit) = runCmdRobust("\"$WMIC_EXE\" OS get TotalVisibleMemorySize,FreePhysicalMemory /value")
        if (exit != 0 || text.isBlank()) return null
        var total: Long? = null
        var free: Long?  = null
        text.lines().forEach { ln ->
            val p = ln.split('=', limit = 2)
            if (p.size == 2) when (p[0].trim().lowercase()) {
                "totalvisiblememorysize" -> total = p[1].trim().toLongOrNull()
                "freephysicalmemory"     -> free  = p[1].trim().toLongOrNull()
            }
        }
        val t = total ?: return null
        val f = free ?: return null
        if (t <= 0) return 0.0
        return ((t - f).toDouble() / t.toDouble()) * 100.0
    }

    // --- PROMEDIO de varias muestras con typeperf ---
    private fun readCpuTotalViaTypeperf(samples: Int = 3, intervalSec: Int = 1): Double? {
        val cmd = "\"$TYPEPERF_EXE\" \"\\Processor(_Total)\\% Processor Time\" -si $intervalSec -sc $samples"
        val (text, exit) = runCmdRobust(cmd)
        if (exit != 0 || text.isBlank()) return null

        val values = text.lineSequence()
            .map { it.substringAfterLast(',').replace("\"", "").trim().replace(',', '.') }
            .mapNotNull { it.toDoubleOrNull() }
            .toList()

        if (values.isEmpty()) return null
        return values.average().coerceIn(0.0, 100.0)
    }

    /** CPU total [%] por la vía más disponible: typeperf -> wmic -> PowerShell (evitado en FAST_MODE) */
    private fun readCpuTotalAny(): Double? {
        // 1) typeperf con promedio de 3
        runCmdRobust("\"$TYPEPERF_EXE\" \"\\Processor(_Total)\\% Processor Time\" -si 1 -sc 3").let { (text, exit) ->
            if (exit == 0 && text.isNotBlank()) {
                val values = text.lineSequence()
                    .map { it.substringAfterLast(',').replace("\"", "").trim().replace(',', '.') }
                    .mapNotNull { it.toDoubleOrNull() }
                    .toList()
                if (values.isNotEmpty()) return values.average().coerceIn(0.0, 100.0)
            }
        }
        // 2) wmic
        runCmdRobust("\"$WMIC_EXE\" cpu get LoadPercentage /value").let { (text, exit) ->
            if (exit == 0 && text.isNotBlank()) {
                val v = text.lineSequence()
                    .firstOrNull { it.startsWith("LoadPercentage", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.toDoubleOrNull()
                if (v != null) return v.coerceIn(0.0, 100.0)
            }
        }
        // 3) PowerShell Get-Counter (solo si NO es FAST_MODE)
        if (!FAST_MODE) {
            val ps = """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
                (Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 1).
                  CounterSamples[0].CookedValue
            """.trimIndent()
            runPsUtf8(ps).let { out ->
                val v = out.trim().replace(',', '.').toDoubleOrNull()
                if (v != null) return v.coerceIn(0.0, 100.0)
            }
        }
        return null
    }

    /* ---------- PowerShell (sólo para fallback CIM) ---------- */
    private fun runPsUtf8(body: String): String {
        val psPath = preferFullPath(POWERSHELL_EXE, "powershell.exe")
        val p = ProcessBuilder(
            psPath, "-NoLogo", "-NonInteractive", "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-Command", body
        ).redirectErrorStream(true).start()
        val bytes = p.inputStream.readAllBytes()
        val out = String(bytes, Charsets.UTF_8).replace("\uFEFF", "")
        val exit = p.waitFor()
        if (exit != 0 || out.isBlank()) {
            if (!NO_LOGS) logDiag("PowerShell exit=$exit\nBODY:\n$body\nOUT:\n$out")
        }
        return out
    }

    /* ============================ Utils ============================ */

    private fun splitCsvLine(line: String, delim: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2; continue }
                    inQuotes = !inQuotes; i++; continue
                }
                ch == delim && !inQuotes -> { result += sb.toString(); sb.setLength(0); i++; continue }
                else -> { sb.append(ch); i++ }
            }
        }
        result += sb.toString()
        return result.map { it.trim('"') }
    }

    private fun applyFilters(items: List<ProcessInfo>, f: Filters?): List<ProcessInfo> {
        if (f == null) return items
        return items.filter { p ->
            (f.name.isNullOrBlank() || p.name.contains(f.name!!, true) || (p.command?.contains(f.name!!, true) == true)) &&
                    (f.user.isNullOrBlank() || p.user.contains(f.user!!, true)) &&
                    (f.state == null || p.state == f.state)
        }
    }

    private fun totalRamBytes(): Long {
        // Intento 1: usar ManagementFactory si el módulo existe
        val viaMx = runCatching {
            Class.forName("java.lang.management.ManagementFactory")
            val mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    as? com.sun.management.OperatingSystemMXBean
            mx?.totalPhysicalMemorySize ?: 0L
        }.getOrNull()

        if (viaMx != null && viaMx > 0L) return viaMx

        // Intento 2: WMIC (TotalVisibleMemorySize viene en KB)
        val (text, exit) = runCmdRobust("\"$WMIC_EXE\" OS get TotalVisibleMemorySize /value")
        if (exit == 0 && text.isNotBlank()) {
            val kb = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("TotalVisibleMemorySize", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.toLongOrNull()
            if (kb != null && kb > 0L) return kb * 1024L
        }

        // Fallback final
        return 0L
    }

    private fun normalizeWinPath(raw: String?): String? = raw?.let {
        it.replaceFirst(Regex("""^\\\\\?\\UNC\\"""),
            """\\""")
            .replaceFirst(Regex("""^\\\\\?\\"""),
                "")
    }

    /* ---------- Log %TEMP% ---------- */
    private fun logDiag(msg: String) {
        if (NO_LOGS) return
        runCatching {
            val tmp = System.getProperty("java.io.tmpdir")
            val path = Paths.get(tmp, "MonitorDeProcesos.log")
            val stamp = LocalDateTime.now()
            val full = "[$stamp] $msg\n\n"
            Files.write(path, full.toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
