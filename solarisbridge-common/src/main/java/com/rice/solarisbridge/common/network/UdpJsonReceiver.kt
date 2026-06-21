package com.rice.solarisbridge.common.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background UDP listener. Binds [port] on a daemon thread and invokes [onJson] for every datagram
 * with the decoded (trimmed, UTF-8) text and the sender "ip:port". start()/stop() are idempotent;
 * stop() closes the socket, which unblocks the receive loop.
 */
class UdpJsonReceiver(
    private val port: Int,
    private val tag: String,
    private val onJson: (json: String, from: String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return

        thread = Thread {
            try {
                val s = DatagramSocket(port)
                socket = s
                Log.i(tag, "Listening UDP on port $port")

                val buf = ByteArray(4096)
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)

                    val msg = p.data.copyOfRange(p.offset, p.offset + p.length)
                        .toString(Charsets.UTF_8)
                        .trim()

                    val fromIp = p.address?.hostAddress ?: "?"
                    val fromPort = p.port
                    onJson(msg, "$fromIp:$fromPort")
                }
            } catch (e: SocketException) {
                Log.i(tag, "Socket closed: ${e.message}")
            } catch (t: Throwable) {
                Log.e(tag, "Receiver error", t)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
                socket = null
                running.set(false)
                Log.i(tag, "Receiver stopped")
            }
        }.apply { name = "udp-rx-$port"; isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
    }

    fun isRunning(): Boolean = running.get()
}