package com.openmausdroid.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openmausdroid.app.core.Prefs
import com.openmausdroid.app.ui.AppShell
import com.openmausdroid.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                FirstLaunchPrompts()
                AppShell()
            }
        }
    }
}

/**
 * First run asks for the two things the environment needs from Android:
 * notification permission (so the foreground service survives) and
 * all-files access (so the rootfs can see the user's storage at /sdcard).
 * Battery optimization is requested right after, to keep the service alive.
 */
@Composable
private fun FirstLaunchPrompts() {
    val context = LocalContext.current
    var stage by remember {
        mutableStateOf(
            when {
                !Prefs.notifAsked -> Stage.NOTIFICATIONS
                !Prefs.storageAsked -> Stage.STORAGE
                !Prefs.batteryAsked -> Stage.BATTERY
                else -> Stage.DONE
            },
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        Prefs.notifAsked = true
        stage = if (!Prefs.storageAsked) Stage.STORAGE else Stage.BATTERY
    }

    when (stage) {
        Stage.NOTIFICATIONS -> AlertDialog(
            onDismissRequest = {
                Prefs.notifAsked = true
                stage = if (!Prefs.storageAsked) Stage.STORAGE else Stage.BATTERY
            },
            title = { Text("Stay notified") },
            text = {
                Text(
                    "OpenMausDroid runs an Ubuntu environment with a bot harness " +
                        "in the background. Notifications keep Android from silently " +
                        "stopping it and tell you when a bot needs your approval.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Prefs.notifAsked = true
                        stage = if (!Prefs.storageAsked) Stage.STORAGE else Stage.BATTERY
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Prefs.notifAsked = true
                    stage = if (!Prefs.storageAsked) Stage.STORAGE else Stage.BATTERY
                }) { Text("Not now") }
            },
        )

        Stage.STORAGE -> AlertDialog(
            onDismissRequest = {
                Prefs.storageAsked = true
                stage = if (!Prefs.batteryAsked) Stage.BATTERY else Stage.DONE
            },
            title = { Text("All-files access") },
            text = {
                Text(
                    "Grant all-files access so the Ubuntu environment can reach " +
                        "your shared storage (visible inside as /sdcard). You can " +
                        "skip this and grant it later from Settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.storageAsked = true
                    stage = if (!Prefs.batteryAsked) Stage.BATTERY else Stage.DONE
                    runCatching {
                        context.startActivity(
                            Intent(SETTINGS_FILES, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Prefs.storageAsked = true
                    stage = if (!Prefs.batteryAsked) Stage.BATTERY else Stage.DONE
                }) { Text("Skip") }
            },
        )

        Stage.BATTERY -> AlertDialog(
            onDismissRequest = {
                Prefs.batteryAsked = true
                stage = Stage.DONE
            },
            title = { Text("Keep it running") },
            text = {
                Text(
                    "Allow OpenMausDroid to ignore battery optimizations so the " +
                        "environment is not killed while a bot is working.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.batteryAsked = true
                    stage = Stage.DONE
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Prefs.batteryAsked = true
                    stage = Stage.DONE
                }) { Text("Later") }
            },
        )

        Stage.DONE -> Unit
    }
}

private enum class Stage { NOTIFICATIONS, STORAGE, BATTERY, DONE }

private const val SETTINGS_FILES = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
