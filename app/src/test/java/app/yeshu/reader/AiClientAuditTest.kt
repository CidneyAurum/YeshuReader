package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AiClient 新增行为的离线用例：usage 解析、SSE 错误帧/未正常收尾、就绪判定与连接测试文案。
 * 不触网：SSE 解析直接喂内存连接。
 */
class AiClientAuditTest {

    @Test
    fun extractUsage_readsPromptAndCompletionTokens() {
        val usage = AiClient.extractUsage("""{"usage":{"prompt_tokens":120,"completion_tokens":45}}""")

        assertEquals(AiClient.TokenUsage(120, 45), usage)
        assertEquals(165, usage!!.totalTokens)
        assertEquals(
            AiClient.TokenUsage(7, 3),
            AiClient.extractUsage("""{"usage":{"input_tokens":7,"output_tokens":3}}""")
        )
        assertNull(AiClient.extractUsage("""{"choices":[]}"""))
        // 服务商回 0 时视为「没有可用用量」，避免界面显示 0 tokens 的假数据
        assertNull(AiClient.extractUsage("""{"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))
        assertNull(AiClient.extractUsage("not-json"))
    }

    @Test
    fun readSseResult_throwsOnProviderErrorFrameInsteadOfReturningPartialText() {
        val stream = """
            data: {"choices":[{"delta":{"content":"前半段"}}]}
            data: {"error":{"message":"rate limit exceeded"}}
        """.trimIndent()

        val error = assertThrows(AiClient.ProviderStreamException::class.java) {
            AiClient.readSseResult(memoryConnection(stream), {})
        }

        assertTrue(error.message.orEmpty().contains("rate limit exceeded"))
        assertTrue(AiClient.userFacingError(error).contains("服务端中断"))
    }

    @Test
    fun readSseResult_marksStreamIncompleteWhenItNeverFinishes() {
        val stream = """
            data: {"choices":[{"delta":{"content":"被截断的"}}]}
            data: {"choices":[{"delta":{"content":"正文"}}]}
        """.trimIndent()
        val notices = mutableListOf<String>()

        val result = AiClient.readSseResult(memoryConnection(stream), {}, onIncomplete = { notices += it })

        assertEquals("被截断的正文", result.text)
        assertFalse(result.complete)
        assertEquals(listOf(AiClient.INCOMPLETE_NOTICE), notices)
    }

    @Test
    fun readSseResult_treatsDoneAndFinishReasonAsComplete() {
        val doneStream = "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}\ndata: [DONE]"
        val doneNotices = mutableListOf<String>()

        val done = AiClient.readSseResult(memoryConnection(doneStream), {}, onIncomplete = { doneNotices += it })

        assertTrue(done.complete)
        assertEquals("完成", done.text)
        assertTrue(doneNotices.isEmpty())

        val finishedStream = "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}"
        assertTrue(AiClient.readSseResult(memoryConnection(finishedStream), {}).complete)
    }

    @Test
    fun readSseResult_reportsUsageFromTheFinalChunk() {
        val stream = """
            data: {"choices":[{"delta":{"content":"答"}}]}
            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2}}
        """.trimIndent()
        var usage: AiClient.TokenUsage? = null

        val result = AiClient.readSseResult(memoryConnection(stream), {}, onUsage = { usage = it })

        assertEquals(AiClient.TokenUsage(9, 2), usage)
        assertTrue(result.complete)
    }

    @Test
    fun isReady_requiresAKeyExceptForKnownLocalHosts() {
        assertFalse(
            "公网地址缺 Key 时请求会以未鉴权身份发出，必须判定为未就绪",
            AiClient.isReady(AiClient.Config(baseUrl = "https://api.example.com/v1", key = "", model = "m"))
        )
        assertTrue(
            AiClient.isReady(AiClient.Config(baseUrl = "https://api.example.com/v1", key = "k", model = "m"))
        )
        assertTrue(
            AiClient.isReady(
                AiClient.Config(
                    baseUrl = "http://127.0.0.1:11434/v1",
                    key = "",
                    model = "m",
                    allowPrivateHttp = true
                )
            )
        )
        assertFalse(
            "模型名为空时仍然未就绪",
            AiClient.isReady(AiClient.Config(baseUrl = "https://api.example.com/v1", key = "k", model = " "))
        )
        assertTrue(AiClient.isNoAuthLocalHost("http://localhost:11434"))
        assertTrue(AiClient.isNoAuthLocalHost("http://reader.local:8080"))
        assertFalse(AiClient.isNoAuthLocalHost("https://api.example.com"))
    }

    @Test
    fun probeResult_describesModelLatencyAndTokens() {
        assertEquals(
            "连接成功 · gpt-x · 812ms · 17 tokens",
            AiClient.ProbeResult("gpt-x", 200, 812, "OK", AiClient.TokenUsage(5, 12)).describe()
        )
        assertEquals(
            "连接成功 · gpt-x · 812ms",
            AiClient.ProbeResult("gpt-x", 200, 812, "OK").describe()
        )
    }

    @Test
    fun userFacingError_surfacesRetryableInterruptionInsteadOfGenericFailure() {
        val retryable = AiClient.RetryableAiException("网络中断，已收到部分内容；重试可能重复计费，请手动重试")

        assertTrue(AiClient.userFacingError(retryable).contains("重复计费"))
        assertEquals("请求已取消", AiClient.userFacingError(java.util.concurrent.CancellationException("x")))
    }

    private fun memoryConnection(body: String): HttpURLConnection =
        object : HttpURLConnection(URL("http://localhost")) {
            override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))

            override fun connect() = Unit

            override fun disconnect() = Unit

            override fun usingProxy() = false
        }
}
