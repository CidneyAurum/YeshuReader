package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URL

class AiClientTest {

    @Test
    fun normalizeBase_addsHttpsAndOpenAiPathForBareHost() {
        assertEquals(
            "https://api.example.com/v1",
            AiClient.normalizeBase("  api.example.com  ")
        )
    }

    @Test
    fun normalizeBase_preservesExplicitPathAndRemovesTrailingSlashes() {
        assertEquals(
            "https://api.example.com/openai/v1",
            AiClient.normalizeBase("https://api.example.com/openai/v1///")
        )
        assertEquals(
            "http://localhost:11434/v1",
            AiClient.normalizeBase("http://localhost:11434/")
        )
    }

    @Test
    fun endpointError_acceptsHttpsWithoutPrivateHttpOptIn() {
        val config = config("https://api.example.com")

        assertNull(AiClient.endpointError(config))
    }

    @Test
    fun endpointError_requiresOptInForPrivateHttp() {
        val error = AiClient.endpointError(
            config("http://localhost:11434", allowPrivateHttp = false)
        )

        assertEquals("HTTP 仅用于本机/局域网服务，请先开启局域网 HTTP", error)
    }

    @Test
    fun endpointError_acceptsLoopbackAndPrivateHttpAfterOptIn() {
        val privateEndpoints = listOf(
            "http://localhost:11434",
            "http://127.0.0.1:8080",
            "http://10.42.0.5:8080",
            "http://172.16.0.1:8080",
            "http://172.31.255.254:8080",
            "http://192.168.1.2:8080",
            "http://169.254.1.2:8080",
            "http://reader.local:8080",
            "http://[::1]:11434"
        )

        privateEndpoints.forEach { endpoint ->
            assertNull(
                "Expected private endpoint to be accepted: $endpoint",
                AiClient.endpointError(config(endpoint, allowPrivateHttp = true))
            )
        }
    }

    @Test
    fun endpointError_rejectsPublicHttpEvenAfterOptIn() {
        val publicEndpoints = listOf(
            "http://api.example.com",
            "http://8.8.8.8",
            "http://172.15.0.1",
            "http://172.32.0.1",
            "http://10.attacker.example",
            "http://127.attacker.example",
            "http://192.168.attacker.example",
            "http://10.999.1.1"
        )

        publicEndpoints.forEach { endpoint ->
            assertEquals(
                "Unexpected policy result for $endpoint",
                "公网接口必须使用 HTTPS",
                AiClient.endpointError(config(endpoint, allowPrivateHttp = true))
            )
        }
    }

    @Test
    fun endpointOrigin_normalizesSchemeHostAndDefaultPort() {
        assertEquals("https://api.example.com:443", AiClient.endpointOrigin("HTTPS://API.EXAMPLE.COM/v1"))
        assertEquals("http://localhost:11434", AiClient.endpointOrigin("http://localhost:11434"))
    }

    @Test
    fun endpointError_reportsMissingAndMalformedAddresses() {
        assertEquals("请填写接口地址", AiClient.endpointError(config("   ")))
        assertEquals("接口地址格式不正确", AiClient.endpointError(config("https://[broken")))
    }

    @Test
    fun extractContent_readsStandardAssistantMessage() {
        val json = """
            {"choices":[{"message":{"role":"assistant","content":"读书，也读懂资料"}}]}
        """.trimIndent()

        assertEquals("读书，也读懂资料", AiClient.extractContent(json))
    }

    @Test
    fun extractContent_supportsLegacyTextAndRejectsInvalidPayloads() {
        val legacy = """{"choices":[{"text":"legacy reply"}]}"""

        assertEquals("legacy reply", AiClient.extractContent(legacy))
        assertNull(AiClient.extractContent("""{"choices":[]}"""))
        assertNull(AiClient.extractContent("""{"unexpected":true}"""))
        assertNull(AiClient.extractContent("not-json"))
    }

    @Test
    fun readSse_accumulatesContentAndRoutesReasoningSeparately() {
        val stream = """
            data: {"choices":[{"delta":{"reasoning_content":"先定位资料","content":null}}]}

            data: {"choices":[{"delta":{"content":"页"}}]}
            data: malformed-json-is-ignored
            data: {"choices":[{"delta":{"content":"枢"}}]}
            data: [DONE]
        """.trimIndent()
        val contentChunks = mutableListOf<String>()
        val reasoningChunks = mutableListOf<String>()

        val result = invokeReadSse(stream, contentChunks::add, reasoningChunks::add)

        assertEquals("页枢", result)
        assertEquals(listOf("页", "枢"), contentChunks)
        assertEquals(listOf("先定位资料"), reasoningChunks)
    }

    @Test
    fun readSse_fallsBackToWholeJsonWhenServerIgnoresStreaming() {
        val wholeResponse = """
            {"choices":[{"message":{"content":"完整响应"}}]}
        """.trimIndent()
        val chunks = mutableListOf<String>()

        val result = invokeReadSse(wholeResponse, chunks::add)

        assertEquals("完整响应", result)
        assertEquals(listOf("完整响应"), chunks)
    }

    @Test
    fun readSse_throwsWhenStreamContainsNoUsableOutput() {
        val error = assertThrows(RuntimeException::class.java) {
            invokeReadSse("data: [DONE]\n", {})
        }

        assertTrue(error.message.orEmpty().contains("未收到任何模型输出"))
    }

    private fun config(baseUrl: String, allowPrivateHttp: Boolean = false) = AiClient.Config(
        baseUrl = baseUrl,
        key = "",
        model = "test-model",
        allowPrivateHttp = allowPrivateHttp
    )

    private fun invokeReadSse(
        body: String,
        onContent: (String) -> Unit,
        onReason: ((String) -> Unit)? = null
    ): String {
        val method = AiClient::class.java.declaredMethods.single { it.name == "readSse" }
        method.isAccessible = true
        return try {
            method.invoke(AiClient, MemoryHttpConnection(body), onContent, onReason) as String
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private class MemoryHttpConnection(body: String) :
        HttpURLConnection(URL("http://localhost")) {

        private val bytes = body.toByteArray(Charsets.UTF_8)

        override fun getInputStream() = ByteArrayInputStream(bytes)

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false
    }
}
