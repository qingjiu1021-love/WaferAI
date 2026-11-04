package com.example.waferai.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.waferai.data.repository.HealthRepository
import com.example.waferai.data.repository.serviceErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Checking : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

class ConnectionViewModel(private val repository: HealthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun markVerified() {
        request?.cancel()
        mutableState.value = ConnectionState.Connected
    }

    fun checkConnection() {
        if (mutableState.value == ConnectionState.Checking) return
        mutableState.value = ConnectionState.Checking
        request = viewModelScope.launch {
            try {
                repository.checkConnection()
                mutableState.value = ConnectionState.Connected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = ConnectionState.Failed(serviceErrorMessage(error))
            }
        }
    }

    class Factory(private val repository: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConnectionViewModel::class.java))
            return ConnectionViewModel(repository) as T
        }
    }
}
