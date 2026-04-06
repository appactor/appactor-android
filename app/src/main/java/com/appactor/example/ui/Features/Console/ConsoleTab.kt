package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appactor.android.models.AppActorStoreCapability
import com.appactor.android.models.AppActorStorefront

@Composable
fun ConsoleTab(
    modifier: Modifier,
    configured: Boolean,
    busy: Boolean,
    summaryMetrics: List<ExampleMetric>,
    statusText: String,
    appUserId: String?,
    isAnonymous: Boolean,
    storefront: AppActorStorefront?,
    storeCapabilities: Set<AppActorStoreCapability>,
    canMakePurchases: Boolean,
    loginUserId: String,
    onLoginUserIdChange: (String) -> Unit,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
    onRestore: () -> Unit,
    onRefreshCustomer: () -> Unit,
    onSyncPurchases: () -> Unit,
    recentLogs: List<ExampleLogEntry>,
) {
    ToolingPage(
        modifier = modifier,
        title = "Control Center",
        subtitle = "SDK boot, identity ve recovery aksiyonlarini tek sayfadan yonet.",
    ) {
        item {
            ToolingSection(
                title = "Live Status",
                subtitle = "Bu alan test sirasinda ilk bakilan runtime ozetini verir.",
            ) {
                ToolingMetricRow(summaryMetrics)
                ToolingValue(label = "Status", value = statusText)
                ToolingValue(label = "App User ID", value = appUserId ?: "-", monospaced = true)
                ToolingValue(
                    label = "Identity Mode",
                    value = if (isAnonymous) "Anonymous" else "Identified",
                    valueColor = if (isAnonymous) ExamplePalette.Warning else ExamplePalette.Success,
                )
                ToolingValue(
                    label = "SDK",
                    value = if (configured) "Configured" else "Waiting",
                    valueColor = if (configured) ExamplePalette.Success else ExamplePalette.Warning,
                )
                ToolingValue(
                    label = "Storefront",
                    value = storefront?.countryCode ?: "Unavailable",
                    valueColor = if (storefront == null) ExamplePalette.Warning else ExamplePalette.Success,
                )
                ToolingValue(
                    label = "Capabilities",
                    value = storeCapabilities
                        .map { it.name }
                        .sorted()
                        .joinToString()
                        .ifBlank { "None" },
                    monospaced = true,
                )
                ToolingValue(
                    label = "Can Purchase",
                    value = if (canMakePurchases) "Yes" else "No",
                    valueColor = if (canMakePurchases) ExamplePalette.Success else ExamplePalette.Warning,
                )
            }
        }
        item {
            ToolingSection(
                title = "Identity",
                subtitle = "Login ve logout akisini dogrudan test et.",
            ) {
                OutlinedTextField(
                    value = loginUserId,
                    onValueChange = onLoginUserIdChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("App User ID") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = onLogIn,
                        modifier = Modifier.weight(1f),
                        enabled = configured && !busy,
                    ) {
                        Text("Log In")
                    }
                    FilledTonalButton(
                        onClick = onLogOut,
                        modifier = Modifier.weight(1f),
                        enabled = configured && !busy,
                    ) {
                        Text("Log Out")
                    }
                }
            }
        }
        item {
            ToolingSection(
                title = "Recovery Actions",
                subtitle = "Restore ve refresh gibi en sik test edilen aksiyonlar.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = onRestore,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = configured && !busy,
                    ) {
                        Text("Restore Purchases")
                    }
                    FilledTonalButton(
                        onClick = onRefreshCustomer,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = configured && !busy,
                    ) {
                        Text("Refresh Customer")
                    }
                    FilledTonalButton(
                        onClick = onSyncPurchases,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = configured && !busy,
                    ) {
                        Text("Sync Purchases")
                    }
                }
            }
        }
        item {
            ToolingSection(
                title = "Recent Activity",
                subtitle = "Son callback ve aksiyon ciktilari.",
            ) {
                ConsoleView(recentLogs.take(6))
            }
        }
    }
}
