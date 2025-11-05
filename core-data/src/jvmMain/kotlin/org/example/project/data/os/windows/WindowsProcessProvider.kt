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
        val wmiMap  = readWmiCsv()              // PID -> (WS bytes, user, cmd)
        val wmicMap = if (wmiMap.isEmpty()) readWmicCsv() else emptyMap() // fallback
        val cpuMap  = readCpuSample()           // PID -> cpu%
        val userMap = readUserMap()             // PID -> user (IncludeUserName)

        // 3) Merge
        val items = taskRows.map { t ->
            val w = wmiMap[t.pid]
            val m = w ?: wmicMap[t.pid]

            val memPctFromTask = (t.memKb * 1024.0 / totalRam) * 100.0
            val memPct = m?.workingSet?.let { (it / totalRam) * 100.0 } ?: memPctFromTask

            val rawCpu = cpuMap[t.pid] ?: 0.0
            val cpuPct = round(rawCpu * 10.0) / 10.0

            // Prioridad usuario: IncludeUserName -> WMI/WMIC -> "?"
            val user = userMap[t.pid] ?: m?.user ?: "?"

            // Limpia prefijo \??\ en algunas rutas de CommandLine
            val cmd = m?.command?.replace("""^\?\?\\+""".toRegex(), "")

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

    /** 2c) Usuarios vía Get-Process -IncludeUserName (mejor cobertura que GetOwner en algunos casos). */
    private fun readUserMap(): Map<Long, String> {
        val ps = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
            Get-Process -IncludeUserName |
              Select-Object Id, UserName |
              ConvertTo-Csv -NoTypeInformation -Delimiter '§'
        """.trimIndent()
        val csv = runPsUtf8(ps).trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), '§')
        val idIdx  = header.indexOfFirst { it.equals("Id", true) }
        val usrIdx = header.indexOfFirst { it.equals("UserName", true) }
        if (idIdx == -1 || usrIdx == -1) return emptyMap()

        val map = HashMap<Long, String>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid = cols.getOrNull(idIdx)?.toLongOrNull() ?: return@forEach
            val user = cols.getOrNull(usrIdx)?.ifBlank { null } ?: return@forEach
            map[pid] = user
        }
        return map
    }

    /** 3) CPU%: muestreo de 1.5 s con Get-Process (CPU acumulada en segundos). */
    private fun readCpuSample(): Map<Long, Double> {
        val ps = """
            ${'$'}cores = [int](Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors;
            ${'$'}p1 = Get-Process | Select-Object Id, CPU;
            Start-Sleep -Milliseconds 1500;
            ${'$'}p2 = Get-Process | Select-Object Id, CPU;
            ${'$'}h = @{}; ${'$'}p1 | ForEach-Object { ${'$'}h[${'$'}_.Id] = ${'$'}_.CPU }
            ${'$'}result = foreach (${ '$' }p in ${ '$' }p2) {
              ${'$'}id = ${'$'}p.Id; ${'$'}c2 = ${'$'}p.CPU; ${'$'}c1 = ${'$'}h[${'$'}id];
              if ( ${'$'}c1 -ne ${'$'}null -and ${'$'}c2 -ne ${'$'}null ) {
                ${'$'}delta = ${'$'}c2 - ${'$'}c1;  # seg CPU en 1.5s
                ${'$'}pct = if ( ${'$'}delta -gt 0 ) { ( ${'$'}delta / 1.5 ) * 100 / ${'$'}cores } else { 0 };
                if ( ${'$'}pct -gt 0 -and ${'$'}pct -lt 0.1 ) { ${'$'}pct = 0.1 }  # mínimo visible
                [pscustomobject]@{ pid=${'$'}id; cpu=[math]::Round(${ '$' }pct,1) }
              }
            }
            ${'$'}result | ConvertTo-Csv -NoTypeInformation -Delimiter '§'
        """.trimIndent()

        val csv = runPsUtf8(ps).trim()
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyMap()

        val header = splitCsvLine(lines.first(), '§')
        val pidIdx = header.indexOfFirst { it.equals("pid", true) }
        val cpuIdx = header.indexOfFirst { it.equals("cpu", true) }
        if (pidIdx == -1 || cpuIdx == -1) return emptyMap()

        val map = HashMap<Long, Double>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val cpu = cols.getOrNull(cpuIdx)?.toDoubleOrNull() ?: 0.0
            map[pid] = cpu
        }
        return map
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
