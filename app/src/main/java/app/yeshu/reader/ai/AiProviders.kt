package app.yeshu.reader.ai

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val textModelHint: String,
    val visionModelHint: String = ""
)

object AiProviders {
    val presets = listOf(
        ProviderPreset("compatible", "兼容接口", "", ""),
        ProviderPreset("tokenrhythm", "基元律动", "https://tokenrhythm.studio/v1", "deepseek-v4-flash"),
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"),
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", "gpt-4.1-mini"),
        ProviderPreset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen-vl-max"),
        ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "glm-4v-flash"),
        ProviderPreset("kimi", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        ProviderPreset("ollama", "Ollama（局域网）", "http://127.0.0.1:11434/v1", "qwen3:8b")
    )
}
