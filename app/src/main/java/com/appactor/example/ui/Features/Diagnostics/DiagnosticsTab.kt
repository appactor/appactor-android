package com.appactor.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DiagnosticsTab(
    modifier: Modifier,
    configured: Boolean,
    logs: List<ExampleLogEntry>,
    statusText: String,
    appUserId: String?,
    isAnonymous: Boolean,
) {
    ToolingPage(
        modifier = modifier,
        title = "Diagnostics",
        subtitle = "Runtime durumu ve callback loglarini standart Material listesiyle takip et.",
    ) {
        item {
            ToolingSection(
                title = "Runtime",
                subtitle = "SDK'in su anki calisma durumu.",
            ) {
                ToolingValue(
                    label = "Configured",
                    value = if (configured) "Yes" else "No",
                    valueColor = if (configured) ExamplePalette.Success else ExamplePalette.Error,
                )
                ToolingValue(label = "User ID", value = appUserId ?: "-", monospaced = true)
                ToolingValue(
                    label = "Anonymous",
                    value = if (isAnonymous) "Yes" else "No",
                    valueColor = if (isAnonymous) ExamplePalette.Warning else ExamplePalette.Success,
                )
                ToolingValue(label = "Status", value = statusText)
            }
        }
        item {
            ToolingSection(
                title = "Recent Logs",
                subtitle = "Son callback ve action ciktilari.",
            ) {
                ConsoleView(logs.take(20))
            }
        }
    }
}
