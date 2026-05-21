package com.appactor.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appactor.android.models.AppActorRemoteConfigs

@Composable
fun ToolsTab(
    modifier: Modifier,
    currentScreen: ToolExampleScreen,
    onScreenChange: (ToolExampleScreen) -> Unit,
    configured: Boolean,
    busy: Boolean,
    remoteConfigLookupKey: String,
    onRemoteConfigLookupKeyChange: (String) -> Unit,
    remoteConfigs: AppActorRemoteConfigs?,
    lastRemoteConfigLoadAt: String?,
    lastRemoteConfigLookup: ExampleConfigLookup?,
    onLoadConfigs: () -> Unit,
    onLookupCachedKey: () -> Unit,
    experimentKeyInput: String,
    onExperimentKeyInputChange: (String) -> Unit,
    experimentResults: List<ExampleExperimentResult>,
    lastExperimentLoadAt: String?,
    onGetAssignment: () -> Unit,
    logs: List<ExampleLogEntry>,
    statusText: String,
    appUserId: String?,
    isAnonymous: Boolean,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = ToolExampleScreen.entries.indexOf(currentScreen)) {
                ToolExampleScreen.entries.forEach { screen ->
                    Tab(
                        selected = currentScreen == screen,
                        onClick = { onScreenChange(screen) },
                        text = { Text(screen.label) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    ToolExampleScreen.RemoteConfig -> RemoteConfigScreen(
                        modifier = Modifier.fillMaxSize(),
                        configured = configured,
                        busy = busy,
                        remoteConfigLookupKey = remoteConfigLookupKey,
                        onRemoteConfigLookupKeyChange = onRemoteConfigLookupKeyChange,
                        remoteConfigs = remoteConfigs,
                        lastRemoteConfigLoadAt = lastRemoteConfigLoadAt,
                        lastRemoteConfigLookup = lastRemoteConfigLookup,
                        onLoadConfigs = onLoadConfigs,
                        onLookupCachedKey = onLookupCachedKey,
                    )

                    ToolExampleScreen.Experiments -> ExperimentsScreen(
                        modifier = Modifier.fillMaxSize(),
                        configured = configured,
                        busy = busy,
                        experimentKeyInput = experimentKeyInput,
                        onExperimentKeyInputChange = onExperimentKeyInputChange,
                        experimentResults = experimentResults,
                        lastExperimentLoadAt = lastExperimentLoadAt,
                        onGetAssignment = onGetAssignment,
                    )

                    ToolExampleScreen.Diagnostics -> DiagnosticsTab(
                        modifier = Modifier.fillMaxSize(),
                        configured = configured,
                        logs = logs,
                        statusText = statusText,
                        appUserId = appUserId,
                        isAnonymous = isAnonymous,
                    )
                }
            }
        }
    }
}
