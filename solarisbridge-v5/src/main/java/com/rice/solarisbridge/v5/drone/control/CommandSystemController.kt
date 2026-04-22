package com.rice.solarisbridge.v5.drone.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rice.solarisbridge.common.commands.control.CommandWatchdog
import com.rice.solarisbridge.common.commands.model.DroneCmd
import com.rice.solarisbridge.common.commands.parser.CommandParsers
import com.rice.solarisbridge.common.contracts.BridgeCommandController
import com.rice.solarisbridge.common.network.UdpJsonReceiver
import com.rice.solarisbridge.common.prefs.AppPrefs
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager

class CommandSystemController(
    private val context: Context,
    private val gimbalController: GimbalController,
    private val onStatusLine: (String) -> Unit,
    private val onRunningChanged: (Boolean) -> Unit,
    private val tag: String = "CommandSystemController"
) : BridgeCommandController {

    private var flightCmdReceiver: UdpJsonReceiver? = null
    private var gimbalCmdReceiver: UdpJsonReceiver? = null

    @Volatile
    private var lastFlightCmd: DroneCmd? = null

    @Volatile
    private var virtualStickEnabled = false

    private val vsManager by lazy { VirtualStickManager.getInstance() }

    private val sendHandler = Handler(Looper.getMainLooper())
    private var sendLoopRunning = false
    private val sendPeriodMs = 50L

    private var flightCmdRxPort = 7000
    private var gimbalCmdRxPort = 7001

    override var isRunning: Boolean = false
        private set

    override val expectedHz: Int = 20

    private val flightCommandWatchdog = CommandWatchdog(
        tag = "FlightCmdWatchdog",
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

    override fun rebuildFromPrefs() {
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

    override fun start() {
        if (isRunning) return

        vsManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(tag, "Virtual Stick ENABLED")
                virtualStickEnabled = true

                vsManager.setVirtualStickAdvancedModeEnabled(true)

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

            override fun onFailure(error: IDJIError) {
                virtualStickEnabled = false
                isRunning = false
                onRunningChanged(false)
                Log.e(tag, "enableVirtualStick failed: ${error.description()}")
                onStatusLine("VS enable FAIL: ${error.description()}")
            }
        })
    }

    override fun stop(moveGimbalToNeutral: Boolean) {
        if (!isRunning) return

        isRunning = false
        stopSendLoop()
        flightCommandWatchdog.stop()

        flightCmdReceiver?.stop()
        gimbalCmdReceiver?.stop()

        lastFlightCmd = zeroFlightCmd()
        sendVirtualStickNow(lastFlightCmd!!)

        virtualStickEnabled = false

        vsManager.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(tag, "Virtual Stick DISABLED")
            }

            override fun onFailure(error: IDJIError) {
                Log.w(tag, "disableVirtualStick failed: ${error.description()}")
            }
        })

        if (moveGimbalToNeutral) {
            gimbalController.moveToNeutral()
        }

        onStatusLine("CMD system STOPPED")
    }

    override fun toggle() {
        if (isRunning) stop(moveGimbalToNeutral = true) else start()
    }

    private fun loadPortsFromPrefs() {
        flightCmdRxPort = AppPrefs.getFlightCmdRxPort(context)
        gimbalCmdRxPort = AppPrefs.getGimbalCmdRxPort(context)
    }

    private fun buildReceivers() {
        flightCmdReceiver = UdpJsonReceiver(
            port = flightCmdRxPort,
            tag = "UDP-FLIGHT-CMD"
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
            tag = "UDP-GIMBAL-CMD"
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
        try {
            val param = buildVirtualStickParam(cmd)
            vsManager.sendVirtualStickAdvancedParam(param)
        } catch (t: Throwable) {
            Log.e(tag, "sendVirtualStickAdvancedParam failed", t)
        }
    }

    private fun buildVirtualStickParam(cmd: DroneCmd): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            roll = clamp(cmd.vx, -23f, 23f)
            pitch = clamp(cmd.vy, -23f, 23f)
            yaw = clamp(cmd.yaw, -100f, 100f)
            verticalThrottle = clamp(cmd.throttle, -6f, 6f)
        }
    }

    private fun clamp(v: Float, min: Float, max: Float): Double {
        return v.coerceIn(min, max).toDouble()
    }

    private fun zeroFlightCmd() = DroneCmd(
        vx = 0f,
        vy = 0f,
        yaw = 0f,
        throttle = 0f
    )
}