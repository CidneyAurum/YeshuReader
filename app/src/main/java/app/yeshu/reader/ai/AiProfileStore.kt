package app.yeshu.reader.ai

import app.yeshu.reader.AiClient
import app.yeshu.reader.Db
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One independently selectable BYOK endpoint. API keys stay outside this model in Keystore. */
data class SavedAiProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val textModel: String,
    val visionModel: String,
    val chatPath: String = AiClient.DEFAULT_CHAT_PATH,
    val modelsPath: String = AiClient.DEFAULT_MODELS_PATH,
    val authHeader: String = AiClient.DEFAULT_AUTH_HEADER,
    val authPrefix: String = AiClient.DEFAULT_AUTH_PREFIX,
    val allowPrivateHttp: Boolean = false
)

/**
 * Stores multiple non-secret provider profiles in Room settings and one encrypted key per profile.
 * The legacy single-profile rows are mirrored for older screens/backups during the migration.
 */
object AiProfileStore {
    const val PROFILES_KEY = "ai_profiles_json"
    const val ACTIVE_PROFILE_KEY = "ai_active_profile_id"
    private const val DEFAULT_PROFILE_ID = "default"
    private const val MAX_PROFILES = 24

    @Synchronized
    fun list(db: Db): List<SavedAiProfile> {
        val stored = parse(db.getSetting(PROFILES_KEY).orEmpty())
        if (stored.isNotEmpty()) {
            migrateLegacyBoundKey(db, stored)
            return stored
        }

        val migrated = SavedAiProfile(
            id = DEFAULT_PROFILE_ID,
            name = "默认配置",
            baseUrl = db.getSetting("ai_base_url").orEmpty(),
            textModel = db.getSetting("ai_model").orEmpty(),
            visionModel = db.getSetting("ai_vision_model").orEmpty(),
            chatPath = db.getSetting("ai_chat_path") ?: AiClient.DEFAULT_CHAT_PATH,
            modelsPath = db.getSetting("ai_models_path") ?: AiClient.DEFAULT_MODELS_PATH,
            authHeader = db.getSetting("ai_auth_header") ?: AiClient.DEFAULT_AUTH_HEADER,
            authPrefix = db.getSetting("ai_auth_prefix") ?: AiClient.DEFAULT_AUTH_PREFIX,
            allowPrivateHttp = db.getSetting("ai_allow_private_http") == "1"
        )
        writeList(db, listOf(migrated))
        db.setSetting(ACTIVE_PROFILE_KEY, migrated.id)

        migrateLegacyBoundKey(db, listOf(migrated))
        mirrorLegacyRows(db, migrated)
        return listOf(migrated)
    }

    @Synchronized
    fun active(db: Db): SavedAiProfile {
        val profiles = list(db)
        val activeId = db.getSetting(ACTIVE_PROFILE_KEY).orEmpty()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first().also {
            db.setSetting(ACTIVE_PROFILE_KEY, it.id)
            mirrorLegacyRows(db, it)
        }
    }

    @Synchronized
    fun setActive(db: Db, id: String): SavedAiProfile? {
        val profile = list(db).firstOrNull { it.id == id } ?: return null
        db.setSetting(ACTIVE_PROFILE_KEY, profile.id)
        mirrorLegacyRows(db, profile)
        return profile
    }

    @Synchronized
    fun save(db: Db, raw: SavedAiProfile, key: String? = null, activate: Boolean = true): SavedAiProfile {
        val profile = sanitize(raw)
        val current = list(db).toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile
        else {
            require(current.size < MAX_PROFILES) { "最多保存 $MAX_PROFILES 个 AI 配置" }
            current += profile
        }
        writeList(db, current)
        if (key != null && key.isNotBlank()) db.setAiKey(profile.id, key, profile.baseUrl)
        if (activate) {
            db.setSetting(ACTIVE_PROFILE_KEY, profile.id)
            mirrorLegacyRows(db, profile)
        }
        return profile
    }

    @Synchronized
    fun create(db: Db, name: String = "新配置"): SavedAiProfile {
        val profile = SavedAiProfile(
            id = UUID.randomUUID().toString(),
            name = uniqueName(list(db), name),
            baseUrl = "",
            textModel = "",
            visionModel = ""
        )
        return save(db, profile)
    }

