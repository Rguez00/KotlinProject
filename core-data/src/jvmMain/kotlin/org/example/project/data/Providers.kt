package org.example.project.data

import org.example.project.ProcessProvider
import org.example.project.SystemInfoProvider
import org.example.project.data.os.linux.LinuxProcessProvider
import org.example.project.data.os.windows.WindowsProcessProvider
import org.example.project.data.platform.OS
import org.example.project.data.platform.OsDetector

object Providers {

    private val provider: ProcessProvider by lazy {
        when (OsDetector.detect()) {
            OS.WINDOWS -> WindowsProcessProvider()
            OS.LINUX   -> LinuxProcessProvider()
            else       -> error("SO no soportado: este proyecto solo da soporte a Windows y Linux.")
        }
    }

    fun processProvider(): ProcessProvider = provider

    fun systemInfoProvider(): SystemInfoProvider =
        (provider as? SystemInfoProvider)
            ?: error("El provider actual no implementa SystemInfoProvider.")
}
