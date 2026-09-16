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
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.common.util.CommonCallbacks
import dji.sdk.mission.MissionControl
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
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

    // --- Upload state machine (see attemptUpload/tryStartIfReady) ---
    private var pendingMission: WaypointMission? = null
    private var pendingCmd: WaypointGotoCmd? = null
    private var uploadAttempt = 0
    @Volatile private var missionStarted = false
    @Volatile private var retryScheduled = false
    private var missionGeneration = 0   // invalidates pending retries when a new mission replaces this one
    private val uploadTimeoutRunnable = Runnable { onUploadTimeout() }

    // DJI V4 waypoint constraints.
    private companion object {
        const val MIN_WP_DISTANCE_M = 1.0       // distances < ~0.5 m are rejected by the SDK
        const val MAX_WP_DISTANCE_M = 1900.0    // SDK limit ~2 km between consecutive waypoints
        const val MIN_SPEED = 1.0f
        const val MAX_SPEED = 15.0f
        const val MAX_UPLOAD_ATTEMPTS = 10      // V4 upload is flaky; a few attempts are usually needed
        const val UPLOAD_RETRY_DELAY_MS = 1500L // let the operator settle back to READY_TO_UPLOAD
        const val UPLOAD_TIMEOUT_MS = 3000L     // if the upload stalls (no update/error), retry
    }

    private val operator: WaypointMissionOperator?
        get() = MissionControl.getInstance().waypointMissionOperator

    private val missionListener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(event: WaypointMissionDownloadEvent) {}

        // Real-time upload progress. We start the mission only once the operator actually reaches
        // READY_TO_EXECUTE, and retry (reload + upload) if the upload reports an error. Marshalled
        // onto the main thread so it never races with the retry/timeout logic.
        override fun onUploadUpdate(event: WaypointMissionUploadEvent) {
            val err = event.error
            val state = event.currentState
            main.post {
                if (missionStarted || !isMissionActive) return@post
                if (err != null) {
                    Log.w(tag, "onUploadUpdate error: ${err.description}")
                    scheduleUploadRetry()
                    return@post
                }
                if (state == WaypointMissionState.READY_TO_EXECUTE) {
                    tryStartIfReady()
                }
            }
        }

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

        // Reset the upload state machine and register the listener before the first attempt.
        pendingMission = buildMission(cmd, current)
        pendingCmd = cmd
        uploadAttempt = 0
        missionStarted = false
        retryScheduled = false
        missionGeneration++
        main.removeCallbacks(uploadTimeoutRunnable)

        op.addListener(missionListener)
        isMissionActive = true
        onStatusLine("MISSION uploading...")

        attemptUpload()
    }

    /**
     * One upload attempt: RE-LOAD the mission, then upload. Re-loading each time is essential —
     * after a failed upload the operator no longer holds a valid loaded mission, so re-uploading
     * without re-loading returns "could not be executed". The mission is NOT started here: it is
     * started (tryStartIfReady) only once the operator actually reaches READY_TO_EXECUTE, signalled
     * by onUploadUpdate or the uploadMission success callback.
     */
    private fun attemptUpload() {
        if (missionStarted || !isMissionActive) return

        val op = operator ?: run { failMission("operator null"); return }
        val mission = pendingMission ?: run { failMission("no pending mission"); return }

        uploadAttempt++
        if (uploadAttempt > MAX_UPLOAD_ATTEMPTS) {
            failMission("upload failed after ${uploadAttempt - 1} attempts")
            return
        }
        Log.i(tag, "upload attempt $uploadAttempt (reload + upload)")
        onStatusLine("MISSION upload attempt $uploadAttempt")

        // A) re-load so the operator always has a valid loaded mission for this attempt.
        val loadError = op.loadMission(mission)
        if (loadError != null) {
            Log.w(tag, "loadMission (attempt $uploadAttempt) failed: ${loadError.description}")
            scheduleUploadRetry()
            return
        }

        // B) upload. Success here may already be READY_TO_EXECUTE, so we check the state; otherwise
        // we wait for the READY_TO_EXECUTE transition in onUploadUpdate.
        op.uploadMission(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(uploadError: DJIError?) {
                if (uploadError != null) {
                    Log.w(tag, "uploadMission (attempt $uploadAttempt) failed: ${uploadError.description}")
                    scheduleUploadRetry()
                } else {
                    tryStartIfReady()
                }
            }
        })

        // C) stall guard: if we never reach READY_TO_EXECUTE (no update, no error), retry.
        main.removeCallbacks(uploadTimeoutRunnable)
        main.postDelayed(uploadTimeoutRunnable, UPLOAD_TIMEOUT_MS)
    }

    /** Starts the mission only when the operator is actually READY_TO_EXECUTE (idempotent). */
    private fun tryStartIfReady() {
        if (missionStarted || !isMissionActive) return
        val op = operator ?: return
        if (op.currentState != WaypointMissionState.READY_TO_EXECUTE) return

        missionStarted = true
        main.removeCallbacks(uploadTimeoutRunnable)

        op.startMission(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(startError: DJIError?) {
                if (startError != null) {
                    Log.w(tag, "startMission failed: ${startError.description}")
                    failMission("startMission failed")
                } else {
                    val c = pendingCmd
                    Log.i(tag, "mission STARTED -> ${c?.lat},${c?.lon} alt=${c?.alt} v=${c?.speed} hdg=${c?.heading}")
                    onStatusLine("MISSION running")
                }
            }
        })
    }

    private fun onUploadTimeout() {
        if (missionStarted || !isMissionActive) return
        if (operator?.currentState == WaypointMissionState.READY_TO_EXECUTE) {
            tryStartIfReady()
        } else {
            Log.w(tag, "upload stalled (timeout) -> retry")
            scheduleUploadRetry()
        }
    }

    /** Schedules a single retry (reload + upload) after a delay; coalesced so triggers don't stack. */
    private fun scheduleUploadRetry() {
        if (retryScheduled || missionStarted || !isMissionActive) return
        main.removeCallbacks(uploadTimeoutRunnable)
        if (uploadAttempt >= MAX_UPLOAD_ATTEMPTS) {
            failMission("upload failed after $uploadAttempt attempts")
            return
        }
        retryScheduled = true
        val gen = missionGeneration
        onStatusLine("MISSION upload retry ${uploadAttempt + 1}")
        main.postDelayed({
            retryScheduled = false
            if (gen == missionGeneration) attemptUpload()   // ignore retries from a replaced mission
        }, UPLOAD_RETRY_DELAY_MS)
    }

    private fun failMission(reason: String) {
        Log.w(tag, "mission FAILED: $reason")
        cleanupAfterMission()
        onMissionFailed(FaultReason.MISSION_ERROR)
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
        missionStarted = false
        retryScheduled = false
        pendingMission = null
        pendingCmd = null
        missionGeneration++   // invalidate any pending upload retry
        main.removeCallbacks(uploadTimeoutRunnable)
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
        missionStarted = false
        retryScheduled = false
        pendingMission = null
        pendingCmd = null
        missionGeneration++   // invalidate any pending retry
        main.removeCallbacks(uploadTimeoutRunnable)
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
        // Waypoint 2: target.
        val wp2 = Waypoint(cmd.lat, cmd.lon, cmd.alt)

        val builder = WaypointMission.Builder()
            .autoFlightSpeed(cruise)
            .maxFlightSpeed(maxV)
            .finishedAction(WaypointMissionFinishedAction.NO_ACTION)   // hover on arrival
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)

        val heading = cmd.heading
        if (heading != null) {
            // In USING_WAYPOINT_HEADING the heading interpolates between two waypoints
            //  with different headings, so:
            //  - wp1.heading = initial bearing toward the target (starts facing where it goes)
            //  - wp2.heading = requested final heading
            // The aircraft rotates gradually during the leg and arrives already correctly oriented.
            wp1.heading = bearingDegrees(current.lat, current.lon, cmd.lat, cmd.lon)
                .roundToInt().coerceIn(-180, 180)
            wp2.heading = heading.roundToInt().coerceIn(-180, 180)
            builder.headingMode(WaypointMissionHeadingMode.USING_WAYPOINT_HEADING)
        } else {
            // No requested heading: nose follows the direction of flight.
            builder.headingMode(WaypointMissionHeadingMode.AUTO)
        }

        return builder
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

    /** Initial bearing (forward azimuth) from point 1 to point 2, in degrees [-180, 180], 0 = North. */
    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return Math.toDegrees(atan2(y, x))
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
