package com.rice.solarisbridge.common.streaming

import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JpegFrameStreamer(
    private val textureView: TextureView,
    private val ip: String,
    private val port: Int,
    private val fps: Int = 8,
    private val jpegQuality: Int = 70,
    private val tag: String = "JpegFrameStreamer"
) {
    companion object {
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte())
        private const val TYPE_JPEG: Byte = 2
    }

    private val running = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var executor: ScheduledExecutorService? = null

    private val lock = Any()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val periodMs = (1000L / fps).coerceAtLeast(50L)

        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleAtFixedRate(
            { captureAndSend() },
            0,
            periodMs,
            TimeUnit.MILLISECONDS
        )

        Log.i(tag, "JpegFrameStreamer started -> $ip:$port fps=$fps")
    }

    fun stop() {
        running.set(false)

        try {
            executor?.shutdownNow()
        } catch (_: Throwable) {
        }
        executor = null

        closeLocked()
        Log.i(tag, "JpegFrameStreamer stopped")
    }

    private fun ensureConnected(): DataOutputStream? {
        if (!running.get()) return null

        synchronized(lock) {
            out?.let { return it }

            return try {
                Log.i(tag, "opening socket to $ip:$port")
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, port), 1500)

                val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
                socket = s
                out = o
                Log.i(tag, "socket connected")
                o
            } catch (t: Throwable) {
                Log.w(tag, "socket connect failed", t)
                closeLocked()
                null
            }
        }
    }

    private fun captureAndSend() {
        if (!running.get()) return
        if (!textureView.isAvailable) return

        try {
            val bitmap = textureView.bitmap ?: return
            sendBitmap(bitmap)
            bitmap.recycle()
        } catch (t: Throwable) {
            Log.w(tag, "captureAndSend failed", t)
        }
    }

    private fun sendBitmap(bitmap: Bitmap) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
        val jpegBytes = baos.toByteArray()

        val stream = ensureConnected() ?: return

        synchronized(lock) {
            try {
                stream.write(MAGIC)
                stream.writeByte(TYPE_JPEG.toInt())
                stream.writeInt(jpegBytes.size)
                stream.write(jpegBytes)
                stream.flush()
            } catch (t: Throwable) {
                Log.w(tag, "sendBitmap failed", t)
                closeLocked()
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
}