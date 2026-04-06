package com.appactor.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun OverviewTab(
    modifier: Modifier,
    configured: Boolean,
    busy: Boolean,
    appUserId: String?,
    isAnonymous: Boolean,
    loginUserId: String,
    onLoginUserIdChange: (String) -> Unit,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
    onRestore: () -> Unit,
    onRefreshCustomer: () -> Unit,
    onSyncPurchases: () -> Unit,
) {
    ExampleScreen(
        modifier = modifier,
        title = "Overview",
        subtitle = "Configuration, identity ve restore akisini tek yerde yonet.",
        glyph = "OV",
        badgeText = if (configured) "Configured" else "Waiting for setup",
        badgeColor = if (configured) ExamplePalette.Success else ExamplePalette.Warning,
    ) {
        item {
            ConfigurationSection(
                configured = configured,
            )
        }
        item {
            IdentitySection(
                appUserId = appUserId,
                isAnonymous = isAnonymous,
                loginUserId = loginUserId,
                onLoginUserIdChange = onLoginUserIdChange,
                configured = configured,
                busy = busy,
                onLogIn = onLogIn,
                onLogOut = onLogOut,
            )
        }
        item {
            QuickActionsSection(
                configured = configured,
                busy = busy,
                onRestore = onRestore,
                onRefreshCustomer = onRefreshCustomer,
                onSyncPurchases = onSyncPurchases,
            )
        }
    }
}
