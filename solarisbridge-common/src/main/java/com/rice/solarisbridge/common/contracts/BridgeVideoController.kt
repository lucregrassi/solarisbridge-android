package com.rice.solarisbridge.common.contracts

interface BridgeVideoController {
    val isRunning: Boolean

    fun rebuildFromPrefs()
    fun start(isPreviewAttached: Boolean)
    fun stop()
    fun toggle(isPreviewAttached: Boolean)
}