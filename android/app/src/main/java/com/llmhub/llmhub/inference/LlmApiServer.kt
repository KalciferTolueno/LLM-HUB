package com.llmhub.llmhub.inference

import android.content.Context
import android.util.Log
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.data.ModelPreferences
import com.llmhub.llmhub.data.ThemePreferences
import com.llmhub.llmhub.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID

@Serializable
data class ChatCompletionRequest(
    val model: String = "local",
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    /** OpenAI-style sampling (Ollama compat); null = use app/model defaults. */
    val temperature: Float? = null,
    val top_p: Float? = null,
    val max_tokens: Int? = null,
    /** Ollama native-style toggle (accepted on /v1 for compatibility): true/false. */
    val think: Boolean? = null,
    /** OpenAI-compatible reasoning control: high/medium/low/none (none disables thinking). */
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    /** Some clients send reasoning config as object, e.g. {"effort":"low"}. */
    val reasoning: JsonElement? = null,
    /**
     * OpenAI/Ollama-compatible: function tool definitions. Injected into the on-device prompt so
     * Gemma and similar models see the same capability list as behind a local Ollama proxy.
     */
    val tools: JsonElement? = null,
    /** OpenAI-style: "auto", "none", "required", or `{"type":"function","function":{"name":"..."}}`. */
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null
)

@Serializable
data class BearisChatListJson(val chats: List<BearisChatJson>)

@Serializable
data class BearisChatJson(
    val id: String,
    val title: String,
    val modelName: String,
    val updatedAt: Long
)

@Serializable
data class BearisMessagesJson(val messages: List<BearisMessageJson>)

@Serializable
data class BearisMessageJson(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    @SerialName("generation_duration_ms")
    val generationDurationMs: Long? = null
)

@Serializable
data class CreateChatBody(val title: String? = null)

@Serializable
data class PatchMessageBody(val content: String)

/** GET /v1/status — loaded model info for the web UI (header, capabilities). */
@Serializable
data class ApiStatusResponse(
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("supports_vision") val supportsVision: Boolean = false,
    @SerialName("supports_audio") val supportsAudio: Boolean = false,
    @SerialName("category") val category: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null
)

@Serializable
data class OpenAiModelListResponse(
    val `object`: String = "list",
    val data: List<OpenAiModelItem>
)

@Serializable
data class OpenAiModelItem(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    val owned_by: String = "llmhub"
)

@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiErrorDetail
)

@Serializable
data class OpenAiErrorDetail(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null
)

@Serializable
data class CreateChatResponse(val id: String)

/**
 * Accepts OpenAI-style string content or multimodal arrays (`[{ "type":"text","text":"..." }]`),
 * as sent by some IDE agents (e.g. Continue).
 */
object OpenAiMessageContentSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpenAiMessageContent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val dec = decoder as? JsonDecoder
            ?: error("OpenAiMessageContentSerializer requires Json input")
        return jsonElementToPlainText(dec.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    private fun jsonElementToPlainText(e: JsonElement): String {
        return when (e) {
            JsonNull -> ""
            is JsonPrimitive -> e.content
            is JsonArray ->
                e.joinToString("\n") { item ->
                    when (item) {
                        is JsonObject -> partObjectToText(item)
                        is JsonPrimitive -> item.content
                        else -> ""
                    }
                }
            is JsonObject -> partObjectToText(e)
        }
    }

    private fun partObjectToText(o: JsonObject): String {
        val type = o["type"]?.jsonPrimitive?.content
        if (type == "image_url" || type == "image") {
            return ""
        }
        o["text"]?.jsonPrimitive?.content?.let { return it }
        o["content"]?.jsonPrimitive?.content?.let { return it }
        return ""
    }

    /** For lenient request parsing (Roo Code, etc.) without a JsonDecoder. */
    fun flattenContentElement(e: JsonElement): String = jsonElementToPlainText(e)
}

@Serializable
data class ChatMessage(
    val role: String,
    @Serializable(with = OpenAiMessageContentSerializer::class)
    val content: String = "",
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCall>? = null
)

@Serializable
data class CompletionUsage(
    @SerialName("prompt_tokens") val prompt_tokens: Int,
    @SerialName("completion_tokens") val completion_tokens: Int,
    @SerialName("total_tokens") val total_tokens: Int
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    /** Rough counts (~4 chars/token), same spirit as Ollama/OpenAI proxies on device. */
    val usage: CompletionUsage? = null
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatAssistantMessage,
    val finish_reason: String? = "stop"
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>
)

@Serializable
data class ChatChunkChoice(
    val index: Int,
    val delta: ChatDelta,
    val finish_reason: String? = null
)

@Serializable
data class ChatDelta(
    /** OpenAI streams send this on the first chunk; some clients (e.g. Continue) wait for it. */
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCallDelta>? = null
)

@Serializable
data class ChatAssistantMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCall>? = null
)

@Serializable
data class ChatToolCall(
    val id: String,
    val type: String = "function",
    val function: ChatToolFunction
)

@Serializable
data class ChatToolFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class ChatToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ChatToolFunctionDelta? = null
)

@Serializable
data class ChatToolFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * Minimal OpenAI-style HTTP server (no Ktor/Netty) to avoid kotlinx-coroutines DEX / binary mismatches on Android.
 * Bundled web UI lives under [ASSET_WEB_DIR] (Vite build copied to app/src/main/assets/).
 */
