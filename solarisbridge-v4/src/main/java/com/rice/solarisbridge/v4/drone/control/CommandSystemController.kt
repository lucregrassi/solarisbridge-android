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
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
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
    // Invoked ONLY while the Virtual Stick is suspended (MISSION state) and a valid,
    // significant flight command arrives from the PC on port 7000.
    // The ControlCoordinator uses it to abort the mission and return to manual control.
    private val onManualOverride: (DroneCmd) -> Unit = {},
    private val tag: String = "CommandSystemControllerV4"
){

    private var flightCmdReceiver: UdpJsonReceiver? = null
    private var gimbalCmdReceiver: UdpJsonReceiver? = null

    @Volatile
    private var lastFlightCmd: DroneCmd? = null

    @Volatile
    private var virtualStickEnabled = false

    // true = Virtual Stick actuation suspended (a mission is running), BUT the 7000/7001
    // receivers stay alive: this is what makes the manual velocity override possible.
    @Volatile
    private var suspended = false

    // Coalesces manual-override notifications to a single one per suspend window
    // (the PC may stream velocity at 20 Hz; we only want to abort the mission once).
    @Volatile
    private var overrideNotified = false

    // Guards against overlapping resume operations (resume is asynchronous): without this,
    // a concurrent override + mission-failure would both re-enable the VS and make goto_state bounce.
    @Volatile
    private var resuming = false

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
                suspended = false
                overrideNotified = false
                resuming = false

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

    fun stop(moveGimbalToNeutral: Boolean = false) {
        if (!isRunning) return

        val fc = flightController()

        isRunning = false
        suspended = false
        overrideNotified = false
        resuming = false
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
        if (isRunning) stop() else start()
    }

    /** True if VS actuation is suspended for a running mission (receivers still alive). */
    val isSuspended: Boolean
        get() = suspended

    /**
     * Suspends Virtual Stick ACTUATION to hand control to the Waypoint Mission, but keeps the
     * 7000/7001 receivers listening. Call this BEFORE starting the mission.
     *
     * Does not change the command mapping/send logic: it only stops the send loop and watchdog
     * and disables Virtual Stick mode on the FlightController (mutually exclusive with a mission).
     */
    fun suspendVirtualStick() {
        if (!isRunning || suspended) return

        suspended = true
        overrideNotified = false   // arm a fresh override window for this mission
        stopSendLoop()
        flightCommandWatchdog.stop()

        // Send a final zero while VS is still enabled, then disable the mode.
        lastFlightCmd = zeroFlightCmd()
        sendVirtualStickNow(lastFlightCmd!!)
        virtualStickEnabled = false

        flightController()?.setVirtualStickModeEnabled(
            false,
            object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    if (error != null) {
                        Log.w(tag, "suspend: setVirtualStickModeEnabled(false) failed: ${error.description}")
                    } else {
                        Log.i(tag, "Virtual Stick SUSPENDED (mission)")
                    }
                }
            }
        )

        onStatusLine("VS SUSPENDED (mission)")
    }

    /**
     * Re-enables Virtual Stick actuation when the mission finishes/aborts.
     * Applies the last lastFlightCmd: if no velocity arrived during the mission it is zero
     * (= hover); if the exit is a manual override it is the triggering command (already stored
     * by the receiver), so control resumes immediately.
     *
     * NB: invoke onResumed only after the successful onResult, so the PC is notified only once
     * the Virtual Stick is actually actuating again (avoids the arrived/resume race).
     */
    fun resumeVirtualStick(onResumed: (() -> Unit)? = null) {
        if (!isRunning || !suspended || resuming) return

        val fc = flightController() ?: run {
            Log.w(tag, "resume: FlightController null")
            onStatusLine("VS resume: FC NULL")
            return
        }

        resuming = true

        try {
            fc.rollPitchControlMode = RollPitchControlMode.VELOCITY
            fc.yawControlMode = YawControlMode.ANGULAR_VELOCITY
            fc.verticalControlMode = VerticalControlMode.VELOCITY
            fc.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        } catch (t: Throwable) {
            Log.e(tag, "resume: failed to configure virtual stick modes", t)
        }

        fc.setVirtualStickModeEnabled(true, object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    resuming = false   // allow a later retry; stays suspended
                    Log.e(tag, "resume: setVirtualStickModeEnabled(true) failed: ${error.description}")
                    onStatusLine("VS resume FAIL: ${error.description}")
                    return
                }

                virtualStickEnabled = true
                try {
                    fc.setVirtualStickAdvancedModeEnabled(true)
                } catch (t: Throwable) {
                    Log.w(tag, "resume: setVirtualStickAdvancedModeEnabled failed", t)
                }

                // Apply the last command right away (zero = hover, or the override command).
                sendVirtualStickNow(lastFlightCmd ?: zeroFlightCmd())

                flightCommandWatchdog.lastCmdRxMs = SystemClock.elapsedRealtime()
                flightCommandWatchdog.start()
                startSendLoop()

                suspended = false
                resuming = false
                Log.i(tag, "Virtual Stick RESUMED")
                onStatusLine("VS RESUMED")
                onResumed?.invoke()
            }
        })
    }

    /** A command is "significant" if any axis exceeds a small threshold (ignores all-zero packets). */
    private fun isSignificant(cmd: DroneCmd): Boolean {
        val eps = 0.01f
        return kotlin.math.abs(cmd.vx) > eps ||
                kotlin.math.abs(cmd.vy) > eps ||
                kotlin.math.abs(cmd.yaw) > eps ||
                kotlin.math.abs(cmd.throttle) > eps
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
                // Always store the last command: needed both in MANUAL (direct actuation)
                // and for a possible resumeVirtualStick() after an override.
                lastFlightCmd = cmd
                flightCommandWatchdog.lastCmdRxMs = SystemClock.elapsedRealtime()

                if (suspended) {
                    // MISSION state: VS is not actuating. The first valid, significant velocity
                    // is a manual-override request -> notify the coordinator once (coalesced).
                    if (isSignificant(cmd) && !overrideNotified) {
                        overrideNotified = true
                        Log.i(tag, "MANUAL OVERRIDE RX $from -> $cmd")
                        onStatusLine("OVERRIDE: $cmd")
                        onManualOverride(cmd)
                    }
                } else {
                    Log.i(tag, "FLIGHT CMD RX $from -> $cmd")
                    onStatusLine("FLIGHT: $cmd")
                }
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
        val product = BridgeBootstrapV4.getProductInstance() as? Aircraft ?: return null
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