package com.example.waferai.data.ai

import android.content.Context
import com.example.waferai.ui.theme.KnowledgeIntervals
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

internal data class KnowledgeTask(
    val id: Int, val name: String, val query: String, val hours: Int, val enabled: Boolean = true,
    val lastRun: Long = 0, val lastFailed: Boolean = false,
    val profileId: String? = null, val endpoint: String? = null, val model: String? = null,
    val reply: String? = null, val lastError: String? = null
) {
    fun matches(profile: AiProfile?) = profile != null && profile.id == profileId &&
        profile.endpoint == endpoint && profile.model == model
}

internal class KnowledgeTaskStore(context: Context, name: String = "knowledge_tasks") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mutable = MutableStateFlow(read())
    val tasks = mutable.asStateFlow()
    private val mutableRunning = MutableStateFlow(emptySet<Int>())
    val running = mutableRunning.asStateFlow()

    private fun read(): List<KnowledgeTask> = prefs.getString("tasks", null)?.let { json ->
        runCatching { gson.fromJson(json, Array<KnowledgeTask>::class.java).toList() }.getOrDefault(emptyList())
    }.orEmpty()

    @Synchronized
    fun add(name: String, query: String, hours: Int, profile: AiProfile): KnowledgeTask {
        require(name.isNotBlank() && query.isNotBlank() && hours in KnowledgeIntervals)
        require(mutable.value.size < 12) { "最多可创建 12 个定时任务。" }
        val id = prefs.getInt("next_id", 7200)
        val task = KnowledgeTask(id, name.trim().take(48), query.trim().take(2000), hours,
            profileId = profile.id, endpoint = profile.endpoint, model = profile.model)
        persist(mutable.value + task, id + 1)
        return task
    }

    @Synchronized
    fun toggle(id: Int, enabled: Boolean): KnowledgeTask? {
        val task = mutable.value.firstOrNull { it.id == id }?.copy(enabled = enabled) ?: return null
        persist(mutable.value.map { if (it.id == id) task else it })
        return task
    }

    @Synchronized
    fun remove(id: Int) = persist(mutable.value.filterNot { it.id == id })

    @Synchronized
    private fun record(id: Int, reply: String?, error: String?) {
        if (mutable.value.none { it.id == id }) return
        persist(mutable.value.map { if (it.id == id) it.copy(lastRun = System.currentTimeMillis(),
            reply = reply ?: it.reply, lastError = error, lastFailed = error != null) else it })
    }

    suspend fun execute(id: Int, profiles: AiProfileStore, client: DeepSeekClient = DeepSeekClient(), scheduled: Boolean = false) {
        val task = synchronized(this) {
            val current = mutable.value.find { it.id == id } ?: return
            if (id in mutableRunning.value || (scheduled && (!current.enabled || current.profileId == null))) return
            mutableRunning.value = mutableRunning.value + id
            current
        }
        try {
            val profileId = requireNotNull(task.profileId) { "旧知识搜索任务，请新建 AI 定时任务。" }
            val request = profiles.request(profileId)
            check(task.matches(request.profile)) { "所绑定模型的接口或模型 ID 已变更，请重新创建任务。" }
            val messages = JSONArray().put(chatMessage("system", "按用户提供的定时任务指令生成可阅读的结果。缺少实时数据时说明所需信息，勿编造。"))
                .put(chatMessage("user", task.query))
            record(id, readableReply(client.chat(request, messages)), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            record(id, null, error.message?.take(400) ?: "模型调用失败，请稍后重试。")
        } finally {
            synchronized(this) { mutableRunning.value = mutableRunning.value - id }
        }
    }

    private fun persist(tasks: List<KnowledgeTask>, nextId: Int? = null) {
        val edit = prefs.edit().putString("tasks", gson.toJson(tasks))
        nextId?.let { edit.putInt("next_id", it) }
        check(edit.commit()) { "无法保存定时任务。" }
        mutable.value = tasks
    }
}