class LlmApiServer(
    private val context: Context,
    private val inferenceService: InferenceService
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** SSE lines omit null fields so deltas look like OpenAI (`{"role":"assistant"}` then `{"content":"..."}`). */
    private val jsonSse = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Second-pass parse for clients that send non-standard but valid JSON (Roo Code, etc.). */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * Must be replaced after [cancel] — otherwise [start] after [stop] would launch handlers on a
     * dead scope and HTTP would accept connections but never respond.
     */
    @Volatile
    private var clientScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val chatRepository: ChatRepository by lazy {
        (context.applicationContext as LlmHubApplication).chatRepository
    }

    /** Active streaming generation for POST /v1/chat/cancel (stop reasoning). */
    private val activeStreamJob = AtomicReference<Job?>(null)

    fun start(port: Int = 8080) {
        if (clientScope.coroutineContext[Job]?.isActive != true) {
            clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        if (serverSocket != null) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket

            val t = Thread({
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        clientScope.launch {
                            handleClientSuspend(client)
                        }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.w("LlmApiServer", "accept failed", e)
                    }
                }
            }, "llm-api-accept")
            acceptThread = t
            t.start()
            Log.d("LlmApiServer", "Server started on port $port")
        } catch (e: Throwable) {
            try {
                serverSocket?.close()
            } catch (_: Throwable) {
            }
            serverSocket = null
            acceptThread = null
            Log.e("LlmApiServer", "Failed to start local API server", e)
            throw e
        }
    }

    /**
     * Cancels all in-flight HTTP handlers (including LAN chat/completions holding the inference mutex)
     * without closing the listening socket. Use before tearing down or swapping the loaded model.
     */
    fun cancelInFlightRequests(reason: String) {
        val ex = CancellationException(reason)
        activeStreamJob.getAndSet(null)?.cancel(ex)
        val old = clientScope
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        old.cancel(ex)
        Log.d("LlmApiServer", "cancelInFlightRequests: $reason")
    }

    fun stop() {
        val ex = CancellationException("local API server stopped")
        activeStreamJob.getAndSet(null)?.cancel(ex)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        val old = clientScope
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        old.cancel(ex)
        try {
            acceptThread?.join(2500)
        } catch (_: Throwable) {
        }
        acceptThread = null
        Log.d("LlmApiServer", "Server stopped")
    }

    fun isRunning(): Boolean {
        val s = serverSocket ?: return false
        return !s.isClosed
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LlmApiServer", "Error getting IP address", e)
        }
        return null
    }

    private suspend fun handleClientSuspend(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                val req = readHttpRequest(input) ?: run {
                    writeOpenAiError(output, 400, "Invalid HTTP request", "invalid_request_error")
                    return
                }

                when {
                    req.method == "POST" && req.path == "/v1/chat/completions" -> {
                        handleChatCompletions(output, req.body)
                    }
                    req.method == "POST" && req.path == "/v1/chat/cancel" -> {
                        handleCancelGeneration(output)
                    }
                    req.method == "GET" && req.path == "/v1/chats" -> {
                        handleListChatsSuspend(output)
                    }
                    req.method == "POST" && req.path == "/v1/chats" -> {
                        handleCreateChatSuspend(output, req.body)
                    }
                    req.method == "GET" && isChatMessagesPath(req.path) -> {
                        handleGetChatMessagesSuspend(output, req.path)
                    }
                    req.method == "PATCH" && isPatchMessagePath(req.path) -> {
                        handlePatchMessageSuspend(output, req.path, req.body)
                    }
                    req.method == "DELETE" && isDeleteChatPath(req.path) -> {
                        handleDeleteChatSuspend(output, req.path)
                    }
                    req.method == "GET" && req.path == "/v1/status" -> {
                        handleApiStatus(output)
                    }
                    req.method == "GET" && req.path == "/v1/models" -> {
                        handleListModels(output)
                    }
                    req.method == "GET" -> {
                        serveStaticGet(req.path, output)
                    }
                    else -> {
                        writeOpenAiError(output, 405, "Method Not Allowed", "invalid_request_error")
                    }
                }
            } catch (e: Exception) {
                Log.e("LlmApiServer", "client handler error", e)
            }
        }
    }

    private fun parseChatCompletionRequest(body: String): ChatCompletionRequest {
        val trimmed = body.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty()) throw IllegalArgumentException("empty body")
        try {
            return json.decodeFromString(ChatCompletionRequest.serializer(), trimmed)
        } catch (_: Exception) {
            return decodeChatCompletionRequestLenient(trimmed)
        }
    }

    private fun decodeChatCompletionRequestLenient(body: String): ChatCompletionRequest {
        val root = lenientJson.decodeFromString(JsonElement.serializer(), body)
        val obj = root as? JsonObject ?: throw IllegalArgumentException("expected JSON object")
        val model = obj["model"]?.jsonPrimitive?.content ?: "local"
        val stream = jsonElementToStreamFlag(obj["stream"])
        val messagesArr = obj["messages"] as? JsonArray
            ?: throw IllegalArgumentException("messages must be a non-null array")
        val messages = messagesArr.mapIndexed { idx, el ->
            val mo = el as? JsonObject ?: throw IllegalArgumentException("messages[$idx] must be object")
            val role = mo["role"]?.jsonPrimitive?.content ?: "user"
            val contentEl = mo["content"] ?: JsonNull
            val text = OpenAiMessageContentSerializer.flattenContentElement(contentEl)
            val toolCallId = mo["tool_call_id"]?.jsonPrimitive?.content
            val toolCalls = parseToolCallsFromMessageObject(mo)
            ChatMessage(
                role = role,
                content = text,
                toolCallId = toolCallId,
                toolCalls = toolCalls
            )
        }
        if (messages.isEmpty()) throw IllegalArgumentException("messages is empty")
        val toolsEl = obj["tools"]
        val tools = when (toolsEl) {
            null, is JsonNull -> null
            else -> toolsEl
        }
        val toolChoiceEl = obj["tool_choice"]
        val toolChoice = when (toolChoiceEl) {
            null, is JsonNull -> null
            else -> toolChoiceEl
        }
        val think = jsonPrimitiveToBoolean(obj["think"])
        val reasoningEffort = (obj["reasoning_effort"] as? JsonPrimitive)?.contentOrNull
        val reasoning = when (val r = obj["reasoning"]) {
            null, is JsonNull -> null
            else -> r
        }
        return ChatCompletionRequest(
            model = model,
            messages = messages,
            stream = stream,
            temperature = jsonPrimitiveToFloat(obj["temperature"]),
            top_p = jsonPrimitiveToFloat(obj["top_p"]),
            max_tokens = jsonPrimitiveToInt(obj["max_tokens"]),
            think = think,
            reasoningEffort = reasoningEffort,
            reasoning = reasoning,
            tools = tools,
            toolChoice = toolChoice
        )
    }

    private fun parseToolCallsFromMessageObject(mo: JsonObject): List<ChatToolCall>? {
        val el = mo["tool_calls"] ?: return null
        if (el is JsonNull) return null
        val arr = el as? JsonArray ?: return null
        if (arr.isEmpty()) return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(ChatToolCall.serializer()), arr)
        } catch (e: Exception) {
            Log.w("LlmApiServer", "message.tool_calls parse failed", e)
            null
        }
    }

    private fun jsonPrimitiveToFloat(el: JsonElement?): Float? {
        val p = el as? JsonPrimitive ?: return null
        return p.content.toFloatOrNull()
    }

    private fun jsonPrimitiveToInt(el: JsonElement?): Int? {
        val p = el as? JsonPrimitive ?: return null
        return p.content.toIntOrNull()
    }

    private fun jsonPrimitiveToBoolean(el: JsonElement?): Boolean? {
        val p = el as? JsonPrimitive ?: return null
        p.booleanOrNull?.let { return it }
        val s = p.content.lowercase(Locale.US)
        return when (s) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private fun resolveThinkingEnabled(request: ChatCompletionRequest, lanDefault: Boolean): Pair<Boolean, String> {
        request.think?.let { return it to "think" }
        request.reasoningEffort?.trim()?.lowercase(Locale.US)?.let { effort ->
            when (effort) {
                "none", "off", "false", "0" -> return false to "reasoning_effort=$effort"
                "low", "medium", "high", "true", "1", "on" -> return true to "reasoning_effort=$effort"
            }
        }
        val reasoningObj = request.reasoning as? JsonObject
        val effortFromObject = reasoningObj?.get("effort")?.jsonPrimitive?.contentOrNull
        effortFromObject?.trim()?.lowercase(Locale.US)?.let { effort ->
            when (effort) {
                "none", "off", "false", "0" -> return false to "reasoning.effort=$effort"
                "low", "medium", "high", "true", "1", "on" -> return true to "reasoning.effort=$effort"
            }
        }
        return lanDefault to "lan_default"
    }

    /** Very rough token estimate for `usage` (Ollama-style clients expect the field). */
    private fun roughTokenCount(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun responseModelName(requestModel: String, loadedName: String): String {
        val r = requestModel.trim()
        if (r.isEmpty() || r.equals("local", ignoreCase = true)) return loadedName
        return r
    }

    private fun toolsJsonForPrompt(tools: JsonElement?): String {
        if (tools == null || tools is JsonNull) return ""
        val encoded = try {
            lenientJson.encodeToString(JsonElement.serializer(), tools)
        } catch (e: Exception) {
            Log.w("LlmApiServer", "tools JSON encode failed", e)
            return ""
        }
        val cap = 28_000
        return if (encoded.length > cap) {
            encoded.take(cap) + "\n... [tools truncated for on-device context cap]\n"
        } else {
            encoded
        }
    }

    private fun toolChoiceSummaryForPrompt(toolChoice: JsonElement?): String {
        if (toolChoice == null || toolChoice is JsonNull) return ""
        return when (toolChoice) {
            is JsonPrimitive -> toolChoice.content
            else ->
                try {
                    lenientJson.encodeToString(JsonElement.serializer(), toolChoice)
                } catch (_: Exception) {
                    ""
                }
        }
    }

    /**
     * Prepends OpenAI-style `tools` + `tool_choice` so on-device models (e.g. Gemma) see the same
     * capability contract as behind Ollama/Continue, while extensions still execute tools locally.
     */
    private fun buildOpenAiToolsPreamble(tools: JsonElement?, toolChoice: JsonElement?): String {
        val toolsBlock = toolsJsonForPrompt(tools)
        if (toolsBlock.isEmpty()) return ""
        val choice = toolChoiceSummaryForPrompt(toolChoice)
        val choiceLine = if (choice.isNotEmpty()) "tool_choice: $choice\n\n" else ""
        return buildString {
            append("### OpenAI-compatible tools (call using this model's native tool/function syntax)\n")
            append(choiceLine)
            append(toolsBlock)
            append("\n### End tools\n\n")
        }
    }

    private fun formatChatMessageForPrompt(m: ChatMessage): String {
        val r = m.role.trim().lowercase(Locale.US)
        return when (r) {
            "tool" -> {
                val id = m.toolCallId?.takeIf { it.isNotBlank() }?.let { " id=$it" }.orEmpty()
                "tool$id: ${m.content}"
            }
            "assistant" -> {
                val parts = mutableListOf<String>()
                val tc = m.toolCalls
                if (tc != null && tc.isNotEmpty()) {
                    val encoded = try {
                        json.encodeToString(ListSerializer(ChatToolCall.serializer()), tc)
                    } catch (_: Exception) {
                        "[]"
                    }
                    parts.add("[assistant tool_calls] $encoded")
                }
                if (m.content.isNotEmpty()) {
                    parts.add(m.content)
                }
                "assistant: " + parts.joinToString("\n")
            }
            else -> "${m.role}: ${m.content}"
        }
    }

    private fun buildLanChatPrompt(request: ChatCompletionRequest): String {
        val preamble = buildOpenAiToolsPreamble(request.tools, request.toolChoice)
        val transcript =
            request.messages.joinToString("\n") { formatChatMessageForPrompt(it) } + "\nassistant: "
        return preamble + transcript
    }

    private data class ParsedLegacyToolCall(
        val name: String,
        val argumentsJson: String
    )

    /**
     * Convert legacy Gemma tool-call text (BEGIN_ARG / JSON-ish inline) into OpenAI-compatible
     * function calls so clients like Continue can execute tools natively.
     */
    private fun parseLegacyToolCallsFromText(raw: String): List<ParsedLegacyToolCall> {
        val text = raw.trim()
        val calls = mutableListOf<ParsedLegacyToolCall>()

        if (text.contains("<|tool_call", ignoreCase = true)) {
            val beginArg = Regex(
                pattern = """<\|tool_call\>\s*call:\s*[^:]+?:([a-zA-Z0-9_\-]+)\s+BEGIN_ARG:\s*filepath\s+([^\s]+)\s+END_ARG\s+BEGIN_ARG:\s*contents\s*([\s\S]*?)(?=(?:<\|tool_call\>|$))""",
                option = RegexOption.IGNORE_CASE
            )
            beginArg.findAll(text).forEach { m ->
                val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
                val filepath = m.groupValues.getOrNull(2)?.trim().orEmpty()
                val contents = m.groupValues.getOrNull(3)
                    ?.replace(Regex("""^\s*Apply\s*(\r?\n)+""", RegexOption.IGNORE_CASE), "")
                    ?.replace(Regex("""\s*END_ARG\s*$""", RegexOption.IGNORE_CASE), "")
                    ?.trimEnd()
                    .orEmpty()
                if (name.isNotEmpty()) {
                    val args = buildString {
                        append("{\"filepath\":")
                        append(json.encodeToString(String.serializer(), filepath))
                        append(",\"contents\":")
                        append(json.encodeToString(String.serializer(), contents))
                        append("}")
                    }
                    calls.add(ParsedLegacyToolCall(name = name, argumentsJson = args))
                }
            }

            val inlineLegacy = Regex(
                pattern = """<\|tool_call\>\s*call:([a-zA-Z0-9_\-]+)\{filepath:<\|"\|>([^<]+)<\|"\|>,contents:<\|"\|>([\s\S]*?)<\|"\|>\}""",
                option = RegexOption.IGNORE_CASE
            )
            inlineLegacy.findAll(text).forEach { m ->
                val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
                val filepath = m.groupValues.getOrNull(2)?.trim().orEmpty()
                val contents = m.groupValues.getOrNull(3)?.replace("\r\n", "\n").orEmpty()
                if (name.isNotEmpty()) {
                    val args = buildString {
                        append("{\"filepath\":")
                        append(json.encodeToString(String.serializer(), filepath))
                        append(",\"contents\":")
                        append(json.encodeToString(String.serializer(), contents))
                        append("}")
                    }
                    calls.add(ParsedLegacyToolCall(name = name, argumentsJson = args))
                }
            }
        }

        // OpenAI-ish fallback: model emits markdown/json content with {"tool_calls":[...]}.
        calls.addAll(parseToolCallsFromJsonishText(text))
        return calls
    }

    private fun parseToolCallsFromJsonishText(text: String): List<ParsedLegacyToolCall> {
        val out = mutableListOf<ParsedLegacyToolCall>()
        val seen = mutableSetOf<String>()

        fun add(name: String, args: String) {
            if (name.isBlank()) return
            val key = "$name::$args"
            if (seen.add(key)) out.add(ParsedLegacyToolCall(name.trim(), args))
        }

        fun argAsJson(argEl: JsonElement?): String {
            if (argEl == null || argEl is JsonNull) return "{}"
            return when (argEl) {
                is JsonObject, is JsonArray -> lenientJson.encodeToString(JsonElement.serializer(), argEl)
                is JsonPrimitive -> {
                    val s = argEl.content.trim()
                    if (s.startsWith("{") || s.startsWith("[")) s else "{}"
                }
            }
        }

        fun parseToolCallsArray(arr: JsonArray) {
            arr.forEach { tc ->
                val o = tc as? JsonObject ?: return@forEach
                val fn = o["function"] as? JsonObject ?: return@forEach
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val args = argAsJson(fn["arguments"])
                add(name, args)
            }
        }

        fun parseCandidateJson(candidate: String) {
            val el = try {
                lenientJson.decodeFromString(JsonElement.serializer(), candidate)
            } catch (_: Exception) {
                return
            }
            when (el) {
                is JsonObject -> {
                    val tc = el["tool_calls"] as? JsonArray
                    if (tc != null) parseToolCallsArray(tc)
                }
                is JsonArray -> parseToolCallsArray(el)
                else -> Unit
            }
        }

        parseCandidateJson(text)
        val fencedJson = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
        fencedJson.findAll(text).forEach { m ->
            val body = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (body.isNotEmpty()) parseCandidateJson(body)
        }
        return out
    }

    private fun toChatToolCalls(parsedCalls: List<ParsedLegacyToolCall>): List<ChatToolCall> {
        return parsedCalls.mapIndexed { i, c ->
            ChatToolCall(
                id = "call_${System.currentTimeMillis()}_$i",
                type = "function",
                function = ChatToolFunction(name = c.name, arguments = c.argumentsJson)
            )
        }
    }

    /**
     * Guard against infinite Agent loops: if the model emits the exact same tool call right after
     * a tool result message, we suppress the repeated tool_calls payload for this turn.
     */
    private fun isImmediateRepeatedToolLoop(
        request: ChatCompletionRequest,
        parsedCalls: List<ParsedLegacyToolCall>
    ): Boolean {
        if (parsedCalls.isEmpty()) return false
        val lastRole = request.messages.lastOrNull()?.role?.trim()?.lowercase(Locale.US) ?: return false
        if (lastRole != "tool") return false
        val previousAssistantWithTools =
            request.messages
                .asReversed()
                .firstOrNull { it.role.trim().equals("assistant", ignoreCase = true) && !it.toolCalls.isNullOrEmpty() }
                ?: return false
        val prev = previousAssistantWithTools.toolCalls ?: return false
        if (prev.size != parsedCalls.size) return false
        for (i in parsedCalls.indices) {
            val a = prev[i]
            val b = parsedCalls[i]
            if (!a.function.name.trim().equals(b.name.trim(), ignoreCase = true)) return false
            if (a.function.arguments.trim() != b.argumentsJson.trim()) return false
        }
        return true
    }

    private fun jsonElementToStreamFlag(el: JsonElement?): Boolean {
        if (el == null) return false
        val p = el as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { return it }
        if (p.isString) {
            val c = p.content.lowercase(Locale.US)
            return c == "true" || c == "1" || c == "yes"
        }
        return p.content == "1"
    }

    private suspend fun handleChatCompletions(output: java.io.OutputStream, body: String) {
        val request = try {
            parseChatCompletionRequest(body)
        } catch (e: Exception) {
            Log.e(
                "LlmApiServer",
                "chat/completions JSON decode failed: ${e.message}; bodyHead=${body.trim().take(500)}",
                e
            )
            val detail = e.message?.let { ": $it" }.orEmpty()
            writeOpenAiError(output, 400, "Invalid JSON body$detail", "invalid_request_error")
            return
        }

        val model = inferenceService.getCurrentlyLoadedModel()
        if (model == null) {
            writeOpenAiError(output, 503, "No model loaded in Bearis", "server_error", code = "model_not_loaded")
            return
        }

        var prompt = buildLanChatPrompt(request)
        // IDE agents (Continue, etc.) often send huge prompts → OOM / ANR on phone; cap by rough context.
        val charCap = (model.contextWindowSize * 3).coerceIn(12_000, 96_000)
        if (prompt.length > charCap) {
            Log.w(
                "LlmApiServer",
                "chat/completions prompt ${prompt.length} chars exceeds cap $charCap; truncating (IDE/agent client?)"
            )
            prompt = prompt.take(charCap) +
                "\n\n[... context truncated for on-device model limit ...]\nassistant: "
        }

            val themePreferences = ThemePreferences(context.applicationContext)
            val hasExplicitTooling =
                request.tools != null ||
                    request.messages.any { msg ->
                        msg.role.equals("tool", ignoreCase = true) ||
                            !msg.toolCalls.isNullOrEmpty()
                    }
            val looksLikeAgentToolBridge =
                request.messages.any { msg ->
                    if (!msg.role.equals("user", ignoreCase = true)) return@any false
                    val text = msg.content ?: return@any false
                    text.contains("tool output for", ignoreCase = true) ||
                        text.contains("tool call", ignoreCase = true) ||
                        text.contains("read_currently_open_file", ignoreCase = true)
                }
            val webSearchEnabled = if (hasExplicitTooling || looksLikeAgentToolBridge) {
                Log.d(
                    "LlmApiServer",
                    "chat/completions disabling phone web search (agent/tool traffic detected)"
                )
                false
            } else {
                try {
                    themePreferences.webSearchEnabled.first()
                } catch (_: Exception) {
                    true
                }
            }
            val lanReasoningDefault = try {
                themePreferences.localApiReasoningEnabled.first()
            } catch (_: Exception) {
                false
            }
            val sessionChatId = "local-api-${UUID.randomUUID()}"

            val modelPrefs = ModelPreferences(context.applicationContext)
            val restoreThinking = modelPrefs.getModelConfig(model.name)?.enableThinking ?: false
            val (apiThinking, thinkingSource) = resolveThinkingEnabled(request, lanReasoningDefault)
            Log.d(
                "LlmApiServer",
                "chat/completions thinking: effective=$apiThinking source=$thinkingSource " +
                    "(restore saved UI=$restoreThinking, lanDefault=$lanReasoningDefault)"
            )

            val responseModel = responseModelName(request.model, model.name)

            try {
                inferenceService.setGenerationParameters(
                    maxTokens = request.max_tokens,
                    topK = null,
                    topP = request.top_p,
                    temperature = request.temperature,
                    nGpuLayers = null,
                    enableThinking = apiThinking,
                    contextWindow = null
                )

                if (request.stream) {
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/event-stream; charset=utf-8\r\n")
                    append("Connection: close\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("\r\n")
                }
                output.write(header.toByteArray(Charset.forName("UTF-8")))
                output.flush()

                val flow = inferenceService.generateResponseStreamWithSession(
                    prompt,
                    model,
                    sessionChatId,
                    emptyList(),
                    null,
                    webSearchEnabled,
                    emptyList(),
                    useAgentTools = false
                )
                val completionId = "chatcmpl-${System.currentTimeMillis()}"
                val assistantText = StringBuilder()
                val streamJob = clientScope.launch {
                    try {
                        fun writeSseEvent(chunk: ChatCompletionChunk) {
                            val line =
                                "data: ${jsonSse.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n"
                            output.write(line.toByteArray(Charset.forName("UTF-8")))
                            output.flush()
                        }

                        val nowSec = { System.currentTimeMillis() / 1000 }
                        var sentAssistantRole = false
                        val preferToolCallsForThisRequest = request.tools != null
                        fun emitAssistantRoleIfNeeded() {
                            if (sentAssistantRole) return
                            sentAssistantRole = true
                            writeSseEvent(
                                ChatCompletionChunk(
                                    id = completionId,
                                    created = nowSec(),
                                    model = responseModel,
                                    choices = listOf(
                                        ChatChunkChoice(0, ChatDelta(role = "assistant", content = null))
                                    )
                                )
                            )
                        }

                        flow.collect { chunk ->
                            if (chunk.isEmpty()) return@collect
                            assistantText.append(chunk)
                            // Stream content incrementally in all modes for compatibility with IDE clients.
                            emitAssistantRoleIfNeeded()
                            writeSseEvent(
                                ChatCompletionChunk(
                                    id = completionId,
                                    created = nowSec(),
                                    model = responseModel,
                                    choices = listOf(
                                        ChatChunkChoice(0, ChatDelta(content = chunk))
                                    )
                                )
                            )
                        }
                        emitAssistantRoleIfNeeded()
                        val parsedToolCalls =
                            if (preferToolCallsForThisRequest) {
                                val parsedToolCallsRaw = parseLegacyToolCallsFromText(assistantText.toString())
                                val repeatedToolLoop = isImmediateRepeatedToolLoop(request, parsedToolCallsRaw)
                                if (repeatedToolLoop) {
                                    Log.w("LlmApiServer", "Suppressed repeated tool_calls to break agent loop")
                                }
                                if (repeatedToolLoop) emptyList() else parsedToolCallsRaw
                            } else {
                                // Raw chat mode: never synthesize tool_calls unless client explicitly sent tools.
                                emptyList()
                            }
                        if (parsedToolCalls.isNotEmpty()) {
                            val toolCalls = parsedToolCalls.mapIndexed { index, c ->
                                ChatToolCallDelta(
                                    index = index,
                                    id = "call_${System.currentTimeMillis()}_$index",
                                    type = "function",
                                    function = ChatToolFunctionDelta(
                                        name = c.name,
                                        arguments = c.argumentsJson
                                    )
                                )
                            }
                            writeSseEvent(
                                ChatCompletionChunk(
                                    id = completionId,
                                    created = nowSec(),
                                    model = responseModel,
                                    choices = listOf(
                                        ChatChunkChoice(
                                            0,
                                            ChatDelta(toolCalls = toolCalls)
                                        )
                                    )
                                )
                            )
                        } else if (assistantText.isEmpty()) {
                            writeSseEvent(
                                ChatCompletionChunk(
                                    id = completionId,
                                    created = nowSec(),
                                    model = responseModel,
                                    choices = listOf(
                                        ChatChunkChoice(
                                            0,
                                            ChatDelta(content = "")
                                        )
                                    )
                                )
                            )
                        }
                        writeSseEvent(
                            ChatCompletionChunk(
                                id = completionId,
                                created = nowSec(),
                                model = responseModel,
                                choices = listOf(
                                    ChatChunkChoice(
                                        0,
                                        ChatDelta(),
                                        finish_reason = if (parsedToolCalls.isNotEmpty()) "tool_calls" else "stop"
                                    )
                                )
                            )
                        )
                        output.write("data: [DONE]\n\n".toByteArray(Charset.forName("UTF-8")))
                        output.flush()
                    } catch (e: CancellationException) {
                        try {
                            output.write("data: [DONE]\n\n".toByteArray(Charset.forName("UTF-8")))
                            output.flush()
                        } catch (_: Exception) {
                        }
                        throw e
                    } catch (e: Exception) {
                        Log.e("LlmApiServer", "stream error", e)
                    }
                }
                activeStreamJob.set(streamJob)
                try {
                    streamJob.join()
                } finally {
                    activeStreamJob.compareAndSet(streamJob, null)
                }
            } else {
                val responseText = try {
                    val sb = StringBuilder()
                    inferenceService.generateResponseStreamWithSession(
                        prompt,
                        model,
                        sessionChatId,
                        emptyList(),
                        null,
                        webSearchEnabled,
                        emptyList(),
                        useAgentTools = false
                    ).collect { sb.append(it) }
                    sb.toString()
                } catch (e: Exception) {
                    Log.e("LlmApiServer", "generate failed", e)
                    writeOpenAiError(output, 500, e.message ?: "error", "server_error")
                    return
                }
                val promptTok = roughTokenCount(prompt)
                val completionTok = roughTokenCount(responseText)
                val parsedToolCalls =
                    if (request.tools != null) {
                        val parsedToolCallsRaw = parseLegacyToolCallsFromText(responseText)
                        val repeatedToolLoop = isImmediateRepeatedToolLoop(request, parsedToolCallsRaw)
                        if (repeatedToolLoop) {
                            Log.w("LlmApiServer", "Suppressed repeated tool_calls to break agent loop")
                        }
                        if (repeatedToolLoop) emptyList() else parsedToolCallsRaw
                    } else {
                        // Raw chat mode: keep model output as plain assistant content.
                        emptyList()
                    }
                val assistantMessage =
                    if (parsedToolCalls.isNotEmpty()) {
                        ChatAssistantMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = toChatToolCalls(parsedToolCalls)
                        )
                    } else {
                        ChatAssistantMessage(
                            role = "assistant",
                            content = responseText,
                            toolCalls = null
                        )
                    }
                val response = ChatCompletionResponse(
                    id = "chatcmpl-${System.currentTimeMillis()}",
                    created = System.currentTimeMillis() / 1000,
                    model = responseModel,
                    choices = listOf(
                        ChatChoice(
                            0,
                            assistantMessage,
                            finish_reason = if (parsedToolCalls.isNotEmpty()) "tool_calls" else "stop"
                        )
                    ),
                    usage = CompletionUsage(
                        prompt_tokens = promptTok,
                        completion_tokens = completionTok,
                        total_tokens = promptTok + completionTok
                    )
                )
                val jsonBody = json.encodeToString(ChatCompletionResponse.serializer(), response)
                val bytes = jsonBody.toByteArray(Charset.forName("UTF-8"))
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(header.toByteArray(Charset.forName("UTF-8")))
                output.write(bytes)
                output.flush()
                }
            } finally {
                inferenceService.setGenerationParameters(
                    maxTokens = null,
                    topK = null,
                    topP = null,
                    temperature = null,
                    nGpuLayers = null,
                    enableThinking = restoreThinking,
                    contextWindow = null
                )
            }
    }

    private fun handleCancelGeneration(output: java.io.OutputStream) {
        val j = activeStreamJob.getAndSet(null)
        j?.cancel(CancellationException("user requested stop"))
        writeTextResponse(output, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    private fun handleApiStatus(output: java.io.OutputStream) {
        val m = inferenceService.getCurrentlyLoadedModel()
        val body = json.encodeToString(
            ApiStatusResponse.serializer(),
            ApiStatusResponse(
                modelName = m?.name,
                supportsVision = m?.supportsVision == true,
                supportsAudio = m?.supportsAudio == true,
                category = m?.category,
                contextWindow = m?.contextWindowSize
            )
        )
        writeTextResponse(output, 200, body, "application/json; charset=utf-8")
    }

    /**
     * OpenAI-compatible model discovery for IDE extensions (Continue, etc.).
     * We expose the currently loaded on-device model, if any.
     */
    private fun handleListModels(output: java.io.OutputStream) {
        val loaded = inferenceService.getCurrentlyLoadedModel()
        val data = if (loaded != null) {
            listOf(
                OpenAiModelItem(
                    id = loaded.name,
                    created = System.currentTimeMillis() / 1000
                )
            )
        } else {
            emptyList()
        }
        val body = json.encodeToString(
            OpenAiModelListResponse.serializer(),
            OpenAiModelListResponse(data = data)
        )
        writeTextResponse(output, 200, body, "application/json; charset=utf-8")
    }

    private suspend fun handleListChatsSuspend(output: java.io.OutputStream) {
        val list = chatRepository.getNonEmptyChatsOnce()
        val dto = BearisChatListJson(
            chats = list.map {
                BearisChatJson(it.id, it.title, it.modelName, it.updatedAt)
            }
        )
        val jsonBody = json.encodeToString(BearisChatListJson.serializer(), dto)
        writeTextResponse(output, 200, jsonBody, "application/json; charset=utf-8")
    }

    private suspend fun handleCreateChatSuspend(output: java.io.OutputStream, body: String) {
        val model = inferenceService.getCurrentlyLoadedModel()
        if (model == null) {
            writeOpenAiError(output, 503, "No model loaded", "server_error", code = "model_not_loaded")
            return
        }
        val title = try {
            if (body.isBlank()) "Web chat"
            else json.decodeFromString(CreateChatBody.serializer(), body).title?.trim()?.ifEmpty { null } ?: "Web chat"
        } catch (_: Exception) {
            "Web chat"
        }
        val id = chatRepository.createNewChat(title, model.name, null)
        val resp = CreateChatResponse(id)
        writeTextResponse(output, 200, json.encodeToString(CreateChatResponse.serializer(), resp), "application/json; charset=utf-8")
    }

    private suspend fun handleGetChatMessagesSuspend(output: java.io.OutputStream, path: String) {
        val chatId = extractChatIdFromMessagesPath(path) ?: run {
            writeOpenAiError(output, 400, "Bad path", "invalid_request_error")
            return
        }
        val rows = chatRepository.getMessagesForChatSync(chatId)
        val dto = BearisMessagesJson(
            messages = rows.map { m ->
                BearisMessageJson(
                    id = m.id,
                    role = if (m.isFromUser) "user" else "assistant",
                    content = m.content,
                    timestamp = m.timestamp,
                    generationDurationMs = m.generationDurationMs
                )
            }
        )
        writeTextResponse(output, 200, json.encodeToString(BearisMessagesJson.serializer(), dto), "application/json; charset=utf-8")
    }

    private suspend fun handlePatchMessageSuspend(output: java.io.OutputStream, path: String, body: String) {
        val (chatId, messageId) = extractPatchMessageIds(path) ?: run {
            writeOpenAiError(output, 400, "Bad path", "invalid_request_error")
            return
        }
        val content = try {
            json.decodeFromString(PatchMessageBody.serializer(), body).content
        } catch (e: Exception) {
            writeOpenAiError(output, 400, "Invalid JSON", "invalid_request_error")
            return
        }
        try {
            chatRepository.editUserMessageAndTruncate(chatId, messageId, content)
        } catch (e: Exception) {
            writeOpenAiError(output, 400, e.message ?: "edit failed", "invalid_request_error")
            return
        }
        writeTextResponse(output, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    private suspend fun handleDeleteChatSuspend(output: java.io.OutputStream, path: String) {
        val chatId = extractChatIdFromDeletePath(path) ?: run {
            writeOpenAiError(output, 400, "Bad path", "invalid_request_error")
            return
        }
        val existing = chatRepository.getChatById(chatId)
        if (existing == null) {
            writeOpenAiError(output, 404, "Chat not found", "invalid_request_error")
            return
        }
        try {
            chatRepository.deleteChat(chatId)
        } catch (e: Exception) {
            Log.e("LlmApiServer", "delete chat failed", e)
            writeOpenAiError(output, 500, e.message ?: "delete failed", "server_error")
            return
        }
        writeTextResponse(output, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    private fun isDeleteChatPath(path: String): Boolean {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        return p.size == 3 && p[0] == "v1" && p[1] == "chats"
    }

    private fun extractChatIdFromDeletePath(path: String): String? {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        return if (p.size == 3 && p[0] == "v1" && p[1] == "chats") p[2] else null
    }

    private fun isChatMessagesPath(path: String): Boolean {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        return p.size == 4 && p[0] == "v1" && p[1] == "chats" && p[3] == "messages"
    }

    private fun isPatchMessagePath(path: String): Boolean {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        return p.size == 5 && p[0] == "v1" && p[1] == "chats" && p[3] == "messages"
    }

    private fun extractChatIdFromMessagesPath(path: String): String? {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        return if (p.size == 4 && p[0] == "v1" && p[1] == "chats" && p[3] == "messages") p[2] else null
    }

    private fun extractPatchMessageIds(path: String): Pair<String, String>? {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }
        return if (p.size == 5 && p[0] == "v1" && p[1] == "chats" && p[3] == "messages") {
            p[2] to p[4]
        } else null
    }

    private fun serveStaticGet(pathRaw: String, output: java.io.OutputStream) {
        if (pathRaw.startsWith("/v1")) {
            writeOpenAiError(
                output,
                404,
                "Not found. Use /v1/chat/completions, /v1/models, or /v1/status.",
                "invalid_request_error"
            )
            return
        }

        val rel = pathRaw.trim().trimStart('/').ifEmpty { "index.html" }
        if (rel.contains("..")) {
            writeTextResponse(output, 400, "Bad path", "text/plain; charset=utf-8")
            return
        }

        var bytes = readWebAssetBytes(rel)
        if (bytes == null && !rel.startsWith("assets/")) {
            bytes = readWebAssetBytes("index.html")
        }
        if (bytes == null) {
            writeTextResponse(
                output,
                404,
                "Web UI missing. Rebuild local-api-chat-web and sync to app/src/main/assets/local_api_web/.",
                "text/plain; charset=utf-8"
            )
            return
        }

        val mime = mimeForFileName(rel)
        val cache = if (rel.startsWith("assets/")) "public, max-age=31536000" else "no-cache"
        writeBinaryResponse(output, 200, bytes, mime, cache)
    }

    private fun readWebAssetBytes(relativePath: String): ByteArray? {
        val normalized = relativePath.trimStart('/').replace('\\', '/')
        if (normalized.contains("..")) return null
        val fullPath = "$ASSET_WEB_DIR/$normalized"
        return try {
            context.assets.open(fullPath).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeForFileName(path: String): String {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".html") -> "text/html; charset=utf-8"
            lower.endsWith(".js") -> "application/javascript; charset=utf-8"
            lower.endsWith(".css") -> "text/css; charset=utf-8"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    private fun writeBinaryResponse(
        output: java.io.OutputStream,
        code: Int,
        body: ByteArray,
        contentType: String,
        cacheControl: String
    ) {
        val status = when (code) {
            200 -> "200 OK"
            else -> "$code"
        }
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: $cacheControl\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charset.forName("UTF-8")))
        output.write(body)
        output.flush()
    }

    private fun writeTextResponse(
        output: java.io.OutputStream,
        code: Int,
        body: String,
        contentType: String
    ) {
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            503 -> "503 Service Unavailable"
            else -> "$code"
        }
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charset.forName("UTF-8")))
        output.write(bytes)
        output.flush()
    }

    private fun writeOpenAiError(
        output: java.io.OutputStream,
        httpCode: Int,
        message: String,
        type: String,
        param: String? = null,
        code: String? = null
    ) {
        val body = json.encodeToString(
            OpenAiErrorResponse.serializer(),
            OpenAiErrorResponse(
                OpenAiErrorDetail(
                    message = message,
                    type = type,
                    param = param,
                    code = code
                )
            )
        )
        writeTextResponse(output, httpCode, body, "application/json; charset=utf-8")
    }

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val body: String
    )

    private fun readHttpRequest(input: BufferedInputStream): ParsedRequest? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        val delim = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        var headerEndIndex = -1
        while (buffer.size() < 256 * 1024) {
            val n = input.read(chunk)
            if (n <= 0) return null
            buffer.write(chunk, 0, n)
            val arr = buffer.toByteArray()
            headerEndIndex = indexOfSubArray(arr, delim)
            if (headerEndIndex >= 0) break
        }
        if (headerEndIndex < 0) return null

        val arr = buffer.toByteArray()
        val headerBytes = arr.copyOfRange(0, headerEndIndex)
        val afterHeaders = arr.copyOfRange(headerEndIndex + delim.size, arr.size)

        val headerText = headerBytes.toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore("?")

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

        val bodyStream = ByteArrayOutputStream()
        bodyStream.write(afterHeaders)
        var need = contentLength - bodyStream.size()
        while (need > 0) {
            val n = input.read(chunk, 0, minOf(need, chunk.size))
            if (n <= 0) break
            bodyStream.write(chunk, 0, n)
            need -= n
        }
        val bodyString = bodyStream.toByteArray().toString(Charsets.UTF_8)
        return ParsedRequest(method, path, bodyString)
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private const val ASSET_WEB_DIR = "local_api_web"
    }
}
