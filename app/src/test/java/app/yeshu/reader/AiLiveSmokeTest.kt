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
        model = props.getProperty("yeshu.ai.model", "deepseek-v4-flash")
        apiKey = System.getenv("YESHU_AI_KEY")?.takeIf { it.isNotBlank() }
            ?: props.getProperty("yeshu.ai.key", "")
        assumeTrue("未提供 AI 密钥，跳过实况测试", apiKey.isNotBlank())
    }

    private fun config(key: String = apiKey) = AiClient.Config(baseUrl = baseUrl, key = key, model = model)

    @Test
    fun `端点归一化指向 chat completions`() {
        assertEquals("$baseUrl/chat/completions", AiClient.chatCompletionsEndpoint(baseUrl))
    }

    @Test
    fun `实况流式对话能拿到完整回答`() {
        val answer = AiClient.chat(config(), "你是测试助手。", "只回复两个字：可用")
        assertTrue("回答为空", answer.isNotBlank())
    }

    @Test
    fun `流式增量累加结果与最终返回一致`() {
        // 服务端会在结尾多发一个 choices 为空数组的块，解析器必须能跳过它而不中断。
        val chunks = mutableListOf<String>()
        val answer = AiClient.chat(config(), "你是测试助手。", "从 1 数到 5，用逗号分隔，不要其他内容", onDelta = { chunks.add(it) })
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
