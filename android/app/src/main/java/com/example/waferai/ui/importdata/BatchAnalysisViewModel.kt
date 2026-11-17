package com.example.waferai.ui.importdata

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.report.AnalysisStage
import com.example.waferai.data.report.LocalAnalysisService
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal enum class BatchStatus(val label: String) {
    QUEUED("等待处理"), RUNNING("正在处理"), DONE("已完成"), FAILED("处理失败"), CANCELLED("已取消")
}

internal data class BatchItem(
    val id: String = UUID.randomUUID().toString(), val uri: String, val name: String,
    val threshold: Int, val status: BatchStatus = BatchStatus.QUEUED,
    val stage: AnalysisStage? = null, val waferId: Int? = null,
    val report: String? = null, val error: String? = null
)

internal data class BatchState(
    val items: List<BatchItem> = emptyList(), val loading: Boolean = true,
    val running: Boolean = false, val message: String? = null,
    val exporting: Boolean = false
) {
    val completed get() = items.count { it.status == BatchStatus.DONE }
    val failed get() = items.count { it.status == BatchStatus.FAILED || it.status == BatchStatus.CANCELLED }
    val fraction get() = if (items.isEmpty()) 0f else items.sumOf {
        when (it.status) {
            BatchStatus.QUEUED -> 0.0
            BatchStatus.RUNNING -> (it.stage?.fraction ?: 0f).toDouble()
            else -> 1.0
        }
    }.toFloat() / items.size
}

/** One worker, bounded queue, and durable checkpoints; no Activity is retained. */
internal class BatchAnalysisViewModel(private val service: LocalAnalysisService) : ViewModel() {
    private val mutable = MutableStateFlow(BatchState())
    val state = mutable.asStateFlow()
    private var worker: Job? = null
    private var adding = false
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = service.queueFile.readFully()
                    require(bytes.size <= 256 * 1024)
                    Gson().fromJson(bytes.toString(Charsets.UTF_8), Array<BatchItem>::class.java).take(100).map {
                        require(it.id.isNotBlank() && it.uri.isNotBlank() && it.name.isNotBlank() && it.threshold in 1..100)
                        requireNotNull(it.status)
                        if (it.status == BatchStatus.RUNNING || it.status == BatchStatus.QUEUED)
                            it.copy(status = BatchStatus.CANCELLED, stage = null, error = "应用已关闭，可点击重试继续。") else it
                    }
                }.getOrDefault(emptyList())
            }
            mutable.value = BatchState(items = items, loading = false)
        }
    }

    fun add(uris: List<Uri>, threshold: Int) {
        if (mutable.value.loading || adding || uris.isEmpty()) return
        adding = true
        viewModelScope.launch {
            try {
                val available = (100 - mutable.value.items.size).coerceAtLeast(0)
                val inputs = uris.distinct().take(available)
                val items = inputs.map { uri ->
                    service.retainPermission(uri)
                    BatchItem(uri = uri.toString(), name = service.filename(uri), threshold = threshold)
                }
                mutable.update { it.copy(items = it.items + items, message = if (inputs.size < uris.distinct().size) "队列最多保留 100 项，请先清除已完成记录。" else null) }
                persist()
                start()
            } finally { adding = false }
        }
    }

    private fun change(id: String, transform: (BatchItem) -> BatchItem) {
        mutable.update { state -> state.copy(items = state.items.map { if (it.id == id) transform(it) else it }) }
    }

    fun start() {
        if (mutable.value.loading || worker?.isActive == true) return
        worker = viewModelScope.launch {
            mutable.update { it.copy(running = true) }
            var active: String? = null
            try {
                while (true) {
                    val item = mutable.value.items.firstOrNull { it.status == BatchStatus.QUEUED } ?: break
                    active = item.id
                    change(item.id) { it.copy(status = BatchStatus.RUNNING, stage = AnalysisStage.READING, error = null) }
                    persist()
                    try {
                        val result = service.analyzeFile(Uri.parse(item.uri), item.threshold) { stage ->
                            change(item.id) { if (it.status == BatchStatus.RUNNING) it.copy(stage = stage) else it }
                        }
                        change(item.id) { it.copy(status = BatchStatus.DONE, stage = null,
                            waferId = result.wafer.id, report = result.report.path, error = null) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        change(item.id) { it.copy(status = BatchStatus.FAILED, stage = null,
                            error = error.message ?: "无法处理文件，请检查格式与读取权限。") }
                    }
                    persist()
                    active = null
                }
            } catch (cancelled: CancellationException) {
                active?.let { id -> change(id) { it.copy(status = BatchStatus.CANCELLED, stage = null, error = "已取消，已保存的晶圆和检测结果仍保留。") } }
                throw cancelled
            } finally {
                mutable.update { it.copy(running = false) }
                withContext(NonCancellable) { persist() }
                worker = null
                if (mutable.value.items.any { it.status == BatchStatus.QUEUED }) start()
            }
        }
    }

    fun cancel() {
        mutable.update { state -> state.copy(items = state.items.map {
            if (it.status == BatchStatus.QUEUED) it.copy(status = BatchStatus.CANCELLED, error = "已取消，可重试。") else it
        }) }
        worker?.cancel()
        if (worker == null) viewModelScope.launch { persist() }
    }

    fun retry() {
        if (mutable.value.running || mutable.value.loading) return
        mutable.update { state -> state.copy(items = state.items.map {
            if (it.status == BatchStatus.FAILED || it.status == BatchStatus.CANCELLED)
                it.copy(status = BatchStatus.QUEUED, stage = null, error = null) else it
        }, message = null) }
        start()
    }

    fun clearFinished() {
        if (mutable.value.running) return
        mutable.update { it.copy(items = emptyList(), message = null) }
        viewModelScope.launch { persist() }
    }

    fun export(id: String, uri: Uri) {
        val item = mutable.value.items.firstOrNull { it.id == id } ?: return
        val path = item.report ?: return
        if (mutable.value.exporting) return
        mutable.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            try { service.export(path, uri); mutable.update { it.copy(message = "PDF 报告已保存到所选位置。") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "导出失败，请重新选择保存位置。") } }
            finally { mutable.update { it.copy(exporting = false) } }
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) { saveMutex.withLock {
        try {
            val bytes = Gson().toJson(mutable.value.items).toByteArray(Charsets.UTF_8)
            val file = service.queueFile
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "无法保存任务记录，请检查存储空间。") } }
    } }

    class Factory(private val service: LocalAnalysisService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BatchAnalysisViewModel::class.java))
            return BatchAnalysisViewModel(service) as T
        }
    }
}
