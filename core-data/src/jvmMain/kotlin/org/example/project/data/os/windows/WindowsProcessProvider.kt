// REEMPLAZA el contenido de WindowsProcessProvider por esto:
package org.example.project.data.os.windows

import org.example.project.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

class WindowsProcessProvider : ProcessProvider, SystemInfoProvider {

    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> = runCatching {
        // Get-Process -> CSV: Id, ProcessName, CPU (segundos acumulados), WS (Working Set bytes)
        val psCmd = """
            powershell -NoProfile -Command "Get-Process | Select-Object Id,ProcessName,CPU,WS | ConvertTo-Csv -NoTypeInformation"
        """.trimIndent()
        val p = ProcessBuilder("cmd","/c", psCmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream, Charset.forName("Cp1252")))
        val lines = out.readLines().drop(1) // quita cabecera CSV
        val list = lines.mapNotNull { line ->
            val cols = parseCsv(line)
            if (cols.size < 4) return@mapNotNull null
            val pid  = cols[0].toLongOrNull() ?: return@mapNotNull null
            val name = cols[1]
            // CPU viene en segundos acumulados -> para v1 usamos 0.0 como % y luego lo refinamos (Paso 4)
            val cpuPct = 0.0
            val wsBytes = cols[3].toDoubleOrNull() ?: 0.0
            val memPct = 0.0 // v1 simple; lo refinamos con total RAM en Paso 4
            ProcessInfo(pid, name, user = "?", cpuPct, memPct, ProcState.OTHER, null)
        }.let { items ->
            applyFilters(items, filters)
        }
        list
    }

    override suspend fun kill(pid: Long): Result<Unit> = runCatching {
        ProcessBuilder("cmd","/c","taskkill /PID $pid /F").start().waitFor()
        Unit
    }

    override suspend fun summary(): Result<SystemSummary> = runCatching {
        // Usamos OperatingSystemMXBean para una aproximación cross-JVM
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        val cpu = (os as? com.sun.management.OperatingSystemMXBean)?.systemCpuLoad?.let { it * 100.0 } ?: 0.0
        val total = (os as? com.sun.management.OperatingSystemMXBean)?.totalPhysicalMemorySize?.toDouble() ?: 0.0
        val free  = (os as? com.sun.management.OperatingSystemMXBean)?.freePhysicalMemorySize?.toDouble() ?: 0.0
        val memPct = if (total > 0) ((total - free) / total) * 100.0 else 0.0
        SystemSummary(cpu, memPct)
    }

    private fun parseCsv(line: String): List<String> {
        // parseo CSV simple con comillas
        val res = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < line.length) {
                    if (i + 1 < line.length && line[i] == '"' && line[i+1] == '"') { sb.append('"'); i += 2; continue }
                    if (line[i] == '"') { i++; break }
                    sb.append(line[i]); i++
                }
                res.add(sb.toString())
                if (i < line.length && line[i] == ',') i++
            } else {
                val j = line.indexOf(',', i).let { if (it == -1) line.length else it }
                res.add(line.substring(i, j).trim())
                i = j + 1
            }
        }
        return res
    }

    private fun applyFilters(items: List<ProcessInfo>, f: Filters?): List<ProcessInfo> {
        if (f == null) return items
        return items.filter { p ->
            (f.name.isNullOrBlank() || p.name.contains(f.name!!, ignoreCase = true) || (p.command?.contains(f.name!!, true) == true)) &&
                    (f.user.isNullOrBlank() || p.user.equals(f.user, ignoreCase = true)) &&
                    (f.state == null || p.state == f.state)
        }
    }
}
