package com.example.waferai.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.repository.ImportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val restoreRevision: Int = 0
)

internal class BackupViewModel(private val repository: ImportRepository) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()

    fun begin() { mutable.value = mutable.value.copy(busy = true, message = null, error = false) }

    fun cancel(message: String? = null) {
        mutable.value = mutable.value.copy(busy = false, message = message, error = message != null)
    }

    fun run(uri: Uri, restore: Boolean) {
        begin()
        viewModelScope.launch {
            try {
                val count = if (restore) repository.restoreBackup(uri) else repository.exportBackup(uri)
                mutable.value = mutable.value.copy(
                    busy = false, message = if (restore) "已恢复 $count 张晶圆。" else "已备份 $count 张晶圆。",
                    restoreRevision = mutable.value.restoreRevision + if (restore) 1 else 0
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cancel(if (restore) "恢复失败，原有数据已保留。请检查备份文件。" else "备份失败，请检查空间或另选保存位置。")
            }
        }
    }

    class Factory(private val repository: ImportRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(repository) as T
    }
}
