package com.openmausdroid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openmausdroid.app.R
import com.openmausdroid.app.core.Omb
import com.openmausdroid.app.core.Runtime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class ChatBot(
    val id: String,
    val name: String,
    val title: String,
    val threadId: String?,
)

private data class ChatCard(
    val title: String,
    val subtitle: String,
    val options: List<String>,
    val requestId: String?,
    val tool: String?,
    val answered: String?,
    val expired: Boolean,
)

private data class ChatMsg(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val card: ChatCard?,
)

@Composable
fun ChatScreen(onOpenSetup: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ready by Runtime.serverReady.collectAsState()
    val events by Omb.events.collectAsState(initial = null)

    var bots by remember { mutableStateOf<List<ChatBot>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val bot = bots.firstOrNull { it.id == selectedId }

    suspend fun reloadBots() {
        runCatching {
            val arr = Omb.bots()
            val list = (0 until arr.length()).mapNotNull { i -> parseBot(arr.optJSONObject(i)) }
            bots = list
            if (selectedId == null || list.none { it.id == selectedId }) {
                selectedId = list.firstOrNull()?.id
            }
        }.onFailure { error = it.message }
    }

    suspend fun reloadMessages() {
        val thread = bot?.threadId ?: return
        runCatching {
            messages = parseMessages(Omb.threadMessages(thread))
        }.onFailure { error = it.message }
    }

    LaunchedEffect(ready) {
        if (ready) {
            reloadBots()
            reloadMessages()
        }
    }

    LaunchedEffect(selectedId) {
        if (ready && selectedId != null) reloadMessages()
    }

    // Refresh on harness events that change chats.
    val eventKind = events?.kind
    LaunchedEffect(eventKind) {
        if (ready && (eventKind == "bot" || eventKind == "bot.deleted" ||
                eventKind == "message" || eventKind == "message.patch")
        ) {
            reloadBots()
            reloadMessages()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val target = bot ?: return
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { Omb.sendMessage(target.id, text, target.threadId) }
                .onSuccess {
                    draft = ""
                    reloadMessages()
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun answer(card: ChatCard?, option: String) {
        if (card == null) return
        val target = bot ?: return
        val requestId = card.requestId ?: return
        val thread = target.threadId ?: return
        busy = true
        error = null
        scope.launch {
            runCatching {
                val behavior = when {
                    card.tool != null -> if (option == "Deny") "deny" else "allow"
                    else -> "answer"
                }
                if (behavior == "answer") {
                    Omb.respond(thread, requestId, behavior, option)
                } else {
                    Omb.respond(thread, requestId, behavior, null)
                }
                reloadMessages()
            }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ServiceBanner(onOpenSetup = onOpenSetup)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(bot?.name ?: if (bots.isEmpty()) "No bots" else "Choose bot")
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    bots.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name) },
                            onClick = {
                                selectedId = item.id
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            bot?.title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = if (!ready) {
                            "Start the environment to chat."
                        } else {
                            "Say hello - your message goes to the bot running in the Ubuntu environment."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(messages, key = { it.id }) { msg ->
                if (msg.fromUser) {
                    UserBubble(msg.text)
                } else {
                    BotBubble(msg) { option -> answer(msg.card, option) }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                maxLines = 5,
            )
            Button(
                onClick = { send() },
                enabled = ready && !busy && draft.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.widthIn(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Send, contentDescription = null)
                }
                Text(stringResource(R.string.send))
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BotBubble(msg: ChatMsg, onAnswer: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp),
    ) {
        if (msg.text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        msg.card?.let { card ->
            ApprovalCard(card = card, onAnswer = onAnswer)
        }
    }
}

@Composable
private fun ApprovalCard(card: ChatCard, onAnswer: (String) -> Unit) {
    val settled = card.answered != null && card.answered.isNotEmpty()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (settled) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (settled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            if (card.subtitle.isNotBlank()) {
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            when {
                card.expired -> Text("This request expired.", style = MaterialTheme.typography.bodySmall)
                settled -> Text("Answered: ${card.answered}", style = MaterialTheme.typography.bodySmall)
                else -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    card.options.forEach { option ->
                        if (card.tool != null) {
                            if (option == "Deny") {
                                OutlinedButton(
                                    enabled = true,
                                    onClick = { onAnswer(option) },
                                ) { Text(option) }
                            } else {
                                Button(onClick = { onAnswer(option) }) { Text(option) }
                            }
                        } else {
                            Button(onClick = { onAnswer(option) }) { Text(option) }
                        }
                    }
                }
            }
        }
    }
}

private fun parseBot(obj: JSONObject?): ChatBot? {
    if (obj == null) return null
    val id = obj.optString("id")
    if (id.isEmpty()) return null
    return ChatBot(
        id = id,
        name = obj.optString("name").ifEmpty { "Bot" },
        title = obj.optString("title"),
        threadId = obj.optString("threadId").ifEmpty { null },
    )
}

private fun parseMessages(arr: JSONArray): List<ChatMsg> =
    (0 until arr.length()).mapNotNull { i ->
        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = obj.optString("id").ifEmpty { "m$i" }
        val card = obj.optJSONObject("card")?.let(::parseCard)
        ChatMsg(
            id = id,
            fromUser = obj.optString("role") == "user",
            text = obj.optString("text"),
            card = card,
        )
    }

private fun parseCard(obj: JSONObject): ChatCard {
    val optionsArr = obj.optJSONArray("options") ?: JSONArray()
    val options = (0 until optionsArr.length()).map { optionsArr.optString(it) }
    return ChatCard(
        title = obj.optString("title").ifEmpty { "Approval needed" },
        subtitle = obj.optString("subtitle"),
        options = options,
        requestId = obj.optString("requestId").ifEmpty { null },
        tool = obj.optString("tool").ifEmpty { null },
        answered = obj.optString("answered"),
        expired = obj.optBoolean("expired"),
    )
}
