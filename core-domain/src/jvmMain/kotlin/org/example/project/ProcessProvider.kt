package org.example.project

/**
 * Puerto de dominio para obtener/actuar sobre procesos.
 * Implementaciones reales vivirán en core-data por SO.
 */
interface ProcessProvider {
    suspend fun listProcesses(filters: Filters? = null): Result<List<ProcessInfo>>
    suspend fun kill(pid: Long): Result<Unit>
}
