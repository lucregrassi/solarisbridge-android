package com.rice.solarisbridge.v4.drone.control

import android.content.Context
import com.rice.solarisbridge.common.contracts.BridgeCommandController

class CommandSystemController(
    private val context: Context,
    private val gimbalController: GimbalController,
    private val onStatusLine: (String) -> Unit
) : BridgeCommandController {

    override val isRunning: Boolean = false
    override val expectedHz: Int = 20

    override fun rebuildFromPrefs() = Unit
    override fun start() = Unit
    override fun stop(moveGimbalToNeutral: Boolean) = Unit
    override fun toggle() = Unit
}