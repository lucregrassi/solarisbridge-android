package com.rice.solarisbridge.v5.drone.model

data class DroneCmd(
    val vx: Float,        // m/s o normalized: dipende da come mapperai poi
    val vy: Float,
    val yaw: Float,       // deg/s o normalized
    val throttle: Float   // m/s o normalized
)

data class GimbalCmd(
    val yaw: Float,       // gradi
    val pitch: Float,     // gradi
    val roll: Float       // gradi
)