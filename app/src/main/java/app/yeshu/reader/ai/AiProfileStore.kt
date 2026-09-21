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
    val allowPrivateHttp: Boolean = false,
    /** 上次「从接口读取模型」的结果，按配置保存：切换配置或重进设置页都不必重新联网。 */
    val models: List<String> = emptyList()
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
    private const val MAX_MODELS = 500
    private const val MAX_NAME_CHARS = 40
    private const val MAX_URL_CHARS = 2048
    private const val MAX_MODEL_CHARS = 240
    private const val MAX_HEADER_CHARS = 80

    /** 表单校验失败：消息可以直接展示给用户，不经过 AiClient 的脱敏映射。 */
    class ProfileValidationException(message: String) : IllegalArgumentException(message)

    /**
     * 解析结果缓存。[AiClient.config] 会在每条聊天消息、每次设置页重组时调用 [list]，
     * 每次都重新解析 JSON 并做 Keystore 迁移在热路径上代价过高；这里按原始 JSON 字符串
     * 命中缓存（设置行被改写时字符串变化，缓存自然失效，也不会读到过期数据）。
     */
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedProfiles: List<SavedAiProfile>? = null

    /** 旧明文 Key 的迁移每个进程最多尝试一次，避免每次读配置都触发 Keystore 解密。 */
    @Volatile private var legacyKeyMigrated = false

    @Synchronized
    fun list(db: Db): List<SavedAiProfile> {
        val raw = db.getSetting(PROFILES_KEY).orEmpty()
        val cached = cachedProfiles
        if (cached != null && cachedRaw == raw) return cached

        val stored = parse(raw)
        if (stored.isNotEmpty()) {
            migrateLegacyBoundKey(db, stored)
            return cache(raw, stored)
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
        return cache(db.getSetting(PROFILES_KEY).orEmpty(), listOf(migrated))
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
        // 超长输入在表单层就被拒绝，而不是静默截断成另一个值再存进配置
        lengthError(raw)?.let { throw ProfileValidationException(it) }
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

    /**
     * 复制配置。[copyKey] 默认为假：只复制接口元数据，Key 需要用户明确选择才一并复制，
     * 否则同一个 Key 会静默出现在两个配置里，删掉其中一个时很难判断另一个是否还在用。
     */
    @Synchronized
    fun duplicate(db: Db, source: SavedAiProfile, copyKey: Boolean = false): SavedAiProfile {
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueName(list(db), "${source.name} 副本")
        )
        val key = if (copyKey) db.getAiKey(source.id, source.baseUrl).takeIf { it.isNotBlank() } else null
        return save(db, duplicate, key)
    }

    /**
     * 删除配置并返回新的当前配置。激活项取原位置相邻的幸存配置（前一个优先，其次后一个），
     * 而不是无条件回到列表第一项；删除的不是当前配置时，当前配置保持不变。
     */
    @Synchronized
    fun delete(db: Db, id: String): SavedAiProfile {
        val current = list(db)
        val index = current.indexOfFirst { it.id == id }
        val remaining = current.filterNot { it.id == id }.toMutableList()
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
        val activeId = db.getSetting(ACTIVE_PROFILE_KEY).orEmpty()
        val active = when {
            activeId != id && remaining.any { it.id == activeId } -> remaining.first { it.id == activeId }
            index > 0 -> remaining[index - 1]
            else -> remaining.first()
        }
        db.setSetting(ACTIVE_PROFILE_KEY, active.id)
        mirrorLegacyRows(db, active)
        return active
    }

    /** 只更新某个配置的「已发现模型」列表，不触碰其他字段与激活状态。 */
    @Synchronized
    fun saveModels(db: Db, id: String, models: List<String>): SavedAiProfile? {
        val current = list(db).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val cleaned = models.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "null" }
            .distinct()
            .take(MAX_MODELS)
            .toList()
        current[index] = current[index].copy(models = cleaned)
        writeList(db, current)
        return current[index]
    }

    /**
     * 表单长度校验：返回可直接展示的中文提示，由设置页拒绝保存。
     * [sanitize] 里的 take() 只是读取历史数据时的最后一道保险，正常输入不会走到那里被静默截断。
     */
    fun lengthError(value: SavedAiProfile): String? = when {
        value.name.trim().length > MAX_NAME_CHARS -> "配置名称最多 $MAX_NAME_CHARS 个字符"
        value.baseUrl.trim().length > MAX_URL_CHARS -> "接口地址最多 $MAX_URL_CHARS 个字符"
        value.textModel.trim().length > MAX_MODEL_CHARS -> "文本模型名最多 $MAX_MODEL_CHARS 个字符"
        value.visionModel.trim().length > MAX_MODEL_CHARS -> "视觉模型名最多 $MAX_MODEL_CHARS 个字符"
        value.chatPath.trim().length > MAX_URL_CHARS -> "Chat 接口路径最多 $MAX_URL_CHARS 个字符"
        value.modelsPath.trim().length > MAX_URL_CHARS -> "模型列表路径最多 $MAX_URL_CHARS 个字符"
        value.authHeader.trim().length > MAX_HEADER_CHARS -> "鉴权 Header 最多 $MAX_HEADER_CHARS 个字符"
        value.authPrefix.trim().length > MAX_HEADER_CHARS -> "Key 前缀最多 $MAX_HEADER_CHARS 个字符"
        else -> null
    }

    private fun cache(raw: String, profiles: List<SavedAiProfile>): List<SavedAiProfile> {
        cachedRaw = raw
        cachedProfiles = profiles
        return profiles
    }

    private fun sanitize(value: SavedAiProfile): SavedAiProfile = value.copy(
        id = value.id.ifBlank { UUID.randomUUID().toString() },
        name = value.name.trim().take(MAX_NAME_CHARS).ifBlank { "未命名配置" },
        baseUrl = value.baseUrl.trim().take(MAX_URL_CHARS),
        textModel = value.textModel.trim().take(MAX_MODEL_CHARS),
        visionModel = value.visionModel.trim().take(MAX_MODEL_CHARS),
        chatPath = value.chatPath.trim().take(MAX_URL_CHARS),
        modelsPath = value.modelsPath.trim().take(MAX_URL_CHARS),
        authHeader = value.authHeader.trim().take(MAX_HEADER_CHARS).ifBlank { AiClient.DEFAULT_AUTH_HEADER },
        authPrefix = value.authPrefix.trim().take(MAX_HEADER_CHARS),
        models = value.models.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "null" }
            .distinct()
            .take(MAX_MODELS)
            .toList()
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
        // 写库即失效缓存：下一次 list 会按新的 JSON 重新解析
        cachedProfiles = null
        cachedRaw = null
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
                    .put("allowPrivateHttp", profile.allowPrivateHttp)
                    .put("models", JSONArray(profile.models)))
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
                    allowPrivateHttp = item.optBoolean("allowPrivateHttp", false),
                    models = jsonModels(item.optJSONArray("models"))
                )))
            }
        }.take(MAX_PROFILES)
    }.getOrDefault(emptyList())

    /** 读取历史配置里保存的模型列表；元素不是字符串或为空时直接跳过。 */
    private fun jsonModels(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let(::add)
            }
        }.distinct().take(MAX_MODELS)
    }

    /**
     * Retries migration of the old provider-bound slot on every profile read until the encrypted
     * profile copy can be read back successfully. This keeps a process interruption or transient
     * Keystore failure from stranding the user's existing Key. Origin-less legacy Keys still need
     * the explicit confirmation flow in Settings and are never migrated here.
     *
     * 每个进程最多执行一次：这段迁移会对每个配置做一次 Keystore 解密，而 [list] 位于
     * 「每条聊天消息 / 每次设置重组」的热路径上。迁移本身失败时不置位，下次读取仍会重试。
     */
    private fun migrateLegacyBoundKey(db: Db, profiles: List<SavedAiProfile>) {
        if (legacyKeyMigrated) return
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
        legacyKeyMigrated = true
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
