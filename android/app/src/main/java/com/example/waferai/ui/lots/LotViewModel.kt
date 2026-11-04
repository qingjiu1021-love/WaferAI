package com.example.waferai.ui.lots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.remote.LotSummary
import com.example.waferai.data.repository.ImportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LotUiState(
    val records: List<LotSummary> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

class LotViewModel(private val repository: ImportRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LotUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun clearForServerChange() {
        request?.cancel()
        mutableState.value = LotUiState()
    }

    fun refresh() = load(true)

    fun loadMore() {
        if (mutableState.value.loading || !mutableState.value.hasMore) return
        load(false)
    }

    private fun load(reset: Boolean) {
        request?.cancel()
        val previous = if (reset) LotUiState() else mutableState.value
        mutableState.value = previous.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val page = repository.listLots(20, previous.records.size)
                mutableState.value = LotUiState(records = (previous.records + page).distinctBy { it.id }, hasMore = page.size == 20)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = previous.copy(loading = false, error = "无法读取手机批次汇总，请检查存储空间后重试。")
            }
        }
    }

    class Factory(private val repository: ImportRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LotViewModel::class.java))
            return LotViewModel(repository) as T
        }
    }
}
