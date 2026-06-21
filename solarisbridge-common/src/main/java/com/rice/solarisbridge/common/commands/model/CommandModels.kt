package com.rice.solarisbridge.common.commands.model

// Manual flight command (Virtual Stick). Axis units depend on the control modes set
// by the flight controller (velocity m/s for roll/pitch/throttle, deg/s for yaw).
data class DroneCmd(
    val vx: Float,
    val vy: Float,
    val yaw: Float,
    val throttle: Float
)

// Gimbal command, angles in degrees.
data class GimbalCmd(
    val yaw: Float,
    val pitch: Float,
    val roll: Float
)

/**
 * "Go to these coordinates" command received from the PC on a dedicated port (default 7002).
 * Used to build a DJI Waypoint Mission.
 *
 * - lat/lon: target waypoint coordinates (decimal degrees, WGS84).
 * - alt: target altitude in metres, RELATIVE TO THE TAKEOFF POINT (matches V4
 *        aircraftLocation.altitude and the waypoint altitude reference).
 * - speed: desired cruise speed in m/s (mapped to autoFlightSpeed/maxFlightSpeed).
 * - heading: optional. If set -> aircraft heading at the waypoint in degrees [-180..180]
 *            relative to north (USING_WAYPOINT_HEADING). If null -> AUTO heading mode
 *            (nose follows the direction of travel).
 */
data class WaypointGotoCmd(
    val lat: Double,
    val lon: Double,
    val alt: Float,
    val speed: Float,
    val heading: Float? = null
)