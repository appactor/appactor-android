package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appactor.android.models.AppActorOffering
import com.appactor.android.models.AppActorPackage

@Composable
fun OfferingCard(
    offering: AppActorOffering,
    busy: Boolean,
    onPurchase: (AppActorPackage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailRow(
            title = offering.displayName,
            subtitle = offering.lookupKey ?: offering.id,
            badge = if (offering.isCurrent) "Current" else null,
            badgeColor = ExamplePalette.Accent,
        )
        offering.packages.forEach { pack ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        pack.productName ?: pack.displayName ?: pack.productId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${pack.packageType.name} · ${pack.productId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        pack.localizedPriceString ?: "Price unavailable",
                        style = MaterialTheme.typography.titleSmall,
                        color = ExamplePalette.Accent,
                    )
                    pack.tokenAmount?.let {
                        Text(
                            "tokenAmount=$it",
                            style = MaterialTheme.typography.labelMedium,
                            color = ExamplePalette.Warning,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            listOfNotNull(pack.basePlanId, pack.offerId).joinToString(" · ").ifBlank { "No basePlan/offer" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = { onPurchase(pack) },
                            enabled = !busy,
                        ) {
                            Text("Purchase")
                        }
                    }
                }
            }
        }
    }
}
