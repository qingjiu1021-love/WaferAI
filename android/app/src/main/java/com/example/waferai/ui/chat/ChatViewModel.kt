package com.example.waferai.ui.chat

import android.content.Context
import android.app.Application
import android.net.Uri
import com.example.waferai.data.ai.ChatAttachment
import com.example.waferai.data.ai.ChatArchive
import com.example.waferai.data.ai.ChatConversation
import java.util.UUID
import com.example.waferai.data.ai.ChatMemoryStore
import com.example.waferai.data.ai.AiProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.ai.AiProfileStore
import com.example.waferai.data.ai.ChatPrompt
import com.example.waferai.data.ai.ChatStore
import com.example.waferai.data.ai.ChatThinking
import com.example.waferai.data.ai.ChatTurn
import com.example.waferai.data.ai.DeepSeekClient
import com.example.waferai.data.ai.LocalWaferAssistant
import com.example.waferai.data.ai.WaferKnowledge
import com.example.waferai.data.ai.ChatWebSearch
import com.example.waferai.data.ai.ChatSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChatUiState(
    val archive: ChatArchive = ChatArchive(),
    val draft: String = "",
    val pending: String? = null,
    val loading: Boolean = true,
    val writing: Boolean = false,
    val error: String? = null,
    val attachment: ChatAttachment? = null,
    val memory: String = "",
    val webSearch: Boolean = false,
    val stage: String? = null,
    val startedAt: Long = 0,
    val searchNotice: String? = null
) {
    val turns get() = archive.active.turns
    val busy: Boolean get() = loading || writing || pending != null
}

