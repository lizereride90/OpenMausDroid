package com.openmausdroid.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

data class OmbEvent(val kind: String, val data: JSONObject)

/**
 * Thin client for the OpenMausBot harness API on 127.0.0.1:8799.
 * The harness rejects requests whose Origin does not point back at itself,
 * so every call (including the SSE stream) carries that Origin header.
 */
object Omb {

    const val PORT = 8799
    const val BASE = "http://127.0.0.1:$PORT"

    private const val ORIGIN = "http://127.0.0.1:$PORT"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventsJob: kotlinx.coroutines.Job? = null
    private var sseCall: okhttp3.Call? = null

    val events = MutableSharedFlow<OmbEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val available: Boolean
        get() = Runtime.serverReady.value

    // ---------------------------------------------------------------- core

    suspend fun get(path: String): JSONObject = request("GET", path, null)
    suspend fun getArray(path: String): JSONArray = requestRaw("GET", path, null).let {
        when {
            it.startsWith("[") -> JSONArray(it)
            else -> throw IllegalStateException("expected JSON array: ${it.take(200)}")
        }
    }

    suspend fun post(path: String, body: JSONObject?): JSONObject =
        request("POST", path, body?.toString())

    suspend fun patch(path: String, body: JSONObject?): JSONObject =
        request("PATCH", path, body?.toString())

    suspend fun delete(path: String) {
        requestRaw("DELETE", path, null)
    }

    private suspend fun request(method: String, path: String, body: String?): JSONObject {
        val text = requestRaw(method, path, body)
        if (text.isBlank()) return JSONObject()
        return JSONObject(text)
    }

    private suspend fun requestRaw(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url("$BASE$path")
                .header("Origin", ORIGIN)
                .header("Accept", "application/json")
            if (body != null) {
                builder.method(method, body.toRequestBody(JSON_TYPE))
            } else {
                builder.method(method, null)
            }
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("$method $path -> HTTP ${resp.code}: ${text.take(300)}")
                }
                text
            }
        }

    // ------------------------------------------------------------- API ops

    suspend fun health(): JSONObject = get("/api/health")

    suspend fun config(): JSONObject = get("/api/config")

    suspend fun patchConfig(patch: JSONObject): JSONObject = patch("/api/config", patch)

    suspend fun bots(): JSONArray =
        get("/api/bots?messages=50").optJSONArray("bots") ?: JSONArray()

    suspend fun instances(): JSONArray =
        get("/api/instances").optJSONArray("instances") ?: JSONArray()

    suspend fun createBot(name: String, soul: String? = null): JSONObject {
        val body = JSONObject().put("name", name).put("approvalMode", "ask")
        if (!soul.isNullOrBlank()) body.put("soul", soul)
        return post("/api/bots", body)
    }

    suspend fun deleteBot(id: String) = delete("/api/bots/$id")

    suspend fun sendMessage(botId: String, text: String, threadId: String? = null): JSONObject {
        val body = JSONObject().put("text", text)
        if (threadId != null) body.put("threadId", threadId)
        return post("/api/bots/$botId/messages", body)
    }

    suspend fun setModel(botId: String, selection: JSONObject): JSONObject =
        patch("/api/bots/$botId/model", selection)

    suspend fun threadMessages(threadId: String): JSONArray =
        get("/api/threads/$threadId/messages").optJSONArray("messages") ?: JSONArray()

    suspend fun respond(
        threadId: String,
        requestId: String,
        behavior: String,
        message: String? = null,
    ): JSONObject {
        val body = JSONObject()
            .put("behavior", behavior)
            .put("requestId", requestId)
        if (message != null) body.put("message", message)
        return post("/api/threads/$threadId/respond", body)
    }

    suspend fun mcpServers(): JSONArray =
        get("/api/mcp/servers").optJSONArray("servers") ?: JSONArray()

    suspend fun addMcpServer(config: JSONObject): JSONObject =
        post("/api/mcp/servers", config)

    suspend fun removeMcpServer(name: String) = delete("/api/mcp/servers/$name")

    suspend fun skills(botId: String): JSONArray =
        get("/api/bots/$botId/skills").optJSONArray("skills") ?: JSONArray()

    suspend fun addSkill(botId: String, sourceUrl: String): JSONObject =
        post("/api/bots/$botId/skills", JSONObject().put("source", sourceUrl))

    suspend fun removeSkill(botId: String, name: String) =
        delete("/api/bots/$botId/skills/$name")

    /** Imports an agent .md file: the server accepts the raw markdown as a JSON string. */
    suspend fun importTeam(rawMarkdown: String, mode: String = "add"): JSONObject {
        val quoted = JSONObject.quote(rawMarkdown)
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE/api/teams/import?mode=$mode")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .post(quoted.toRequestBody(JSON_TYPE))
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("import -> HTTP ${resp.code}: ${text.take(300)}")
                }
                text
            }
        }.let { return if (it.isBlank()) JSONObject() else JSONObject(it) }
    }

    /** Installs the immutable app prompt as the profile text sent to every bot. */
    suspend fun enforcePrompt() {
        val prompt = appContext.assets.open("prompts/system-prompt.md")
            .bufferedReader().use { it.readText() }
        val patch = JSONObject().put(
            "profile",
            JSONObject().put("aboutMe", prompt.trim()),
        )
        patchConfig(patch)
        Prefs.promptEnforced = true
        Runtime.append("system prompt enforced")
    }

    // ----------------------------------------------------------------- SSE

    fun startEvents() {
        if (eventsJob?.isActive == true) return
        eventsJob = scope.launch {
            var backoff = 1000L
            while (isActive) {
                try {
                    connectSse()
                    backoff = 1000L
                } catch (e: Exception) {
                    if (!isActive) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    fun stopEvents() {
        eventsJob?.cancel()
        eventsJob = null
        // The SSE read blocks on the socket; closing the call ends readLine().
        runCatching { sseCall?.cancel() }
        sseCall = null
    }

    private suspend fun connectSse() {
        val request = Request.Builder()
            .url("$BASE/api/events")
            .header("Origin", ORIGIN)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = sseHttp.newCall(request)
        sseCall = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("events -> HTTP ${resp.code}")
            val reader = resp.body?.byteStream()
                ?.bufferedReader() ?: throw IllegalStateException("no events body")
            readEvents(reader)
        }
    }

    private suspend fun readEvents(reader: BufferedReader) {
        val data = StringBuilder()
        val active = kotlin.coroutines.coroutineContext.isActive
        while (active) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        dispatch(data.toString())
                        data.setLength(0)
                    }
                }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
        }
    }

    private fun dispatch(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val kind = json.optString("kind", json.optString("event", ""))
        if (kind.isEmpty()) return
        events.tryEmit(OmbEvent(kind, json))
    }

    suspend fun waitUntilReady(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { health() }.isSuccess) return@withContext true
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return@withContext false
            }
        }
        false
    }
}
