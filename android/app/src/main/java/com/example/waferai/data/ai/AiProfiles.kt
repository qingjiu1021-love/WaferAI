package com.example.waferai.data.ai

import android.content.Context
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

enum class AiProvider(val title: String, val baseUrl: String, val initialModel: String = "",
    val protocol: AiProtocol = AiProtocol.OPENAI) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    DOUBAO("豆包 · 火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
    KIMI("Kimi", "https://api.moonshot.cn/v1"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash"),
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic · Claude", "https://api.anthropic.com/v1", protocol = AiProtocol.ANTHROPIC),
    BEDROCK("Amazon Bedrock", "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
    SILICONFLOW("硅基流动 · SiliconFlow", "https://api.siliconflow.cn/v1"),
    STEPFUN("阶跃星辰 · StepFun", "https://api.stepfun.com/v1"),
    MINIMAX("MiniMax", "https://api.minimaxi.com/v1"),
    BAIDU("百度千帆 · ERNIE", "https://qianfan.baidubce.com/v2"),
    TENCENT("腾讯云 TokenHub · 混元", "https://tokenhub.tencentmaas.com/v1"),
    XIAOMI("小米 MiMo", "https://api.xiaomimimo.com/v1"),
    MODELSCOPE("魔搭社区 · ModelScope", "https://api-inference.modelscope.cn/v1"),
    XAI("xAI · Grok", "https://api.x.ai/v1"),
    MISTRAL("Mistral AI", "https://api.mistral.ai/v1"),
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    TOGETHER("Together AI", "https://api.together.xyz/v1"),
    FIREWORKS("Fireworks AI", "https://api.fireworks.ai/inference/v1"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1"),
    DEEPINFRA("DeepInfra", "https://api.deepinfra.com/v1/openai"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai"),
    HUGGINGFACE("Hugging Face", "https://router.huggingface.co/v1"),
    NVIDIA("NVIDIA NIM", "https://integrate.api.nvidia.com/v1"),
    SAMBANOVA("SambaNova", "https://api.sambanova.ai/v1"),
    COHERE("Cohere", "https://api.cohere.ai/compatibility/v1"),
    CUSTOM("自定义兼容接口", "")
}

enum class AiProtocol(val title: String, val suffix: String) {
    OPENAI("OpenAI Chat Completions", "/chat/completions"),
    RESPONSES("OpenAI Responses", "/responses"),
    ANTHROPIC("Anthropic Messages", "/messages")
}

internal const val MaxProviderModels = 30
private val ProviderIdPattern = Regex("[a-z][a-z0-9-]{0,39}")

data class AiModelEntry(val id: String, val displayName: String = "") {
    val label get() = displayName.ifBlank { id }
}

data class AiProfile(
    val id: String,
    val name: String,
    val provider: AiProvider,
    val endpoint: String,
    val model: String,
    val providerId: String? = null,
    private val savedProtocol: AiProtocol? = null,
    val modelDisplayName: String? = null
) {
    val protocol get() = savedProtocol ?: provider.protocol
    val selectionName get() = modelDisplayName ?: if (provider == AiProvider.CUSTOM || providerId != null) model else name
    val selectionDetail get() = "${if (providerId != null || provider == AiProvider.CUSTOM) name else provider.title} · $model"
    val baseAddress get() = endpoint.removeSuffix(protocol.suffix)
}

data class AiProfilesState(
    val profiles: List<AiProfile> = emptyList(),
    val activeId: String? = null
) {
    val active: AiProfile? get() = profiles.find { it.id == activeId }
}

internal class AiRequest(val profile: AiProfile, val key: String)

internal class AiConnection(
    val existingId: String?,
    val provider: AiProvider,
    val name: String,
    val address: String,
    val key: String,
    val providerId: String = "",
    val protocol: AiProtocol = AiProtocol.OPENAI
)

class AiProfileStore(
    context: Context,
    name: String = "ai_profiles",
    legacyName: String = "deepseek_credentials"
) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val vault = AiKeyVault("$legacyName.aes")
    private val gson = Gson()

    init {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val encrypted = legacy.getString("encrypted_key", null)
        val iv = legacy.getString("iv", null)
        if (!preferences.contains("profiles") && encrypted != null && iv != null) {
            val id = UUID.randomUUID().toString()
            val provider = AiProvider.DEEPSEEK
            val model = legacy.getString("model", null) ?: provider.initialModel
            val profile = AiProfile(id, provider.title, provider, completionEndpoint(provider.baseUrl), model)
            val migrated = preferences.edit()
                .putString("profiles", gson.toJson(listOf(profile)))
                .putString("active", id)
                .putString("key_$id", encrypted)
                .putString("iv_$id", iv)
                .commit()
            if (migrated) legacy.edit().remove("encrypted_key").remove("iv").apply()
        }
    }

    @Synchronized
    fun read(): AiProfilesState {
        val profiles = gson.fromJson(preferences.getString("profiles", "[]"), Array<AiProfile>::class.java)
            ?.toList().orEmpty()
        val storedId = preferences.getString("active", null)
        val activeId = profiles.find { it.id == storedId }?.id ?: profiles.firstOrNull()?.id
        return AiProfilesState(profiles, activeId)
    }

    fun save(id: String?, provider: AiProvider, name: String, address: String, model: String, key: String) =
        save(AiConnection(id, provider, name, address, key), listOf(AiModelEntry(model)))

    @Synchronized
    internal fun save(connection: AiConnection, models: List<AiModelEntry>) {
        val state = read()
        val existing = state.profiles.find { it.id == connection.existingId }
        val group = state.profiles.filter { it.id == existing?.id ||
            (existing?.providerId != null && it.providerId == existing.providerId) }
        val custom = connection.provider == AiProvider.CUSTOM
        val normalizedModels = models.map {
            AiModelEntry(modelId(connection.provider, it.id), modelDisplayName(it.displayName).orEmpty())
        }.distinctBy(AiModelEntry::id)
        require(normalizedModels.isNotEmpty()) { "请至少添加一个模型 ID。" }
        require(normalizedModels.size <= MaxProviderModels) { "已达到模型目录数量上限。" }
        val request = prepare(connection, normalizedModels.first().id)
        val providerId = if (custom) connection.providerId.trim().ifBlank {
            // Legacy callers and installations originally stored one custom model per profile.
            existing?.providerId ?: "custom-${request.profile.id.take(8)}"
        } else existing?.providerId ?: "preset:${request.profile.id}"
        require(state.profiles.none { it.providerId == providerId && it !in group }) {
            "此 Provider ID 已存在，请在管理模型中编辑现有提供商。"
        }
        val replacements = normalizedModels.map { model ->
            val original = group.find { it.model == model.id } ?: existing.takeIf { group.size == 1 && normalizedModels.size == 1 }
            request.profile.copy(id = original?.id ?: UUID.randomUUID().toString(), model = model.id,
                providerId = providerId, modelDisplayName = model.displayName.takeIf(String::isNotEmpty))
        }
        val index = state.profiles.indexOfFirst { it in group }
        val updated = (state.profiles - group.toSet()).toMutableList().apply {
            addAll(if (index < 0) size else index, replacements)
        }
        val active = state.activeId.takeIf { id -> updated.any { it.id == id } } ?: replacements.first().id
        val editor = preferences.edit().putString("profiles", gson.toJson(updated))
            .putString("active", active)
        val (encrypted, iv) = vault.encrypt(request.key)
        replacements.forEach { profile ->
            editor.putString("key_${profile.id}", encrypted).putString("iv_${profile.id}", iv)
        }
        group.filter { original -> replacements.none { it.id == original.id } }.forEach {
            editor.remove("key_${it.id}").remove("iv_${it.id}")
        }
        check(editor.commit()) { "无法保存 AI 配置。" }
    }

    @Synchronized
    internal fun prepare(connection: AiConnection, model: String? = null): AiRequest {
        val existing = read().profiles.find { it.id == connection.existingId }
        require(connection.existingId == null || existing != null) { "找不到这条 AI 配置。" }
        val custom = connection.provider == AiProvider.CUSTOM
        val protocol = if (custom) connection.protocol else connection.provider.protocol
        val endpoint = completionEndpoint(connection.address.ifBlank { connection.provider.baseUrl }, protocol)
        val providerId = connection.providerId.trim()
        require(!custom || providerId.isEmpty() || ProviderIdPattern.matches(providerId)) {
            "Provider ID 须以小写字母开头，可包含小写字母、数字和连字符，最多 40 个字符。"
        }
        val name = connection.name.trim().ifBlank { connection.provider.title }
        require(name.length <= 60 && name.none { it.isISOControl() }) { "显示名称最多 60 个字符。" }
        val profile = AiProfile(existing?.id ?: UUID.randomUUID().toString(), name, connection.provider,
            endpoint, model?.let { modelId(connection.provider, it) }.orEmpty(), providerId.ifBlank { null }, protocol)
        val credential = parseAiKey(connection.key).ifEmpty {
            require(existing?.endpoint == profile.endpoint && existing.provider == profile.provider &&
                existing.protocol == profile.protocol) {
                "请填写这个接口的 API 密钥。"
            }
            storedKey(existing.id)
        }
        return AiRequest(profile, credential)
    }

    @Synchronized
    fun select(id: String) {
        require(read().profiles.any { it.id == id }) { "找不到这条 AI 配置。" }
        check(preferences.edit().putString("active", id).commit()) { "无法切换 AI 配置。" }
    }

    @Synchronized
    internal fun renameModel(id: String, name: String) {
        val profiles = read().profiles
        require(profiles.any { it.id == id }) { "找不到此模型。" }
        val displayName = modelDisplayName(name)
        val updated = profiles.map { if (it.id == id) it.copy(modelDisplayName = displayName) else it }
        check(preferences.edit().putString("profiles", gson.toJson(updated)).commit()) { "无法保存模型名称。" }
    }

    @Synchronized
    fun restoreDefaultSelection(): AiProfilesState {
        val state = read()
        val defaultId = state.profiles.firstOrNull()?.id
        if (defaultId != null && state.activeId != defaultId) select(defaultId)
        return state.copy(activeId = defaultId)
    }

    @Synchronized
    fun remove(id: String) {
        val state = read()
        val remaining = state.profiles.filterNot { it.id == id }
        val active = if (state.activeId == id) remaining.firstOrNull()?.id else state.activeId
        check(
            preferences.edit().putString("profiles", gson.toJson(remaining)).putString("active", active)
                .remove("key_$id").remove("iv_$id").commit()
        ) { "无法移除 AI 配置。" }
    }

    @Synchronized
    internal fun request(profileId: String? = null): AiRequest {
        val library = read()
        val profile = checkNotNull(if (profileId == null) library.active else library.profiles.find { it.id == profileId }) {
            "请先添加并选择模型。"
        }
        return AiRequest(profile, storedKey(profile.id))
    }

    private fun storedKey(id: String): String {
        val encrypted = checkNotNull(preferences.getString("key_$id", null)) { "请重新填写 API 密钥。" }
        val iv = checkNotNull(preferences.getString("iv_$id", null)) { "请重新填写 API 密钥。" }
        return vault.decrypt(encrypted, iv)
    }
}

internal fun parseAiKey(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    val value = if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed.substringAfter(' ').trim() else trimmed
    val key = value.removeSurrounding("\"").removeSurrounding("'")
    require(key.length in 8..1024 && key.all { it.code in 33..126 }) { "请填写完整的 API 密钥。" }
    return key
}

internal fun modelId(provider: AiProvider, input: String): String {
    val value = if (provider == AiProvider.GEMINI) input.trim().removePrefix("models/") else input.trim()
    require(value.length in 1..160 && value.none { it.isWhitespace() || it.isISOControl() }) {
        "请填写平台提供的模型 ID。"
    }
    return value
}

internal fun modelDisplayName(input: String): String? {
    val value = input.trim()
    require(value.length <= 60 && value.none(Char::isISOControl)) { "模型名称最多 60 个字符，且不能换行。" }
    return value.takeIf(String::isNotEmpty)
}

internal fun completionEndpoint(input: String, protocol: AiProtocol = AiProtocol.OPENAI): String {
    val url = requireNotNull(input.trim().toHttpUrlOrNull()) { "请输入完整的 HTTPS 接口地址。" }
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "接口地址须使用 HTTPS，且不包含账号、查询参数或锚点。"
    }
    val path = url.encodedPath.trimEnd('/')
    require(AiProtocol.entries.none { it != protocol && path.endsWith(it.suffix) }) {
        "API 地址与所选协议不匹配，请填写对应的接口地址。"
    }
    val endpoint = when {
        path.endsWith(protocol.suffix) -> path
        protocol == AiProtocol.ANTHROPIC && !path.endsWith("/v1") -> "$path/v1/messages"
        else -> path + protocol.suffix
    }
    return url.newBuilder().encodedPath(endpoint).build().toString()
}

internal fun modelCatalogUrl(profile: AiProfile) = profile.baseAddress.toHttpUrl().newBuilder()
    .addPathSegment("models").apply {
        if (profile.protocol == AiProtocol.ANTHROPIC) addQueryParameter("limit", "200")
    }.build()
