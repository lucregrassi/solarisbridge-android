package com.rice.solarisbridge.v5.drone.telemetry

import android.content.Context
import android.util.Log
import com.rice.solarisbridge.common.contracts.BridgeTelemetryController
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.common.streaming.TelemetryStreamer
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class TelemetryController(
    private val context: Context,
    private val tag: String = "TelemetryController"
) : BridgeTelemetryController {

    private var telemetryStreamer: TelemetryStreamer? = null
    private var telemetryTimer: Timer? = null

    override var isRunning: Boolean = false
        private set

    override fun rebuildFromPrefs() {
        val ip = AppPrefs.getPcIp(context) ?: return
        val telemetryTxPort = AppPrefs.getTelemetryTxPort(context)

        if (isRunning) {
            stop()
        }

        telemetryStreamer = TelemetryStreamer(ip, telemetryTxPort)
    }

    override fun start() {
        if (isRunning) return

        TelemetryProvider.startIfNeeded()
        telemetryStreamer?.start()

        telemetryTimer = fixedRateTimer(
            name = "telemetry",
            daemon = true,
            initialDelay = 0L,
            period = 100L
        ) {
            val snap = TelemetryProvider.getSnapshot()
            telemetryStreamer?.sendCurrentSnapshot(snap)
        }

        isRunning = true
        Log.i(tag, "Telemetry stream STARTED")
    }

    override fun stop() {
        if (!isRunning) return

        telemetryTimer?.cancel()
        telemetryTimer = null

        telemetryStreamer?.stop()
        TelemetryProvider.stopIfNeeded()

        isRunning = false
        Log.i(tag, "Telemetry stream STOPPED")
    }

    override fun toggle() {
        if (isRunning) stop() else start()
    }
}