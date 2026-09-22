package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 实况冒烟测试：用真实服务商接口跑通「请求 → SSE 流式解析 → 文本提取」的完整链路。
 *
 * 默认跳过，不影响离线 CI。启用方式（任选其一）：
 *  - 环境变量 `YESHU_AI_KEY`
 *  - 未跟踪的 `local.properties`：`yeshu.ai.key=...`
 * 服务地址与模型同样从 `yeshu.ai.baseUrl` / `yeshu.ai.model` 读取。
 *
 * 密钥只从本机未跟踪文件读取，绝不写入源码或测试资源。
 */
class AiLiveSmokeTest {

    private lateinit var baseUrl: String
    private lateinit var model: String
    private var apiKey: String = ""

    @Before
    fun loadLocalConfig() {
        val props = Properties()
        // 单元测试的工作目录是模块目录（app/），local.properties 在项目根，两处都找。
        listOf(File("../local.properties"), File("local.properties"))
            .firstOrNull { it.isFile }
            ?.inputStream()?.use { props.load(it) }
        baseUrl = props.getProperty("yeshu.ai.baseUrl", "https://tokenrhythm.studio/v1")
        // 用稳定别名而不是带版本/日期的具体 id：服务商轮换版本时别名不会失效
        model = props.getProperty("yeshu.ai.model", "deepseek-flash")
        apiKey = System.getenv("YESHU_AI_KEY")?.takeIf { it.isNotBlank() }
            ?: props.getProperty("yeshu.ai.key", "")
        assumeTrue("未提供 AI 密钥，跳过实况测试", apiKey.isNotBlank())
    }

    private fun config(key: String = apiKey) = AiClient.Config(baseUrl = baseUrl, key = key, model = model)

    /**
     * 网络不可达时跳过而不是失败。
     *
     * 实况测试依赖真实服务商，DNS 不通、连不上、超时都属于「这次没法测」，
     * 不该让离线测试套件变红——那会掩盖真正的回归。凭据被拒（401/403）同理：
     * Key 可能已按安全建议轮换过。
     */
    private fun skipIfUnavailable(error: Throwable): Nothing {
        val transient = error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.NoRouteToHostException ||
            // 网关瞬时掐断 TLS 握手（负载/中间设备）：重试通常能过，不是代码问题。
            error is javax.net.ssl.SSLException ||
            (error.message?.let { it.contains("401") || it.contains("403") } == true)
        assumeTrue("服务商当前不可达，跳过实况测试：${error.message}", !transient)
        throw error
    }

    /** 包一层：把「连不上/被拒」转成跳过，其余异常照旧失败。 */
    private inline fun <T> live(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        skipIfUnavailable(error)
    }


    @Test
    fun `端点归一化指向 chat completions`() {
        assertEquals("$baseUrl/chat/completions", AiClient.chatCompletionsEndpoint(baseUrl))
    }

    @Test
    fun `采样参数被真实网关接受`() {
        // 学习包依赖低温度 + 明确长度上限；网关拒绝这两个参数时必须能降级而不是直接失败。
        val answer = live { AiClient.chat(
            config(),
            system = "你是测试助手。",
            user = "只回复两个字：可用",
            temperature = 0.2,
            maxTokens = 64,
        ) }
        assertTrue("回答为空", answer.isNotBlank())
    }

    @Test
    fun `json 模式被真实网关接受`() {
        val answer = live { AiClient.chat(
            config(),
            system = "你是 JSON 生成器。只输出 JSON，不要解释。",
            user = """输出 {"ok":true} 这个对象""",
            temperature = 0.0,
            maxTokens = 200,
            jsonMode = true,
        ) }
        val text = answer.trim()
        assertTrue("返回内容不是 JSON：${text.take(120)}", text.startsWith("{"))
    }

    @Test
    fun `探测接口报告模型与延迟`() {
        // probe 在失败时抛错，成功时返回结构化结果。
        val probe = live { AiClient.probe(config()) }
        assertTrue("未报告模型名", probe.model.isNotBlank())
        assertTrue("延迟未测量", probe.latencyMs >= 0)
        assertTrue("描述信息不完整：${probe.describe()}", probe.describe().contains(probe.model))
        assertEquals("状态码应为 200", 200, probe.httpStatus)
    }

    @Test
    fun `用量统计被解析出来`() {
        val usage = java.util.concurrent.atomic.AtomicReference<AiClient.TokenUsage?>()
        live { AiClient.chat(config(), system = "你是测试助手。", user = "只回复两个字：可用", onUsage = { usage.set(it) }) }
        val tokens = usage.get()
        assertNotNull("没有回调用量信息", tokens)
        assertTrue("未解析出输入 tokens", tokens!!.promptTokens > 0)
    }

    @Test
    fun `实况流式对话能拿到完整回答`() {
        val answer = live { AiClient.chat(config(), "你是测试助手。", "只回复两个字：可用") }
        assertTrue("回答为空", answer.isNotBlank())
    }

    @Test
    fun `流式增量累加结果与最终返回一致`() {
        // 服务端会在结尾多发一个 choices 为空数组的块，解析器必须能跳过它而不中断。
        val chunks = mutableListOf<String>()
        val answer = live { AiClient.chat(config(), "你是测试助手。", "从 1 数到 5，用逗号分隔，不要其他内容", onDelta = { chunks.add(it) }) }
        assertTrue("没有收到任何流式增量", chunks.isNotEmpty())
        assertEquals("流式增量拼接结果与返回值不一致", answer, chunks.joinToString(""))
        assertTrue("回答里应当包含数字", answer.contains("1") && answer.contains("5"))
    }

    @Test
    fun `密钥无效时给出面向用户的错误`() {
        val error = runCatching { AiClient.chat(config(key = "sk_tr_definitely_invalid"), "sys", "hi") }
            .exceptionOrNull()
        assertNotNull("无效密钥应当抛错，而不是静默返回空", error)
        val message = AiClient.userFacingError(error!!)
        assertTrue("错误信息不应为空", message.isNotBlank())
        assertFalse("错误信息不应包含密钥", message.contains("sk_tr_definitely_invalid"))
    }

    @Test
    fun `响应文本提取能处理真实返回体`() {
        // 非流式返回体的解析路径（整段 JSON 回退时使用）。
        val body = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""
        assertEquals("ok", AiClient.extractContent(body))
        // 服务端返回空 choices 时应当返回 null 而不是抛异常。
        assertEquals(null, AiClient.extractContent("""{"id":"x","choices":[]}"""))
    }
}
