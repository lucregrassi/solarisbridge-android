package com.rice.solarismatrice

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class TelemetryStreamer(private val ip: String, private val port: Int) {
    private val TAG = "TelemetryStreamer"
    private var socket: DatagramSocket? = null
    private var addr: InetAddress = InetAddress.getByName(ip)
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        try {
            socket = DatagramSocket()
            running.set(true)
            Log.i(TAG, "Telemetry streamer started -> $ip:$port")
        } catch (t: Throwable) {
            Log.e(TAG, "TelemetryStreamer start failed", t)
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        Log.i(TAG, "Telemetry streamer stopped")
    }

    /**
     * Costruisce JSON dallo snapshot attuale e lo manda via UDP.
     * Chiamare a 10Hz (o periodicamente).
     */
    fun sendCurrentSnapshot(snapshot: TelemetryProvider.Snapshot) {
        if (!running.get() || socket == null) return

        try {
            val json = JSONObject()
            json.put("ts", snapshot.ts)
            json.put("lat", snapshot.lat)
            json.put("lon", snapshot.lon)
            json.put("alt", snapshot.alt)
            json.put("vel_x", snapshot.velX)
            json.put("vel_y", snapshot.velY)
            json.put("vel_z", snapshot.velZ)
            json.put("roll", snapshot.roll)
            json.put("pitch", snapshot.pitch)
            json.put("yaw", snapshot.yaw)
            json.put("battery", snapshot.batteryPercent)
            json.put("home_lat", snapshot.homeLat)
            json.put("home_lon", snapshot.homeLon)
            json.put("home_alt", snapshot.alt)
            json.put("compass", snapshot.compassHeading)

            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val pkt = DatagramPacket(payload, payload.size, addr, port)
            socket?.send(pkt)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send telemetry packet", t)
        }
    }
}