package com.rice.solarismatrice

data class DroneTelemetry(
    val ts: Long,
    val lat: Double?,
    val lon: Double?,
    val alt: Double?,
    val yaw: Double?,
    val pitch: Double?,
    val roll: Double?
)