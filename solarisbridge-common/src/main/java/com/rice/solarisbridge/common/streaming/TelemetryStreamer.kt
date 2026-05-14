package com.rice.solarisbridge.common.streaming

import android.util.Log
import com.rice.solarisbridge.common.telemetry.model.TelemetrySnapshot
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class TelemetryStreamer(private val ip: String, private val port: Int) {
    private val TAG = "TelemetryStreamer"
    private var socket: DatagramSocket? = null
    private val addr: InetAddress = InetAddress.getByName(ip)
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            socket = DatagramSocket()
            Log.i(TAG, "Telemetry streamer started -> $ip:$port")
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "TelemetryStreamer start failed", t)
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        Log.i(TAG, "Telemetry streamer stopped")
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    fun sendCurrentSnapshot(s: TelemetrySnapshot) {
        val sock = socket ?: return
        if (!running.get()) return

        try {
            val json = JSONObject()
            json.put("ts", System.currentTimeMillis())
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
            val pkt = DatagramPacket(payload, payload.size, addr, port)
            sock.send(pkt)

            Log.d(TAG, "TX telemetry: $json")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send telemetry packet", t)
        }
    }
}