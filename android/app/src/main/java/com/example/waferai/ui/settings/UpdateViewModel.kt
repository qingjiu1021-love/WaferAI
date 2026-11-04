package com.example.waferai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.account.AppRelease
import com.example.waferai.data.account.GithubUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class UpdateState(val busy: Boolean = false, val release: AppRelease? = null, val error: String? = null)
internal class UpdateViewModel : ViewModel() {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    fun check() {
        if (mutable.value.busy) return
        mutable.value = UpdateState(busy = true)
        viewModelScope.launch {
            try { mutable.value = UpdateState(release = GithubUpdates.latest()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = UpdateState(error = if (error is IllegalStateException || error is IllegalArgumentException) error.message else "连接 GitHub 失败，请检查网络后重试。") }
        }
    }
}
