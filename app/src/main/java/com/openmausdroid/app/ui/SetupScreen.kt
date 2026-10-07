package com.openmausdroid.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openmausdroid.app.core.Prefs
import com.openmausdroid.app.core.Runtime
import com.openmausdroid.app.core.Setup
import com.openmausdroid.app.service.MausService

@Composable
fun SetupScreen(onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val phase by Runtime.phase.collectAsState()
    val progress by Runtime.setupProgress.collectAsState()
    val lines by Runtime.log.collectAsState()
    val error by Runtime.lastError.collectAsState()
    val listState = rememberLazyListState()

    var resumeTick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val notifGranted = remember(resumeTick) { notificationsGranted(context) }
    val filesGranted = remember(resumeTick) { filesAccessGranted() }
    val batteryGranted = remember(resumeTick) { batteryIgnored(context) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { resumeTick++ }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    val running = phase == Runtime.Phase.EXTRACTING ||
        phase == Runtime.Phase.BOOTSTRAP ||
        phase == Runtime.Phase.STARTING ||
        phase == Runtime.Phase.READY

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Environment", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = when (phase) {
                Runtime.Phase.IDLE ->
                    if (Setup.rootfsReady) "Installed. Start the environment to use it."
                    else "First start extracts Ubuntu and installs XFCE, VNC and OpenMausBot. " +
                        "This downloads nothing extra and can take 10-20 minutes."
                Runtime.Phase.EXTRACTING -> "Extracting the Ubuntu rootfs..."
                Runtime.Phase.BOOTSTRAP -> "Installing packages inside the rootfs..."
                Runtime.Phase.STARTING -> "Starting the harness and terminal..."
                Runtime.Phase.READY -> "Environment ready."
                Runtime.Phase.FAILED -> "Setup failed - see the log below."
                Runtime.Phase.STOPPED -> "Environment stopped."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (phase) {
            Runtime.Phase.EXTRACTING ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            Runtime.Phase.BOOTSTRAP, Runtime.Phase.STARTING ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else -> Unit
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phase == Runtime.Phase.READY) {
                Button(onClick = onOpenChat) { Text("Open chat") }
            } else {
                Button(
                    onClick = { MausService.start(context) },
                    enabled = !running,
                ) { Text(if (Setup.rootfsReady) "Start" else "Start setup") }
            }
            OutlinedButton(
                onClick = { MausService.stop(context) },
                enabled = running,
            ) { Text("Stop") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionChip(
                label = "Notifications",
                granted = notifGranted,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppSettings(context)
                    }
                },
            )
            PermissionChip(
                label = "Files",
                granted = filesGranted,
                onClick = { openFilesSettings(context) },
            )
            PermissionChip(
                label = "Battery",
                granted = batteryGranted,
                onClick = { openBatterySettings(context) },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (line.startsWith("ERROR")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionChip(label: String, granted: Boolean, onClick: () -> Unit) {
    if (granted) {
        Text(
            text = "$label: on",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text("$label: off", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == 0

private fun filesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

private fun batteryIgnored(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }
}

private fun openFilesSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }
}
