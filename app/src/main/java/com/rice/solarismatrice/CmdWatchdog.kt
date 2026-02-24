package com.rice.solarismatrice

import android.os.Handler
import android.os.Looper
import android.util.Log

class CmdWatchdog(
    private val tag: String = "CmdWatchdog",
    private val tickMs: Long = 50L,          // 20 Hz check
    private val timeoutMs: Long = 200L,      // soglia failsafe
    private val onDroneFailSafe: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    @Volatile var lastDroneRxMs: Long = 0L

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return

            val now = android.os.SystemClock.elapsedRealtime()

            val droneAge = now - lastDroneRxMs

            if (lastDroneRxMs != 0L && droneAge > timeoutMs) {
                Log.w(tag, "DRONE TIMEOUT: age=${droneAge}ms -> failsafe")
                onDroneFailSafe()
                // opzionale: azzera per non richiamarlo ogni tick
                lastDroneRxMs = 0L
            }

            handler.postDelayed(this, tickMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(runnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
        lastDroneRxMs = 0L
    }
}