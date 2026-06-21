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
    private val tag: String = "ControlCoordinatorV4"
) {

    enum class GotoState { IDLE, ENROUTE, ARRIVED, FAILED }
    private enum class Mode { MANUAL, MISSION }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var isArmed: Boolean = false
        private set

    private var mode: Mode = Mode.MANUAL

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
        if (mode == Mode.MISSION) {
            waypointMission.abortMission()  // aircraft hovers (mission stop / failsafe)
        }
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
        if (!isGotoValid(cmd)) {
            onStatusLine("GOTO rejected (preconditions)")
            return@post   // stay in the current state (self-loop)
        }

        // No-op if already suspended (replace case). The replace itself (stop current mission
        // before starting the new one) is handled inside WaypointMissionController.startMission.
        commandSystem.suspendVirtualStick()   // VS off, but 7000/7001 stay alive (override)
        mode = Mode.MISSION
        setGotoState(GotoState.ENROUTE)
        waypointMission.startMission(cmd)
        onStatusLine("MISSION ENROUTE")
    }

    /** Mission completed successfully. */
    fun onMissionFinished() = main.post {
        if (mode != Mode.MISSION) return@post
        // Re-enable the Virtual Stick FIRST (receiver actuating), THEN report arrived to the PC.
        commandSystem.resumeVirtualStick {
            mode = Mode.MANUAL
            setGotoState(GotoState.ARRIVED)
            onStatusLine("MISSION ARRIVED -> manual")
        }
    }

    /** Mission interrupted/failed. */
    fun onMissionFailed(reason: WaypointMissionController.FaultReason) = main.post {
        if (mode != Mode.MISSION) return@post
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
                commandSystem.resumeVirtualStick {
                    mode = Mode.MANUAL
                    setGotoState(GotoState.FAILED)
                    onStatusLine("MISSION FAILED: $reason -> hover/manual")
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
        if (waypointMission.isMissionActive) {
            waypointMission.abortMission()
        }
        commandSystem.resumeVirtualStick {
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
     * Preconditions to accept a goto: healthy GPS, aircraft flying, and plausible value ranges.
     * The maximum target distance is enforced in WaypointMissionController via the haversine guard.
     */
    private fun isGotoValid(cmd: WaypointGotoCmd): Boolean {
        if (!gpsHealthyProvider()) {
            Log.w(tag, "goto invalid: GPS not healthy")
            return false
        }
        if (!isFlyingProvider()) {
            Log.w(tag, "goto invalid: aircraft not flying")
            return false
        }
        if (cmd.lat !in -90.0..90.0 || cmd.lon !in -180.0..180.0) {
            Log.w(tag, "goto invalid: lat/lon out of range")
            return false
        }
        if (cmd.alt < MIN_ALT_M || cmd.alt > MAX_ALT_M) {
            Log.w(tag, "goto invalid: altitude ${cmd.alt} out of [$MIN_ALT_M, $MAX_ALT_M]")
            return false
        }
        if (cmd.speed <= 0f) {
            Log.w(tag, "goto invalid: non-positive speed")
            return false
        }
        return true
    }

    private companion object {
        // Plausible altitude bounds (metres relative to takeoff) used as a sanity guard.
        const val MIN_ALT_M = -5f
        const val MAX_ALT_M = 500f
    }
}
