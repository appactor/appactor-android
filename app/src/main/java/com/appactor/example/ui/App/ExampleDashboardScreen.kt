package com.appactor.example.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appactor.example.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExampleDashboardScreen(
    activity: Activity,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val state = rememberExampleAppState(activity)

    DisposableEffect(state.configured) {
        if (state.configured) {
            state.wireSdkCallbacks()
        }
        onDispose {
            state.clearSdkCallbacks()
        }
    }

    LaunchedEffect(state.configured) {
        if (!state.configured) {
            runCatching {
                state.configureOnLaunchIfNeeded()
            }.onFailure { throwable ->
                state.handleInitialConfigureFailure(throwable)
            }
        }
    }

    if (!state.configured) {
        ExampleSplashScreen(
            statusText = state.statusText,
            isConfiguring = state.isConfiguring,
            canRetry = state.canRetryInitialConfigure,
            onRetry = {
                scope.launch {
                    runCatching {
                        state.retryInitialConfigure()
                    }.onFailure { throwable ->
                        state.handleInitialConfigureFailure(throwable)
                    }
                }
            },
        )
        return
    }

    val currentMainTab = MainExampleTab.valueOf(state.mainTab)
    val currentToolsScreen = ToolExampleScreen.valueOf(state.toolsScreen)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(screenTitle(currentMainTab, currentToolsScreen)) },
            )
        },
        bottomBar = {
            NavigationBar {
                MainExampleTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentMainTab == tab,
                        onClick = { state.mainTab = tab.name },
                        icon = {
                            TabGlyph(
                                label = tab.glyph,
                                color = if (currentMainTab == tab) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (currentMainTab) {
            MainExampleTab.Console -> ConsoleTab(
                modifier = contentModifier,
                configured = state.configured,
                busy = state.busyAction != null,
                summaryMetrics = state.summaryMetrics,
                statusText = state.statusText,
                appUserId = com.appactor.android.api.AppActor.shared.appUserId,
                isAnonymous = com.appactor.android.api.AppActor.shared.isAnonymous,
                storefront = state.storefront,
                storeCapabilities = state.storeCapabilities,
                canMakePurchases = state.canMakePurchases,
                loginUserId = state.loginUserId,
                onLoginUserIdChange = { state.loginUserId = it },
                onLogIn = {
                    state.launchAction(scope, snackbarHostState, "Log In") {
                        state.logIn()
                    }
                },
                onLogOut = {
                    state.launchAction(scope, snackbarHostState, "Log Out") {
                        state.logOut()
                    }
                },
                onRestore = {
                    state.launchAction(scope, snackbarHostState, "Restore") {
                        state.restore()
                    }
                },
                onRefreshCustomer = {
                    state.launchAction(scope, snackbarHostState, "Refresh Customer") {
                        state.refreshCustomer()
                    }
                },
                onSyncPurchases = {
                    state.launchAction(scope, snackbarHostState, "Sync Purchases") {
                        state.syncPurchases()
                    }
                },
                recentLogs = state.logs,
            )

            MainExampleTab.Billing -> OfferingsTab(
                modifier = contentModifier,
                configured = state.configured,
                busy = state.busyAction != null,
                offerings = state.offerings,
                customerInfo = state.customerInfo,
                onLoadOfferings = {
                    state.launchAction(scope, snackbarHostState, "Load Offerings") {
                        state.loadOfferings()
                    }
                },
                onCheckPremium = {
                    state.launchAction(scope, snackbarHostState, "Check Premium") {
                        state.checkPremium()
                    }
                },
                onPurchasePackage = { pack ->
                    state.launchAction(scope, snackbarHostState, "Purchase ${pack.productId}") {
                        state.purchasePackage(pack)
                    }
                },
            )

            MainExampleTab.Customer -> CustomerTab(
                modifier = contentModifier,
                configured = state.configured,
                busy = state.busyAction != null,
                customerInfo = state.customerInfo,
                offlineKeys = state.offlineKeys,
                onFetchCustomer = {
                    state.launchAction(scope, snackbarHostState, "Fetch Customer") {
                        state.fetchCustomer()
                    }
                },
                onLoadOfflineKeys = {
                    state.launchAction(scope, snackbarHostState, "Offline Keys") {
                        state.loadOfflineKeys()
                    }
                },
            )

            MainExampleTab.Tools -> ToolsTab(
                modifier = contentModifier,
                currentScreen = currentToolsScreen,
                onScreenChange = { state.toolsScreen = it.name },
                configured = state.configured,
                busy = state.busyAction != null,
                remoteConfigLookupKey = state.remoteConfigLookupKey,
                onRemoteConfigLookupKeyChange = { state.remoteConfigLookupKey = it },
                remoteConfigs = state.remoteConfigs,
                lastRemoteConfigLoadAt = state.lastRemoteConfigLoadAt,
                lastRemoteConfigLookup = state.lastRemoteConfigLookup,
                onLoadConfigs = {
                    state.launchAction(scope, snackbarHostState, "Load Configs") {
                        state.loadRemoteConfigs()
                    }
                },
                onLookupCachedKey = { state.lookupCachedRemoteConfig() },
                experimentKeyInput = state.experimentKeyInput,
                onExperimentKeyInputChange = { state.experimentKeyInput = it },
                experimentResults = state.experimentResults,
                lastExperimentLoadAt = state.lastExperimentLoadAt,
                onGetAssignment = {
                    state.launchAction(scope, snackbarHostState, "Get Assignment") {
                        state.getExperimentAssignment()
                    }
                },
                logs = state.logs,
                statusText = state.statusText,
                appUserId = com.appactor.android.api.AppActor.shared.appUserId,
                isAnonymous = com.appactor.android.api.AppActor.shared.isAnonymous,
            )
        }
    }
}

@Composable
private fun ExampleSplashScreen(
    statusText: String,
    isConfiguring: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.example_splash_logo),
                        contentDescription = "AppActor logo",
                        modifier = Modifier.size(132.dp),
                    )
                    Text(
                        text = "AppActor Test App",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (isConfiguring) "SDK kuruluyor" else "SDK kurulumu durdu",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = statusText,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (isConfiguring) {
                        CircularProgressIndicator()
                    } else if (canRetry) {
                        Button(onClick = onRetry) {
                            Text("Retry Configure")
                        }
                    }
                }
            }
        }
    }
}

private fun screenTitle(
    mainTab: MainExampleTab,
    toolScreen: ToolExampleScreen,
): String {
    return when (mainTab) {
        MainExampleTab.Console -> "Control Center"
        MainExampleTab.Billing -> "Catalog & Purchase"
        MainExampleTab.Customer -> "Customer State"
        MainExampleTab.Tools -> toolScreen.label
    }
}