internal class ChatViewModel(
    context: Context,
    private val store: ChatStore,
    private val profiles: AiProfileStore,
    private val client: DeepSeekClient = DeepSeekClient(),
    private val knowledge: WaferKnowledge? = null,
    private val web: ChatWebSearch = ChatWebSearch(knowledge)
) : AndroidViewModel(context.applicationContext as Application) {
    private val memoryStore = ChatMemoryStore(getApplication())
    private val webPrefs = context.getSharedPreferences("chat_web_search", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ChatUiState())
    val state = mutable.asStateFlow()
    val busy = state.map { it.busy }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private var request: Job? = null

    init {
        viewModelScope.launch {
            try {
                val (archive, memory) = withContext(Dispatchers.IO) { store.readArchive() to memoryStore.read() }
                mutable.value = ChatUiState(archive = archive, memory = memory, loading = false, webSearch = webPrefs.getBoolean("enabled", false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = ChatUiState(loading = false, error = "无法读取本机对话。")
            }
        }
    }

    fun edit(text: String) {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(draft = text.take(2000), error = null)
    }

    fun toggleWebSearch() {
        if (mutable.value.busy) return
        val enabled = !mutable.value.webSearch
        mutable.value = mutable.value.copy(writing = true)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { webPrefs.edit().putBoolean("enabled", enabled).commit() }
            mutable.value = if (saved) mutable.value.copy(webSearch = enabled, writing = false, error = null)
            else mutable.value.copy(writing = false, error = "无法保存联网搜索设置。")
        }
    }

    fun attach(uri: Uri, photo: Boolean, capture: Boolean = false) {
        if (mutable.value.busy) {
            if (capture) discardCapture(uri)
            return
        }
        mutable.value = mutable.value.copy(writing = true, error = null)
        viewModelScope.launch {
            try {
                val item = withContext(Dispatchers.IO) { ChatAttachment.read(getApplication(), uri, photo) }
                mutable.value = mutable.value.copy(attachment = item, writing = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(writing = false, error = error.message ?: "无法读取附件。")
            } finally {
                if (capture) discardCapture(uri)
            }
        }
    }

    fun discardCapture(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
    }

    fun cameraUnavailable() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(error = "无法打开系统相机。")
    }

    fun photoPickerUnavailable() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(error = "无法打开系统相册。")
    }

    fun attachText(text: String) {
        if (mutable.value.busy || text.isBlank()) return
        mutable.value = mutable.value.copy(attachment = ChatAttachment("文本", text.trim().take(8000)), error = null)
    }

    fun removeAttachment() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(attachment = null, error = null)
    }

    fun saveMemory(notes: String) {
        val previous = mutable.value
        if (previous.busy) return
        val updated = notes.trim().take(1800)
        mutable.value = previous.copy(writing = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { memoryStore.write(updated) }
                mutable.value = mutable.value.copy(memory = updated, writing = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = previous.copy(error = "无法保存记忆，请重试。")
            }
        }
    }

    fun send(summary: String?, profileId: String? = null, english: Boolean = false, rememberContext: Boolean = true,
             replyLanguage: String = if (english) "English" else "简体中文", thinking: ChatThinking = ChatThinking.DEFAULT) {
        val previous = mutable.value
        val attachment = previous.attachment
        if (previous.busy || (previous.draft.isBlank() && attachment == null)) return
        val question = previous.draft.trim().ifBlank { if (english) "Explain this attachment." else "请解读这个附件。" }
        val memory = previous.memory.takeIf { rememberContext }.orEmpty()
        mutable.value = previous.copy(pending = question, draft = "", error = null, searchNotice = null, startedAt = System.currentTimeMillis(), stage = "准备请求")
        request = viewModelScope.launch {
            try {
                var contextId: String? = null
                val search = if (previous.webSearch) {
                    mutable.value = mutable.value.copy(stage = "正在搜索网页资料")
                    web.search(question)
                } else ChatSearchResult(emptyList())
                mutable.value = mutable.value.copy(searchNotice = search.notice, stage = if (profileId == null) "正在整理资料" else "等待模型思考与回复")
                val (reply, model) = if (profileId == null) withContext(Dispatchers.Default) {
                    val localHistory = previous.turns.takeLastWhile { it.model == LocalWaferAssistant.Name }
                        .takeIf { rememberContext }.orEmpty()
                    require(attachment == null || attachment.jpeg == null || attachment.text != null) {
                        "这张照片需要支持图片输入的在线模型。规则配色的晶圆图也可以从晶圆页导入。"
                    }
                    val topic = LocalWaferAssistant.topic(question, localHistory)
                    val references = if (topic.isBlank()) emptyList() else knowledge?.search(topic).orEmpty().take(2)
                    val mentionsMemory = listOf("记忆", "记住", "偏好", "memory", "remember").any { it in question.lowercase() }
                    val matchedNotes = memory.lineSequence().filter { note ->
                        note.isNotBlank() && (mentionsMemory || Regex("[a-z0-9-]{3,}|[\\u4e00-\\u9fff]{2,}")
                            .findAll(question.lowercase()).any { it.value in note.lowercase() })
                    }.take(5).toList()
                    val guidance = buildString {
                        if (!mentionsMemory || matchedNotes.isEmpty()) append(LocalWaferAssistant.answer(question, localHistory, summary, english))
                        if (matchedNotes.isNotEmpty()) append(if (english) "\n\nSaved memory\n" else "\n\n已保存的记忆\n")
                        if (matchedNotes.isNotEmpty()) append(matchedNotes.joinToString("\n"))
                        attachment?.text?.let {
                            append(if (english) "\n\nAttachment preview\n" else "\n\n附件内容预览\n")
                            append(it.take(900))
                        }
                    }.trim()
                    val reply = if (references.isEmpty()) guidance else guidance +
                        (if (english) "\n\nFrom the offline knowledge base\n" else "\n\n本地知识库\n") +
                        references.joinToString("\n\n") { "${it.title(english)}\n${it.body(english)}\n${it.source}" }
                    val sourceNote = if (search.sources.isEmpty()) "" else if (english) {
                        "\n\nRelated web summaries are available next to the model name."
                    } else "\n\n已找到相关网页，点击模型名称旁的箭头查看摘要。"
                    (reply + sourceNote) to LocalWaferAssistant.Name
                } else {
                    val profile = withContext(Dispatchers.IO) { profiles.request(profileId) }
                    contextId = "$profileId:${profile.profile.endpoint}"
                    require(attachment?.jpeg == null || profile.profile.provider != AiProvider.DEEPSEEK) {
                        "当前 DeepSeek 连接使用文本接口，请切换到支持图片输入的模型。"
                    }
                    val history = previous.turns.takeLastWhile { it.profileId == contextId }
                        .takeIf { rememberContext }.orEmpty()
                    val messages = withContext(Dispatchers.Default) {
                        ChatPrompt.messages(history, question, summary, memory, attachment, replyLanguage,
                            if (previous.webSearch) ChatWebSearch.context(search) else "")
                    }
                    client.chat(profile, messages, thinking) to
                        "${profile.profile.provider.title} · ${profile.profile.model}"
                }
                val updated = (previous.turns + ChatTurn(question, reply, model, summary, contextId, attachment?.name, search.sources, search.notice)).takeLast(30)
                mutable.value = mutable.value.copy(writing = true, stage = "正在保存回答")
                val archive = previous.archive.updateActive(updated)
                withContext(Dispatchers.IO) { store.writeArchive(archive) }
                mutable.value = ChatUiState(archive = archive, loading = false, memory = previous.memory, webSearch = previous.webSearch)
            } catch (cancelled: CancellationException) {
                mutable.value = previous.copy(error = "已停止等待回复。")
                throw cancelled
            } catch (error: Exception) {
                mutable.value = previous.copy(error = error.message ?: "对话请求失败，请重试。")
            }
        }
    }

    fun cancel() {
        if (!mutable.value.writing) request?.cancel()
    }

    fun newChat() {
        val previous = mutable.value
        if (previous.busy) return
        val saved = previous.archive.conversations.filter { it.turns.isNotEmpty() }
        if (saved.size >= 30) {
            mutable.value = previous.copy(error = "对话历史已满，请先删除一条旧对话。")
            return
        }
        val empty = ChatConversation(UUID.randomUUID().toString())
        changeConversation(ChatArchive(empty.id, listOf(empty) + saved))
    }

    fun openChat(id: String) {
        val archive = mutable.value.archive
        if (archive.activeId == id || archive.conversations.none { it.id == id }) return
        changeConversation(archive.copy(activeId = id,
            conversations = archive.conversations.filter { it.id == id || it.turns.isNotEmpty() }))
    }

    fun deleteChat(id: String) {
        val archive = mutable.value.archive
        val remaining = archive.conversations.filterNot { it.id == id }
        val next = if (remaining.isEmpty()) ChatArchive()
            else archive.copy(activeId = if (archive.activeId == id) remaining.first().id else archive.activeId,
                conversations = remaining)
        changeConversation(next)
    }

    fun clear() = changeConversation(mutable.value.archive.updateActive(emptyList()))

    private fun changeConversation(next: ChatArchive) {
        val previous = mutable.value
        if (previous.busy) return
        mutable.value = previous.copy(writing = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.writeArchive(next) }
                mutable.value = ChatUiState(archive = next, loading = false, memory = previous.memory, webSearch = previous.webSearch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = previous.copy(error = "无法保存本机对话，请重试。")
            }
        }
    }

    class Factory(private val context: Context, private val store: ChatStore, private val profiles: AiProfileStore,
                  private val knowledge: WaferKnowledge) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(context, store, profiles, knowledge = knowledge) as T
    }
}
