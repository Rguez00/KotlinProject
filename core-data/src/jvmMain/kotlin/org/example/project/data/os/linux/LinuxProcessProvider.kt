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
        // CPU total a partir de /proc/stat en dos muestras (≈500ms)
        fun readCpuLine(): LongArray {
            val line = readFile("/proc/stat").lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return LongArray(0)
            val parts = line.split(Regex("\\s+")).drop(1) // user nice system idle iowait irq softirq steal guest guest_nice
            return parts.take(10).mapNotNull { it.toLongOrNull() }.toLongArray()
        }

        fun usagePct(a: LongArray, b: LongArray): Double {
            if (a.size < 4 || b.size < 4) return 0.0
            val idleA = a[3] + (a.getOrNull(4) ?: 0L)
            val idleB = b[3] + (b.getOrNull(4) ?: 0L)
            val totalA = a.sum()
            val totalB = b.sum()
            val totalD = (totalB - totalA).coerceAtLeast(1)
            val idleD  = (idleB - idleA).coerceAtLeast(0)
            val busyD  = (totalD - idleD).coerceAtLeast(0)
            return (busyD.toDouble() / totalD.toDouble()) * 100.0
        }

        val a = readCpuLine()
        Thread.sleep(500)
        val b = readCpuLine()
        val cpuPct = usagePct(a, b)

        // Memoria desde /proc/meminfo (MemTotal/MemAvailable)
        val memInfo = readFile("/proc/meminfo")
        fun value(k: String) = Regex("$k:\\s+(\\d+)").find(memInfo)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val totalKb = value("MemTotal")
        val freeKb  = value("MemAvailable")
        val memPct  = if (totalKb > 0) ((totalKb - freeKb) / totalKb) * 100.0 else 0.0

        SystemSummary(totalCpuPercent = cpuPct.coerceIn(0.0, 100.0), totalMemPercent = memPct.coerceIn(0.0, 100.0))
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
