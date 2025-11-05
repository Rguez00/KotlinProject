// REEMPLAZA el contenido de LinuxProcessProvider por esto:
package org.example.project.data.os.linux

import org.example.project.*
import java.io.BufferedReader
import java.io.InputStreamReader

class LinuxProcessProvider : ProcessProvider, SystemInfoProvider {

    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> = runCatching {
        val cmd = listOf(
            "bash","-lc",
            "ps -eo pid,comm,user,pcpu,pmem,stat,cmd --no-headers"
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader()
        val list = out.readLines().mapNotNull { line ->
            // pid  comm  user  pcpu  pmem  stat  cmd...
            val parts = line.trim().split(Regex("\\s+"), limit = 7)
            if (parts.size < 6) return@mapNotNull null
            val pid   = parts[0].toLongOrNull() ?: return@mapNotNull null
            val name  = parts[1]
            val user  = parts[2]
            val pcpu  = parts[3].toDoubleOrNull() ?: 0.0
            val pmem  = parts[4].toDoubleOrNull() ?: 0.0
            val state = when (parts[5].firstOrNull()?.uppercaseChar()) {
                'R' -> ProcState.RUNNING
                'S' -> ProcState.SLEEPING
                'Z' -> ProcState.ZOMBIE
                'T' -> ProcState.STOPPED
                else -> ProcState.OTHER
            }
            val cmdline = if (parts.size >= 7) parts[6] else null
            ProcessInfo(pid, name, user, pcpu, pmem, state, cmdline)
        }.let { items ->
            applyFilters(items, filters)
        }
        list
    }

    override suspend fun kill(pid: Long): Result<Unit> = runCatching {
        ProcessBuilder("bash","-lc","kill -9 $pid").start().waitFor()
        Unit
    }

    override suspend fun summary(): Result<SystemSummary> = runCatching {
        // CPU: (1 - idle) vía /proc/stat en 2 muestras, aproximado; aquí versión simple: 0.0 y lo mejoramos en Paso 4
        // Memoria: usamos /proc/meminfo (MemTotal/MemAvailable)
        val memInfo = readFile("/proc/meminfo")
        fun value(k: String) = Regex("$k:\\s+(\\d+)").find(memInfo)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val totalKb = value("MemTotal")
        val freeKb  = value("MemAvailable")
        val usedPct = if (totalKb > 0) ((totalKb - freeKb) / totalKb) * 100.0 else 0.0
        SystemSummary(totalCpuPercent = 0.0, totalMemPercent = usedPct)
    }

    private fun readFile(path: String): String =
        BufferedReader(InputStreamReader(Runtime.getRuntime().exec(arrayOf("bash","-lc","cat $path")).inputStream)).use { it.readText() }

    private fun applyFilters(items: List<ProcessInfo>, f: Filters?): List<ProcessInfo> {
        if (f == null) return items
        return items.filter { p ->
            (f.name.isNullOrBlank() || p.name.contains(f.name!!, ignoreCase = true) || (p.command?.contains(f.name!!, true) == true)) &&
                    (f.user.isNullOrBlank() || p.user.equals(f.user, ignoreCase = true)) &&
                    (f.state == null || p.state == f.state)
        }
    }
}