    @Synchronized
    fun duplicate(db: Db, source: SavedAiProfile): SavedAiProfile {
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueName(list(db), "${source.name} 副本")
        )
        val key = db.getAiKey(source.id, source.baseUrl).takeIf { it.isNotBlank() }
        return save(db, duplicate, key)
    }

    @Synchronized
    fun delete(db: Db, id: String): SavedAiProfile {
        val remaining = list(db).filterNot { it.id == id }.toMutableList()
        db.removeAiKey(id)
        if (remaining.isEmpty()) {
            remaining += SavedAiProfile(
                id = UUID.randomUUID().toString(),
                name = "默认配置",
                baseUrl = "",
                textModel = "",
                visionModel = ""
            )
        }
        writeList(db, remaining)
        val active = remaining.first()
        db.setSetting(ACTIVE_PROFILE_KEY, active.id)
        mirrorLegacyRows(db, active)
        return active
    }

    private fun sanitize(value: SavedAiProfile): SavedAiProfile = value.copy(
        id = value.id.ifBlank { UUID.randomUUID().toString() },
        name = value.name.trim().take(40).ifBlank { "未命名配置" },
        baseUrl = value.baseUrl.trim().take(2048),
        textModel = value.textModel.trim().take(240),
        visionModel = value.visionModel.trim().take(240),
        chatPath = value.chatPath.trim().take(2048),
        modelsPath = value.modelsPath.trim().take(2048),
        authHeader = value.authHeader.trim().take(80).ifBlank { AiClient.DEFAULT_AUTH_HEADER },
        authPrefix = value.authPrefix.trim().take(80)
    )

    private fun uniqueName(profiles: List<SavedAiProfile>, desired: String): String {
        val base = desired.trim().ifBlank { "新配置" }.take(34)
        val existing = profiles.mapTo(hashSetOf()) { it.name }
        if (base !in existing) return base
        var suffix = 2
        while ("$base $suffix" in existing) suffix++
        return "$base $suffix"
    }

    private fun writeList(db: Db, profiles: List<SavedAiProfile>) {
        db.setSetting(PROFILES_KEY, JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("textModel", profile.textModel)
                    .put("visionModel", profile.visionModel)
                    .put("chatPath", profile.chatPath)
                    .put("modelsPath", profile.modelsPath)
                    .put("authHeader", profile.authHeader)
                    .put("authPrefix", profile.authPrefix)
                    .put("allowPrivateHttp", profile.allowPrivateHttp))
            }
        }.toString())
    }

    private fun parse(raw: String): List<SavedAiProfile> = runCatching {
        val array = JSONArray(raw)
        buildList<SavedAiProfile> {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank() || any { it.id == id }) continue
                add(sanitize(SavedAiProfile(
                    id = id,
                    name = item.optString("name", "未命名配置"),
                    baseUrl = item.optString("baseUrl"),
                    textModel = item.optString("textModel"),
                    visionModel = item.optString("visionModel"),
                    chatPath = item.optString("chatPath", AiClient.DEFAULT_CHAT_PATH),
                    modelsPath = item.optString("modelsPath", AiClient.DEFAULT_MODELS_PATH),
                    authHeader = item.optString("authHeader", AiClient.DEFAULT_AUTH_HEADER),
                    authPrefix = item.optString("authPrefix", AiClient.DEFAULT_AUTH_PREFIX),
                    allowPrivateHttp = item.optBoolean("allowPrivateHttp", false)
                )))
            }
        }.take(MAX_PROFILES)
    }.getOrDefault(emptyList())

    /**
     * Retries migration of the old provider-bound slot on every profile read until the encrypted
     * profile copy can be read back successfully. This keeps a process interruption or transient
     * Keystore failure from stranding the user's existing Key. Origin-less legacy Keys still need
     * the explicit confirmation flow in Settings and are never migrated here.
     */
    private fun migrateLegacyBoundKey(db: Db, profiles: List<SavedAiProfile>) {
        profiles.asSequence().filter { it.baseUrl.isNotBlank() }.forEach { profile ->
            val oldKey = db.getAiKey(profile.baseUrl)
            if (oldKey.isBlank()) return@forEach
            if (!db.hasAiKey(profile.id)) {
                runCatching {
                    db.setAiKey(profile.id, oldKey, profile.baseUrl)
                }
            }
            if (db.getAiKey(profile.id, profile.baseUrl) == oldKey) {
                db.setAiKey("", profile.baseUrl)
                return
            }
        }
    }

    private fun mirrorLegacyRows(db: Db, profile: SavedAiProfile) {
        db.setSetting("ai_base_url", profile.baseUrl)
        db.setSetting("ai_model", profile.textModel)
        db.setSetting("ai_vision_model", profile.visionModel)
        db.setSetting("ai_chat_path", profile.chatPath)
        db.setSetting("ai_models_path", profile.modelsPath)
        db.setSetting("ai_auth_header", profile.authHeader)
        db.setSetting("ai_auth_prefix", profile.authPrefix)
        db.setSetting("ai_allow_private_http", if (profile.allowPrivateHttp) "1" else "0")
    }
}
