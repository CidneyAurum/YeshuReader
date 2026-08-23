package com.example.helloandroid

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI 兼容 chat/completions 客户端（HttpURLConnection + org.json，零依赖） */
object AiClient {

    data class Config(val baseUrl: String, val key: String, val model: String)

    fun config(db: Db): Config = Config(
        baseUrl = (db.getSetting("ai_base_url") ?: "").trim(),
        key = (db.getSetting("ai_key") ?: "").trim(),
        model = (db.getSetting("ai_model") ?: "").trim()
    )

    fun isReady(c: Config): Boolean =
        c.baseUrl.startsWith("http") && c.model.isNotBlank()

    /**
     * 地址规范化：容错用户输入——
     * 1) 去尾部斜杠；2) 只有域名没有路径时自动补 /v1（OpenAI 惯例）。
     * 例：https://tokenrhythm.studio → https://tokenrhythm.studio/v1
     */
    fun normalizeBase(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http")) u = "https://$u"
        u = u.trimEnd('/')
        val schemeEnd = u.indexOf("://") + 3
        val hasPath = u.indexOf('/', schemeEnd) != -1   // host 后还有路径段
        return if (hasPath) u else "$u/v1"
    }

    /**
     * 同步调用（调用方负责放子线程）。返回 assistant 回复文本。
     * onDelta 非空时走流式请求并逐段回调（打字机效果）；服务端不支持时自动回退一次性解析。
     * 失败抛 RuntimeException，message 含 HTTP 状态与响应片段。
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
            if (onDelta != null) {
                val r = postChat(cfg, msgs, timeoutMs, null, null)
                onDelta(r)
                r
            } else throw e
        }
    }

    /** 兼容标准 OpenAI 结构；容错解析 */
    fun extractContent(respJson: String): String? = try {
        val json = JSONObject(respJson)
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) null
        else {
            val first = choices.getJSONObject(0)
            first.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                ?: first.optString("text", "").takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

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
        return withFallback(cfg, msgs, timeoutMs, onDelta, onReason)
    }

    /** 统一发送实现：onDelta 非空 → stream:true 并解析 SSE；否则普通请求 */
    private fun postChat(
        cfg: Config,
        msgs: JSONArray,
        timeoutMs: Int,
        onDelta: ((String) -> Unit)?,
        onReason: ((String) -> Unit)? = null
    ): String {
        val urlStr = normalizeBase(cfg.baseUrl) + "/chat/completions"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (cfg.key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer " + cfg.key)

            val body = JSONObject()
                .put("model", cfg.model)
                .put("messages", msgs)
                .put("stream", onDelta != null)
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                throw RuntimeException("HTTP $code ${err.take(300)}")
            }

            if (onDelta == null) {
                val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                return extractContent(resp) ?: throw RuntimeException("响应缺少 choices[0].message.content")
            }
            return readSse(conn, onDelta, onReason)
        } finally {
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
