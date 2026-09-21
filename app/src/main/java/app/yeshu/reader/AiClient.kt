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

    /** 连接测试用的最小请求与展示上限。 */
    private const val PROBE_PROMPT = "Reply with OK."
    private const val PROBE_MAX_TOKENS = 16
    private const val PROBE_ECHO_CHARS = 120

    /** 流未正常收尾时的提示文案，交给调用方决定怎么标注。 */
    const val INCOMPLETE_NOTICE = "响应未正常结束（可能被截断），已保留已接收内容"

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

    /**
     * 服务端返回的 token 用量。部分兼容服务商不返回 usage，此时各字段为 0。
     * [describe] 只用于界面展示，不参与任何计费计算。
     */
    data class TokenUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
        val totalTokens: Int get() = promptTokens + completionTokens
        val isEmpty: Boolean get() = promptTokens == 0 && completionTokens == 0
        fun describe(): String = "输入 $promptTokens · 输出 $completionTokens tokens"
    }

    /** 连接测试结果：设置页用它报告「连接成功 · 模型 · 延迟 · token」，而不是固定文案。 */
    data class ProbeResult(
        val model: String,
        val httpStatus: Int,
        val latencyMs: Long,
        val echo: String,
        val usage: TokenUsage = TokenUsage()
    ) {
        fun describe(): String {
            val tokens = if (usage.isEmpty) "" else " · ${usage.totalTokens} tokens"
            return "连接成功 · $model · ${latencyMs}ms$tokens"
        }
    }

    /** 服务端在流中途返回 error 帧（限流、余额不足、内容审核等）。[detail] 已脱敏。 */
    class ProviderStreamException(val detail: String) : RuntimeException("服务端中断：$detail")

    /**
     * 已收到部分内容后网络中断：自动重发会重复计费，必须交给用户决定。
     * 与 [java.io.IOException] 区分开，避免上层把它当成「还没开始就失败」而无提示地重试。
     */
    class RetryableAiException(message: String) : RuntimeException(message)

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

    /**
     * 就绪判定：模型名、地址合法，并且必须有 Key。
     * 唯一例外是「本机无鉴权服务」——回环地址、localhost、*.local 与模拟器宿主别名，
     * 这些地址上的本地推理服务（Ollama/LM Studio 等）通常不需要 Key。
     * 其余地址缺 Key 时请求会以未鉴权身份发出，必须在发起前就拦住，而不是让用户看到 401。
     */
    fun isReady(c: Config): Boolean =
        c.model.isNotBlank() && endpointError(c) == null && (c.key.isNotBlank() || isNoAuthLocalHost(c.baseUrl))

    /** 已知可无 Key 的本机地址（只认字面量，不做 DNS 解析）。 */
    internal fun isNoAuthLocalHost(rawBase: String): Boolean {
        val host = runCatching { URI(normalizeBase(rawBase)).host }.getOrNull() ?: return false
        return isAllowedCleartextHost(host)
    }

    /** Converts transport/provider failures to safe UI text without echoing response bodies. */
    fun userFacingError(error: Throwable): String {
        if (error is CancellationException) return "请求已取消"
        if (error is RetryableAiException) return error.message.orEmpty().ifBlank { "网络中断，请重试" }
        if (error is ProviderStreamException) return error.message.orEmpty().ifBlank { "服务端中断，请重试" }
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
        // 流中途中断与引用修复提示自带中文结论，直接透传才有可操作性
        message.contains("服务端中断", true) || message.contains("网络中断", true) -> message
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

    /**
     * 判断主机是否为「本机/局域网」字面量。
     * 只接受 IP 字面量（IPv4、IPv6、IPv4 映射形式、带 zone-id 的链路本地地址），
     * 不做域名解析，避免 DNS 超时与「域名指向内网」造成的判定歧义。
     * 安全意图保持不变：只有环回、链路本地、私有网段可用明文 HTTP，公网地址必须 HTTPS。
     */
    private fun isPrivateNetworkHost(host: String): Boolean {
        val value = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%').trim()
        if (value.isEmpty()) return false
        parseIpv4(value)?.let { return isPrivateIpv4(it) }
        if (':' !in value) return false
        // 冒号形式只可能是 IPv6 字面量（可能内嵌 IPv4）；字符集不合法则直接否决，避免走解析器
        if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }
        val bytes = runCatching { java.net.InetAddress.getByName(value).address }.getOrNull() ?: return false
        if (bytes.size == 4) return isPrivateIpv4(bytes)
        if (bytes.size != 16) return false
        // IPv4 映射/兼容地址（::ffff:192.168.1.1）：按内嵌的 IPv4 判定
        if (bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        ) {
            return isPrivateIpv4(bytes.copyOfRange(12, 16))
        }
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return when {
            bytes.copyOfRange(0, 15).all { it == 0.toByte() } && bytes[15] == 1.toByte() -> true  // ::1 环回
            first == 0xFE && (second and 0xC0) == 0x80 -> true                                     // fe80::/10 链路本地
            first == 0xFE && (second and 0xC0) == 0xC0 -> true                                     // fec0::/10 站点本地
            (first and 0xFE) == 0xFC -> true                                                       // fc00::/7 唯一本地
            else -> false
        }
    }

    /** 严格解析点分十进制 IPv4；非四段/越界/前导零一律返回 null（避免八进制歧义绕过内网判定） */
    private fun parseIpv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = ByteArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            if (part.length > 1 && part[0] == '0') return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            octets[index] = octet.toByte()
        }
        return octets
    }

    private fun isPrivateIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
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

    /**
     * 模型列表发现 + chat 兜底校验。
     *
     * 注意：生产代码已改用 [discoverModels]（设置页读列表）与 [probe]（测试当前模型），
     * 本方法只剩 app/src/test 的兼容性测试在引用；确认测试迁移后应整体删除。
     */
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
     * onRestart 用于告知调用方「已上屏的增量作废，需清空后重新接收」（断流重发时回调一次）。
     * [temperature]/[maxTokens] 为空时不发送对应字段，避免覆盖服务商自己的默认值；
     * [jsonMode] 会带上 response_format={"type":"json_object"}，仅在服务端明确因此拒绝时自动降级重试一次。
     * [onNotice] 报告「已自动重试」等需要用户知情但不属于错误的事件；
     * [onIncomplete] 在流没有正常结束（缺 [DONE]/finish_reason）时回调，调用方应标注结果可能被截断；
     * [onUsage] 回传服务端 usage（部分服务商不返回）。
     * 失败抛 RuntimeException；HTTP 错误仅暴露状态码，不回显服务端响应正文。
     */
    fun chat(
        cfg: Config,
        system: String?,
        user: String,
        onDelta: ((String) -> Unit)? = null,
        timeoutMs: Int = 180_000,
        onReason: ((String) -> Unit)? = null,
        onRestart: (() -> Unit)? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
        onNotice: ((String) -> Unit)? = null,
        onIncomplete: ((String) -> Unit)? = null,
        onUsage: ((TokenUsage) -> Unit)? = null
    ): String {
        val msgs = JSONArray()
        if (!system.isNullOrBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        msgs.put(JSONObject().put("role", "user").put("content", user))
        return withFallback(
            cfg, msgs, timeoutMs, onDelta, onReason, onRestart,
            options(temperature, maxTokens, jsonMode, onNotice, onIncomplete, onUsage)
        )
    }

    /**
     * 多轮对话（与书聊天气）：history 为 (role, content) 列表，role 是 "user"/"assistant"。
     * system 由调用方拼好书上下文。其余参数语义同 [chat]。
     */
    fun chatHistory(
        cfg: Config,
        system: String?,
        history: List<Pair<String, String>>,
        onDelta: ((String) -> Unit)? = null,
        timeoutMs: Int = 180_000,
        onReason: ((String) -> Unit)? = null,
        onRestart: (() -> Unit)? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
        onNotice: ((String) -> Unit)? = null,
        onIncomplete: ((String) -> Unit)? = null,
        onUsage: ((TokenUsage) -> Unit)? = null
    ): String {
        val msgs = JSONArray()
        if (!system.isNullOrBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        for ((role, content) in history) {
            if (content.isBlank()) continue
            msgs.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", content))
        }
        return withFallback(
            cfg, msgs, timeoutMs, onDelta, onReason, onRestart,
            options(temperature, maxTokens, jsonMode, onNotice, onIncomplete, onUsage)
        )
    }

    private fun options(
        temperature: Double? = null,
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
        onNotice: ((String) -> Unit)? = null,
        onIncomplete: ((String) -> Unit)? = null,
        onUsage: ((TokenUsage) -> Unit)? = null
    ) = ChatOptions(temperature, maxTokens, jsonMode, onNotice, onIncomplete, onUsage)

    private data class ChatOptions(
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val jsonMode: Boolean = false,
        val onNotice: ((String) -> Unit)? = null,
        val onIncomplete: ((String) -> Unit)? = null,
        val onUsage: ((TokenUsage) -> Unit)? = null
    )

    /**
     * 流式断流自动降级：SSE 中途 IOException（弱网/代理断流）时，重发一次非流式请求保证结果可用，
     * 全文通过 onDelta 补发。
     *
     * 重发前会先回调 onRestart，调用方据此丢弃已累积/已上屏的部分输出，避免「半截 + 全文」重复。
     * 调用方未提供 onRestart 时无法纠正已上屏内容，此时不再重发，直接抛出原始异常。
     * 已经收到过 token 时不再自动重发：第二次完整生成会重复计费，必须由用户选择。
     */
    private fun withFallback(
        cfg: Config,
        msgs: JSONArray,
        timeoutMs: Int,
        onDelta: ((String) -> Unit)?,
        onReason: ((String) -> Unit)? = null,
        onRestart: (() -> Unit)? = null,
        options: ChatOptions = ChatOptions()
    ): String {
        var received = false
        val trackingDelta: ((String) -> Unit)? = onDelta?.let { callback ->
            { chunk ->
                if (chunk.isNotEmpty()) received = true
                callback(chunk)
            }
        }
        return try {
            postChat(cfg, msgs, timeoutMs, trackingDelta, onReason, options)
        } catch (e: java.io.IOException) {
            if (cancellation.get()?.isCancelled() == true) throw CancellationException("AI 请求已取消")
            if (onDelta == null || onRestart == null) throw e
            if (received) {
                throw RetryableAiException("网络中断，已收到部分内容；重试可能重复计费，请手动重试")
            }
            onRestart()
            options.onNotice?.invoke("网络中断，已自动重试一次（可能重复计费）")
            val r = postChat(cfg, msgs, timeoutMs, null, null, options)
            onDelta(r)
            r
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
     * 需要服务端支持 image_url 内容块（GPT-4o/qwen-vl/glm-4v 等）。onRestart 语义同 [chat]。
     */
    fun chatVision(
        cfg: Config,
        system: String?,
        userText: String,
        pages: List<Bitmap>,
        onDelta: ((String) -> Unit)? = null,
        timeoutMs: Int = 180_000,
        onReason: ((String) -> Unit)? = null,
        onRestart: (() -> Unit)? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
        onNotice: ((String) -> Unit)? = null,
        onIncomplete: ((String) -> Unit)? = null,
        onUsage: ((TokenUsage) -> Unit)? = null
    ): String {
        val visionModel = cfg.visionModel.ifBlank {
            throw IllegalStateException("未配置视觉模型，无法识别扫描页或图片")
        }
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", userText))
        for (bmp in pages) {
            val scaled = if (bmp.width > 900) {
                // 极宽极扁的页面按比例算高会得到 0，createScaledBitmap 会抛异常，必须夹到 1 以上
                val h = (bmp.height * 900 / bmp.width).coerceAtLeast(1)
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
        return withFallback(
            cfg.copy(model = visionModel), msgs, timeoutMs, onDelta, onReason, onRestart,
            options(temperature, maxTokens, jsonMode, onNotice, onIncomplete, onUsage)
        )
    }

    /** 统一发送实现：onDelta 非空 → stream:true 并解析 SSE；否则普通请求 */
    private fun postChat(
        cfg: Config,
        msgs: JSONArray,
        timeoutMs: Int,
        onDelta: ((String) -> Unit)?,
        onReason: ((String) -> Unit)? = null,
        options: ChatOptions = ChatOptions()
    ): String = try {
        sendChat(cfg, msgs, timeoutMs, onDelta, onReason, options, jsonMode = options.jsonMode)
    } catch (e: JsonModeRejected) {
        // 少数兼容服务商不认 response_format；只在明确因此被拒（400 且提到它）时降级重试一次。
        if (!options.jsonMode) throw RuntimeException(e.message ?: "请求被服务端拒绝")
        options.onNotice?.invoke("服务端不支持 JSON 输出模式，已改用普通模式重试")
        sendChat(cfg, msgs, timeoutMs, onDelta, onReason, options, jsonMode = false)
    }

    private class JsonModeRejected(message: String) : RuntimeException(message)

    private fun sendChat(
        cfg: Config,
        msgs: JSONArray,
        timeoutMs: Int,
        onDelta: ((String) -> Unit)?,
        onReason: ((String) -> Unit)?,
        options: ChatOptions,
        jsonMode: Boolean
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
            // 采样参数只在调用方明确给出时发送：写死默认值会覆盖服务商自己的默认行为。
            options.temperature?.let { body.put("temperature", it) }
            options.maxTokens?.let { body.put("max_tokens", it) }
            if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val message = httpErrorMessage(code, conn, cfg.key)
                if (jsonMode && code == 400 && mentionsJsonMode(message)) throw JsonModeRejected(message)
                throw RuntimeException(message)
            }

            if (onDelta == null) {
                val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                extractUsage(resp)?.let { usage -> options.onUsage?.invoke(usage) }
                return extractContent(resp) ?: throw RuntimeException("响应缺少 choices[0].message.content")
            }
            return try {
                readSseResult(conn, onDelta, onReason, options.onIncomplete, options.onUsage).text
            } catch (e: ProviderStreamException) {
                // 服务商错误帧可能被网关回显请求头，交给这里统一脱敏后再上抛
                throw ProviderStreamException(sanitizeDetail(e.detail, cfg.key))
            }
        } finally {
            token?.detach(conn)
            conn.disconnect()
        }
    }

    /**
     * 只回传服务商自己的 error.message，并抹掉可能被网关回显的密钥、限制长度。
     *
     * 整个响应体不能直接抛给用户（网关有时会回显请求诊断信息甚至鉴权数据），
     * 但只给一个「HTTP 400」用户又完全无从排查——比如模型名写错时。
     */
    private fun httpErrorMessage(code: Int, conn: HttpURLConnection, key: String): String {
        val body = runCatching {
            conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().trim()
        val safe = sanitizeDetail(detail, key)
        return if (safe.isEmpty()) "HTTP $code" else "HTTP $code：$safe"
    }

    /** 抹掉可能被回显的密钥并限制长度；返回值可直接展示给用户。 */
    private fun sanitizeDetail(detail: String, key: String): String =
        (if (key.isNotBlank()) detail.replace(key, "***") else detail).take(200).trim()

    /** 服务端是否明确因为 response_format 而拒绝；只有这种 400 才值得去掉它重试。 */
    private fun mentionsJsonMode(message: String): Boolean {
        val value = message.lowercase()
        return "response_format" in value || "json_object" in value || "json mode" in value || "json 模式" in value
    }

    /** 解析 usage；兼容 prompt_tokens/completion_tokens 与 input_tokens/output_tokens。 */
    internal fun extractUsage(respJson: String): TokenUsage? {
        val root = runCatching { JSONObject(respJson) }.getOrNull() ?: return null
        val usage = sequenceOf(root.optJSONObject("usage"), root.optJSONObject("data")?.optJSONObject("usage"))
            .filterNotNull()
            .firstOrNull() ?: return null
        val prompt = intOrZero(usage, "prompt_tokens", "input_tokens")
        val completion = intOrZero(usage, "completion_tokens", "output_tokens")
        return TokenUsage(prompt, completion).takeIf { !it.isEmpty }
    }

    private fun intOrZero(source: JSONObject, vararg keys: String): Int =
        keys.firstNotNullOfOrNull { key -> optionalString(source, key)?.toDoubleOrNull()?.toInt() } ?: 0

    /** org.json 对 JSON null 的 optString 会返回字面量 "null"，统一在这里过滤。 */
    private fun optionalString(source: JSONObject?, key: String): String? {
        if (source == null || !source.has(key) || source.isNull(key)) return null
        return source.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * 连接测试：一次最小 chat 请求，返回模型名、HTTP 状态、往返延迟与模型回显。
     * 只服务设置页的「测试当前模型」，不写日志、不回显密钥，也不做任何重试。
     */
    fun probe(cfg: Config, timeoutMs: Int = 30_000): ProbeResult {
        endpointError(cfg)?.let { throw IllegalArgumentException(it) }
        require(cfg.model.isNotBlank()) { "请填写模型名称" }
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", PROBE_PROMPT))
        val urlStr = chatCompletionsEndpoint(cfg.baseUrl, cfg.chatPath)
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        val startedAt = System.currentTimeMillis()
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
                .put("stream", false as Any)
                .put("temperature", 0.0)
                .put("max_tokens", PROBE_MAX_TOKENS)
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException(httpErrorMessage(code, conn, cfg.key))
            val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return ProbeResult(
                model = cfg.model.trim(),
                httpStatus = code,
                latencyMs = System.currentTimeMillis() - startedAt,
                echo = extractContent(resp).orEmpty().trim().take(PROBE_ECHO_CHARS),
                usage = extractUsage(resp) ?: TokenUsage()
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析 SSE 流：delta.content 正文 / delta.reasoning_content 推理思考（DeepSeek 混合推理模型）。
     * 生产路径走 [readSseResult]（需要 complete 标记）；这个三参数入口保留原名与原签名，
     * 因为 app/src/test 的既有用例通过反射按名字和参数个数调用它。
     */
    @Suppress("unused")
    private fun readSse(
        conn: HttpURLConnection,
        onDelta: (String) -> Unit,
        onReason: ((String) -> Unit)?
    ): String = readSseResult(conn, onDelta, onReason).text

    internal data class SseResult(val text: String, val complete: Boolean)

    /**
     * SSE 解析实现。[complete] 为 false 表示流没有以 [DONE] 或 finish_reason 正常收尾，
     * 调用方应把结果标注为可能被截断；错误帧直接抛出，绝不当作「部分成功」返回。
     */
    internal fun readSseResult(
        conn: HttpURLConnection,
        onDelta: (String) -> Unit,
        onReason: ((String) -> Unit)? = null,
        onIncomplete: ((String) -> Unit)? = null,
        onUsage: ((TokenUsage) -> Unit)? = null
    ): SseResult {
        val acc = StringBuilder()
        val raw = StringBuilder()   // 兜底用：万一服务端无视 stream 返回了完整 JSON
        var sawDone = false
        var sawFinishReason = false
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
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                try {
                    val root = JSONObject(payload)
                    // 错误帧（限流/余额/审核）必须在解析 choices 之前抛出：
                    // 静默跳过会让循环正常结束，半截输出被当成完整成功结果返回。
                    if (root.has("error")) throw ProviderStreamException(streamErrorMessage(root))
                    val choice = root.optJSONArray("choices")?.optJSONObject(0)
                    if (optionalString(choice, "finish_reason") != null) sawFinishReason = true
                    // 只有带 usage 的收尾帧才需要再解析一次，避免每个增量帧都重复解析
                    if ("usage" in payload) extractUsage(payload)?.let { usage -> onUsage?.invoke(usage) }
                    // 注意：org.json 的 optString 对 JSON null 返回字面量 "null"！
                    // DeepSeek 混合推理模型的推理阶段 delta.content 为 JSON null，必须严格过滤
                    val dObj = choice?.optJSONObject("delta")
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
                } catch (e: ProviderStreamException) {
                    throw e
                } catch (e: Exception) {
                    // 单行坏数据跳过
                }
            }
        }
        val complete = sawDone || sawFinishReason
        if (acc.isNotEmpty()) {
            if (!complete) onIncomplete?.invoke(INCOMPLETE_NOTICE)
            return SseResult(acc.toString(), complete)
        }
        // 兜底：整包 JSON（服务端不支持流式）
        val rawText = raw.toString()
        val full = extractContent(rawText)
            ?: throw RuntimeException("未收到任何模型输出")
        extractUsage(rawText)?.let { usage -> onUsage?.invoke(usage) }
        onDelta(full)
        return SseResult(full, true)
    }

    /** 只回传服务商自己的 error.message，过滤掉整包 JSON 或空值。 */
    private fun streamErrorMessage(root: JSONObject): String {
        val nested = root.optJSONObject("error")?.optString("message").orEmpty().trim()
        val flat = root.optString("error").trim()
        return nested.ifBlank { flat }.ifBlank { "服务端返回未知错误" }.take(200)
    }
}
