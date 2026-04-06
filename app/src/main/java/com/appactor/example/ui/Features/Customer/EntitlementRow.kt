package com.appactor.example.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.appactor.android.models.AppActorEntitlementInfo

@Composable
fun EntitlementRow(entitlement: AppActorEntitlementInfo) {
    OutlinedCard {
        ListItem(
            headlineContent = { Text(entitlement.identifier) },
            supportingContent = {
                Text(
                    listOfNotNull(entitlement.status, entitlement.productIdentifier).joinToString(" · "),
                )
            },
            trailingContent = {
                Text(
                    text = if (entitlement.isActive) "Active" else "Inactive",
                    color = if (entitlement.isActive) ExamplePalette.Success else ExamplePalette.Error,
                )
            },
        )
    }
}
