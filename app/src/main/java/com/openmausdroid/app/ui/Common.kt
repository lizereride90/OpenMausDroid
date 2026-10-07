package com.openmausdroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmausdroid.app.core.Runtime

/** Shown on every API-backed screen while the environment is not serving. */
@Composable
fun ServiceBanner(onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    val ready by Runtime.serverReady.collectAsState()
    val phase by Runtime.phase.collectAsState()
    if (ready) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (phase) {
                    Runtime.Phase.IDLE -> "Environment not started"
                    Runtime.Phase.EXTRACTING -> "Extracting rootfs…"
                    Runtime.Phase.BOOTSTRAP -> "Installing packages…"
                    Runtime.Phase.STARTING -> "Starting harness…"
                    Runtime.Phase.FAILED -> "Environment failed - see Setup"
                    else -> "Harness not answering"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onOpenSetup) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Text(" Setup", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
