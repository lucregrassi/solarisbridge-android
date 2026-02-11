package com.rice.solarismatrice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DroneState {
    private val _connected = MutableStateFlow(false)

    fun setConnected(value: Boolean) {
        _connected.value = value
    }
}