package com.openmausdroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmausdroid.app.core.Runtime

private const val TTYD_URL = "http://127.0.0.1:7681"

@Composable
fun TerminalScreen(onOpenSetup: () -> Unit) {
    val ready by Runtime.serverReady.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ServiceBanner(onOpenSetup = onOpenSetup)
        if (ready) {
            WebViewBox(
                url = TTYD_URL,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally),
            )
        } else {
            Text(
                "The terminal is available once the environment is running.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
