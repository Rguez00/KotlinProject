package org.example.project

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.example.project.ui.ProcessListScreen
import org.example.project.ui.theme.AppTheme

fun main() = application {
    Window(
        title = "Monitor de Procesos",
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 1000.dp, height = 600.dp), // ← tamaño inicial
        resizable = false,
    ) {
        AppTheme {
            ProcessListScreen()
        }
    }
}
