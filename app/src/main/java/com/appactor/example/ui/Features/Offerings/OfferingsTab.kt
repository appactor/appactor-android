package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appactor.android.models.AppActorCustomerInfo
import com.appactor.android.models.AppActorOfferings
import com.appactor.android.models.AppActorPackage

@Composable
fun OfferingsTab(
    modifier: Modifier,
    configured: Boolean,
    busy: Boolean,
    offerings: AppActorOfferings?,
    customerInfo: AppActorCustomerInfo,
    onLoadOfferings: () -> Unit,
    onCheckPremium: () -> Unit,
    onPurchasePackage: (AppActorPackage) -> Unit,
) {
    ToolingPage(
        modifier = modifier,
        title = "Catalog & Purchase",
        subtitle = "Offerings'i cek, package'lari gor ve dogrudan offering uzerinden satin alma dene.",
    ) {
        item {
            ToolingSection(
                title = "Actions",
                subtitle = "Testing icin gerekli tek iki adim burada.",
            ) {
                FilledTonalButton(
                    onClick = onLoadOfferings,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Load Offerings")
                }
                FilledTonalButton(
                    onClick = onCheckPremium,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Check Premium")
                }
                if (customerInfo.hasActiveEntitlement("premium")) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Premium entitlement active") },
                    )
                }
            }
        }
        item {
            ToolingSection(
                title = "Available Packages",
                subtitle = "Raw product degil, sadece offering'den gelen package satin alinabilir.",
            ) {
                if (offerings?.all.isNullOrEmpty()) {
                    Text(
                        text = "Once offerings yukle. Bu liste dolunca buradan satin alma yapabilirsin.",
                    )
                } else {
                    offerings!!.all.values
                        .sortedBy { it.id }
                        .forEach { offering ->
                            Text(
                                text = offering.displayName,
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            offering.lookupKey?.let {
                                Text(
                                    text = it,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            offering.packages.forEach { pack ->
                                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ListItem(
                                            headlineContent = {
                                                Text(pack.productName ?: pack.displayName ?: pack.productId)
                                            },
                                            supportingContent = {
                                                Column {
                                                    Text(pack.productId, fontFamily = FontFamily.Monospace)
                                                    Text(
                                                        listOfNotNull(pack.basePlanId, pack.offerId)
                                                            .joinToString(" · ")
                                                            .ifBlank { pack.packageType.name },
                                                    )
                                                }
                                            },
                                            trailingContent = {
                                                Text(
                                                    text = pack.localizedPriceString ?: "-",
                                                    color = ExamplePalette.Accent,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            },
                                        )
                                        pack.tokenAmount?.let {
                                            Text(
                                                text = "Token amount: $it",
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { onPurchasePackage(pack) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .padding(bottom = 16.dp),
                                            enabled = !busy,
                                        ) {
                                            Text("Purchase Package")
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
}
