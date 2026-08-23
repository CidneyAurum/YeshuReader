package app.yeshu.reader.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only an AES-GCM encrypted API key; the wrapping key never leaves Android Keystore. */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readApiKey(expectedOrigin: String): String {
        if (expectedOrigin.isBlank()) return ""
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return ""
        val value = runCatching {
            val parts = payload.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrElse { "" }
        if (value.isBlank()) return ""
        val storedOrigin = prefs.getString(KEY_ORIGIN, "").orEmpty()
        if (storedOrigin.isBlank()) {
            // Bind payloads written by the pre-profile build to the currently configured origin once.
            prefs.edit().putString(KEY_ORIGIN, expectedOrigin).apply()
            return value
        }
        return value.takeIf { storedOrigin == expectedOrigin }.orEmpty()
    }

    fun writeApiKey(value: String, origin: String) {
        if (value.isBlank()) {
            prefs.edit().remove(KEY_PAYLOAD).remove(KEY_ORIGIN).apply()
            return
        }
        require(origin.isNotBlank()) { "API Key 必须绑定有效的服务地址" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PAYLOAD, payload).putString(KEY_ORIGIN, origin).apply()
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
