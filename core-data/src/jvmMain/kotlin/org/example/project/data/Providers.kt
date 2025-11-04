package org.example.project.data

import org.example.project.ProcessProvider
import org.example.project.SystemInfoProvider
import org.example.project.data.os.linux.LinuxProcessProvider
import org.example.project.data.os.mac.MacProcessProvider
import org.example.project.data.os.windows.WindowsProcessProvider
import org.example.project.data.platform.OsDetector
import org.example.project.data.platform.OS

/**
 * Fábrica simple para obtener implementaciones por SO.
 * De momento, todas son stubs que devuelven listas vacías / métricas 0.
 */
object Providers {
    fun processProvider(): ProcessProvider = when (OsDetector.detect()) {
        OS.WINDOWS -> WindowsProcessProvider()
        OS.MAC     -> MacProcessProvider()
        OS.LINUX   -> LinuxProcessProvider()
        OS.UNKNOWN -> LinuxProcessProvider() // por defecto
    }

    fun systemInfoProvider(): SystemInfoProvider = when (OsDetector.detect()) {
        OS.WINDOWS -> WindowsProcessProvider()
        OS.MAC     -> MacProcessProvider()
        OS.LINUX   -> LinuxProcessProvider()
        OS.UNKNOWN -> LinuxProcessProvider()
    }
}
