package com.rice.solarismatrice

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ImageStreamer(private val ip: String, private val port: Int) {

    private val TAG = "ImageStreamer"
    private val running = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null

    private val lock = Any()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(TAG, "ImageStreamer start -> $ip:$port")
        // connessione lazy: si apre al primo send
    }

    fun stop() {
        running.set(false)
        closeLocked()
        Log.i(TAG, "ImageStreamer stopped")
    }

    private fun ensureConnected(): DataOutputStream? {
        if (!running.get()) return null

        synchronized(lock) {
            out?.let { return it }

            return try {
                Log.i(TAG, "Connecting to $ip:$port ...")
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, port), 1500)

                val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
                socket = s
                out = o
                Log.i(TAG, "Connected to $ip:$port")
                o
            } catch (t: Throwable) {
                Log.w(TAG, "Connect failed to $ip:$port", t)
                closeLocked()
                null
            }
        }
    }

    private fun closeLocked() {
        synchronized(lock) {
            try { out?.close() } catch (_: Throwable) {}
            try { socket?.close() } catch (_: Throwable) {}
            out = null
            socket = null
        }
    }

    fun sendRawYuv(data: ByteArray, width: Int, height: Int) {
        if (!running.get()) return

        try {
            val s = ensureConnected() ?: return
            synchronized(lock) {
                s.writeInt(width)
                s.writeInt(height)
                s.writeInt(data.size)
                s.write(data)
                s.flush()
            }
        } catch (_: Throwable) {
            closeLocked()
        }
    }
}