package org.example.project.data.os.windows

import org.example.project.*
import java.nio.charset.Charset
import kotlin.math.max

class WindowsProcessProvider : ProcessProvider, SystemInfoProvider {

    /* ===================== API ===================== */

    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> = runCatching {
        // 1) Fuente base SIEMPRE disponible: tasklist (con memoria en KB)
        val taskRows = readTasklistCsv()  // pid, name, memKb
        val totalRam = totalRamBytes().coerceAtLeast(1L).toDouble()

        // 2) Enriquecemos con WMI (si falla, seguimos con lo de tasklist)
        val wmi = readWmiCsv() // pid -> WmiRow

        val items = taskRows.map { t ->
            // MEM% calculado siempre desde tasklist
            val memPctFromTasklist = (t.memKb * 1024.0 / totalRam) * 100.0

            // Si WMI está, podemos sustituir mem por WorkingSetSize (más exacto) y añadir user/cmd
            val w = wmi[t.pid]
            val memPct = w?.workingSet?.let { (it / totalRam) * 100.0 } ?: memPctFromTasklist

            ProcessInfo(
                pid = t.pid,
                name = t.name,
                user = w?.user ?: "?",       // si WMI no está, se queda '?'
                cpuPercent = 0.0,            // próximo paso: muestreo
                memPercent = memPct,
                state = ProcState.OTHER,
                command = w?.command
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

    @Suppress("DEPRECATION") // warnings del MXBean en algunos JDK
    override suspend fun summary(): Result<SystemSummary> = runCatching {
        val mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean

        val cpu = (mx?.systemCpuLoad ?: 0.0) * 100.0
        val total = mx?.totalPhysicalMemorySize?.toDouble() ?: 0.0
        val free  = mx?.freePhysicalMemorySize?.toDouble() ?: 0.0
        val memPct = if (total > 0) ((total - free) / total) * 100.0 else 0.0

        SystemSummary(
            totalCpuPercent = max(0.0, cpu),
            totalMemPercent = max(0.0, memPct)
        )
    }

    /* ===================== Implementación ===================== */

    /** Fila mínima procedente de tasklist. */
    private data class TaskRow(
        val pid: Long,
        val name: String,
        val memKb: Long
    )

    /** Fila enriquecida procedente de WMI. */
    private data class WmiRow(
        val pid: Long,
        val workingSet: Double, // bytes
        val command: String?,
        val user: String
    )

    /** Lee tasklist en CSV y devuelve filas con memoria en KB. Siempre debería funcionar. */
    private fun readTasklistCsv(): List<TaskRow> {
        val p = ProcessBuilder("cmd", "/c", "tasklist /FO CSV /NH")
            .redirectErrorStream(true).start()
        val text = String(p.inputStream.readAllBytes(), Charset.forName("Cp1252")).trim()
        p.waitFor()

        // "Image Name","PID","Session Name","Session#","Mem Usage"
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val cols = splitCsvLine(line, ',')
                if (cols.size < 5) return@mapNotNull null
                val name = cols[0].trim('"')
                val pid = cols[1].trim('"').toLongOrNull() ?: return@mapNotNull null

                // "12.345 K" / "12,345 K" -> nos quedamos con dígitos
                val memStr = cols[4].trim('"')
                val kb = memStr.filter { it.isDigit() }.toLongOrNull() ?: 0L

                TaskRow(pid = pid, name = name, memKb = kb)
            }.toList()
    }

    /** Lee Win32_Process (WMI) y devuelve datos enriquecidos; si falla, retorna vacío. */
    private fun readWmiCsv(): Map<Long, WmiRow> {
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

        val map = HashMap<Long, WmiRow>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvLine(line, '§')
            val pid = cols.getOrNull(pidIdx)?.toLongOrNull() ?: return@forEach
            val ws  = cols.getOrNull(wsIdx)?.toDoubleOrNull() ?: 0.0
            val cmd = cols.getOrNull(cmdIdx)?.ifBlank { null }
            val usr = cols.getOrNull(usrIdx)?.ifBlank { "?" } ?: "?"
            map[pid] = WmiRow(pid, ws, cmd, usr)
        }
        return map
    }

    /** Ejecuta PowerShell forzando salida UTF-8 (evita CSV vacíos por locale). */
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

    /** Parser CSV con comillas y delimitador configurable. */
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
