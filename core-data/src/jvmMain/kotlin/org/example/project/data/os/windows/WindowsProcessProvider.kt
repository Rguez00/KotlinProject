package org.example.project.data.os.windows

import org.example.project.*
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.round

class WindowsProcessProvider : ProcessProvider, SystemInfoProvider {

    /* ===================== API ===================== */

    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> = runCatching {
        // 1) Base robusta: tasklist (siempre funciona). Trae PID, Nombre y Memoria (KB).
        val taskRows = readTasklistCsv() // pid, name, memKb
        if (taskRows.isEmpty()) return@runCatching emptyList<ProcessInfo>()

        val totalRam = totalRamBytes().coerceAtLeast(1L).toDouble()

        // 2) Enriquecimientos (tolerantes a fallo)
        val wmiMap   = readWmiCsv()                              // PID -> (WS bytes, user, cmd)
        val wmicMap  = if (wmiMap.isEmpty()) readWmicCsv() else emptyMap() // fallback WMI
        val cpuMap   = readCpuSample()                           // PID -> cpu%
        val userMapWmi = readUserMap()                           // PID -> user (GetOwner)
        val userMapTasklistV = readUsersFromTasklistV()          // PID -> user (tasklist /V)

        // 3) Merge
        val items = taskRows.map { t ->
            val w = wmiMap[t.pid]
            val m = w ?: wmicMap[t.pid]

            val memPctFromTask = (t.memKb * 1024.0 / totalRam) * 100.0
            val memPctRaw = m?.workingSet?.let { (it / totalRam) * 100.0 } ?: memPctFromTask
            val memPct = round(memPctRaw * 10.0) / 10.0

            val cpuPct = cpuMap[t.pid] ?: 0.0

            // Prioridad de usuario: tasklist /V -> GetOwner() -> WMI/WMIC -> "?"
            val user = userMapTasklistV[t.pid]
                ?: userMapWmi[t.pid]
                ?: m?.user
                ?: "?"

            // Limpia prefijo \\??\ en algunas rutas de CommandLine
            val cmdRaw = m?.command
            val cmd = cmdRaw?.replaceFirst("^\\\\\\\\\\?\\\\\\\\".toRegex(), "")

            val state = if (cpuPct > 0.0) ProcState.RUNNING else ProcState.OTHER

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

    override suspend fun kill(pid: Long): Result<Unit> = runCatching {
        val exit = ProcessBuilder("cmd", "/c", "taskkill /PID $pid /F")
            .redirectErrorStream(true).start().waitFor()
        if (exit != 0) error("taskkill devolvió código $exit")
        Unit
    }

    @Suppress("DEPRECATION")
    override suspend fun summary(): Result<SystemSummary> = runCatching {
        val mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean

        val cpu = (mx?.systemCpuLoad ?: 0.0) * 100.0
        val total = mx?.totalPhysicalMemorySize?.toDouble() ?: 0.0
        val free  = mx?.freePhysicalMemorySize?.toDouble() ?: 0.0
        val memPct = if (total > 0) ((total - free) / total) * 100.0 else 0.0

        SystemSummary(totalCpuPercent = max(0.0, cpu), totalMemPercent = max(0.0, memPct))
    }

    /* ===================== Implementación ===================== */

    /** tasklist mínima. */
    private data class TaskRow(val pid: Long, val name: String, val memKb: Long)

    /** Fila enriquecida (WMI/WMIC). */
    private data class EnrichedRow(val pid: Long, val workingSet: Double, val command: String?, val user: String)

    /** 1) TASKLIST CSV — siempre disponible. */
    private fun readTasklistCsv(): List<TaskRow> {
        val p = ProcessBuilder("cmd", "/c", "tasklist /FO CSV /NH")
            .redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("Cp1252")).trim()
        p.waitFor()

        // "Image Name","PID","Session Name","Session#","Mem Usage"
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val cols = splitCsvLine(line, ',')
            if (cols.size < 5) return@mapNotNull null
            val name = cols[0].trim('"')
            val pid  = cols[1].trim('"').toLongOrNull() ?: return@mapNotNull null
            val memStr = cols[4].trim('"') // "12.345 K" / "12,345 K"
            val kb = memStr.filter { it.isDigit() }.toLongOrNull() ?: 0L
            TaskRow(pid, name, kb)
        }.toList()
    }

