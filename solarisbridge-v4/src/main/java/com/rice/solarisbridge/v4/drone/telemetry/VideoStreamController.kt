package com.rice.solarisbridge.v4.drone.telemetry

import android.content.Context
import com.rice.solarisbridge.common.contracts.BridgeVideoController

class VideoStreamController(
    private val context: Context
) : BridgeVideoController {

    override val isRunning: Boolean = false

    override fun rebuildFromPrefs() = Unit
    override fun start(isPreviewAttached: Boolean) = Unit
    override fun stop() = Unit
    override fun toggle(isPreviewAttached: Boolean) = Unit
}