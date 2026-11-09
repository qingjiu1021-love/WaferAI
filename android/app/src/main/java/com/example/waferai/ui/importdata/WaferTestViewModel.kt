package com.example.waferai.ui.importdata

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.local.DemoWafer
import com.example.waferai.data.local.WaferTestHistory
import com.example.waferai.data.local.WaferTestHistoryEntry
import com.example.waferai.data.local.WaferTestPlan
import com.example.waferai.data.remote.WaferDetail
import com.example.waferai.data.remote.WaferPrediction
import com.example.waferai.data.report.ReportSnapshot
import com.example.waferai.data.repository.ImportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

internal data class WaferTestUiState(
    val plan: WaferTestPlan? = null,
    val completed: Int = 0,
    val passed: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val savingResult: Boolean = false,
    val openingRecord: Boolean = false,
    val completedAt: Instant? = null,
    val recorded: Boolean = false,
    val prediction: WaferPrediction? = null,
    val exporting: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val busy get() = running || openingRecord || exporting
}

internal data class WaferTestHistoryUiState(
    val entries: List<WaferTestHistoryEntry>? = null,
    val error: String? = null
)

internal class WaferTestViewModel(
    private val repository: ImportRepository,
    private val testHistory: WaferTestHistory = repository.testHistory
) : ViewModel() {
    private val mutableState = MutableStateFlow(WaferTestUiState())
    val state = mutableState.asStateFlow()
    private val mutableHistory = MutableStateFlow(WaferTestHistoryUiState())
    val history = mutableHistory.asStateFlow()
    private var runJob: Job? = null
    private var pendingReport: ReportSnapshot? = null

    fun simulate(limit: Double, comparisonLimit: Double) = start {
        withContext(Dispatchers.Default) { WaferTestPlan.simulated(limit, comparisonLimit) }
    }

    fun replay(sample: DemoWafer) = start {
        val wafer = repository.readDemo(sample).detail
        withContext(Dispatchers.Default) { WaferTestPlan.replay(wafer, sample) }
    }

    fun replay(wafer: WaferDetail) = start {
        withContext(Dispatchers.Default) { WaferTestPlan.replay(wafer) }
    }

    private fun start(prepare: suspend () -> WaferTestPlan) {
        if (mutableState.value.busy) return
        mutableState.value = WaferTestUiState(running = true)
        runJob = viewModelScope.launch {
            try {
                val plan = prepare()
                mutableState.value = mutableState.value.copy(plan = plan)
                val total = plan.dieIndices.size
                val stride = (total + 31) / 32
                var passed = 0
                for (start in 0 until total step stride) {
                    delay(110)
                    mutableState.first { !it.paused }
                    val end = minOf(start + stride, total)
                    passed += withContext(Dispatchers.Default) {
                        (start until end).count { plan.statusAt(plan.dieIndices[it]) == 1 }
                    }
                    mutableState.first { !it.paused }
                    mutableState.value = mutableState.value.copy(completed = end, passed = passed)
                }
                finish(plan)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val current = mutableState.value
                mutableState.value = current.copy(running = false, paused = false, savingResult = false,
                    error = error.message ?: "无法运行晶圆测试，请重试。")
            }
        }
    }

    private suspend fun finish(plan: WaferTestPlan) {
        val completedAt = Instant.now()
        mutableState.value = mutableState.value.copy(finished = true, completedAt = completedAt)
        var issue: String? = null
        val prediction = try {
            withContext(Dispatchers.Default) { repository.predictor.predict(plan.wafer) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            issue = "AI 检测失败，仍可查看晶圆图和导出测试报告。"
            null
        }
        mutableState.value = mutableState.value.copy(prediction = prediction, savingResult = true)
        val saved = try {
            withContext(Dispatchers.IO) { testHistory.save(plan, prediction, completedAt) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            issue = listOfNotNull(issue, "测试历史保存失败，当前结果仍可导出 PDF。").joinToString(" ")
            false
        }
        mutableState.value = mutableState.value.copy(running = false, savingResult = false, recorded = saved, error = issue)
    }

    fun togglePause() {
        val current = mutableState.value
        if (current.running && !current.finished && current.plan != null) {
            mutableState.value = current.copy(paused = !current.paused)
        }
    }

    fun cancel() {
        if (!mutableState.value.running || mutableState.value.finished) return
        runJob?.cancel()
        mutableState.value = WaferTestUiState(message = "已取消测试，未完成的结果不会保存。")
    }

    fun loadHistory() {
        viewModelScope.launch {
            mutableHistory.value = WaferTestHistoryUiState()
            try {
                mutableHistory.value = WaferTestHistoryUiState(withContext(Dispatchers.IO) { testHistory.list() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableHistory.value = WaferTestHistoryUiState(error = "无法读取测试历史，请稍后重试。")
            }
        }
    }

    fun openRecord(id: Long) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(openingRecord = true)
        viewModelScope.launch {
            try {
                val (saved, completedAt) = withContext(Dispatchers.IO) { testHistory.open(id) }
                val plan = withContext(Dispatchers.Default) { WaferTestPlan.restore(saved.plan) }
                mutableState.value = WaferTestUiState(
                    plan = plan, completed = plan.wafer.totalCount, passed = plan.wafer.goodCount,
                    finished = true, completedAt = completedAt, recorded = true, prediction = saved.prediction
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(openingRecord = false, error = "无法打开这条测试记录。")
            }
        }
    }

    fun prepareReport(): String? {
        val current = mutableState.value
        val plan = current.plan ?: return null
        if (current.busy || !current.finished) return null
        val sourceLabel = if (plan.mode == WaferTestPlan.SIMULATION) "仿真数据" else plan.mode
        val report = ReportSnapshot(plan.wafer, current.prediction, testSource = "$sourceLabel · ${plan.source}",
            testCompletedAt = current.completedAt, testComparison = plan.comparison)
        pendingReport = report
        mutableState.value = current.copy(exporting = true, message = null, error = null)
        return report.suggestedFilename()
    }

    fun reset() {
        if (!mutableState.value.busy) {
            mutableState.value = WaferTestUiState()
        }
    }

    fun export(uri: Uri?) {
        val report = pendingReport
        pendingReport = null
        if (uri == null || report == null) {
            mutableState.value = mutableState.value.copy(exporting = false)
            return
        }
        viewModelScope.launch {
            try {
                repository.exportPdf(uri, report)
                mutableState.value = mutableState.value.copy(exporting = false, message = "测试 PDF 报告已保存。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(exporting = false, error = "报告保存失败，请重试。")
            }
        }
    }

    class Factory(private val repository: ImportRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WaferTestViewModel::class.java))
            return WaferTestViewModel(repository) as T
        }
    }
}
