package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConfigurationSection(
    configured: Boolean,
) {
    PRCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionHeader(
                    title = "Configuration",
                    glyph = "CF",
                    color = ExamplePalette.Accent,
                    subtitle = "SDK reset ve bootstrap durumunu kontrol et",
                )
                Spacer(Modifier.weight(1f))
                StatusBadge(
                    text = if (configured) "Configured" else "Connecting...",
                    color = if (configured) ExamplePalette.Success else ExamplePalette.Warning,
                )
            }
            Text(
                text = "Example app launch sirasinda canonical configure akisini otomatik calistirir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
