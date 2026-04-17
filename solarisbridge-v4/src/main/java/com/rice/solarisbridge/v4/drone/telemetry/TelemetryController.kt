package com.rice.solarisbridge.v4.drone.telemetry

import android.content.Context
import com.rice.solarisbridge.common.contracts.BridgeTelemetryController

class TelemetryController(
    private val context: Context
) : BridgeTelemetryController {

    override var isRunning: Boolean = false
        private set

    override fun rebuildFromPrefs() = Unit

    override fun start() {
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    override fun toggle() {
        if (isRunning) stop() else start()
    }
}