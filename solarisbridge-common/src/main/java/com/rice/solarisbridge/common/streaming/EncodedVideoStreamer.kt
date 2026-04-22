package com.rice.solarisbridge.common.streaming

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EncodedVideoStreamer(
    private val host: String,
    private val port: Int,
    private val tag: String = "EncodedVideoStreamer"
) {
    companion object {
        private val MAGIC = byteArrayOf(
            'V'.code.toByte(),
            'S'.code.toByte(),
            'T'.code.toByte(),
            'R'.code.toByte()
        )
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: DataOutputStream? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.i(tag, "already started")
            return
        }

        ioExecutor.execute {
            ensureConnected()
        }
    }

    fun sendFrame(data: ByteArray, offset: Int, length: Int) {
        if (!running.get()) {
            Log.d(tag, "sendFrame skipped: not running")
            return
        }
        if (length <= 0) {
            Log.d(tag, "sendFrame skipped: invalid length")
            return
        }

        Log.d(tag, "sendFrame enqueue: length=$length")

        val frame = ByteArray(length)
        System.arraycopy(data, offset, frame, 0, length)

        ioExecutor.execute {
            if (!running.get()) {
                Log.d(tag, "sendFrame worker skipped: not running")
                return@execute
            }

            val out = ensureConnected() ?: run {
                Log.w(tag, "sendFrame skipped: connection not available")
                return@execute
            }

            try {
                out.write(MAGIC)
                out.writeInt(length)
                out.write(frame)
                out.flush()
                Log.d(tag, "frame sent: $length bytes")
            } catch (t: Throwable) {
                Log.w(tag, "frame send failed", t)
                stopInternal()
            }
        }
    }

    fun stop() {
        ioExecutor.execute {
            stopInternal()
        }
    }

    private fun ensureConnected(): DataOutputStream? {
        output?.let { return it }
        if (!running.get()) return null

        return try {
            Log.i(tag, "opening socket to $host:$port")

            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), 3000)

            val out = DataOutputStream(
                BufferedOutputStream(s.getOutputStream(), 256 * 1024)
            )

            socket = s
            output = out

            Log.i(tag, "socket connected")
            out
        } catch (t: Throwable) {
            Log.w(tag, "socket connect failed", t)
            stopInternal()
            null
        }
    }

    private fun stopInternal() {
        running.set(false)

        try {
            output?.flush()
        } catch (_: Throwable) {
        }

        try {
            output?.close()
        } catch (_: Throwable) {
        }

        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        output = null
        socket = null

        Log.i(tag, "Encoded streamer stopped")
    }
}