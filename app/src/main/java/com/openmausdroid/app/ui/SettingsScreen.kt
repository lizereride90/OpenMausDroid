package com.openmausdroid.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openmausdroid.app.core.Omb
import com.openmausdroid.app.core.Runtime
import com.openmausdroid.app.service.MausService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class McpRow(val name: String, val detail: String)
private data class SkillRow(val name: String, val detail: String)
private data class SimpleBot(val id: String, val name: String)

@Composable
fun SettingsScreen(onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready by Runtime.serverReady.collectAsState()
    val phase by Runtime.phase.collectAsState()

    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var anthropicKey by remember { mutableStateOf("") }
    var anthropicUrl by remember { mutableStateOf("") }
    var configLoaded by remember { mutableStateOf(false) }

    var mcpServers by remember { mutableStateOf<List<McpRow>>(emptyList()) }
    var showMcpAdd by remember { mutableStateOf(false) }

    var bots by remember { mutableStateOf<List<SimpleBot>>(emptyList()) }
    var skillBotId by remember { mutableStateOf<String?>(null) }
    var skills by remember { mutableStateOf<List<SkillRow>>(emptyList()) }
    var skillSource by remember { mutableStateOf("") }

    var importResult by remember { mutableStateOf<String?>(null) }
    var showPrompt by remember { mutableStateOf(false) }

    suspend fun loadMcp() {
        runCatching {
            val arr = Omb.mcpServers()
            mcpServers = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isEmpty()) return@mapNotNull null
                val detail = when {
                    o.optString("url").isNotEmpty() -> o.optString("url")
                    o.optString("command").isNotEmpty() ->
                        listOf(o.optString("command"), o.optJSONArray("args").orJoin())
                            .filter { it.isNotEmpty() }.joinToString(" ")
                    else -> "-"
                }
                McpRow(name, detail)
            }
        }.onFailure { error = it.message }
    }

    suspend fun loadBots() {
        runCatching {
            val arr = Omb.bots()
            bots = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty()) null else SimpleBot(id, o.optString("name").ifEmpty { "Bot" })
            }
            if (skillBotId == null || bots.none { it.id == skillBotId }) {
                skillBotId = bots.firstOrNull()?.id
            }
        }.onFailure { error = it.message }
    }

    suspend fun loadSkills() {
        val botId = skillBotId ?: return
        runCatching {
            val obj = Omb.skills(botId)
            skills = (0 until obj.length()).mapNotNull { i ->
                val s = obj.optJSONObject(i) ?: return@mapNotNull null
                val name = s.optString("name")
                if (name.isEmpty()) null
                else SkillRow(name, s.optString("source").ifEmpty { s.optString("description") })
            }
        }.onFailure { error = it.message }
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        runCatching {
            val cfg = Omb.config()
            val anthropic = cfg.optJSONObject("anthropic")
            anthropicUrl = anthropic?.optString("url").orEmpty()
            anthropicKey = anthropic?.optString("key").orEmpty()
            configLoaded = true
        }.onFailure { error = it.message }
        loadMcp()
        loadBots()
        loadSkills()
    }

    LaunchedEffect(skillBotId) {
        if (ready && skillBotId != null) loadSkills()
    }

    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                            ?: throw IllegalStateException("could not read file")
                    }
                    Omb.importTeam(raw)
                    importResult = "Agent imported."
                }.onFailure {
                    error = it.message
                    importResult = null
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ServiceBanner(onOpenSetup = onOpenSetup)
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        // ------------------------------------------------------- environment
        item {
            SectionCard(title = "Environment") {
                Text(
                    text = when (phase) {
                        Runtime.Phase.READY -> "Running"
                        Runtime.Phase.IDLE -> "Stopped"
                        else -> "Working: ${phase.name.lowercase()}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { MausService.start(context) }) { Text("Start") }
                    OutlinedButton(
                        onClick = { MausService.stop(context) },
                        enabled = phase != Runtime.Phase.IDLE && phase != Runtime.Phase.STOPPED,
                    ) { Text("Stop") }
                    TextButton(onClick = onOpenSetup) { Text("Setup / logs") }
                }
            }
        }

        // ---------------------------------------------------------- provider
        item {
            SectionCard(title = "Provider (Anthropic)") {
                Text(
                    "Enter an API key to chat through Anthropic. Claude Code and " +
                        "Codex CLIs installed in the environment can also sign in " +
                        "themselves from the terminal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it },
                    label = {
                        Text(if (configLoaded && anthropicKey.isNotEmpty()) "API key (saved)" else "API key")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = anthropicUrl,
                    onValueChange = { anthropicUrl = it },
                    label = { Text("Base URL (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val anthropic = JSONObject()
                                if (anthropicKey.isNotBlank()) anthropic.put("key", anthropicKey.trim())
                                if (anthropicUrl.isNotBlank()) anthropic.put("url", anthropicUrl.trim())
                                if (anthropic.length() == 0) throw IllegalStateException("nothing to save")
                                Omb.patchConfig(JSONObject().put("anthropic", anthropic))
                            }.onSuccess {
                                notice = "Provider settings saved."
                                anthropicKey = ""
                                error = null
                            }.onFailure { error = it.message }
                        }
                    },
                    enabled = ready && (anthropicKey.isNotBlank() || anthropicUrl.isNotBlank()),
                ) { Text("Save provider") }
                notice?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ------------------------------------------------------------ agents
        item {
            SectionCard(title = "Agents (.md files)") {
                Text(
                    "Upload an agent definition. The file's frontmatter names it; " +
                        "it is imported into the environment and can then be " +
                        "assigned to a bot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { mdLauncher.launch(arrayOf("text/markdown", "text/plain", "text/*")) },
                    enabled = ready,
                ) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null)
                    Text(" Import agent file")
                }
                importResult?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // -------------------------------------------------------------- MCP
        item {
            SectionCard(title = "MCP servers") {
                if (mcpServers.isEmpty()) {
                    Text(
                        "No MCP servers configured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                mcpServers.forEach { server ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                server.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    runCatching { Omb.removeMcpServer(server.name) }
                                        .onSuccess { loadMcp() }
                                        .onFailure { err -> error = err.message }
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove server")
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showMcpAdd = true },
                    enabled = ready,
                ) { Text("Add MCP server") }
            }
        }

        // ----------------------------------------------------------- skills
        item {
            SectionCard(title = "Skills") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    bots.forEach { bot ->
                        TextButton(
                            onClick = { skillBotId = bot.id },
                        ) {
                            Text(
                                bot.name,
                                color = if (bot.id == skillBotId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                skills.forEach { skill ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                            if (skill.detail.isNotBlank()) {
                                Text(
                                    skill.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val botId = skillBotId ?: return@IconButton
                                scope.launch {
                                    runCatching { Omb.removeSkill(botId, skill.name) }
                                        .onSuccess { loadSkills() }
                                        .onFailure { err -> error = err.message }
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove skill")
                        }
                    }
                }
                OutlinedTextField(
                    value = skillSource,
                    onValueChange = { skillSource = it },
                    label = { Text("Skill source URL (github.com/...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        val botId = skillBotId ?: return@OutlinedButton
                        scope.launch {
                            runCatching { Omb.addSkill(botId, skillSource.trim()) }
                                .onSuccess {
                                    skillSource = ""
                                    loadSkills()
                                }
                                .onFailure { err -> error = err.message }
                        }
                    },
                    enabled = ready && skillBotId != null && skillSource.isNotBlank(),
                ) { Text("Install skill") }
            }
        }

        // ------------------------------------------------------------- prompt
        item {
            SectionCard(title = "System prompt") {
                Text(
                    "The app injects an immutable environment prompt into every " +
                        "conversation (profile text), before any agent file. Edit " +
                        "agent files to change behaviour; this prompt stays fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showPrompt = true }) { Text("Read prompt") }
            }
        }

        item {
            HorizontalDivider()
            Text(
                "OpenMausDroid - Ubuntu + OpenMausBot on Android",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (showMcpAdd) {
        AddMcpDialog(
            onDismiss = { showMcpAdd = false },
            onAdd = { body ->
                showMcpAdd = false
                scope.launch {
                    runCatching { Omb.addMcpServer(body) }
                        .onSuccess { loadMcp() }
                        .onFailure { err -> error = err.message }
                }
            },
        )
    }

    if (showPrompt) {
        PromptDialog(onDismiss = { showPrompt = false })
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AddMcpDialog(onDismiss: () -> Unit, onAdd: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (a-z, 0-9, -)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Remote URL (https://...)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("or command (npx -y server)", maxLines = 1) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (url.isNotBlank() || command.isNotBlank()),
                onClick = {
                    val body = JSONObject().put("name", name.trim()).put("enabled", true)
                    if (url.isNotBlank()) {
                        body.put("url", url.trim())
                    } else {
                        val parts = command.trim().split(" ").filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) {
                            body.put("command", parts.first())
                            val args = JSONArray()
                            parts.drop(1).forEach { args.put(it) }
                            body.put("args", args)
                        }
                    }
                    onAdd(body)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PromptDialog(onDismiss: () -> Unit) {
    val prompt = remember {
        runCatching {
            com.openmausdroid.app.OpenMausApp.instance.assets
                .open("prompts/system-prompt.md")
                .bufferedReader().use { it.readText() }
        }.getOrDefault("(prompt missing)")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Immutable system prompt") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun JSONArray?.orJoin(): String {
    if (this == null) return ""
    return (0 until length()).joinToString(" ") { optString(it) }
}
