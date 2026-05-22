package com.marcopolo.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PermissionsState {
    PENDING,
    GRANTED,
    DENIED
}

class PermissionsViewModel : ViewModel() {

    private val _state = MutableStateFlow(PermissionsState.PENDING)
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    fun onResult(granted: Boolean) {
        _state.value = if (granted) PermissionsState.GRANTED else PermissionsState.DENIED
    }

    fun isGranted(): Boolean = _state.value == PermissionsState.GRANTED
}
