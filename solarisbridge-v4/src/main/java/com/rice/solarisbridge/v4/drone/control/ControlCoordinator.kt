package com.rice.solarisbridge.v4.drone.control

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rice.solarisbridge.common.commands.model.DroneCmd
import com.rice.solarisbridge.common.commands.model.WaypointGotoCmd

/**
 * ControlCoordinator (V4)
 *
 * IDLE / MANUAL / MISSION state machine. Pure LOGIC: no sockets. It receives events from the
 * two executors and decides the transitions:
 *  - CommandSystemController  -> Virtual Stick (start/stop/suspend/resume + onManualOverride)
 *  - WaypointMissionController -> DJI mission (startListening/startMission/abortMission)
 *
 * goto_state reported to the PC via telemetry: IDLE / ENROUTE / ARRIVED / FAILED.
 *
 * Entry points coming from network threads (onGotoReceived, onManualOverride) are marshalled
 * onto the main looper, because Virtual Stick / DJI mission calls must run on the main thread.
 */
class ControlCoordinator(
    private val commandSystem: CommandSystemController,
    private val waypointMission: WaypointMissionController,
    // Publishes the goto state to telemetry (goto_state field).
    private val onGotoState: (GotoState) -> Unit,
    // Updates the "PC control" button UI (armed = MANUAL or MISSION).
    private val onArmedChanged: (Boolean) -> Unit,
    private val onStatusLine: (String) -> Unit,
    // Safety preconditions to accept a goto (wired to telemetry in MainActivity).
    private val gpsHealthyProvider: () -> Boolean = { true },
    private val isFlyingProvider: () -> Boolean = { true },
    // A usable (finite, non-zero) current position: never bypassable, the mission needs it.
    private val positionValidProvider: () -> Boolean = { true },
    // Sticky mission outcome for the phone UI (NOT overwritten by the 20 Hz command lines).
    private val onMissionStatus: (String) -> Unit = {},
    // Reason of the last goto failure/rejection, forwarded to the PC as "goto_error" (null = none).
    private val onGotoError: (String?) -> Unit = {},
    // One-line aircraft diagnostics, appended to rejection logs.
    private val diagnostics: () -> String = { "" },
    private val tag: String = "ControlCoordinatorV4"
) {

    enum class GotoState { IDLE, ENROUTE, ARRIVED, FAILED }
    private enum class Mode { MANUAL, MISSION }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var isArmed: Boolean = false
        private set

    private var mode: Mode = Mode.MANUAL

    // Incremented on every goto/override/disarm: invalidates a pending (not yet started) mission.
    private var gotoGeneration = 0

    // Target of the mission in progress: an identical goto re-sent by the PC while this mission is
    // being uploaded/flown is ignored instead of restarting (and possibly never starting) it.
    private var activeGoto: WaypointGotoCmd? = null
    private var duplicateGotoCount = 0

    // ---------------------------------------------------------------------
    // Command button: arms/disarms the whole PC control surface (manual + mission)
    // ---------------------------------------------------------------------

    fun arm() = main.post {
        if (isArmed) return@post
        commandSystem.start()              // -> MANUAL (Virtual Stick active)
        waypointMission.startListening()   // goto channel 7002 listening
        isArmed = true
        mode = Mode.MANUAL
        setGotoState(GotoState.IDLE)
        onArmedChanged(true)
        onStatusLine("PC CONTROL ARMED (manual)")
    }

    fun disarm() = main.post {
        if (!isArmed) return@post
        gotoGeneration++
        if (mode == Mode.MISSION) {
            waypointMission.abortMission()  // aircraft hovers (mission stop / failsafe)
            onMissionStatus("ABORTED: PC control disarmed")
        }
        activeGoto = null
        commandSystem.stop(moveGimbalToNeutral = false)
        waypointMission.stopListening()
        isArmed = false
        mode = Mode.MANUAL
        setGotoState(GotoState.IDLE)
        onArmedChanged(false)
        onStatusLine("PC CONTROL DISARMED")
    }

    fun toggleArmed() {
        if (isArmed) disarm() else arm()
    }

    // ---------------------------------------------------------------------
    // Events from the WaypointMissionController
    // ---------------------------------------------------------------------

    /** Goto received from the PC (already parsed). Validation + MANUAL/MISSION -> MISSION. */
    fun onGotoReceived(cmd: WaypointGotoCmd) = main.post {
        if (!isArmed) {
            Log.w(tag, "goto ignored: PC control not armed")
            return@post
        }
        val current = activeGoto
        if (mode == Mode.MISSION && current != null && isSameTarget(current, cmd)) {
            duplicateGotoCount++
            if (duplicateGotoCount == 1 || duplicateGotoCount % 20 == 0) {
                Log.i(tag, "duplicate goto ignored (x$duplicateGotoCount): mission to this target already in progress")
            }
            return@post
        }

        val rejection = gotoRejectionReason(cmd)
        if (rejection != null) {
            Log.w(tag, "GOTO rejected: $rejection | ${diagnostics()}")
            onStatusLine("GOTO rejected: $rejection")
            onMissionStatus("REJECTED: $rejection")
            if (mode != Mode.MISSION) {
                // Tell the PC explicitly, otherwise it would wait for "arrived" forever.
                onGotoError("rejected: $rejection")
                setGotoState(GotoState.FAILED)
            }
            return@post   // a running mission (if any) is left untouched
        }

        if (mode == Mode.MISSION) {
            Log.i(tag, "new goto replaces the current mission: $cmd")
        }
        activeGoto = cmd
        duplicateGotoCount = 0
        onGotoError(null)
        onMissionStatus("STARTING -> ${cmd.lat},${cmd.lon} alt=${cmd.alt} hdg=${cmd.heading}")

        // The mission is started ONLY after the FlightController confirms Virtual Stick is off
        // (plus a short settle delay): starting a mission while VS is still enabled is rejected by
        // the aircraft and was the main cause of the intermittent mission failures.
        // The replace itself (stop current mission before starting the new one) is handled
        // inside WaypointMissionController.startMission.
        mode = Mode.MISSION
        setGotoState(GotoState.ENROUTE)
        val gen = ++gotoGeneration
        commandSystem.suspendVirtualStick { ok ->   // VS off, but 7000/7001 stay alive (override)
            if (gen != gotoGeneration || mode != Mode.MISSION || !isArmed) return@suspendVirtualStick
            if (!ok) {
                Log.w(tag, "goto: could not disable Virtual Stick, mission not started")
                onMissionFailed(
                    WaypointMissionController.FaultReason.MISSION_ERROR,
                    "could not disable Virtual Stick"
                )
                return@suspendVirtualStick
            }
            main.postDelayed({
                if (gen != gotoGeneration || mode != Mode.MISSION || !isArmed) return@postDelayed
                waypointMission.startMission(cmd)
                onStatusLine("MISSION ENROUTE")
            }, VS_SETTLE_MS)
        }
    }

    /** Mission completed successfully. */
    fun onMissionFinished() = main.post {
        if (mode != Mode.MISSION) return@post
        activeGoto = null
        // Re-enable the Virtual Stick FIRST (receiver actuating), THEN report arrived to the PC.
        commandSystem.resumeVirtualStick { ok ->
            mode = Mode.MANUAL
            if (ok) {
                onGotoError(null)
                setGotoState(GotoState.ARRIVED)
                onStatusLine("MISSION ARRIVED -> manual")
                onMissionStatus("ARRIVED")
            } else {
                // Arrived, but the PC cannot fly it: do not claim "arrived". The next PC velocity
                // command retries the resume through onManualOverride (VS still suspended).
                val msg = "arrived but Virtual Stick could not be re-enabled"
                onGotoError(msg)
                setGotoState(GotoState.FAILED)
                onStatusLine("MISSION: $msg")
                onMissionStatus("FAILED: $msg")
            }
        }
    }

    /** Mission interrupted/failed. */
    fun onMissionFailed(reason: WaypointMissionController.FaultReason, detail: String) = main.post {
        if (mode != Mode.MISSION) return@post
        activeGoto = null
        Log.w(tag, "mission failed: $reason - $detail")
        onGotoError(detail)
        onMissionStatus("FAILED: $detail")
        when (reason) {
            WaypointMissionController.FaultReason.RC_LOST -> {
                // Radio-controller lost: the aircraft's own configured signal-loss failsafe takes
                // over while the app is disconnected (we do not force a behaviour from the app).
                // We mark FAILED and leave the Virtual Stick suspended. When the link returns and
                // the PC sends a command, onManualOverride() (suspended-state recovery) re-enables
                // the Virtual Stick and hands control back.
                mode = Mode.MANUAL
                setGotoState(GotoState.FAILED)
                onStatusLine("MISSION FAILED: RC lost -> on-board failsafe")
            }
            else -> {
                // GPS lost / mission error: hover + return to the Virtual Stick.
                commandSystem.resumeVirtualStick { ok ->
                    mode = Mode.MANUAL
                    setGotoState(GotoState.FAILED)
                    onStatusLine(
                        if (ok) "MISSION FAILED: $detail -> hover/manual"
                        else "MISSION FAILED: $detail (VS resume failed, hovering)"
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Event from the CommandSystemController (manual override during MISSION)
    // ---------------------------------------------------------------------

    // Suspended-state based (not mode based): this covers both the in-mission override and the
    // recovery after an RC_LOST fault, where the Virtual Stick is left suspended until the PC
    // sends a command again.
    fun onManualOverride(cmd: DroneCmd) = main.post {
        if (!commandSystem.isSuspended) return@post
        Log.i(tag, "manual override -> abort mission (if any) + resume")
        gotoGeneration++
        if (mode == Mode.MISSION) {
            onMissionStatus("OVERRIDE: PC velocity ${cmd} -> mission cancelled")
        }
        activeGoto = null
        if (waypointMission.isMissionActive) {
            waypointMission.abortMission()
        }
        commandSystem.resumeVirtualStick { ok ->
            if (!ok) {
                onStatusLine("OVERRIDE: VS resume failed, retry on next command")
                return@resumeVirtualStick
            }
            mode = Mode.MANUAL
            setGotoState(GotoState.IDLE)
            onStatusLine("OVERRIDE -> manual")
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun setGotoState(state: GotoState) {
        onGotoState(state)
        Log.i(tag, "goto_state = $state")
    }

    /**
     * Preconditions to accept a goto: usable position, healthy GPS, aircraft flying, and
     * plausible value ranges. Returns null if valid, otherwise a short human-readable reason.
     * The maximum target distance is enforced in WaypointMissionController via the haversine guard.
     */
    private fun gotoRejectionReason(cmd: WaypointGotoCmd): String? {
        if (!cmd.lat.isFinite() || !cmd.lon.isFinite() ||
            cmd.lat !in -90.0..90.0 || cmd.lon !in -180.0..180.0
        ) {
            return "target lat/lon invalid (${cmd.lat}, ${cmd.lon})"
        }
        if (!cmd.alt.isFinite() || cmd.alt < MIN_ALT_M || cmd.alt > MAX_ALT_M) {
            return "altitude ${cmd.alt} outside [$MIN_ALT_M, $MAX_ALT_M]"
        }
        if (!cmd.speed.isFinite() || cmd.speed <= 0f) {
            return "non-positive speed ${cmd.speed}"
        }
        if (!positionValidProvider()) {
            return "aircraft position invalid (no GPS fix?)"
        }
        if (!gpsHealthyProvider()) {
            return "GPS not healthy (too few satellites)"
        }
        if (!isFlyingProvider()) {
            return "aircraft not flying"
        }
        return null
    }

    /** Same target within ~10 cm / 20 cm / 1 deg: treated as a re-send of the same goto. */
    private fun isSameTarget(a: WaypointGotoCmd, b: WaypointGotoCmd): Boolean {
        // Local copies: smart casts are not allowed on properties declared in another module.
        val ha = a.heading
        val hb = b.heading
        val sameHeading = if (ha == null || hb == null) ha == hb else kotlin.math.abs(ha - hb) < 1f
        return kotlin.math.abs(a.lat - b.lat) < 1e-6 &&
                kotlin.math.abs(a.lon - b.lon) < 1e-6 &&
                kotlin.math.abs(a.alt - b.alt) < 0.2f &&
                sameHeading
    }

    private companion object {
        // Plausible altitude bounds (metres relative to takeoff) used as a sanity guard.
        const val MIN_ALT_M = -5f
        const val MAX_ALT_M = 500f

        // Settle time between "VS disabled" confirmation and mission load/upload.
        const val VS_SETTLE_MS = 1000L   // with 500 ms the 1st upload was rejected in the 2026-09-25 test ("info not completely uploaded")
    }
}
