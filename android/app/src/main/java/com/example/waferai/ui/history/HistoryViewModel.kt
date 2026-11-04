package com.example.waferai.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.remote.WaferRecord
import com.example.waferai.data.remote.LotSummary
import com.example.waferai.data.local.WaferOrder
import com.example.waferai.data.local.WaferCsvQuery
import com.example.waferai.data.local.WaferYieldBand
import com.example.waferai.data.repository.ImportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class HistoryUiState(
    val records: List<WaferRecord> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val lot: LotSummary? = null,
    val query: String = "",
    val order: WaferOrder = WaferOrder.RECENT,
    val onlyBelowTarget: Boolean = false,
    val threshold: Int = 90,
    val yieldBand: WaferYieldBand? = null
)

data class CsvUiState(val busy: Boolean = false, val message: String? = null, val error: Boolean = false)

class HistoryViewModel(private val repository: ImportRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state = mutableState.asStateFlow()
    private val mutableCsv = MutableStateFlow(CsvUiState())
    val csv = mutableCsv.asStateFlow()
    private var pendingCsv: WaferCsvQuery? = null
    private var request: Job? = null
    private var lotId: Int? = null
    private var reviewingYield = false

    fun clearForServerChange() {
        request?.cancel()
        lotId = null
        reviewingYield = false
        mutableState.value = HistoryUiState()
    }

    fun show(lotId: Int?, review: Boolean, threshold: Int, yieldBand: WaferYieldBand? = null) {
        val current = mutableState.value
        val selectionChanged = this.lotId != lotId || reviewingYield != review || current.yieldBand != yieldBand
        mutableState.value = if (selectionChanged) {
            HistoryUiState(
                order = if (review) WaferOrder.LOWEST else WaferOrder.RECENT,
                onlyBelowTarget = review,
                threshold = threshold,
                yieldBand = yieldBand
            )
        } else current.copy(threshold = threshold)
        this.lotId = lotId
        reviewingYield = review
        load(reset = true, keepRecords = !selectionChanged && current.threshold == threshold)
    }

    fun orderBy(order: WaferOrder) {
        if (mutableState.value.order == order) return
        mutableState.value = mutableState.value.copy(order = order)
        load(reset = true)
    }

    fun toggleBelowTarget() {
        mutableState.value = mutableState.value.copy(onlyBelowTarget = !mutableState.value.onlyBelowTarget)
        load(reset = true)
    }

    fun search(query: String) {
        mutableState.value = mutableState.value.copy(query = query.take(120))
        load(reset = true, waitForTyping = true)
    }

    fun refresh() = load(reset = true, keepRecords = true)

    fun prepareCsv(): Boolean {
        val current = mutableState.value
        if (current.loading || current.records.isEmpty() || mutableCsv.value.busy) return false
        pendingCsv = WaferCsvQuery(lotId, current.query, current.order,
            current.threshold.takeIf { current.onlyBelowTarget }, current.yieldBand)
        mutableCsv.value = CsvUiState(busy = true)
        return true
    }

    fun cancelCsv(error: String? = null) {
        pendingCsv = null
        mutableCsv.value = CsvUiState(message = error, error = error != null)
    }

    fun exportCsv(uri: Uri) {
        val query = pendingCsv ?: return cancelCsv("导出请求已失效，请重新选择导出。")
        pendingCsv = null
        viewModelScope.launch {
            try {
                val count = repository.exportCsv(uri, query)
                mutableCsv.value = CsvUiState(message = "已导出 $count 条晶圆记录。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableCsv.value = CsvUiState(message = "CSV 写入失败，请选择其他位置后重试。", error = true)
            }
        }
    }

    fun loadMore() {
        if (mutableState.value.loading || !mutableState.value.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean, waitForTyping: Boolean = false, keepRecords: Boolean = false) {
        request?.cancel()
        val current = mutableState.value
        val previous = if (reset && !keepRecords) current.copy(records = emptyList(), hasMore = true) else current
        val limit = if (keepRecords) maxOf(20, current.records.size) else 20
        val selectedLotId = lotId
        mutableState.value = previous.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                if (waitForTyping) delay(200)
                val lot = if (reset && selectedLotId != null) repository.lotDetail(selectedLotId) else previous.lot
                val page = repository.listWafers(
                    limit = limit + 1, offset = if (reset) 0 else previous.records.size, lotId = selectedLotId, query = previous.query,
                    order = previous.order, belowPercent = previous.threshold.takeIf { previous.onlyBelowTarget },
                    yieldBand = previous.yieldBand
                )
                mutableState.value = previous.copy(
                    records = ((if (reset) emptyList() else previous.records) + page.take(limit)).distinctBy { it.id },
                    loading = false,
                    hasMore = page.size > limit,
                    lot = lot,
                    error = null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = previous.copy(loading = false, error = "无法读取手机历史记录，请检查存储空间后重试。")
            }
        }
    }

    class Factory(private val repository: ImportRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryViewModel::class.java))
            return HistoryViewModel(repository) as T
        }
    }
}
