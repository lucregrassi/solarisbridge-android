package com.rice.solarisbridge.v4.drone.telemetry

import android.content.Context
import android.util.Log
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
import com.rice.solarisbridge.v4.drone.control.WaypointMissionController
import dji.common.battery.BatteryState
import dji.common.flightcontroller.FlightControllerState
import dji.sdk.products.Aircraft
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Timer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.fixedRateTimer

/**
 * Collects aircraft state via V4 flight-controller/battery callbacks into an atomic [Snapshot],
 * and streams it to the PC as UDP JSON every 100 ms. Also exposes helpers used by the autonomous
 * navigation: current position for the mission, satellite count, isFlying, and the goto_state field.
 */
class TelemetryController(
    private val context: Context,
    private val tag: String = "TelemetryControllerV4",
    private val onSatelliteCountChanged: (Int?) -> Unit = {}
) {

    data class Snapshot(
        val ts: Long = System.currentTimeMillis(),
        val lat: Double? = null,
        val lon: Double? = null,
        val ultrasonicHeight: Double? = null,
        val altRelTakeoff: Double? = null,   // altitude relative to takeoff point (waypoint reference)
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
        val satelliteCount: Int? = null,
        val isFlying: Boolean? = null,
    )

    private var telemetryTimer: Timer? = null
    private var socket: DatagramSocket? = null
    private var addr: InetAddress? = null
    private var telemetryTxPort: Int = 0

    private var callbacksBound = false

    private val snapshot = AtomicReference(Snapshot())

    // Autonomous-navigation state, forwarded to the PC in the telemetry JSON.
    // Values: "idle" / "enroute" / "arrived" / "failed". Written by the ControlCoordinator.
    @Volatile
    private var gotoState: String = "idle"

    fun setGotoState(value: String) {
        gotoState = value
    }

    /**
     * Current position used to build waypoint 1 of the mission.
     * Returns null if lat/lon/altitude are not available yet.
     */
    fun currentPositionForMission(): WaypointMissionController.CurrentPosition? {
        val s = snapshot.get()
        val lat = s.lat
        val lon = s.lon
        val alt = s.altRelTakeoff
        return if (lat != null && lon != null && alt != null) {
            WaypointMissionController.CurrentPosition(lat, lon, alt.toFloat())
        } else {
            null
        }
    }

    /** Satellite count from the latest snapshot (null if unavailable). */
    fun latestSatelliteCount(): Int? = snapshot.get().satelliteCount

    /** true if the aircraft is reported airborne in the latest snapshot. */
    fun isFlying(): Boolean = snapshot.get().isFlying == true

    private var monitoringRetryTimer: Timer? = null

    var isRunning: Boolean = false
        private set

    fun rebuildFromPrefs() {
        val wasRunning = isRunning

        if (wasRunning) {
            stop()
        }

        val ip = AppPrefs.getPcIp(context)
        telemetryTxPort = AppPrefs.getTelemetryTxPort(context)

        if (!ip.isNullOrBlank()) {
            try {
                addr = InetAddress.getByName(ip)
            } catch (t: Throwable) {
                Log.e(tag, "Invalid telemetry IP: $ip", t)
                addr = null
            }
        } else {
            addr = null
        }

        if (wasRunning) {
            start()
        }
    }

    fun startMonitoring() {
        if (callbacksBound) return
        if (monitoringRetryTimer != null) return

        monitoringRetryTimer = fixedRateTimer(
            name = "telemetry-monitoring-v4",
            daemon = true,
            initialDelay = 0L,
            period = 1000L
        ) {
            val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft

            if (aircraft == null || !aircraft.isConnected) {
                Log.w(tag, "startMonitoring retry: Aircraft not ready")
                onSatelliteCountChanged(null)
                return@fixedRateTimer
            }

            val bound = bindSdkCallbacks()

            if (bound) {
                callbacksBound = true

                monitoringRetryTimer?.cancel()
                monitoringRetryTimer = null

                Log.i(tag, "Telemetry monitoring STARTED")
            } else {
                callbacksBound = false
                Log.w(tag, "startMonitoring retry: callbacks not bound yet")
                onSatelliteCountChanged(null)
            }
        }
    }

    fun stopMonitoring() {
        monitoringRetryTimer?.cancel()
        monitoringRetryTimer = null

        if (callbacksBound) {
            unbindSdkCallbacks()
            callbacksBound = false
            Log.i(tag, "Telemetry monitoring STOPPED")
        }
    }

    fun start() {
        if (isRunning) return

        if (addr == null || telemetryTxPort <= 0) {
            Log.w(tag, "Telemetry target not configured")
            return
        }

        try {
            socket = DatagramSocket()
        } catch (t: Throwable) {
            Log.e(tag, "Failed to open telemetry socket", t)
            return
        }

        startMonitoring()

        telemetryTimer = fixedRateTimer(
            name = "telemetry-v4",
            daemon = true,
            initialDelay = 0L,
            period = 100L
        ) {
            sendSnapshot(snapshot.get())
        }

        isRunning = true
        Log.i(tag, "Telemetry stream STARTED")
    }

    fun stop() {
        if (!isRunning) return

        telemetryTimer?.cancel()
        telemetryTimer = null

        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        socket = null
        isRunning = false

        Log.i(tag, "Telemetry stream STOPPED")
    }

    fun toggle() {
        if (isRunning) {
            stop()
        } else {
            start()
        }
    }

    private fun bindSdkCallbacks(): Boolean {
        val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft ?: run {
            Log.w(tag, "bindSdkCallbacks: Aircraft null")
            return false
        }

        if (!aircraft.isConnected) {
            Log.w(tag, "bindSdkCallbacks: Aircraft not connected")
            return false
        }

        val flightController = aircraft.flightController
        if (flightController == null) {
            Log.w(tag, "bindSdkCallbacks: FlightController null")
            return false
        }

        Log.i(
            tag,
            "bindSdkCallbacks: aircraft=$aircraft connected=${aircraft.isConnected} fc=$flightController battery=${aircraft.battery}"
        )

        try {
            flightController.setStateCallback { state ->
                updateFromFlightState(state)
            }
        } catch (t: Throwable) {
            Log.e(tag, "bindSdkCallbacks: failed to set flight controller callback", t)
            return false
        }

        try {
            aircraft.battery?.setStateCallback { state ->
                updateFromBatteryState(state)
            }
        } catch (t: Throwable) {
            Log.w(tag, "bindSdkCallbacks: failed to set battery callback", t)
        }

        return true
    }

    private fun unbindSdkCallbacks() {
        val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft ?: return

        try {
            aircraft.flightController?.setStateCallback(null)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to clear flight controller callback", t)
        }

        try {
            aircraft.battery?.setStateCallback(null)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to clear battery callback", t)
        }
    }

    private fun updateFromFlightState(state: FlightControllerState) {
        val compassHeading = try {
            (BridgeBootstrapV4.getProductInstance() as? Aircraft)
                ?.flightController
                ?.compass
                ?.heading
                ?.toDouble()
        } catch (_: Throwable) {
            null
        }

        val rawSatellites = try {
            state.satelliteCount
        } catch (_: Throwable) {
            null
        }

        val lat = state.aircraftLocation?.latitude
        val lon = state.aircraftLocation?.longitude

        val hasValidLocation =
            lat != null &&
                    lon != null &&
                    lat != 0.0 &&
                    lon != 0.0 &&
                    !lat.isNaN() &&
                    !lon.isNaN()

        val satellites = if (rawSatellites != null && rawSatellites > 0) {
            rawSatellites
        } else if (hasValidLocation) {
            rawSatellites
        } else {
            null
        }

        val old = snapshot.get()

        snapshot.set(
            old.copy(
                ts = System.currentTimeMillis(),
                lat = lat,
                lon = lon,
                ultrasonicHeight = state.ultrasonicHeightInMeters.toDouble(),
                altRelTakeoff = try { state.aircraftLocation?.altitude?.toDouble() } catch (_: Throwable) { null },
                velX = state.velocityX.toDouble(),
                velY = state.velocityY.toDouble(),
                velZ = state.velocityZ.toDouble(),
                roll = state.attitude?.roll,
                pitch = state.attitude?.pitch,
                yaw = state.attitude?.yaw,
                compassHeading = compassHeading,
                satelliteCount = satellites,
                isFlying = try { state.isFlying } catch (_: Throwable) { null }
            )
        )

        onSatelliteCountChanged(satellites)
    }

    private fun updateFromBatteryState(state: BatteryState) {
        val old = snapshot.get()

        snapshot.set(
            old.copy(
                ts = System.currentTimeMillis(),
                batteryPercent = state.chargeRemainingInPercent
            )
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        when (value) {
            null -> put(key, JSONObject.NULL)

            is Double -> {
                if (value.isNaN() || value.isInfinite()) {
                    put(key, JSONObject.NULL)
                } else {
                    put(key, value)
                }
            }

            is Float -> {
                if (value.isNaN() || value.isInfinite()) {
                    put(key, JSONObject.NULL)
                } else {
                    put(key, value)
                }
            }

            else -> put(key, value)
        }
    }

    private fun sendSnapshot(s: Snapshot) {
        val sock = socket ?: return
        val target = addr ?: return

        try {
            val now = System.currentTimeMillis()

            val json = JSONObject()
            json.put("ts", now)
            json.put("data_ts", s.ts)

            json.putNullable("lat", s.lat)
            json.putNullable("lon", s.lon)
            json.putNullable("ultrasonic_height", s.ultrasonicHeight)
            json.putNullable("altitude_rel_takeoff", s.altRelTakeoff)
            json.putNullable("vel_x", s.velX)
            json.putNullable("vel_y", s.velY)
            json.putNullable("vel_z", s.velZ)
            json.putNullable("roll", s.roll)
            json.putNullable("pitch", s.pitch)
            json.putNullable("yaw", s.yaw)
            json.putNullable("battery_percent", s.batteryPercent)
            json.putNullable("home_lat", s.homeLat)
            json.putNullable("home_lon", s.homeLon)
            json.putNullable("compass_heading", s.compassHeading)
            json.putNullable("satellite_count", s.satelliteCount)
            json.putNullable("is_flying", s.isFlying)
            json.put("goto_state", gotoState)

            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val pkt = DatagramPacket(payload, payload.size, target, telemetryTxPort)

            sock.send(pkt)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to send telemetry packet", t)
        }
    }
}