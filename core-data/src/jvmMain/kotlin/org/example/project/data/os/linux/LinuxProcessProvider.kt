package org.example.project.data.os.linux

import org.example.project.*

class LinuxProcessProvider : ProcessProvider, SystemInfoProvider {
    override suspend fun listProcesses(filters: Filters?): Result<List<ProcessInfo>> =
        Result.success(emptyList())

    override suspend fun kill(pid: Long): Result<Unit> =
        Result.success(Unit)

    override suspend fun summary(): Result<SystemSummary> =
        Result.success(SystemSummary(totalCpuPercent = 0.0, totalMemPercent = 0.0))
}
