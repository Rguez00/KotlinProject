package org.example.project.data

import org.example.project.ProcessProvider
import org.example.project.SystemInfoProvider
import org.example.project.data.os.linux.LinuxProcessProvider
import org.example.project.data.os.windows.WindowsProcessProvider
// TODO: macOS más adelante

object Providers {
    private val osName = System.getProperty("os.name").lowercase()

    fun processProvider(): ProcessProvider = when {
        osName.contains("win")   -> WindowsProcessProvider()
        osName.contains("linux") -> LinuxProcessProvider()
        osName.contains("mac") || osName.contains("darwin") -> LinuxProcessProvider() // stub temporal
        else -> LinuxProcessProvider()
    }

    fun systemInfoProvider(): SystemInfoProvider =
        processProvider() as SystemInfoProvider
}
