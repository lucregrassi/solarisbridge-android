package com.rice.solarismatrice

import java.net.InetSocketAddress
import java.net.Socket

object SocketTester {
    fun canConnect(ip: String, port: Int, timeoutMs: Int = 1500): Boolean {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            return true
        }
    }
}