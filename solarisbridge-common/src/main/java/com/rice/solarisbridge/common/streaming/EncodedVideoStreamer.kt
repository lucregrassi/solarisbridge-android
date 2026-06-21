package com.rice.solarisbridge.common.streaming

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards already-encoded video frames (raw H.264 elementary stream) to the PC
 * over a TCP socket, without decoding or re-encoding them.
 *
 * Wire format (unchanged, big-endian, matches the PC receiver):
 *   MAGIC ("VSTR") + length (int32) + frame bytes
 *
 * Latency control:
 *   Frames are pushed into a bounded queue and drained by a dedicated sender
 *   thread. The producer (the DJI video callback) never blocks: if the network
 *   cannot keep up and the queue fills, the OLDEST queued frame is dropped so we
 *   always converge towards the freshest data instead of accumulating delay.
 *   (Dropping frames may cause brief artifacts until the next H.264 keyframe,
 *   which the drone emits periodically, so the picture self-heals.)
 */
class EncodedVideoStreamer(
    private val host: String,
    private val port: Int,
    private val maxQueuedFrames: Int = 12,
    private val connectTimeoutMs: Int = 3000,
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

    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(maxQueuedFrames)

    @Volatile private var senderThread: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.i(tag, "already started")
            return
        }

        queue.clear()

        val t = Thread({ senderLoop() }, "EncodedVideoStreamer-sender")
        t.isDaemon = true
        senderThread = t
        t.start()

        Log.i(tag, "started -> $host:$port (maxQueued=$maxQueuedFrames)")
    }

    /**
     * Enqueue an encoded frame for transmission. Non-blocking: safe to call from
     * the DJI video data callback thread. The [data] array is copied, so the
     * caller may reuse its buffer immediately.
     */
    fun sendFrame(data: ByteArray, offset: Int, length: Int) {
        if (!running.get() || length <= 0) return

        val frame = ByteArray(length)
        System.arraycopy(data, offset, frame, 0, length)

        if (!queue.offer(frame)) {
            // Queue full: network is behind. Drop the oldest frame to keep
            // latency bounded and prefer the freshest data.
            queue.poll()
            queue.offer(frame)
            Log.d(tag, "queue full: dropped oldest frame")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        senderThread?.interrupt()
        senderThread = null
        queue.clear()
        closeSocket()

        Log.i(tag, "stopped")
    }

    private fun senderLoop() {
        while (running.get()) {
            // Connect eagerly: as soon as start() is called we open the socket to
            // the PC, even before any frame is available. This makes the PC see
            // the incoming connection immediately and separates network problems
            // ("never connects") from "no video frames being produced".
            val out = ensureConnected() ?: continue

            val frame = try {
                queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }

            try {
                out.write(MAGIC)
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
            } catch (t: Throwable) {
                Log.w(tag, "frame send failed, will reconnect", t)
                closeSocket()
            }
        }
        closeSocket()
    }

    private fun ensureConnected(): DataOutputStream? {
        output?.let { return it }
        if (!running.get()) return null

        return try {
            Log.i(tag, "opening socket to $host:$port")

            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val out = DataOutputStream(
                BufferedOutputStream(s.getOutputStream(), 256 * 1024)
            )

            socket = s
            output = out

            Log.i(tag, "socket connected")
            out
        } catch (t: Throwable) {
            Log.w(tag, "socket connect failed", t)
            closeSocket()
            // Back off briefly before the loop retries, so we don't spin.
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            null
        }
    }

    private fun closeSocket() {
        try { output?.flush() } catch (_: Throwable) {}
        try { output?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        output = null
        socket = null
    }
}
