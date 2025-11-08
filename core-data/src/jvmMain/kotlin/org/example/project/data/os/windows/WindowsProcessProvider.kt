package org.example.project.data.os.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.example.project.*
import org.example.project.data.perf.Perf
import java.nio.charset.Charset
import kotlin.math.max

class WindowsProcessProvider : ProcessProvider, SystemInfoProvider {

    /* ===================== Cachés ligeras ===================== */
    private companion object {
        private const val USER_TTL_MS = 60_000L      // cache usuarios/estado
        private const val CPU_TTL_MS  = 1_500L       // cache cpu%
    }

    @Volatile private var userCache: Map<Long, String> = emptyMap()
    @Volatile private var statusCache: Map<Long, String> = emptyMap()
    @Volatile private var userCacheAt: Long = 0L

    @Volatile private var cpuCache: Map<Long, Double> = emptyMap()
    @Volatile private var cpuCacheAt: Long = 0L

    private fun now() = System.currentTimeMillis()
    private fun userCacheExpired() = (now() - userCacheAt) > USER_TTL_MS
    private fun cpuCacheValid()   = (now() - cpuCacheAt) <= CPU_TTL_MS && cpuCache.isNotEmpty()

    /* ============================  API  ============================ */

    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> = runCatching {
        coroutineScope {
            Perf.mark("listProcesses/start")

            // SIEMPRE queremos usuario y ruta para completar columnas
            val needUsers = true
            val needWmi   = true

            // CPU en paralelo (con caché)
            val cpuMapDeferred = async(Dispatchers.IO) {
                Perf.time("cpu% sample") {
                    if (cpuCacheValid()) cpuCache
                    else {
                        val m = readCpuSample()
                        cpuCache = m; cpuCacheAt = now()
                        m
                    }
                }
            }

            // Usuarios + Status (una sola pasada de tasklist /V) con caché
            val usersDeferred = async(Dispatchers.IO) {
                if (needUsers && userCacheExpired()) {
                    val (users, status) = Perf.time("tasklist /V users+status") { readUsersAndStatusFromTasklistV() }
                    if (users.isNotEmpty() || status.isNotEmpty()) {
                        userCache   = users
                        statusCache = status
                        userCacheAt = now()
                    }
                }
                userCache
            }

            // Base: tasklist
            val taskRows = Perf.time("tasklist base") {
                withContext(Dispatchers.IO) { readTasklistCsv() }
            }
            Perf.size("tasklist base", taskRows)
            if (taskRows.isEmpty()) return@coroutineScope emptyList<ProcessInfo>()

            val totalRam = totalRamBytes().coerceAtLeast(1L).toDouble()

            // Enriquecimientos solo si hace falta (ruta + working set exacto)
            val wmiMap = if (needWmi) Perf.time("WMI base") {
                withContext(Dispatchers.IO) { readWmiCsvExecutablePath() }
            } else emptyMap()

            val wmicMap = if (needWmi && wmiMap.isEmpty()) Perf.time("WMIC fallback") {
                withContext(Dispatchers.IO) { readWmicCsvExecutablePath() }
            } else emptyMap()

            // Espera a CPU/usuarios
            val cpuMap = cpuMapDeferred.await()
            val users  = usersDeferred.await()

            Perf.size("cpu map", cpuMap)
            Perf.size("users map", users)
            Perf.size("wmi data", wmiMap)
            Perf.size("wmic data", wmicMap)

            // Merge
            val items = Perf.time("merge") {
                taskRows.map { t ->
                    val w = wmiMap[t.pid]
                    val m = w ?: wmicMap[t.pid]

                    val memPctFromTask = (t.memKb * 1024.0 / totalRam) * 100.0
                    val memPctRaw      = m?.workingSet?.let { (it / totalRam) * 100.0 } ?: memPctFromTask
                    val memPct         = kotlin.math.round(memPctRaw * 10.0) / 10.0

                    val cpuPct = cpuMap[t.pid] ?: 0.0
                    val user   = users[t.pid] ?: m?.user ?: "?"
                    val cmdRaw = m?.command
                    val cmd    = cmdRaw?.replaceFirst("^\\\\\\\\\\?\\\\\\\\".toRegex(), "")

                    // Estado: prioriza "Status" de tasklist /V; si no, fallback a CPU>0
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
            }
            Perf.size("merged list", items)
            Perf.mark("listProcesses/end")

            applyFilters(items, filters).sortedBy { it.name.lowercase() }
        }
    }

    override suspend fun kill(pid: Long): Result<Unit> = runCatching {
        val p = ProcessBuilder("cmd", "/c", "taskkill /PID $pid /F")
            .redirectErrorStream(true)
            .start()
        p.inputStream.readAllBytes() // consume para evitar bloqueo
        val exit = p.waitFor()
        if (exit != 0) error("taskkill devolvió código $exit")
        Unit
    }

    @Suppress("DEPRECATION")
    override suspend fun summary(): Result<SystemSummary> = runCatching {
        val mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean

        val cpu   = (mx?.systemCpuLoad ?: 0.0) * 100.0
        val total = mx?.totalPhysicalMemorySize?.toDouble() ?: 0.0
        val free  = mx?.freePhysicalMemorySize?.toDouble() ?: 0.0
        val memPct = if (total > 0) ((total - free) / total) * 100.0 else 0.0

        SystemSummary(totalCpuPercent = max(0.0, cpu), totalMemPercent = max(0.0, memPct))
    }

    /* ===================== Implementación ===================== */

    private data class TaskRow(val pid: Long, val name: String, val memKb: Long)
    private data class EnrichedRow(val pid: Long, val workingSet: Double, val command: String?, val user: String)

    private fun readTasklistCsv(): List<TaskRow> {
        val p = ProcessBuilder("cmd", "/c", "tasklist /FO CSV /NH")
            .redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("windows-1252")).trim()
        p.waitFor()
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val cols = splitCsvLine(line, ',')
            if (cols.size < 5) return@mapNotNull null
            val name = cols[0].trim('"')
            val pid  = cols[1].trim('"').toLongOrNull() ?: return@mapNotNull null
            val memStr = cols[4].trim('"')                 // "12.345 K" o "12,345 K"
            val kb = memStr.filter { it.isDigit() }.toLongOrNull() ?: 0L
            TaskRow(pid, name, kb)
        }.toList()
    }

    /** Usuarios + Status (una sola pasada) */
    private fun readUsersAndStatusFromTasklistV(): Pair<Map<Long, String>, Map<Long, String>> {
        val p = ProcessBuilder("cmd", "/c", "tasklist /V /FO CSV /NH")
            .redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("windows-1252")).trim()
        p.waitFor()
        if (text.isBlank()) return emptyMap<Long, String>() to emptyMap()

        val users  = HashMap<Long, String>()
        val status = HashMap<Long, String>()
        // "Image Name","PID","Session Name","Session#","Mem Usage","Status","User Name","CPU Time","Window Title"
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val cols = splitCsvLine(line, ',')
            if (cols.size < 8) return@forEach
            val pid  = cols[1].trim('"').toLongOrNull() ?: return@forEach
            val st   = cols[5].trim('"')
            val usr  = cols[6].trim('"')
            if (usr.isNotBlank() && !usr.equals("N/A", true)) users[pid] = usr
            if (st.isNotBlank() && !st.equals("N/A", true))   status[pid] = st
        }
        return users to status
    }

    /** WMI/CIM — WorkingSetSize + ExecutablePath (ruta) */
    private fun readWmiCsvExecutablePath(): Map<Long, EnrichedRow> {
        val psBody = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
            Get-CimInstance Win32_Process |
              Select-Object ProcessId, ExecutablePath, @{n='WS';e={${'$'}_.WorkingSetSize}} |
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
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = cols.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val exe = cols.getOrNull(pathIdx)?.ifBlank { null }
            map[pid] = EnrichedRow(pid, ws, exe, user = "?")
        }
        return map
    }

    /** WMIC fallback — ExecutablePath + WorkingSetSize (cuando CIM no da datos) */
    private fun readWmicCsvExecutablePath(): Map<Long, EnrichedRow> {
        val p = ProcessBuilder(
            "cmd", "/c",
            "wmic process get ProcessId,ExecutablePath,WorkingSetSize /FORMAT:CSV"
        ).redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("windows-1252")).trim()
        p.waitFor()
        if (text.isBlank()) return emptyMap()

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()
        val header = splitCsvLine(lines.first(), ',')
        val pidIdx  = header.indexOfFirst { it.equals("ProcessId", true) }
        val pathIdx = header.indexOfFirst { it.equals("ExecutablePath", true) }
        val wsIdx   = header.indexOfFirst { it.equals("WorkingSetSize", true) }
        if (pidIdx == -1 || wsIdx == -1) return emptyMap()

        val map = HashMap<Long, EnrichedRow>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, ',')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = cols.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val exe = cols.getOrNull(pathIdx)?.ifBlank { null }
            map[pid] = EnrichedRow(pid, ws, exe, user = "?")
        }
        return map
    }

    /** Muestra CPU% por proceso (corrigiendo el escape de '$' en PowerShell) */
    private fun readCpuSample(): Map<Long, Double> {
        val ps = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
            ${'$'}cores = [int](Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors

            Get-CimInstance Win32_PerfFormattedData_PerfProc_Process |
              Where-Object { ${'$'}_.IDProcess -ne ${'$'}null -and ${'$'}_.Name -ne '_Total' -and ${'$'}_.Name -ne 'Idle' } |
              Select-Object IDProcess,
                @{ n='CPU'; e = {
                      if ( ${'$'}cores -gt 0 ) {
                          [math]::Round([math]::Min(100, [math]::Max(0, ( ${'$'}_.PercentProcessorTime / ${'$'}cores ))), 1)
                      } else { 0 }
                  } } |
              ConvertTo-Csv -NoTypeInformation -Delimiter '§'
        """.trimIndent()

        val csv = runPsUtf8(ps).trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), '§')
        val pidIdx = header.indexOfFirst { it.equals("IDProcess", true) }
        val cpuIdx = header.indexOfFirst { it.equals("CPU", true) }
        if (pidIdx == -1 || cpuIdx == -1) return emptyMap()

        val out = HashMap<Long, Double>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            if (pid == 0L) return@forEach // Idle
            val cpu = cols.getOrNull(cpuIdx)?.toDoubleOrNull() ?: 0.0
            out[pid] = cpu
        }
        return out
    }

    private fun runPsUtf8(body: String): String {
        val p = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-Command", body
        ).redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charsets.UTF_8)
        p.waitFor()
        return text.replace("\uFEFF", "")
    }

    /* ============================ Utilidades ============================ */

    private fun splitCsvLine(line: String, delim: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i += 2; continue
                    }
                    inQuotes = !inQuotes; i++; continue
                }
                ch == delim && !inQuotes -> {
                    result += sb.toString(); sb.setLength(0); i++; continue
                }
                else -> { sb.append(ch); i++ }
            }
        }
        result += sb.toString()
        return result.map { it.trim('"') }
    }

    private fun applyFilters(items: List<ProcessInfo>, f: Filters?): List<ProcessInfo> {
        if (f == null) return items
        return items.filter { p ->
            (f.name.isNullOrBlank() || p.name.contains(f.name!!, ignoreCase = true) || (p.command?.contains(f.name!!, true) == true)) &&
                    (f.user.isNullOrBlank() || p.user.contains(f.user!!, ignoreCase = true)) &&
                    (f.state == null || p.state == f.state)
        }
    }

    private fun totalRamBytes(): Long {
        val mx = (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean)
        return mx?.totalPhysicalMemorySize ?: 0L
    }
}
