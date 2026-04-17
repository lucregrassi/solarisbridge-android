package com.rice.solarisbridge.common.telemetry.model

data class TelemetrySnapshot(
    val ts: Long = System.currentTimeMillis(),
    val lat: Double? = null,
    val lon: Double? = null,
    val ultrasonicHeight: Double? = null,
    val velX: Double? = null,
    val velY: Double? = null,
    val velZ: Double? = null,
    val roll: Double? = null,
    val pitch: Double? = null,
    val yaw: Double? = null,
    val batteryPercent: Int? = null,
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val compassHeading: Double? = null,
)