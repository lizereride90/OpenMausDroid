package com.openmausdroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmausdroid.app.core.Omb
import com.openmausdroid.app.core.Runtime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class BotRow(val id: String, val name: String, val title: String, val model: String)
private data class ModelOption(val instanceId: String, val instanceName: String, val modelId: String, val label: String)

@Composable
fun BotsScreen(onOpenSetup: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ready by Runtime.serverReady.collectAsState()

    var bots by remember { mutableStateOf<List<BotRow>>(emptyList()) }
    var models by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var modelFor by remember { mutableStateOf<BotRow?>(null) }

    suspend fun reload() {
        runCatching {
            val arr = Omb.bots()
            bots = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                val selection = obj.optJSONObject("modelSelection")
                BotRow(
                    id = id,
                    name = obj.optString("name").ifEmpty { "Bot" },
                    title = obj.optString("title"),
                    model = listOf(
                        selection?.optString("instanceId").orEmpty(),
                        selection?.optString("model").orEmpty(),
                    ).filter { it.isNotEmpty() }.joinToString(" / "),
                )
            }
            val inst = Omb.instances()
            val options = mutableListOf<ModelOption>()
            for (i in 0 until inst.length()) {
                val obj = inst.optJSONObject(i) ?: continue
                val instanceId = obj.optString("instanceId")
                val instanceName = obj.optString("displayName").ifEmpty { instanceId }
                val modelsObj = obj.optJSONObject("models") ?: continue
                val modelArr = modelsObj.optJSONArray("options") ?: JSONArray()
                for (j in 0 until modelArr.length()) {
                    val m = modelArr.optJSONObject(j) ?: continue
                    val modelId = m.optString("id")
                    if (modelId.isEmpty()) continue
                    options += ModelOption(
                        instanceId = instanceId,
                        instanceName = instanceName,
                        modelId = modelId,
                        label = m.optString("label").ifEmpty { modelId },
                    )
                }
            }
            models = options
        }.onFailure { error = it.message }
    }

    LaunchedEffect(ready) {
        if (ready) reload()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ServiceBanner(onOpenSetup = onOpenSetup)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Bots", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { showCreate = true }, enabled = ready) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("New")
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(bots, key = { it.id }) { bot ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium)
                            if (bot.title.isNotBlank()) {
                                Text(
                                    bot.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = bot.model.ifEmpty { "model: default" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        OutlinedButton(onClick = { modelFor = bot }, enabled = ready) {
                            Text("Model")
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    runCatching { Omb.deleteBot(bot.id) }
                                        .onSuccess { reload() }
                                        .onFailure { error = it.message }
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete bot")
                        }
                    }
                }
            }
            if (bots.isEmpty() && ready) {
                item {
                    Text(
                        "No bots yet - create one, or wait for the default bot to appear.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateBotDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, description ->
                showCreate = false
                scope.launch {
                    runCatching { Omb.createBot(name, description.ifBlank { null }) }
                        .onSuccess { reload() }
                        .onFailure { error = it.message }
                }
            },
        )
    }

    modelFor?.let { target ->
        ModelPickerDialog(
            bot = target,
            options = models,
            onDismiss = { modelFor = null },
            onPick = { option ->
                modelFor = null
                scope.launch {
                    runCatching {
                        Omb.setModel(
                            target.id,
                            JSONObject()
                                .put("instanceId", option.instanceId)
                                .put("model", option.modelId),
                        )
                    }.onSuccess { reload() }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

@Composable
private fun CreateBotDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New bot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / soul (optional)") },
                    minLines = 2,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), description.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelPickerDialog(
    bot: BotRow,
    options: List<ModelOption>,
    onDismiss: () -> Unit,
    onPick: (ModelOption) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model for ${bot.name}") },
        text = {
            if (options.isEmpty()) {
                Text("No provider models are available yet. Connect a provider in Settings.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(options, key = { "${it.instanceId}|${it.modelId}" }) { option ->
                        TextButton(
                            onClick = { onPick(option) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${option.instanceName} - ${option.modelId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
