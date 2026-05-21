package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun QuickActionsSection(
    configured: Boolean,
    busy: Boolean,
    onRestore: () -> Unit,
    onRefreshCustomer: () -> Unit,
    onSyncPurchases: () -> Unit,
) {
    PRCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeader(
                title = "Quick Actions",
                glyph = "QA",
                color = ExamplePalette.Warning,
                subtitle = "Test sirasinda en sik kullanilan recovery adimlari",
            )
            ActionTiles(
                tiles = listOf(
                    ExampleActionTile(
                        title = "Restore",
                        subtitle = "Restore purchases ve state refresh",
                        glyph = "RS",
                        color = ExamplePalette.Success,
                        enabled = configured && !busy,
                        onClick = onRestore,
                    ),
                    ExampleActionTile(
                        title = "Refresh Customer",
                        subtitle = "Server customer snapshot",
                        glyph = "CU",
                        color = ExamplePalette.Info,
                        enabled = configured && !busy,
                        onClick = onRefreshCustomer,
                    ),
                    ExampleActionTile(
                        title = "Sync Purchases",
                        subtitle = "Receipt queue + server refresh",
                        glyph = "SY",
                        color = ExamplePalette.Accent,
                        enabled = configured && !busy,
                        onClick = onSyncPurchases,
                    ),
                ),
            )
        }
    }
}
