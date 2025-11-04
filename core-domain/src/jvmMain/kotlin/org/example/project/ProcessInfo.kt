package org.example.project

data class ProcessInfo(
    val pid: Long,
    val name: String,
    val user: String,
    val cpuPercent: Double,
    val memPercent: Double,
    val state: ProcState,
    val command: String? = null
)
