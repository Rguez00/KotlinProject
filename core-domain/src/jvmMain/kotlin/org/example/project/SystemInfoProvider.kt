package org.example.project

/**
 * Puerto de dominio para métricas/resumen del sistema.
 */
interface SystemInfoProvider {
    suspend fun summary(): Result<SystemSummary>
}
