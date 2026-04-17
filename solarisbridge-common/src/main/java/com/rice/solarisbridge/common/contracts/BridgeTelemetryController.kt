package com.rice.solarisbridge.common.contracts

interface BridgeTelemetryController {
    val isRunning: Boolean
    fun rebuildFromPrefs()
    fun start()
    fun stop()
    fun toggle()
}