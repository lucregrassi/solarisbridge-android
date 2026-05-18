package com.rice.solarisbridge.v4.drone.telemetry

import android.content.Context
import android.util.Log
import com.rice.solarisbridge.common.prefs.AppPrefs
import com.rice.solarisbridge.v4.app.BridgeBootstrapV4
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

class TelemetryController(
    private val context: Context,
    private val tag: String = "TelemetryControllerV4"
){

    data class Snapshot(
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

    private var telemetryTimer: Timer? = null
    private var socket: DatagramSocket? = null
    private var addr: InetAddress? = null
    private var telemetryTxPort: Int = 0

    private val snapshot = AtomicReference(Snapshot())

    var isRunning: Boolean = false
        private set

    fun rebuildFromPrefs() {
        val wasRunning = isRunning
        if (wasRunning) stop()

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

        if (wasRunning) start()
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

        bindSdkCallbacks()

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

        unbindSdkCallbacks()

        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null

        isRunning = false
        Log.i(tag, "Telemetry stream STOPPED")
    }

    fun toggle() {
        if (isRunning) stop() else start()
    }

    private fun bindSdkCallbacks() {
        val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft ?: run {
            Log.w(tag, "Aircraft null")
            return
        }

        aircraft.flightController?.setStateCallback { state ->
            updateFromFlightState(state)
        }

        aircraft.battery?.setStateCallback { state ->
            updateFromBatteryState(state)
        }
    }

    private fun unbindSdkCallbacks() {
        val aircraft = BridgeBootstrapV4.getProductInstance() as? Aircraft ?: return
        try {
            aircraft.flightController?.setStateCallback(null)
        } catch (_: Throwable) {
        }
        try {
            aircraft.battery?.setStateCallback(null)
        } catch (_: Throwable) {
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

        val old = snapshot.get()
        snapshot.set(
            old.copy(
                ts = System.currentTimeMillis(),
                lat = state.aircraftLocation?.latitude,
                lon = state.aircraftLocation?.longitude,
                ultrasonicHeight = state.ultrasonicHeightInMeters.toDouble(),
                velX = state.velocityX.toDouble(),
                velY = state.velocityY.toDouble(),
                velZ = state.velocityZ.toDouble(),
                roll = state.attitude?.roll,
                pitch = state.attitude?.pitch,
                yaw = state.attitude?.yaw,
                compassHeading = compassHeading
            )
        )
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
                if (value.isNaN() || value.isInfinite()) put(key, JSONObject.NULL)
                else put(key, value)
            }
            is Float -> {
                if (value.isNaN() || value.isInfinite()) put(key, JSONObject.NULL)
                else put(key, value)
            }
            else -> put(key, value)
        }
    }

    private fun sendSnapshot(s: Snapshot) {
        val sock = socket ?: return
        val target = addr ?: return

        try {
            val json = JSONObject()
            json.put("ts", s.ts)
            json.putNullable("lat", s.lat)
            json.putNullable("lon", s.lon)
            json.putNullable("ultrasonic_height", s.ultrasonicHeight)
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

            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val pkt = DatagramPacket(payload, payload.size, target, telemetryTxPort)
            sock.send(pkt)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to send telemetry packet", t)
        }
    }
}