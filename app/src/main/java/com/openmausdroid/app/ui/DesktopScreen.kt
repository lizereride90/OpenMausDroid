package com.openmausdroid.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmausdroid.app.core.Runtime
import com.openmausdroid.app.core.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DesktopScreen(onOpenSetup: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ready by Runtime.serverReady.collectAsState()
    val running by Runtime.sessions.collectAsState()
    var selected by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }

    // Keep the running set fresh even when the service watchdog is quiet.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            Sessions.refresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ServiceBanner(onOpenSetup = onOpenSetup)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            (1..3).forEach { n ->
                val active = running.contains(n)
                if (active) {
                    Button(
                        onClick = { selected = n },
                    ) { Text(if (selected == n) "Session $n (viewing)" else "View session $n") }
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) { Sessions.stop(n) }
                        },
                    ) { Text("Stop $n") }
                } else {
                    OutlinedButton(
                        onClick = {
                            error = null
                            scope.launch(Dispatchers.IO) {
                                val ok = Sessions.start(n)
                                if (!ok) error = "Could not start session $n"
                            }
                        },
                        enabled = ready,
                    ) { Text("Start $n") }
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (running.contains(selected)) {
            WebViewBox(
                url = Sessions.vncUrl(selected),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            Text(
                "Start a session to see the XFCE desktop. Multiple sessions run " +
                    "side by side, each with its own VNC display.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
