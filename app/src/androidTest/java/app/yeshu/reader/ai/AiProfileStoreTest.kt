package app.yeshu.reader.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.yeshu.reader.AiClient
import app.yeshu.reader.Db
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AiProfileStoreTest {

    @Test
    fun profilesKeepIndependentModelsEndpointsAndEncryptedKeys() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Db(context)
        val settingKeys = listOf(
            AiProfileStore.PROFILES_KEY, AiProfileStore.ACTIVE_PROFILE_KEY,
            "ai_base_url", "ai_model", "ai_vision_model", "ai_chat_path", "ai_models_path",
            "ai_auth_header", "ai_auth_prefix", "ai_allow_private_http"
        )
        val original = settingKeys.associateWith(db::getSetting)
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        val seedId = UUID.randomUUID().toString()
        val base = "https://profiles.example/v1"

        try {
            db.setSetting(AiProfileStore.PROFILES_KEY, JSONArray().put(JSONObject()
                .put("id", seedId).put("name", "测试初始配置")
                .put("baseUrl", "").put("textModel", "").put("visionModel", "")
                .put("chatPath", AiClient.DEFAULT_CHAT_PATH)
                .put("modelsPath", AiClient.DEFAULT_MODELS_PATH)
                .put("authHeader", AiClient.DEFAULT_AUTH_HEADER)
                .put("authPrefix", AiClient.DEFAULT_AUTH_PREFIX)
                .put("allowPrivateHttp", false)).toString())
            db.setSetting(AiProfileStore.ACTIVE_PROFILE_KEY, seedId)

            val first = SavedAiProfile(
                id = firstId,
                name = "文本服务",
                baseUrl = base,
                textModel = "text-a",
                visionModel = "",
                chatPath = "/chat-a",
                modelsPath = "/models-a"
            )
            val second = SavedAiProfile(
                id = secondId,
                name = "视觉服务",
                baseUrl = base,
                textModel = "text-b",
                visionModel = "vision-b",
                chatPath = "/chat-b",
                modelsPath = "/models-b",
                authHeader = "api-key",
                authPrefix = ""
            )
            AiProfileStore.save(db, first, key = "profile-key-a")
            AiProfileStore.save(db, second, key = "profile-key-b")

            assertEquals("profile-key-a", db.getAiKey(firstId, base))
            assertEquals("profile-key-b", db.getAiKey(secondId, base))
            assertNotEquals(db.getAiKey(firstId, base), db.getAiKey(secondId, base))
            assertEquals(secondId, AiProfileStore.active(db).id)

            AiProfileStore.setActive(db, firstId)
            val config = AiClient.config(db)
            assertEquals("text-a", config.model)
            assertEquals("/chat-a", config.chatPath)
            assertEquals("profile-key-a", config.key)
            assertTrue(AiProfileStore.list(db).any { it.id == secondId && it.visionModel == "vision-b" })
        } finally {
            db.removeAiKey(firstId)
            db.removeAiKey(secondId)
            settingKeys.forEach { key ->
                original[key]?.let { db.setSetting(key, it) } ?: db.deleteSetting(key)
            }
        }
    }
}
