package com.rice.solarisbridge.v5.drone.control

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class CommandWatchdog(
    private val tag: String = "CommandWatchdog",
    private val tickMs: Long = 50L,
    private val timeoutMs: Long = 200L,
    private val onFailSafe: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    @Volatile
    var lastCmdRxMs: Long = 0L

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return

            val now = SystemClock.elapsedRealtime()
            val cmdAge = now - lastCmdRxMs

            if (lastCmdRxMs != 0L && cmdAge > timeoutMs) {
                Log.w(tag, "CMD TIMEOUT: age=${cmdAge}ms -> failsafe")
                onFailSafe()
                lastCmdRxMs = 0L
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
        lastCmdRxMs = 0L
    }
}