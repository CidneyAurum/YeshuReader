package app.yeshu.reader

import android.graphics.Bitmap
import android.util.Base64
import app.yeshu.reader.ai.AiProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException

/** OpenAI 兼容 chat/completions 客户端（HttpURLConnection + org.json，零依赖） */
object AiClient {

    const val DEFAULT_CHAT_PATH = "/chat/completions"
    const val DEFAULT_MODELS_PATH = "/models"
    const val DEFAULT_AUTH_HEADER = "Authorization"
    const val DEFAULT_AUTH_PREFIX = "Bearer"

    /** Disconnects the active HttpURLConnection so a user cancellation stops network I/O. */
    class CancelToken {
        @Volatile private var cancelled = false
        @Volatile private var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }

        internal fun attach(value: HttpURLConnection) {
            ensureActive()
            connection = value
            ensureActive()
        }

        internal fun detach(value: HttpURLConnection) {
            if (connection === value) connection = null
        }

        internal fun ensureActive() {
            if (cancelled) throw CancellationException("AI 请求已取消")
        }

        internal fun isCancelled(): Boolean = cancelled
    }

    private val cancellation = ThreadLocal<CancelToken?>()

    fun <T> withCancellation(token: CancelToken, block: () -> T): T {
        val previous = cancellation.get()
        cancellation.set(token)
        return try {
            token.ensureActive()
            block()
        } finally {
            cancellation.set(previous)
        }
    }

    data class Config(
        val baseUrl: String,
        val key: String,
        val model: String,
        val visionModel: String = "",
        val allowPrivateHttp: Boolean = false,
        val chatPath: String = DEFAULT_CHAT_PATH,
        val modelsPath: String = DEFAULT_MODELS_PATH,
        val authHeader: String = DEFAULT_AUTH_HEADER,
        val authPrefix: String = DEFAULT_AUTH_PREFIX
    )

    fun config(db: Db): Config {
        val profile = AiProfileStore.active(db)
        val textModel = profile.textModel.trim()
        val configuredVision = profile.visionModel.trim()
        val baseUrl = profile.baseUrl.trim()
        return Config(
            baseUrl = baseUrl,
            key = db.getAiKey(profile.id, baseUrl),
            model = textModel,
            visionModel = configuredVision.ifBlank { textModel.takeIf { it.contains("vision", true) || it.contains("vl", true) }.orEmpty() },
            allowPrivateHttp = profile.allowPrivateHttp,
            chatPath = profile.chatPath,
            modelsPath = profile.modelsPath,
            authHeader = profile.authHeader,
            authPrefix = profile.authPrefix
        )
    }

    fun isReady(c: Config): Boolean = c.model.isNotBlank() && endpointError(c) == null

    /** Converts transport/provider failures to safe UI text without echoing response bodies. */
    fun userFacingError(error: Throwable): String {
        if (error is CancellationException) return "请求已取消"
        val message = error.message.orEmpty()
        if (message.startsWith("HTTP ")) return "服务端拒绝请求（${message.removePrefix("HTTP ").trim()}）"
        return when (error) {
            is java.net.SocketTimeoutException -> "连接超时"
            is java.net.UnknownHostException -> "域名解析失败"
            is java.io.IOException -> "网络连接失败"
            else -> message.takeIf { it.isNotBlank() }?.let { safeValidationMessage(it) } ?: "请求失败"
        }
    }

    private fun safeValidationMessage(message: String): String = when {
        message.contains("HTTPS", true) || message.contains("HTTP", true) -> message
        message.contains("地址", true) -> message
        message.contains("模型", true) -> message
        else -> "请求失败"
    }

    fun endpointError(c: Config): String? {
        if (c.baseUrl.isBlank()) return "请填写接口地址"
        if (c.key.isNotBlank() && !isValidHeaderName(c.authHeader)) return "鉴权 Header 名称不正确"
        if (c.key.isNotBlank() && c.authHeader.lowercase() in FORBIDDEN_AUTH_HEADERS) {
            return "鉴权 Header 不能使用 HTTP 协议保留字段"
        }
        if (c.authPrefix.contains('\r') || c.authPrefix.contains('\n')) return "鉴权前缀不能包含换行"
        val baseUri = runCatching { URI(normalizeBase(c.baseUrl)) }.getOrNull()
            ?: return "接口地址格式不正确"
        val uri = runCatching { URI(chatCompletionsEndpoint(c.baseUrl, c.chatPath)) }.getOrNull()
            ?: return "Chat 接口地址格式不正确"
        if (uri.host.isNullOrBlank()) return "接口地址格式不正确"
        if (baseUri.host.isNullOrBlank()) return "接口地址格式不正确"
        if (!sameOrigin(baseUri, uri)) return "Chat 接口不能切换到其他服务商域名"
        if (uri.scheme.equals("https", true)) return null
        if (!uri.scheme.equals("http", true)) return "只支持 HTTPS，或经确认的局域网 HTTP"
        if (!c.allowPrivateHttp) return "HTTP 仅用于本机/局域网服务，请先开启局域网 HTTP"
        val host = uri.host.orEmpty()
        if (isAllowedCleartextHost(host) || isPrivateNetworkHost(host)) return null
        return "公网接口必须使用 HTTPS"
    }

    private fun isValidHeaderName(value: String): Boolean =
        value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '-' }

    private val FORBIDDEN_AUTH_HEADERS = setOf(
        "host", "content-length", "content-type", "connection", "transfer-encoding",
        "expect", "upgrade", "proxy-authorization", "proxy-authenticate", "te", "trailer",
        "via", "forwarded", "x-forwarded-host"
    )

    private fun sameOrigin(first: URI, second: URI): Boolean {
        val firstScheme = first.scheme?.lowercase().orEmpty()
        val secondScheme = second.scheme?.lowercase().orEmpty()
        val firstPort = effectivePort(first)
        val secondPort = effectivePort(second)
        return firstScheme == secondScheme &&
            first.host.equals(second.host, ignoreCase = true) &&
            firstPort == secondPort
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    private fun isAllowedCleartextHost(host: String): Boolean {
        val value = host.lowercase().removePrefix("[").removeSuffix("]")
        return value == "localhost" || value == "127.0.0.1" || value == "10.0.2.2" ||
            value.endsWith(".local")
    }

    private fun isPrivateNetworkHost(host: String): Boolean {
        val value = host.lowercase().removePrefix("[").removeSuffix("]")
        val parts = value.split('.')
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return when {
                octets[0] == 127 -> true
                octets[0] == 10 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                else -> false
            }
        }
        if (':' in value && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '%' }) {
            return runCatching {
                java.net.InetAddress.getByName(value).let {
                    it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress
                }
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * 地址规范化：容错用户输入——
     * 1) 去尾部斜杠；2) 只有域名没有路径时自动补 /v1（OpenAI 惯例）；
     * 3) 用户误填完整 /chat/completions 或 /models 地址时还原为 API Base URL。
     * 例：https://tokenrhythm.studio → https://tokenrhythm.studio/v1
     */
    fun normalizeBase(raw: String): String {
        var candidate = raw.trim()
        if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) {
            candidate = "https://$candidate"
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return candidate.trimEnd('/')
        val authority = uri.rawAuthority ?: return candidate.trimEnd('/')
        var path = uri.rawPath.orEmpty().trimEnd('/')
        path = when {
            path.endsWith("/chat/completions", true) -> path.dropLast("/chat/completions".length)
            path.endsWith("/models", true) -> path.dropLast("/models".length)
            else -> path
        }.trimEnd('/')
        if (path.isBlank()) path = "/v1"
        val scheme = uri.scheme?.lowercase().orEmpty()
        return "$scheme://$authority$path"
    }

    /**
     * Resolves an endpoint relative to the API Base URL. A leading slash is intentionally treated
     * as Base-relative rather than origin-relative because compatible providers commonly document
     * `Base URL = .../v1` and `Chat = /chat/completions` as two separate fields.
     * A complete HTTP(S) URL is also accepted, but [endpointError] refuses cross-origin key sends.
     */
    internal fun resolveEndpoint(rawBase: String, configuredPath: String, defaultPath: String): String {
        val path = configuredPath.trim().ifBlank { defaultPath }
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
            return normalizeAbsoluteEndpoint(path)
        }
        return normalizeBase(rawBase).trimEnd('/') + "/" + path.trimStart('/')
    }

    internal fun chatCompletionsEndpoint(raw: String, path: String = DEFAULT_CHAT_PATH): String =
        resolveEndpoint(raw, path, DEFAULT_CHAT_PATH)

    internal fun modelsEndpoint(raw: String, path: String = DEFAULT_MODELS_PATH): String =
        resolveEndpoint(raw, path, DEFAULT_MODELS_PATH)

    private fun normalizeAbsoluteEndpoint(raw: String): String {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val authority = uri.rawAuthority ?: return raw.trim()
        val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authority$path$query"
    }

    internal fun authorizationHeader(key: String, prefix: String = DEFAULT_AUTH_PREFIX): String? =
        key.trim().takeIf { it.isNotBlank() }?.let { secret ->
            prefix.trim().takeIf { it.isNotBlank() }?.let { "$it $secret" } ?: secret
        }

    /** Stable scheme/host/port identity used to bind an encrypted key to one provider. */
    fun endpointOrigin(raw: String): String {
        val uri = URI(normalizeBase(raw))
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        require(scheme in setOf("http", "https") && host.isNotBlank())
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }

    /**
     * OpenAI-compatible model discovery. Never logs the Authorization header or response.
     * Some compatible providers intentionally omit /models. In that case a minimal chat request
     * using the user-selected model verifies the actual endpoint instead of reporting a false
     * connection failure.
     */
    fun discoverModels(cfg: Config, timeoutMs: Int = 20_000): List<String> {
        endpointError(cfg)?.let { throw IllegalArgumentException(it) }
        if (cfg.modelsPath.isBlank()) return emptyList()
        val modelsUrl = modelsEndpoint(cfg.baseUrl, cfg.modelsPath)
        val baseUri = URI(normalizeBase(cfg.baseUrl))
        val modelsUri = runCatching { URI(modelsUrl) }.getOrNull()
            ?: throw IllegalArgumentException("模型列表接口地址格式不正确")
        if (!sameOrigin(baseUri, modelsUri)) {
            throw IllegalArgumentException("模型列表接口不能切换到其他服务商域名")
        }
        val conn = URL(modelsUrl).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            authorizationHeader(cfg.key, cfg.authPrefix)?.let {
                conn.setRequestProperty(cfg.authHeader, it)
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val response = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return extractModelIds(response)
        } finally {
            conn.disconnect()
        }
    }

    internal fun extractModelIds(response: String): List<String> {
        val root = JSONObject(response)
        val candidates = listOfNotNull(
            root.optJSONArray("data"),
            root.optJSONArray("models"),
            root.optJSONObject("data")?.optJSONArray("models")
        )
        val models = buildList {
            candidates.forEach { array ->
                val sizeBeforeArray = size
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index)
                    if (value != null) {
                        sequenceOf("id", "name", "model")
                            .map { value.optString(it).takeUnless { text -> text == "null" }.orEmpty() }
                            .firstOrNull { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
                if (size == sizeBeforeArray) addAll(jsonStringElements(array))
            }
            if (isEmpty()) addAll(extractStringModelArrays(response))
        }.distinct().sorted()
        if (models.isEmpty()) throw RuntimeException("模型列表格式不兼容")
        return models
    }

    fun listModels(cfg: Config, timeoutMs: Int = 20_000): List<String> {
        val discovery = runCatching { discoverModels(cfg, timeoutMs) }
        discovery.getOrNull()?.let { return it }

        // Discovery is optional in the OpenAI-compatible ecosystem. Verify chat itself before
        // surfacing a failure; the custom model name and arbitrary key value are passed unchanged.
        if (cfg.model.isBlank()) throw discovery.exceptionOrNull() ?: IllegalArgumentException("请填写模型名称")
        chat(
            cfg = cfg,
            system = null,
            user = "Reply with OK.",
            timeoutMs = timeoutMs
        )
        return emptyList()
    }

    /**
     * 同步调用（调用方负责放子线程）。返回 assistant 回复文本。
     * onDelta 非空时走流式请求并逐段回调（打字机效果）；服务端不支持时自动回退一次性解析。
     * 失败抛 RuntimeException；HTTP 错误仅暴露状态码，不回显服务端响应正文。
     */
    fun chat(
        cfg: Config,
        system: String?,
        user: String,
        onDelta: ((String) -> Unit)? = null,
        timeoutMs: Int = 180_000,
        onReason: ((String) -> Unit)? = null
    ): String {
        val msgs = JSONArray()
        if (!system.isNullOrBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        msgs.put(JSONObject().put("role", "user").put("content", user))
        return withFallback(cfg, msgs, timeoutMs, onDelta, onReason)
    }

    /**
     * 多轮对话（与书聊天气）：history 为 (role, content) 列表，role 是 "user"/"assistant"。
     * system 由调用方拼好书上下文。
     */
    fun chatHistory(
        cfg: Config,
        system: String?,
        history: List<Pair<String, String>>,
        onDelta: ((String) -> Unit)? = null,
        timeoutMs: Int = 180_000,
        onReason: ((String) -> Unit)? = null
    ): String {
        val msgs = JSONArray()
        if (!system.isNullOrBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        for ((role, content) in history) {
            if (content.isBlank()) continue
            msgs.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", content))
        }
        return withFallback(cfg, msgs, timeoutMs, onDelta, onReason)
    }

    /**
     * 流式断流自动降级：SSE 中途 IOException（弱网/代理断流）时，
     * 静默重发一次非流式请求保证结果可用，全文通过 onDelta 补发。
     */
    private fun withFallback(
        cfg: Config,
        msgs: JSONArray,
        timeoutMs: Int,
        onDelta: ((String) -> Unit)?,
        onReason: ((String) -> Unit)? = null
    ): String {
        return try {
            postChat(cfg, msgs, timeoutMs, onDelta, onReason)
        } catch (e: java.io.IOException) {
            if (cancellation.get()?.isCancelled() == true) throw CancellationException("AI 请求已取消")
            if (onDelta != null) {
                val r = postChat(cfg, msgs, timeoutMs, null, null)
                onDelta(r)
                r
            } else throw e
        }
    }

    /** 兼容标准 OpenAI 结构；容错解析 */
    fun extractContent(respJson: String): String? = try {
        val root = JSONObject(respJson)
        sequenceOf(root, root.optJSONObject("data"))
            .filterNotNull()
            .mapNotNull(::extractCompatibleContent)
            .firstOrNull()
    } catch (_: Exception) {
        null
    }

    private fun extractCompatibleContent(json: JSONObject): String? {
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        json.optString("response").takeIf { it.isNotBlank() }?.let { return it }
        json.optJSONObject("message")?.let { jsonText(it, "content") }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            first?.optJSONObject("message")?.let { jsonText(it, "content") }
                ?.takeIf { it.isNotBlank() }?.let { return it }
            first?.let { jsonText(it, "text") }?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val output = json.optJSONArray("output") ?: return null
        return buildList {
            for (index in 0 until output.length()) {
                val content = output.optJSONObject(index)?.optJSONArray("content") ?: continue
                for (partIndex in 0 until content.length()) {
                    content.optJSONObject(partIndex)?.optString("text")
                        ?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.joinToString("").takeIf { it.isNotBlank() }
    }

    private fun jsonText(source: JSONObject, key: String): String? {
        source.optJSONArray(key)?.let { array ->
            return buildList {
                for (index in 0 until array.length()) {
                    val part = array.optJSONObject(index)
                    if (part != null) {
                        sequenceOf("text", "content")
                            .map { part.optString(it).takeUnless { text -> text == "null" }.orEmpty() }
                            .firstOrNull { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
                if (isEmpty()) addAll(jsonStringElements(array))
            }.joinToString("").takeIf { it.isNotBlank() }
        }
        return source.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    /** JVM tests use a deliberately tiny org.json; this also tolerates string-only model arrays. */
    private fun jsonStringElements(array: JSONArray): List<String> =
        Regex("\"(?:\\\\.|[^\"\\\\])*\"").findAll(array.toString()).mapNotNull { match ->
            decodeJsonString(match.value)
        }.toList()

    private fun extractStringModelArrays(response: String): List<String> =
        Regex("\"(?:models|data)\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .findAll(response)
            .flatMap { match ->
                val body = match.groupValues[1]
                if (!body.trimStart().startsWith('"')) emptySequence()
                else Regex("\"(?:\\\\.|[^\"\\\\])*\"").findAll(body).mapNotNull { decodeJsonString(it.value) }
            }
            .toList()

    private fun decodeJsonString(token: String): String? =
        runCatching { JSONObject("{\"value\":$token}") }
            .getOrNull()?.optString("value")
            ?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * 视觉多模态请求：文本 + 页面图片（PDF 走此通道）。
     * 图片压缩为 JPEG（质量 70，宽度压到 ~900px），base64 内嵌 data URI。
     * 需要服务端支持 image_url 内容块（GPT-4o/qwen-vl/glm-4v 等）。
     */
    fun chatVision(
        cfg: Config,
        system: String?,
        userText: String,
        pages: List<Bitmap>,
        onDelta: ((String) -> Unit)? = null,
        timeoutMs: Int = 180_000,
        onReason: ((String) -> Unit)? = null
    ): String {
        val visionModel = cfg.visionModel.ifBlank {
            throw IllegalStateException("未配置视觉模型，无法识别扫描页或图片")
        }
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", userText))
        for (bmp in pages) {
            val scaled = if (bmp.width > 900) {
                val h = bmp.height * 900 / bmp.width
                Bitmap.createScaledBitmap(bmp, 900, h, true)
            } else bmp
            val bo = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bo)
            if (scaled !== bmp) scaled.recycle()
            val b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")
                )
            )
        }
        val userMsg = JSONObject().put("role", "user").put("content", content)
        val msgs = JSONArray()
        if (!system.isNullOrBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        msgs.put(userMsg)
        return withFallback(cfg.copy(model = visionModel), msgs, timeoutMs, onDelta, onReason)
    }

    /** 统一发送实现：onDelta 非空 → stream:true 并解析 SSE；否则普通请求 */
    private fun postChat(
        cfg: Config,
        msgs: JSONArray,
        timeoutMs: Int,
        onDelta: ((String) -> Unit)?,
        onReason: ((String) -> Unit)? = null
    ): String {
        val token = cancellation.get()
        token?.ensureActive()
        endpointError(cfg)?.let { throw IllegalArgumentException(it) }
        val urlStr = chatCompletionsEndpoint(cfg.baseUrl, cfg.chatPath)
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        token?.attach(conn)
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            authorizationHeader(cfg.key, cfg.authPrefix)?.let {
                conn.setRequestProperty(cfg.authHeader, it)
            }

            val body = JSONObject()
                .put("model", cfg.model)
                .put("messages", msgs)
                // Use Object overload as well as Android's boolean overload; this keeps local JVM
                // tests compatible with the lightweight org.json implementation on their classpath.
                .put("stream", (onDelta != null) as Any)
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                // Do not surface provider response bodies: proxies sometimes echo request
                // diagnostics, authorization data, or other sensitive material.
                throw RuntimeException("HTTP $code")
            }

            if (onDelta == null) {
                val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                return extractContent(resp) ?: throw RuntimeException("响应缺少 choices[0].message.content")
            }
            return readSse(conn, onDelta, onReason)
        } finally {
            token?.detach(conn)
            conn.disconnect()
        }
    }

    /** 解析 SSE 流：delta.content 正文 / delta.reasoning_content 推理思考（DeepSeek 混合推理模型） */
    private fun readSse(
        conn: HttpURLConnection,
        onDelta: (String) -> Unit,
        onReason: ((String) -> Unit)?
    ): String {
        val acc = StringBuilder()
        val raw = StringBuilder()   // 兜底用：万一服务端无视 stream 返回了完整 JSON
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                cancellation.get()?.ensureActive()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) {
                    raw.append(line).append('\n')
                    continue
                }
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                try {
                    // 注意：org.json 的 optString 对 JSON null 返回字面量 "null"！
                    // DeepSeek 混合推理模型的推理阶段 delta.content 为 JSON null，必须严格过滤
                    val dObj = JSONObject(payload)
                        .getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("delta")
                    if (dObj != null) {
                        val content: String? =
                            if (dObj.has("content") && !dObj.isNull("content")) dObj.getString("content") else null
                        val reason: String? =
                            if (dObj.has("reasoning_content") && !dObj.isNull("reasoning_content")) {
                                dObj.getString("reasoning_content")
                            } else null
                        if (!content.isNullOrEmpty()) {
                            acc.append(content)
                            onDelta(content)
                        }
                        if (!reason.isNullOrEmpty()) onReason?.invoke(reason)
                    }
                } catch (e: Exception) {
                    // 单行坏数据跳过
                }
            }
        }
        if (acc.isNotEmpty()) return acc.toString()
        // 兜底：整包 JSON（服务端不支持流式）
        val full = extractContent(raw.toString())
            ?: throw RuntimeException("未收到任何模型输出")
        onDelta(full)
        return full
    }
}
