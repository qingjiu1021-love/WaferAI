package com.example.waferai.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject

data class AiDraft(
    val id: String? = null,
    val provider: AiProvider = AiProvider.DEEPSEEK,
    val name: String = "",
    val address: String = provider.baseUrl,
    val model: String = provider.initialModel,
    val key: String = "",
    val providerId: String = "",
    val protocol: AiProtocol = provider.protocol,
    val models: List<AiModelEntry> = if (model.isBlank()) emptyList() else listOf(AiModelEntry(model))
) {
    val custom get() = provider == AiProvider.CUSTOM
    val selectedModel get() = model.takeIf { id -> models.any { it.id == id } } ?: models.firstOrNull()?.id.orEmpty()
    internal fun connection() = AiConnection(id, provider, name, address, key, providerId, protocol)
}

data class AiCenterState(
    val library: AiProfilesState,
    val draft: AiDraft? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val availableModels: List<String> = emptyList()
)

class AiCenterViewModel(private val store: AiProfileStore) : ViewModel() {
    private val client = DeepSeekClient()
    private val mutableState = MutableStateFlow(AiCenterState(store.read()))
    val state = mutableState.asStateFlow()

    fun open(profile: AiProfile? = null) {
        val previous = mutableState.value
        if (previous.saving) return
        val draft = profile?.let {
            val models = previous.library.profiles.filter { other ->
                other.id == it.id || (it.providerId != null && other.providerId == it.providerId)
            }.map { entry ->
                val legacyName = entry.name.takeIf { entry.provider != AiProvider.CUSTOM &&
                    entry.providerId == null && it != entry.provider.title }
                AiModelEntry(entry.model, entry.modelDisplayName ?: legacyName.orEmpty())
            }
            AiDraft(it.id, it.provider, it.name, it.baseAddress, it.model, providerId =
                if (it.provider == AiProvider.CUSTOM) it.providerId ?: "custom-${it.id.take(8)}" else "",
                protocol = it.protocol, models = models)
        } ?: AiDraft()
        mutableState.value = AiCenterState(previous.library, draft)
    }

    fun edit(draft: AiDraft) {
        if (!mutableState.value.saving) {
            mutableState.value = AiCenterState(mutableState.value.library, draft)
        }
    }

    fun close() {
        if (!mutableState.value.saving) {
            mutableState.value = AiCenterState(mutableState.value.library)
        }
    }

    fun testConnection() = update("测试请求失败，请重试。") { previous ->
        val draft = requireNotNull(previous.draft)
        val request = withContext(Dispatchers.IO) { store.prepare(draft.connection(), draft.selectedModel) }
        client.test(request)
        previous.copy(notice = "${request.profile.model} 已返回测试回复。")
    }

    fun fetchModels() = update("无法获取模型目录，可手动添加模型 ID。") { previous ->
        val draft = requireNotNull(previous.draft)
        val request = withContext(Dispatchers.IO) { store.prepare(draft.connection()) }
        val found = client.listModels(request)
        previous.copy(availableModels = found,
            notice = if (found.isEmpty()) "接口未返回模型，可手动添加模型 ID。" else null)
    }

    fun dismissCatalog() {
        val previous = mutableState.value
        mutableState.value = previous.copy(availableModels = emptyList())
    }

    fun addModels(ids: List<String>, displayName: String = "") {
        val previous = mutableState.value
        val draft = previous.draft ?: return
        if (previous.saving) return
        try {
            val normalized = ids.map { modelId(draft.provider, it) }
            val name = modelDisplayName(displayName).orEmpty()
            val merged = (draft.models + normalized.map { AiModelEntry(it, name) }).distinctBy(AiModelEntry::id)
            require(merged.size <= MaxProviderModels) { "同一个提供商最多可添加 $MaxProviderModels 个模型。" }
            edit(draft.copy(models = merged))
        } catch (error: IllegalArgumentException) {
            mutableState.value = previous.copy(error = error.message, availableModels = emptyList())
        }
    }

    fun importConfig(resolver: ContentResolver, uri: Uri) = update("无法读取配置文件，请检查 JSON 格式。") { previous ->
        val imported = withContext(Dispatchers.IO) {
            val text = requireNotNull(resolver.openInputStream(uri)) { "无法打开配置文件。" }.source().buffer().use {
                require(!it.request(128 * 1024 + 1L)) { "配置文件不能超过 128 KB。" }
                it.readUtf8().removePrefix("\uFEFF")
            }
            parseConfig(text)
        }
        previous.copy(draft = imported.copy(id = previous.draft?.id), notice = "已读取配置，请核对 API 地址并填写密钥。")
    }

    fun save() = update("无法保存 AI 配置。") { previous ->
        val draft = requireNotNull(previous.draft)
        if (draft.custom) require(draft.providerId.isNotBlank()) { "请填写 Provider ID。" }
        val library = withContext(Dispatchers.IO) {
            store.save(draft.connection(), draft.models)
            store.read()
        }
        AiCenterState(library)
    }

    fun select(id: String) = changeLibrary { store.select(id) }
    fun renameModel(id: String, name: String) = changeLibrary { store.renameModel(id, name) }

    fun restoreDefaultSelection() {
        val previous = mutableState.value
        check(!previous.saving) { "AI 配置正在保存，请稍后重试。" }
        mutableState.value = previous.copy(library = store.restoreDefaultSelection(), error = null)
    }

    fun remove(id: String) = changeLibrary { store.remove(id) }

    private fun changeLibrary(action: () -> Unit) = update("无法更新 AI 配置。") { previous ->
        previous.copy(library = withContext(Dispatchers.IO) { action(); store.read() })
    }

    private fun update(fallback: String, action: suspend (AiCenterState) -> AiCenterState) {
        val previous = mutableState.value
        if (previous.saving) return
        val ready = previous.copy(error = null, notice = null, availableModels = emptyList())
        mutableState.value = ready.copy(saving = true)
        viewModelScope.launch {
            try {
                mutableState.value = action(ready)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = if (error is org.json.JSONException) fallback else error.message ?: fallback
                mutableState.value = ready.copy(error = message)
            }
        }
    }

    class Factory(private val store: AiProfileStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AiCenterViewModel(store) as T
    }
}

internal fun parseConfig(input: String): AiDraft {
    val json = JSONObject(input)
    val protocol = when (json.optString("protocol", "openai").trim().lowercase()) {
        "openai", "openai chat completions", "chat_completions" -> AiProtocol.OPENAI
        "responses", "openai responses" -> AiProtocol.RESPONSES
        "anthropic", "anthropic messages", "messages" -> AiProtocol.ANTHROPIC
        else -> throw IllegalArgumentException("protocol 应填写 openai、responses 或 anthropic。")
    }
    val address = json.getString("baseUrl")
    completionEndpoint(address, protocol)
    val rows = json.optJSONArray("models") ?: JSONArray()
    require(rows.length() <= MaxProviderModels) { "配置文件最多可包含 $MaxProviderModels 个模型。" }
    val models = List(rows.length()) { index ->
        val item = rows.get(index)
        val value = if (item is JSONObject) item.getString("id") else item as? String
        AiModelEntry(
            modelId(AiProvider.CUSTOM, requireNotNull(value) { "models 须填写模型 ID。" }),
            modelDisplayName((item as? JSONObject)?.optString("displayName").orEmpty()).orEmpty()
        )
    }.distinctBy(AiModelEntry::id)
    return AiDraft(provider = AiProvider.CUSTOM, name = json.optString("displayName"), address = address,
        providerId = json.getString("providerId"), protocol = protocol, models = models)
}
