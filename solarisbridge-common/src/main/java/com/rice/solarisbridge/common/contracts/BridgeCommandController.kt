package com.rice.solarisbridge.common.contracts

interface BridgeCommandController {
    val isRunning: Boolean
    val expectedHz: Int

    fun rebuildFromPrefs()
    fun start()
    fun stop(moveGimbalToNeutral: Boolean)
    fun toggle()
}