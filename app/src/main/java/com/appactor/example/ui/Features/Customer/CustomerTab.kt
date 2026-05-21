package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appactor.android.models.AppActorCustomerInfo

@Composable
fun CustomerTab(
    modifier: Modifier,
    configured: Boolean,
    busy: Boolean,
    customerInfo: AppActorCustomerInfo,
    offlineKeys: Set<String>,
    onFetchCustomer: () -> Unit,
    onLoadOfflineKeys: () -> Unit,
) {
    ToolingPage(
        modifier = modifier,
        title = "Customer State",
        subtitle = "Customer info, entitlement durumu ve local key sonucunu temiz bir listede gor.",
    ) {
        item {
            ToolingSection(
                title = "Actions",
                subtitle = "Customer verisini yenile ve local entitlement gate sonucunu cek.",
            ) {
                FilledTonalButton(
                    onClick = onFetchCustomer,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Fetch Customer")
                }
                FilledTonalButton(
                    onClick = onLoadOfflineKeys,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Load Offline Keys")
                }
            }
        }
        item {
            ToolingSection(
                title = "Snapshot",
                subtitle = "Server veya cache kaynakli customer durumunun ozeti.",
            ) {
                ToolingValue(label = "User", value = customerInfo.appUserId ?: "-", monospaced = true)
                ToolingValue(
                    label = "Premium",
                    value = if (customerInfo.hasActiveEntitlement("premium")) "Active" else "Inactive",
                    valueColor = if (customerInfo.hasActiveEntitlement("premium")) ExamplePalette.Success else ExamplePalette.Error,
                )
                ToolingValue(label = "Request ID", value = customerInfo.requestId ?: "-", monospaced = true)
                customerInfo.firstSeen?.let { ToolingValue(label = "First Seen", value = it) }
                customerInfo.lastSeen?.let { ToolingValue(label = "Last Seen", value = it) }
                customerInfo.tokenBalance?.let { tokenBalance ->
                    ToolingValue(label = "Total Tokens", value = tokenBalance.total.toString())
                    ToolingValue(label = "Renewable", value = tokenBalance.renewable.toString())
                    ToolingValue(label = "Non-Renewable", value = tokenBalance.nonRenewable.toString())
                }
                if (offlineKeys.isNotEmpty()) {
                    ToolingValue(
                        label = "Offline Keys",
                        value = offlineKeys.sorted().joinToString(),
                        monospaced = true,
                    )
                }
            }
        }
        item {
            ToolingSection(
                title = "Entitlements",
                subtitle = "Aktif ve inaktif entitlement kayitlari.",
            ) {
                if (customerInfo.entitlements.isEmpty()) {
                    Text("Entitlement yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    customerInfo.entitlements.values.sortedBy { it.identifier }.forEach { entitlement ->
                        EntitlementRow(entitlement)
                    }
                }
            }
        }
        item {
            ToolingSection(
                title = "Subscriptions",
                subtitle = "Subscription status, base plan ve offer bilgileri.",
            ) {
                if (customerInfo.subscriptions.isEmpty()) {
                    Text("Subscription yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        customerInfo.subscriptions.values.sortedBy { it.productIdentifier }.forEach {
                            SubscriptionRow(it)
                        }
                    }
                }
            }
        }
        val allNonSubs = customerInfo.nonSubscriptions.values.flatten()
        if (allNonSubs.isNotEmpty()) {
            item {
                ToolingSection(
                    title = "Non-subscriptions",
                    subtitle = "Refund ve consumable durumlarini da gosterir.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allNonSubs.sortedBy { it.productIdentifier }.forEach {
                            NonSubscriptionRow(it)
                        }
                    }
                }
            }
        }
    }
}
