package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appactor.android.models.AppActorRemoteConfigs

@Composable
fun RemoteConfigScreen(
    modifier: Modifier,
    configured: Boolean,
    busy: Boolean,
    remoteConfigLookupKey: String,
    onRemoteConfigLookupKeyChange: (String) -> Unit,
    remoteConfigs: AppActorRemoteConfigs?,
    lastRemoteConfigLoadAt: String?,
    lastRemoteConfigLookup: ExampleConfigLookup?,
    onLoadConfigs: () -> Unit,
    onLookupCachedKey: () -> Unit,
) {
    ToolingPage(
        modifier = modifier,
        title = "Remote Config",
        subtitle = "Fetch, cache lookup ve ham config ciktilarini standart Material listesi gibi gor.",
    ) {
        item {
            ToolingSection(
                title = "Lookup",
                subtitle = "Fetch sonrasi cache'ten tek key kontrolu yap.",
            ) {
                OutlinedTextField(
                    value = remoteConfigLookupKey,
                    onValueChange = onRemoteConfigLookupKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Config key") },
                )
                FilledTonalButton(
                    onClick = onLoadConfigs,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Load Configs")
                }
                FilledTonalButton(
                    onClick = onLookupCachedKey,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured && !busy,
                ) {
                    Text("Lookup Cached Key")
                }
            }
        }
        item {
            ToolingSection(
                title = "Status",
                subtitle = "Son fetch ve lookup sonucu.",
            ) {
                ToolingValue(label = "Items", value = "${remoteConfigs?.items?.size ?: 0}")
                ToolingValue(label = "Last Load", value = lastRemoteConfigLoadAt ?: "-")
                lastRemoteConfigLookup?.let { lookup ->
                    ToolingValue(label = "Lookup Key", value = lookup.key, monospaced = true)
                    Text(
                        text = lookup.value.prettyDisplay(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            ToolingSection(
                title = "Configs",
                subtitle = "Tum cache config kayitlari.",
            ) {
                if (remoteConfigs?.items.isNullOrEmpty()) {
                    Text("Config yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        remoteConfigs!!.items.sortedBy { it.key }.forEach { item ->
                            ConfigItemRow(item)
                        }
                    }
                }
            }
        }
    }
}
