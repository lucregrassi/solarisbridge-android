package com.rice.solarisbridge.v4.drone.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rice.solarisbridge.common.commands.model.WaypointGotoCmd
import com.rice.solarisbridge.common.commands.parser.CommandParsers
import com.rice.solarisbridge.common.network.UdpJsonReceiver
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
import dji.common.error.DJIError
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionDownloadEvent
import dji.common.mission.waypoint.WaypointMissionExecutionEvent
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.common.util.CommonCallbacks
import dji.sdk.mission.MissionControl
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WaypointMissionController (V4)
 *
 * Pure mechanics, no state decisions:
 *  - owns the UDP receiver on the goto port (default 7002) + parseGoto;
 *  - builds/loads/starts/aborts a DJI Waypoint Mission (MSDK V4);
 *  - registers the WaypointMissionOperatorListener and translates DJI events into simple
 *    callbacks for the ControlCoordinator (finished / failed).
 *
 * On a goto it only calls onGotoReceived(cmd): the ControlCoordinator validates, suspends the
 * Virtual Stick and then calls startMission(...).
 *
 * Safety: test with propellers removed before any real flight.
 */
class WaypointMissionController(
    private val context: Context,
    private val currentPositionProvider: () -> CurrentPosition?,
    private val onGotoReceived: (WaypointGotoCmd) -> Unit,
    private val onMissionFinished: () -> Unit,
    private val onMissionFailed: (FaultReason) -> Unit,
    private val onStatusLine: (String) -> Unit,
    private val tag: String = "WaypointMissionControllerV4"
) {

    data class CurrentPosition(
        val lat: Double,
        val lon: Double,
        val altRelTakeoff: Float
    )

    enum class FaultReason {
        MISSION_ERROR,   // generic DJI mission error
        GPS_LOST,        // insufficient GPS while RC still connected -> hover + resume VS
        RC_LOST          // radio-controller lost -> on-board failsafe (RTH)
    }

    private val main = Handler(Looper.getMainLooper())

    private var gotoReceiver: UdpJsonReceiver? = null
    private var waypointCmdRxPort = 7002

    @Volatile
    var isMissionActive: Boolean = false
        private set

    // DJI V4 waypoint constraints.
    private companion object {
        const val MIN_WP_DISTANCE_M = 1.0       // distances < ~0.5 m are rejected by the SDK
        const val MAX_WP_DISTANCE_M = 1900.0    // SDK limit ~2 km between consecutive waypoints
        const val MIN_SPEED = 1.0f
        const val MAX_SPEED = 15.0f
        const val MAX_UPLOAD_ATTEMPTS = 4       // V4 upload often needs retries (retryUploadMission)
        const val UPLOAD_RETRY_DELAY_MS = 800L
    }

    private val operator: WaypointMissionOperator?
        get() = MissionControl.getInstance().waypointMissionOperator

    private val missionListener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(event: WaypointMissionDownloadEvent) {}
        override fun onUploadUpdate(event: WaypointMissionUploadEvent) {}
        override fun onExecutionUpdate(event: WaypointMissionExecutionEvent) {}
        override fun onExecutionStart() {
            Log.i(tag, "mission onExecutionStart")
        }

        // error == null -> success; otherwise classify and report a fault.
        override fun onExecutionFinish(error: DJIError?) {
            if (error == null) {
                Log.i(tag, "mission onExecutionFinish: SUCCESS")
                cleanupAfterMission()
                onMissionFinished()
            } else {
                Log.w(tag, "mission onExecutionFinish: ERROR ${error.description}")
                val reason = classifyFault(error)
                cleanupAfterMission()
                onMissionFailed(reason)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Goto channel lifecycle (port 7002)
    // ---------------------------------------------------------------------

    fun rebuildFromPrefs() {
        val wasListening = gotoReceiver?.isRunning() == true
        gotoReceiver?.stop()
        waypointCmdRxPort = AppPrefs.getWaypointCmdRxPort(context)
        buildReceiver()
        if (wasListening) startListening()
    }

    fun startListening() {
        gotoReceiver?.start()
        Log.i(tag, "Goto channel LISTENING on $waypointCmdRxPort")
    }

    fun stopListening() {
        gotoReceiver?.stop()
        Log.i(tag, "Goto channel STOPPED")
    }

    private fun buildReceiver() {
        gotoReceiver = UdpJsonReceiver(
            port = waypointCmdRxPort,
            tag = "UDP-GOTO-CMD-V4"
        ) { json, from ->
            val cmd = CommandParsers.parseGoto(json)
            if (cmd != null) {
                Log.i(tag, "GOTO RX $from -> $cmd")
                onStatusLine("GOTO: $cmd")
                // No decision here: delegate to the coordinator (validation + orchestration).
                onGotoReceived(cmd)
            } else {
                Log.w(tag, "GOTO parse FAIL $from -> $json")
                onStatusLine("GOTO parse FAIL: $json")
            }
        }
    }

    // ---------------------------------------------------------------------
    // DJI mission — called by the ControlCoordinator (on the main thread)
    // ---------------------------------------------------------------------

    /**
     * Builds and starts a 2-waypoint mission: current position -> target.
     * Precondition: the Virtual Stick must ALREADY be suspended (done by the coordinator).
     *
     * Replace: if a mission is already running, it is stopped first and the new one is started
     * only once the stop is confirmed (chained), to avoid starting over a still-stopping mission.
     */
    fun startMission(cmd: WaypointGotoCmd) {
        val op = operator ?: run {
            Log.w(tag, "startMission: WaypointMissionOperator null")
            onMissionFailed(FaultReason.MISSION_ERROR)
            return
        }

        if (isMissionActive) {
            Log.i(tag, "startMission: replacing active mission")
            // Detach the listener before stopping so the deliberate stop is not reported as a fault.
            isMissionActive = false
            try {
                op.removeListener(missionListener)
            } catch (t: Throwable) {
                Log.w(tag, "removeListener failed", t)
            }
            op.stopMission(object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    if (error != null) Log.w(tag, "replace stopMission failed: ${error.description}")
                    main.post { doStartMission(cmd) }   // continue on the main thread
                }
            })
            return
        }

        doStartMission(cmd)
    }

    private fun doStartMission(cmd: WaypointGotoCmd) {
        val current = currentPositionProvider() ?: run {
            Log.w(tag, "startMission: current position unavailable")
            onMissionFailed(FaultReason.GPS_LOST)
            return
        }

        val op = operator ?: run {
            Log.w(tag, "startMission: WaypointMissionOperator null")
            onMissionFailed(FaultReason.MISSION_ERROR)
            return
        }

        val distance = haversineMeters(current.lat, current.lon, cmd.lat, cmd.lon)
        if (distance < MIN_WP_DISTANCE_M || distance > MAX_WP_DISTANCE_M) {
            Log.w(tag, "startMission: distance out of range ($distance m)")
            onStatusLine("GOTO rejected: distance ${"%.1f".format(distance)} m")
            onMissionFailed(FaultReason.MISSION_ERROR)
            return
        }

        val mission = buildMission(cmd, current)

        // load (synchronous) -> upload -> start
        val loadError = op.loadMission(mission)
        if (loadError != null) {
            Log.w(tag, "loadMission failed: ${loadError.description}")
            onMissionFailed(FaultReason.MISSION_ERROR)
            return
        }

        op.addListener(missionListener)
        isMissionActive = true
        onStatusLine("MISSION uploading...")

        uploadThenStart(op, cmd, attempt = 1)
    }

    /**
     * Uploads the mission, then starts it. The DJI V4 error "info of waypoint mission is not
     * completely uploaded" is often transient: the upload does not finish in one call. We retry
     * with retryUploadMission (which resumes the partial upload) up to MAX_UPLOAD_ATTEMPTS before
     * declaring a fault.
     */
    private fun uploadThenStart(op: WaypointMissionOperator, cmd: WaypointGotoCmd, attempt: Int) {
        op.uploadMission(uploadCallback(op, cmd, attempt))
    }

    private fun uploadCallback(
        op: WaypointMissionOperator,
        cmd: WaypointGotoCmd,
        attempt: Int
    ): CommonCallbacks.CompletionCallback<DJIError> =
        object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(uploadError: DJIError?) {
                when {
                    uploadError == null -> startMissionNow(op, cmd)
                    attempt < MAX_UPLOAD_ATTEMPTS -> {
                        Log.w(tag, "uploadMission attempt $attempt failed: ${uploadError.description} -> retry")
                        onStatusLine("MISSION upload retry ${attempt + 1}")
                        // Restart the upload from scratch (retryUploadMission only works while the
                        // operator is still UPLOADING; after this failure it is not, so we re-upload).
                        main.postDelayed(
                            { op.uploadMission(uploadCallback(op, cmd, attempt + 1)) },
                            UPLOAD_RETRY_DELAY_MS
                        )
                    }
                    else -> {
                        Log.w(tag, "uploadMission failed after $attempt attempts: ${uploadError.description}")
                        cleanupAfterMission()
                        onMissionFailed(FaultReason.MISSION_ERROR)
                    }
                }
            }
        }

    private fun startMissionNow(op: WaypointMissionOperator, cmd: WaypointGotoCmd) {
        op.startMission(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(startError: DJIError?) {
                if (startError != null) {
                    Log.w(tag, "startMission failed: ${startError.description}")
                    cleanupAfterMission()
                    onMissionFailed(FaultReason.MISSION_ERROR)
                } else {
                    Log.i(tag, "mission STARTED -> ${cmd.lat},${cmd.lon} alt=${cmd.alt} v=${cmd.speed} hdg=${cmd.heading}")
                    onStatusLine("MISSION running")
                }
            }
        })
    }

    /**
     * Aborts the running mission (manual override or disarm). The target-replace case is handled
     * directly in startMission (stop is chained to the new start), so it does not go through here.
     */
    fun abortMission() {
        val op = operator
        if (op == null || !isMissionActive) {
            isMissionActive = false
            return
        }

        // Detach the listener before stopping so the deliberate stop is not reported as a fault.
        isMissionActive = false
        try {
            op.removeListener(missionListener)
        } catch (t: Throwable) {
            Log.w(tag, "removeListener failed", t)
        }

        op.stopMission(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    Log.w(tag, "stopMission failed: ${error.description}")
                } else {
                    Log.i(tag, "mission STOPPED")
                }
            }
        })
        onStatusLine("MISSION abort")
    }

    private fun cleanupAfterMission() {
        isMissionActive = false
        try {
            operator?.removeListener(missionListener)
        } catch (t: Throwable) {
            Log.w(tag, "removeListener failed", t)
        }
    }

    // ---------------------------------------------------------------------
    // Mission building + helpers
    // ---------------------------------------------------------------------

    private fun buildMission(cmd: WaypointGotoCmd, current: CurrentPosition): WaypointMission {
        val cruise = cmd.speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val maxV = maxOf(cruise, 2.0f).coerceAtMost(MAX_SPEED)

        // Waypoint 1: current position (at current altitude, so the altitude does not jump).
        val wp1 = Waypoint(current.lat, current.lon, current.altRelTakeoff)

        // Waypoint 2: target, with heading if provided.
        val wp2 = Waypoint(cmd.lat, cmd.lon, cmd.alt)
        cmd.heading?.let { h ->
            wp2.heading = h.toInt().coerceIn(-180, 180)
        }

        val headingMode = if (cmd.heading != null) {
            WaypointMissionHeadingMode.USING_WAYPOINT_HEADING
        } else {
            WaypointMissionHeadingMode.AUTO  // nose follows the direction of travel
        }

        return WaypointMission.Builder()
            .autoFlightSpeed(cruise)
            .maxFlightSpeed(maxV)
            .finishedAction(WaypointMissionFinishedAction.NO_ACTION)   // hover on arrival
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .headingMode(headingMode)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .addWaypoint(wp1)
            .addWaypoint(wp2)
            .build()
    }

    /**
     * Classifies a fault. The only distinction that changes the coordinator's behaviour is
     * RC_LOST (on-board failsafe / RTH) vs everything else (hover + resume the Virtual Stick).
     */
    private fun classifyFault(error: DJIError): FaultReason {
        return if (!BridgeBootstrapV4.isProductConnected()) {
            FaultReason.RC_LOST
        } else {
            // GPS_LOST and MISSION_ERROR are handled the same way by the coordinator.
            FaultReason.MISSION_ERROR
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
