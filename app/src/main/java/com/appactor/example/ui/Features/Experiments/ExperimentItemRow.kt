package com.appactor.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appactor.android.models.AppActorExperimentAssignment

@Composable
fun ExperimentItemRow(assignment: AppActorExperimentAssignment) {
    OutlinedCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListItem(
                headlineContent = {
                    Text(assignment.experimentKey, fontWeight = FontWeight.SemiBold)
                },
                supportingContent = { Text(assignment.variantKey) },
                trailingContent = {
                    Text(assignment.valueType.name, color = ExamplePalette.Success)
                },
            )
            Text(
                text = assignment.payload.prettyDisplay(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