    /** 2a) WMI vía PowerShell (UTF-8 forzado) — WorkingSetSize, CommandLine, User. */
    private fun readWmiCsv(): Map<Long, EnrichedRow> {
        val psBody = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
            Get-WmiObject Win32_Process |
              Select-Object ProcessId, Name, CommandLine,
                @{n='WS';e={${'$'}_.WorkingSetSize}},
                @{n='User';e={ ${'$'}o=${'$'}_.GetOwner(); if ( ${'$'}o ) {"${'$'}(${ '$' }o.Domain)\${'$'}(${ '$' }o.User)"} else {'?'} }} |
              ConvertTo-Csv -NoTypeInformation -Delimiter '§'
        """.trimIndent()

        val csv = runPsUtf8(psBody).trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), '§')
        val pidIdx = header.indexOfFirst { it.equals("ProcessId", true) }
        val wsIdx  = header.indexOfFirst { it.equals("WS", true) }
        val cmdIdx = header.indexOfFirst { it.equals("CommandLine", true) }
        val usrIdx = header.indexOfFirst { it.equals("User", true) }
        if (pidIdx == -1 || wsIdx == -1) return emptyMap()

        val map = HashMap<Long, EnrichedRow>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = cols.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val cmd = cols.getOrNull(cmdIdx)?.ifBlank { null }
            val usr = cols.getOrNull(usrIdx)?.ifBlank { "?" } ?: "?"
            map[pid] = EnrichedRow(pid, ws, cmd, usr)
        }
        return map
    }

    /** 2b) WMIC CSV (fallback si WMI falla o viene vacío). */
    private fun readWmicCsv(): Map<Long, EnrichedRow> {
        // WMIC devuelve CSV con cabecera "Node,Caption,CommandLine,ProcessId,WorkingSetSize"
        val p = ProcessBuilder("cmd", "/c", "wmic process get ProcessId,Caption,CommandLine,WorkingSetSize /FORMAT:CSV")
            .redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("Cp1252")).trim()
        p.waitFor()
        if (text.isBlank()) return emptyMap()

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()
        val header = splitCsvLine(lines.first(), ',')
        val pidIdx = header.indexOfFirst { it.equals("ProcessId", true) }
        val cmdIdx = header.indexOfFirst { it.equals("CommandLine", true) }
        val wsIdx  = header.indexOfFirst { it.equals("WorkingSetSize", true) }
        if (pidIdx == -1 || wsIdx == -1) return emptyMap()

        val map = HashMap<Long, EnrichedRow>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, ',')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = cols.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val cmd = cols.getOrNull(cmdIdx)?.ifBlank { null }
            map[pid] = EnrichedRow(pid, ws, cmd, user = "?")
        }
        return map
    }

    /** 2c) Usuario por PID desde tasklist /V (columna "User Name"). */
    private fun readUsersFromTasklistV(): Map<Long, String> {
        val p = ProcessBuilder("cmd", "/c", "tasklist /V /FO CSV /NH")
            .redirectErrorStream(true)
            .start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("Cp1252")).trim()
        p.waitFor()
        if (text.isBlank()) return emptyMap()

        // "Image Name","PID","Session Name","Session#","Mem Usage","Status","User Name","CPU Time","Window Title"
        val map = HashMap<Long, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val cols = splitCsvLine(line, ',')
            if (cols.size < 7) return@forEach
            val pid  = cols[1].trim('"').toLongOrNull() ?: return@forEach
            val user = cols[6].trim('"')
            if (user.isNotBlank() && !user.equals("N/A", true)) {
                map[pid] = user
            }
        }
        return map
    }

    /** Mapa PID -> "DOMINIO\usuario" usando GetOwner(); */
    private fun readUserMap(): Map<Long, String> {
        val ps = """
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
        Get-CimInstance Win32_Process | ForEach-Object {
          ${'$'}o = ${'$'}_.GetOwner();
          ${'$'}user = if (${'$'}o) { "${'$'}(${'$'}o.Domain)\${'$'}(${'$'}o.User)" } else { "" };
          [pscustomobject]@{ Id = ${'$'}_.ProcessId; User = ${'$'}user }
        } | ConvertTo-Csv -NoTypeInformation -Delimiter '§'
    """.trimIndent()

        val csv = runPsUtf8(ps).trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), '§')
        val idIdx  = header.indexOfFirst { it.equals("Id", true) }
        val usrIdx = header.indexOfFirst { it.equals("User", true) }
        if (idIdx == -1 || usrIdx == -1) return emptyMap()

        val map = HashMap<Long, String>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid  = cols.getOrNull(idIdx)?.toLongOrNull() ?: return@forEach
            val user = cols.getOrNull(usrIdx)?.takeIf { it.isNotBlank() } ?: return@forEach
            map[pid] = user
        }
        return map
    }

    /** CPU% por proceso normalizada 0–100: dividimos por núcleos lógicos y filtramos _Total/Idle. */
    private fun readCpuSample(): Map<Long, Double> {
        val ps = """
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
        ${'$'}cores = [int](Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors;

        Get-CimInstance Win32_PerfFormattedData_PerfProc_Process |
          Where-Object { ${'$'}_.IDProcess -ne ${'$'}null -and ${'$'}_.Name -ne '_Total' } |
          Select-Object IDProcess,
            @{ n='CPU'; e = {
                  if ( ${'$'}cores -gt 0 ) {
                      [math]::Min(100, [math]::Max(0, ( ${'$'}_.PercentProcessorTime / ${'$'}cores )))
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
            if (pid == 0L) return@forEach  // ignora System Idle Process
            val cpu = cols.getOrNull(cpuIdx)?.toDoubleOrNull() ?: 0.0
            out[pid] = kotlin.math.round(cpu * 10.0) / 10.0
        }
        return out
    }




    /** Ejecuta PowerShell con salida UTF-8 (evita CSV vacíos por locale/BOM). */
    private fun runPsUtf8(body: String): String {
        val p = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-Command", body
        ).redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("UTF-8"))
        p.waitFor()
        return text.replace("\uFEFF", "")
    }

    /* ===================== Utilidades ===================== */

    /** CSV parser con comillas y delimitador configurable. */
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
