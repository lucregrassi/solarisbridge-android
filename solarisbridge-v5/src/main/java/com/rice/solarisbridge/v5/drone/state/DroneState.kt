package com.rice.solarisbridge.v5.drone.state

import kotlinx.coroutines.flow.MutableStateFlow

object DroneState {
    private val _connected = MutableStateFlow(false)

    fun setConnected(value: Boolean) {
        _connected.value = value
    }
}