package app.yeshu.reader.security

/**
 * 一条已保存 API Key 的真实状态。
 *
 * 之所以要显式区分，是因为「密文非空」并不等于「当前设备能解开、且属于这个服务地址」：
 * 换机/恢复备份后 Keystore 里的包裹密钥会失效，此时密文仍在，但解密一定失败。
 * 界面若只看密文存在就写「已安全保存」，用户会在请求被拒（401）时才发现问题。
 */
enum class KeyState {
    /** 从未保存过。 */
    NONE,

    /** 可解密且来源匹配，可以用于请求。 */
    OK,

    /** 密文存在，但绑定的是别的服务地址（含来源未知的旧数据）。 */
    ORIGIN_MISMATCH,

    /** 密文存在且来源匹配，但当前 Keystore 密钥解不开。需要用户重新填写。 */
    UNDECRYPTABLE;

    /** 界面文案：null 表示无需额外提示（NONE 与 OK 各自有正常文案）。 */
    val hint: String?
        get() = when (this) {
            UNDECRYPTABLE -> "需要重新填写 Key（历史保存已无法解密）"
            ORIGIN_MISMATCH -> "需要重新填写 Key（已保存的 Key 属于其他服务地址）"
            else -> null
        }
}

/**
 * 纯函数状态机：不触碰 Android Keystore，便于 JVM 单测覆盖四种状态。
 * [decryptResult] 只在 successful 时被读取，调用方不要传入明文本身。
 */
object KeyPayloadState {
    fun classify(
        storedOrigin: String?,
        expectedOrigin: String,
        payloadPresent: Boolean,
        decryptSucceeded: Boolean
    ): KeyState = when {
        !payloadPresent -> KeyState.NONE
        // 解不开的优先级高于来源校验：这是唯一需要用户动手重填的情况
        !decryptSucceeded -> KeyState.UNDECRYPTABLE
        storedOrigin.isNullOrBlank() || expectedOrigin.isBlank() -> KeyState.ORIGIN_MISMATCH
        storedOrigin != expectedOrigin -> KeyState.ORIGIN_MISMATCH
        else -> KeyState.OK
    }
}