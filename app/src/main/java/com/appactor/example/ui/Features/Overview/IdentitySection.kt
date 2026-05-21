package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun IdentitySection(
    appUserId: String?,
    isAnonymous: Boolean,
    loginUserId: String,
    onLoginUserIdChange: (String) -> Unit,
    configured: Boolean,
    busy: Boolean,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
) {
    PRCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionHeader(
                    title = "Identity",
                    glyph = "ID",
                    color = ExamplePalette.Info,
                    subtitle = "Anonymous, login ve logout akislari",
                )
                Spacer(Modifier.weight(1f))
                StatusBadge(
                    text = if (isAnonymous) "Anonymous" else "Identified",
                    color = if (isAnonymous) ExamplePalette.Warning else ExamplePalette.Success,
                )
            }
            IdentityValueRow(
                label = "App User ID",
                value = appUserId ?: "-",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    PRTextField(
                        value = loginUserId,
                        onValueChange = onLoginUserIdChange,
                        placeholder = "New User ID",
                    )
                }
                FilledTonalButton(
                    onClick = onLogIn,
                    enabled = configured && !busy,
                ) {
                    Text("Login")
                }
            }
            OutlinedButton(
                onClick = onLogOut,
                enabled = configured && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Logout")
            }
        }
    }
}
