package com.appactor.example.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.appactor.android.models.AppActorRemoteConfigItem

@Composable
fun ConfigItemRow(item: AppActorRemoteConfigItem) {
    OutlinedCard {
        ListItem(
            headlineContent = {
                Text(item.key, fontWeight = FontWeight.SemiBold)
            },
            supportingContent = {
                Text(
                    item.value.prettyDisplay(),
                    fontFamily = FontFamily.Monospace,
                )
            },
            trailingContent = {
                Text(item.valueType.name, color = ExamplePalette.Info)
            },
        )
    }
}
