package app.yeshu.reader.ai

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val textModelHint: String,
    val visionModelHint: String = "",
    /** 选中预设后展示的补充说明；为空则不显示。 */
    val hint: String = "",
    /** 预设本身就走局域网明文 HTTP 时置真，选中后自动开启「允许局域网 HTTP」。 */
    val allowPrivateHttp: Boolean = false
)

object AiProviders {
    /** Ollama 只在用户自己的电脑上监听；手机上填 127.0.0.1 指的是手机自己，永远连不上。 */
    const val OLLAMA_PLACEHOLDER_HOST = "192.168.x.x"

    // textModelHint / visionModelHint 只是“点击预设后预填到输入框”的提示值，不会覆盖用户已经
    // 填好的模型名，用户也可以改成任意 id。因此这里只放确认在该厂商可用的 id：
    // 预填一个不存在的 id 会让第一次“测试当前模型”直接返回 400，而且原因很不直观。
    val presets = listOf(
        ProviderPreset("compatible", "兼容接口", "", ""),
        // 基元律动网关已实测接受该 id。
        ProviderPreset("tokenrhythm", "基元律动", "https://tokenrhythm.studio/v1", "deepseek-v4-flash"),
        // DeepSeek 官方 API 使用 deepseek-chat / deepseek-reasoner 这类对外模型名，
        // 不要照抄网关或内部版本号。
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", "gpt-4.1-mini"),
        ProviderPreset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen-vl-max"),
        ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "glm-4v-flash"),
        ProviderPreset("kimi", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        // 手机上的 127.0.0.1 指向手机自己，预设只能给占位地址：用户必须换成运行 Ollama 的电脑 IP。
        ProviderPreset(
            id = "ollama",
            name = "Ollama（局域网）",
            baseUrl = "http://$OLLAMA_PLACEHOLDER_HOST:11434/v1",
            textModelHint = "qwen3:8b",
            hint = "把 $OLLAMA_PLACEHOLDER_HOST 换成运行 Ollama 的电脑局域网 IP，" +
                "并确认电脑上设置了 OLLAMA_HOST=0.0.0.0；手机上的 127.0.0.1 指的是手机自己。",
            allowPrivateHttp = true
        )
    )
}
