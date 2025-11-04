package org.example.project.data.platform

enum class OS { WINDOWS, LINUX, MAC, UNKNOWN }

object OsDetector {
    fun detect(): OS {
        val name = System.getProperty("os.name")?.lowercase() ?: return OS.UNKNOWN
        return when {
            name.contains("win") -> OS.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OS.MAC
            name.contains("nux") || name.contains("nix") -> OS.LINUX
            else -> OS.UNKNOWN
        }
    }
}
