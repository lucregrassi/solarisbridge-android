package com.rice.solarisbridge.v4.drone.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rice.solarisbridge.common.commands.control.CommandWatchdog
import com.rice.solarisbridge.common.commands.model.DroneCmd
import com.rice.solarisbridge.common.commands.parser.CommandParsers
import com.rice.solarisbridge.common.network.UdpJsonReceiver
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.app.BridgeAppV4
import dji.common.error.DJIError
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft

class CommandSystemController(
    private val context: Context,
    private val gimbalController: GimbalController,
    private val onStatusLine: (String) -> Unit,
    private val onRunningChanged: (Boolean) -> Unit = {},
    private val tag: String = "CommandSystemControllerV4"
){

    private var flightCmdReceiver: UdpJsonReceiver? = null
    private var gimbalCmdReceiver: UdpJsonReceiver? = null

    @Volatile
    private var lastFlightCmd: DroneCmd? = null

    @Volatile
    private var virtualStickEnabled = false

    private val sendHandler = Handler(Looper.getMainLooper())
    private var sendLoopRunning = false
    private val sendPeriodMs = 50L

    private var flightCmdRxPort = 7000
    private var gimbalCmdRxPort = 7001

    var isRunning: Boolean = false
        private set

    val expectedHz: Int = 20

    private val flightCommandWatchdog = CommandWatchdog(
        tag = "FlightCmdWatchdogV4",
        tickMs = 50L,
        timeoutMs = 250L
    ) {
        Log.w(tag, "Flight command watchdog timeout -> sending ZERO")
        lastFlightCmd = zeroFlightCmd()
        sendVirtualStickNow(lastFlightCmd!!)
    }

    private val sendRunnable = object : Runnable {
        override fun run() {
            if (!sendLoopRunning) return
            val cmd = lastFlightCmd ?: zeroFlightCmd()
            sendVirtualStickNow(cmd)
            sendHandler.postDelayed(this, sendPeriodMs)
        }
    }

    fun rebuildFromPrefs() {
        val wasRunning = isRunning
        if (wasRunning) {
            stop(moveGimbalToNeutral = false)
        }

        flightCmdReceiver?.stop()
        gimbalCmdReceiver?.stop()

        loadPortsFromPrefs()
        buildReceivers()

        if (wasRunning) {
            start()
        }
    }

    fun start() {
        if (isRunning) return

        val fc = flightController() ?: run {
            Log.w(tag, "FlightController null")
            onStatusLine("FlightController NULL")
            return
        }

        try {
            fc.rollPitchControlMode = RollPitchControlMode.VELOCITY
            fc.yawControlMode = YawControlMode.ANGULAR_VELOCITY
            fc.verticalControlMode = VerticalControlMode.VELOCITY
            fc.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        } catch (t: Throwable) {
            Log.e(tag, "Failed to configure virtual stick modes", t)
        }

        fc.setVirtualStickModeEnabled(true, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    virtualStickEnabled = false
                    isRunning = false
                    onRunningChanged(false)
                    Log.e(tag, "setVirtualStickModeEnabled(true) failed: ${error.description}")
                    onStatusLine("VS enable FAIL: ${error.description}")
                    return
                }

                Log.i(tag, "Virtual Stick ENABLED")
                virtualStickEnabled = true

                try {
                    fc.setVirtualStickAdvancedModeEnabled(true)
                } catch (t: Throwable) {
                    Log.w(tag, "setVirtualStickAdvancedModeEnabled failed", t)
                }

                lastFlightCmd = zeroFlightCmd()
                sendVirtualStickNow(lastFlightCmd!!)

                flightCommandWatchdog.lastCmdRxMs = SystemClock.elapsedRealtime()
                flightCommandWatchdog.start()
                startSendLoop()

                isRunning = true
                onRunningChanged(true)

                flightCmdReceiver?.start()
                gimbalCmdReceiver?.start()

                onStatusLine("CMD system STARTED")
            }
        })
    }

    fun stop(moveGimbalToNeutral: Boolean) {
        if (!isRunning) return

        val fc = flightController()

        isRunning = false
        onRunningChanged(false)

        stopSendLoop()
        flightCommandWatchdog.stop()

        flightCmdReceiver?.stop()
        gimbalCmdReceiver?.stop()

        lastFlightCmd = zeroFlightCmd()
        sendVirtualStickNow(lastFlightCmd!!)

        virtualStickEnabled = false

        fc?.setVirtualStickModeEnabled(false, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    Log.w(tag, "setVirtualStickModeEnabled(false) failed: ${error.description}")
                } else {
                    Log.i(tag, "Virtual Stick DISABLED")
                }
            }
        })

        if (moveGimbalToNeutral) {
            gimbalController.moveToNeutral()
        }

        onStatusLine("CMD system STOPPED")
    }

    fun toggle() {
        if (isRunning) stop(moveGimbalToNeutral = true) else start()
    }

    private fun loadPortsFromPrefs() {
        flightCmdRxPort = AppPrefs.getFlightCmdRxPort(context)
        gimbalCmdRxPort = AppPrefs.getGimbalCmdRxPort(context)
    }

    private fun buildReceivers() {
        flightCmdReceiver = UdpJsonReceiver(
            port = flightCmdRxPort,
            tag = "UDP-FLIGHT-CMD-V4"
        ) { json, from ->
            val cmd = CommandParsers.parseDrone(json)
            if (cmd != null) {
                lastFlightCmd = cmd
                flightCommandWatchdog.lastCmdRxMs = SystemClock.elapsedRealtime()
                Log.i(tag, "FLIGHT CMD RX $from -> $cmd")
                onStatusLine("FLIGHT: $cmd")
            } else {
                Log.w(tag, "FLIGHT CMD parse FAIL $from -> $json")
                onStatusLine("FLIGHT parse FAIL: $json")
            }
        }

        gimbalCmdReceiver = UdpJsonReceiver(
            port = gimbalCmdRxPort,
            tag = "UDP-GIMBAL-CMD-V4"
        ) { json, from ->
            val cmd = CommandParsers.parseGimbal(json)
            if (cmd != null) {
                Log.i(tag, "GIMBAL CMD RX $from -> $cmd")
                onStatusLine("GIMBAL: $cmd")

                if (isRunning) {
                    gimbalController.applyCmd(cmd)
                }
            } else {
                Log.w(tag, "GIMBAL CMD parse FAIL $from -> $json")
                onStatusLine("GIMBAL parse FAIL: $json")
            }
        }
    }

    private fun startSendLoop() {
        if (sendLoopRunning) return
        sendLoopRunning = true
        sendHandler.post(sendRunnable)
    }

    private fun stopSendLoop() {
        sendLoopRunning = false
        sendHandler.removeCallbacks(sendRunnable)
    }

    private fun sendVirtualStickNow(cmd: DroneCmd) {
        if (!virtualStickEnabled) return

        val fc = flightController() ?: return
        val data = FlightControlData(
            clamp(cmd.vy, -23f, 23f),          // pitch
            clamp(cmd.vx, -23f, 23f),          // roll
            clamp(cmd.yaw, -100f, 100f),       // yaw rate
            clamp(cmd.throttle, -6f, 6f)       // vertical velocity
        )

        try {
            fc.sendVirtualStickFlightControlData(data, object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    if (error != null) {
                        Log.w(tag, "sendVirtualStickFlightControlData failed: ${error.description}")
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(tag, "sendVirtualStickFlightControlData exception", t)
        }
    }

    private fun flightController(): FlightController? {
        val product = BridgeAppV4.getProductInstance() as? Aircraft ?: return null
        return product.flightController
    }

    private fun clamp(v: Float, min: Float, max: Float): Float {
        return v.coerceIn(min, max)
    }

    private fun zeroFlightCmd() = DroneCmd(
        vx = 0f,
        vy = 0f,
        yaw = 0f,
        throttle = 0f
    )
}