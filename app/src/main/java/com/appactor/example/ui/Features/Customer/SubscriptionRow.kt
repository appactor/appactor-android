package com.appactor.example.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.appactor.android.models.AppActorNonSubscription
import com.appactor.android.models.AppActorSubscriptionInfo

@Composable
fun SubscriptionRow(subscription: AppActorSubscriptionInfo) {
    OutlinedCard {
        ListItem(
            headlineContent = { Text(subscription.productIdentifier) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        subscription.status,
                        subscription.basePlanId,
                        subscription.offerId,
                        subscription.purchaseDate,
                        subscription.expiresDate,
                    ).joinToString(" · "),
                )
            },
            trailingContent = {
                Text(
                    text = when {
                        subscription.isActive -> "Active"
                        subscription.isInGracePeriod -> "Grace"
                        else -> "Inactive"
                    },
                    color = when {
                        subscription.isActive -> ExamplePalette.Success
                        subscription.isInGracePeriod -> ExamplePalette.Warning
                        else -> ExamplePalette.Error
                    },
                )
            },
        )
    }
}

@Composable
fun NonSubscriptionRow(item: AppActorNonSubscription) {
    OutlinedCard {
        ListItem(
            headlineContent = { Text(item.productIdentifier) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        item.purchaseDate,
                        item.store.wireValue.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            },
            trailingContent = {
                Text(
                    text = when {
                        item.isRefund == true -> "Refunded"
                        item.isConsumable == true -> "Consumable"
                        else -> "Purchase"
                    },
                    color = when {
                        item.isRefund == true -> ExamplePalette.Error
                        item.isConsumable == true -> ExamplePalette.Info
                        else -> ExamplePalette.Success
                    },
                )
            },
        )
    }
}
