package com.example.waferai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.remote.ServerConnection
import com.example.waferai.data.repository.serviceErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerSettingsState(
    val activeAddress: String,
    val draft: String,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class ServerSettingsViewModel(private val server: ServerConnection) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerSettingsState(server.address, server.address))
    val state = mutableState.asStateFlow()

    fun open() {
        if (!mutableState.value.saving) mutableState.value = ServerSettingsState(server.address, server.address)
    }

    fun edit(value: String) {
        if (!mutableState.value.saving) mutableState.value = mutableState.value.copy(draft = value, message = null, error = null)
    }

    fun save(onVerified: (Boolean) -> Unit) {
        val current = mutableState.value
        if (current.saving) return
        mutableState.value = current.copy(saving = true, message = null, error = null)
        viewModelScope.launch {
            try {
                val changed = server.checkAndSave(current.draft)
                onVerified(changed)
                mutableState.value = ServerSettingsState(
                    server.address, server.address,
                    message = if (changed) "连接成功，地址已保存。请从历史记录重新打开晶圆。" else "连接正常，已保留当前地址。"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = current.copy(saving = false, message = null, error = serviceErrorMessage(error))
            }
        }
    }

    class Factory(private val server: ServerConnection) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ServerSettingsViewModel::class.java))
            return ServerSettingsViewModel(server) as T
        }
    }
}
