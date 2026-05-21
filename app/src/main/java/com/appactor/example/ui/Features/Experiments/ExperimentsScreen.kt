package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExperimentsScreen(
    modifier: Modifier,
    configured: Boolean,
    busy: Boolean,
    experimentKeyInput: String,
    onExperimentKeyInputChange: (String) -> Unit,
    experimentResults: List<ExampleExperimentResult>,
    lastExperimentLoadAt: String?,
    onGetAssignment: () -> Unit,
) {
    ToolingPage(
        modifier = modifier,
        title = "Experiments",
        subtitle = "Assignment sorgula, sonucu ve hedef disi kalan key'leri material tablosu gibi oku.",
    ) {
        item {
            ToolingSection(
                title = "Assignment Lookup",
                subtitle = "Tek bir experiment key ile assignment sonucunu getir.",
            ) {
                OutlinedTextField(
                    value = experimentKeyInput,
                    onValueChange = onExperimentKeyInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Experiment key") },
                )
                FilledTonalButton(
                    onClick = onGetAssignment,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Get Assignment")
                }
            }
        }
        item {
            ToolingSection(
                title = "Status",
                subtitle = "Son fetch ozetini gosterir.",
            ) {
                ToolingValue(
                    label = "Assigned",
                    value = "${experimentResults.count { it.assignment != null }}",
                )
                ToolingValue(
                    label = "Not Targeted",
                    value = "${experimentResults.count { it.assignment == null }}",
                )
                ToolingValue(label = "Last Fetch", value = lastExperimentLoadAt ?: "-")
            }
        }
        if (experimentResults.any { it.assignment != null }) {
            item {
                ToolingSection(
                    title = "Assignments",
                    subtitle = "Server tarafindan atanan varyantlar.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        experimentResults
                            .mapNotNull { it.assignment }
                            .sortedBy { it.experimentKey }
                            .forEach { assignment ->
                                ExperimentItemRow(assignment)
                            }
                    }
                }
            }
        }
        val misses = experimentResults.filter { it.assignment == null }.map { it.key }.distinct().sorted()
        if (misses.isNotEmpty()) {
            item {
                ToolingSection(
                    title = "Not Targeted",
                    subtitle = "Deneyin hedef kitlesine girmeyen key'ler.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        misses.forEach { key ->
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}
