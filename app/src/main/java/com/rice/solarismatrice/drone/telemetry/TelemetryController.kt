package com.rice.solarismatrice.drone.telemetry

import android.content.Context
import android.util.Log
import com.rice.solarismatrice.data.network.TelemetryStreamer
import com.rice.solarismatrice.data.prefs.AppPrefs
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class TelemetryController(
    private val context: Context,
    private val tag: String = "TelemetryController"
) {
    private var telemetryStreamer: TelemetryStreamer? = null
    private var telemetryTimer: Timer? = null
    var isRunning: Boolean = false
        private set

    fun rebuildFromPrefs() {
        val ip = AppPrefs.getPcIp(context) ?: return
        val telemetryTxPort = AppPrefs.getTelemetryTxPort(context)

        if (isRunning) {
            stop()
        }

        telemetryStreamer = TelemetryStreamer(ip, telemetryTxPort)
    }

    fun start() {
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

    fun stop() {
        if (!isRunning) return

        telemetryTimer?.cancel()
        telemetryTimer = null

        telemetryStreamer?.stop()
        TelemetryProvider.stopIfNeeded()

        isRunning = false
        Log.i(tag, "Telemetry stream STOPPED")
    }

    fun toggle() {
        if (isRunning) stop() else start()
    }
}