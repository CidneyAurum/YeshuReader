package app.yeshu.reader.ai

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val textModelHint: String,
    val visionModelHint: String = ""
)

object AiProviders {
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
        ProviderPreset("ollama", "Ollama（局域网）", "http://127.0.0.1:11434/v1", "qwen3:8b")
    )
}
