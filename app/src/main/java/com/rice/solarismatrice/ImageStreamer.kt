package com.rice.solarismatrice

import java.io.DataOutputStream
import java.net.Socket

class ImageStreamer(
    private val ip: String,
    private val port: Int
) {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    @Volatile private var running = false

    fun start() {
        running = true
        Thread {
            try {
                socket = Socket(ip, port)
                out = DataOutputStream(socket!!.getOutputStream())
            } catch (e: Exception) {
                stop()
            }
        }.start()
    }

    fun sendJpeg(jpeg: ByteArray) {
        if (!running || out == null) return
        try {
            out!!.writeInt(jpeg.size)
            out!!.write(jpeg)
            out!!.flush()
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        running = false
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
    }
}