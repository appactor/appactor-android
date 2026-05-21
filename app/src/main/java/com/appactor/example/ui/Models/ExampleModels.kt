package com.appactor.example.ui

import androidx.compose.ui.graphics.Color
import com.appactor.android.models.AppActorConfigValue
import com.appactor.android.models.AppActorExperimentAssignment

enum class MainExampleTab(val label: String, val glyph: String) {
    Console("Console", "CO"),
    Billing("Billing", "BI"),
    Customer("Customer", "CU"),
    Tools("Tools", "TL"),
}

enum class ToolExampleScreen(
    val label: String,
    val subtitle: String,
    val glyph: String,
    val color: Color,
) {
    RemoteConfig(
        label = "Remote Config",
        subtitle = "Fetch, cache ve lookup",
        glyph = "CF",
        color = ExamplePalette.Accent,
    ),
    Experiments(
        label = "Experiments",
        subtitle = "Assignment ve targeting kontrolu",
        glyph = "EX",
        color = ExamplePalette.Success,
    ),
    Diagnostics(
        label = "Logs & Queue",
        subtitle = "Console, runtime state ve receipt queue",
        glyph = "DX",
        color = ExamplePalette.Warning,
    ),
}

data class ExampleMetric(
    val title: String,
    val value: String,
    val color: Color,
)

data class ExampleChoice(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

data class ExampleActionTile(
    val title: String,
    val subtitle: String,
    val glyph: String,
    val color: Color,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

data class ExampleConfigLookup(
    val key: String,
    val value: AppActorConfigValue,
)

data class ExampleExperimentResult(
    val key: String,
    val assignment: AppActorExperimentAssignment?,
    val source: String,
    val timestamp: String,
)

data class ExampleLogEntry(
    val message: String,
    val tone: ExampleLogTone,
    val timestamp: String,
)

enum class ExampleLogTone(val color: Color) {
    Info(ExamplePalette.Info),
    Success(ExamplePalette.Success),
    Warn(ExamplePalette.Warning),
    Error(ExamplePalette.Error),
}
