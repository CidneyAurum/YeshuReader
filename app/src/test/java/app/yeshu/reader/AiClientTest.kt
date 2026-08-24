package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
    fun compatibleEndpoints_appendOnceForBaseOrFullChatUrl() {
        assertEquals(
            "https://token.example/v1/chat/completions",
            AiClient.chatCompletionsEndpoint("https://token.example/v1")
        )
        assertEquals(
            "https://token.example/v1/chat/completions",
            AiClient.chatCompletionsEndpoint("https://token.example/v1/chat/completions/")
        )
        assertEquals(
            "https://token.example/v1/models",
            AiClient.modelsEndpoint("https://token.example/v1/chat/completions")
        )
    }

    @Test
    fun listModels_fallsBackToChatWithCustomKeyPrefix() {
        val server = CompatibilityServer()

        try {
            val customKey = "token_custom_prefix_for_test"
            val models = AiClient.listModels(
                AiClient.Config(
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    key = customKey,
                    model = "vendor-custom-model",
                    allowPrivateHttp = true
                ),
                timeoutMs = 5_000
            )

            server.awaitRequests()
            assertTrue(models.isEmpty())
            assertEquals("Bearer $customKey", server.modelRequestAuthorization.get())
            assertEquals("Bearer $customKey", server.chatRequestAuthorization.get())
            assertTrue("Chat request did not declare a body", server.chatContentLength.get() > 0)
        } finally {
            server.close()
        }
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
    fun endpointError_acceptsSystemWhitelistedPrivateHttpAfterOptIn() {
        val privateEndpoints = listOf(
            "http://localhost:11434",
            "http://127.0.0.1:8080",
            "http://10.0.2.2:11434",
            "http://reader.local:8080"
        )

        privateEndpoints.forEach { endpoint ->
            assertNull(
                "Expected private endpoint to be accepted: $endpoint",
                AiClient.endpointError(config(endpoint, allowPrivateHttp = true))
            )
        }
    }

    @Test
    fun endpointError_requiresLocalHostnameForLanCleartext() {
        val privateIpEndpoints = listOf(
            "http://10.42.0.5:8080",
            "http://172.16.0.1:8080",
            "http://172.31.255.254:8080",
            "http://192.168.1.2:8080",
            "http://169.254.1.2:8080",
            "http://[::1]:11434"
        )

        privateIpEndpoints.forEach { endpoint ->
            assertTrue(
                AiClient.endpointError(config(endpoint, allowPrivateHttp = true))
                    .orEmpty().contains(".local")
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
            "http://192.168.attacker.example"
        )

        publicEndpoints.forEach { endpoint ->
            assertEquals(
                "Unexpected policy result for $endpoint",
                "公网接口必须使用 HTTPS",
                AiClient.endpointError(config(endpoint, allowPrivateHttp = true))
            )
        }
        assertEquals(
            "接口地址格式不正确",
            AiClient.endpointError(config("http://10.999.1.1", allowPrivateHttp = true))
        )
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

    private class CompatibilityServer : AutoCloseable {
        private val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable>()
        val modelRequestAuthorization = AtomicReference<String>()
        val chatRequestAuthorization = AtomicReference<String>()
        val chatContentLength = java.util.concurrent.atomic.AtomicInteger()
        val port: Int = socket.localPort
        private val worker = thread(name = "ai-client-test-server", isDaemon = true) {
            try {
                repeat(2) { serve(socket.accept()) }
            } catch (error: Throwable) {
                if (!socket.isClosed) failure.set(error)
            }
        }

        fun awaitRequests() {
            worker.join(5_000)
            assertFalse("Timed out waiting for compatibility requests", worker.isAlive)
            failure.get()?.let { throw AssertionError("Compatibility server failed", it) }
        }

        private fun serve(client: java.net.Socket) = client.use { connection ->
            connection.soTimeout = 5_000
            val input = connection.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < terminator.size) {
                val value = input.read()
                if (value < 0) break
                headerBytes.write(value)
                matched = if (value.toByte() == terminator[matched]) matched + 1 else 0
            }
            val headerLines = headerBytes.toString(Charsets.US_ASCII).lineSequence().toList()
            val path = headerLines.firstOrNull().orEmpty().split(' ').getOrNull(1).orEmpty()
            val headers = linkedMapOf<String, String>()
            headerLines.drop(1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            input.readNBytes(contentLength)

            val response = if (path == "/v1/models") {
                modelRequestAuthorization.set(headers["authorization"])
                HttpResponse(404, "Not Found", ByteArray(0))
            } else {
                chatRequestAuthorization.set(headers["authorization"])
                chatContentLength.set(contentLength)
                HttpResponse(
                    200,
                    "OK",
                    """{"choices":[{"message":{"content":"OK"}}]}""".toByteArray(Charsets.UTF_8)
                )
            }
            connection.getOutputStream().use { output ->
                output.write(
                    ("HTTP/1.1 ${response.code} ${response.reason}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${response.body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                output.write(response.body)
                output.flush()
            }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }

        private data class HttpResponse(val code: Int, val reason: String, val body: ByteArray)
    }
}
