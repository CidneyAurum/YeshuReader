package app.yeshu.reader.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only an AES-GCM encrypted API key; the wrapping key never leaves Android Keystore. */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readApiKey(expectedOrigin: String): String {
        if (expectedOrigin.isBlank()) return ""
        return if (apiKeyState(expectedOrigin) == KeyState.OK) decryptPayload(KEY_PAYLOAD) else ""
    }

    /** 全局槽位的真实状态；origin 不匹配或无法解密时都如实返回，不再静默为空串。 */
    fun apiKeyState(expectedOrigin: String): KeyState = KeyPayloadState.classify(
        storedOrigin = prefs.getString(KEY_ORIGIN, "").orEmpty(),
        expectedOrigin = expectedOrigin,
        payloadPresent = prefs.getString(KEY_PAYLOAD, null).orEmpty().isNotBlank(),
        decryptSucceeded = decryptOrNull(KEY_PAYLOAD) != null
    )

    /** Reads one profile's independently encrypted key and verifies its provider origin. */
    fun readProfileApiKey(profileId: String, expectedOrigin: String): String {
        if (profileId.isBlank()) return ""
        return if (profileKeyState(profileId, expectedOrigin) == KeyState.OK) {
            decryptPayload("${profileSlot(profileId)}_payload")
        } else {
            ""
        }
    }

    /**
     * 一个配置的 Key 状态。origin 匹配、但当前 Keystore 密钥解不开时返回
     * [KeyState.UNDECRYPTABLE]，界面据此提示「需要重新填写 Key」而不是假报保存成功。
     */
    fun profileKeyState(profileId: String, expectedOrigin: String): KeyState {
        if (profileId.isBlank()) return KeyState.NONE
        val slot = profileSlot(profileId)
        val payloadKey = "${slot}_payload"
        return KeyPayloadState.classify(
            storedOrigin = prefs.getString("${slot}_origin", "").orEmpty(),
            expectedOrigin = expectedOrigin,
            payloadPresent = prefs.getString(payloadKey, null).orEmpty().isNotBlank(),
            decryptSucceeded = decryptOrNull(payloadKey) != null
        )
    }

    /**
     * 旧签名：只回答「这条密文在本机能否解开」，不校验来源。
     * 调用方若能拿到服务地址，应改用 [hasProfileApiKey] 的带 origin 重载。
     */
    fun hasProfileApiKey(profileId: String): Boolean {
        if (profileId.isBlank()) return false
        return decryptPayload("${profileSlot(profileId)}_payload").isNotBlank()
    }

    /** 校验密文可解密且属于 [expectedOrigin]。 */
    fun hasProfileApiKey(profileId: String, expectedOrigin: String): Boolean =
        profileKeyState(profileId, expectedOrigin) == KeyState.OK

    fun hasUnboundApiKey(): Boolean =
        prefs.getString(KEY_PAYLOAD, null).orEmpty().isNotBlank() &&
            prefs.getString(KEY_ORIGIN, "").orEmpty().isBlank()

    fun hasApiKey(): Boolean = prefs.getString(KEY_PAYLOAD, null).orEmpty().isNotBlank()

    /** Explicit user-confirmed migration for a key whose original provider is unknown. */
    fun bindUnboundApiKey(origin: String): Boolean {
        require(origin.isNotBlank()) { "API Key 必须绑定有效的服务地址" }
        if (!hasUnboundApiKey()) return false
        val value = decryptPayload(KEY_PAYLOAD)
        if (value.isBlank()) return false
        writeApiKey(value, origin)
        return true
    }

    fun writeApiKey(value: String, origin: String) {
        if (value.isBlank()) {
            prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ORIGIN).apply()
            return
        }
        require(origin.isNotBlank()) { "API Key 必须绑定有效的服务地址" }
        writeEncrypted(value, origin)
    }

    fun writeProfileApiKey(profileId: String, value: String, origin: String) {
        require(profileId.isNotBlank()) { "AI 配置 ID 不能为空" }
        val slot = profileSlot(profileId)
        val payloadKey = "${slot}_payload"
        val originKey = "${slot}_origin"
        if (value.isBlank()) {
            prefs.edit().remove(payloadKey).remove(originKey).apply()
            return
        }
        require(origin.isNotBlank()) { "API Key 必须绑定有效的服务地址" }
        writeEncrypted(value, origin, payloadKey, originKey)
    }

    fun removeProfileApiKey(profileId: String) {
        val slot = profileSlot(profileId)
        prefs.edit().remove("${slot}_payload").remove("${slot}_origin").apply()
    }

    /** Stores a migrated legacy key without authorizing it for any provider. */
    fun writeUnboundApiKey(value: String) {
        if (value.isBlank()) return
        writeEncrypted(value, "")
    }

    private fun writeEncrypted(
        value: String,
        origin: String,
        payloadKey: String = KEY_PAYLOAD,
        originKey: String = KEY_ORIGIN
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(payloadKey, payload).putString(originKey, origin).apply()
    }

    private fun decryptPayload(payloadKey: String): String = decryptOrNull(payloadKey).orEmpty()

    /** 解密失败与「没有密文」都返回 null；成功时一定返回非空明文（空白值不会被写入）。 */
    private fun decryptOrNull(payloadKey: String): String? = runCatching {
        val payload = prefs.getString(payloadKey, null) ?: return null
        val parts = payload.split(':', limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun profileSlot(profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profileId.toByteArray(Charsets.UTF_8))
        return "ai_profile_" + digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "yeshu_secure"
        const val KEY_PAYLOAD = "ai_api_key"
        const val KEY_ORIGIN = "ai_api_origin"
        const val KEY_ALIAS = "yeshu_ai_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
