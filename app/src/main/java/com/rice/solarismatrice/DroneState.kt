package com.rice.solarismatrice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DroneState {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    fun setConnected(value: Boolean) {
        _connected.value = value
    }
}