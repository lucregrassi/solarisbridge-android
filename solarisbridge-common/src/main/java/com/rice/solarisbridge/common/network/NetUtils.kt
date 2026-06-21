package com.rice.solarisbridge.common.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** Small networking helpers: local IPv4 discovery and IPv4 string validation. */
object NetUtils {
    /** Returns the first non-loopback IPv4 address of an up interface, or null. */
    fun getFirstLocalIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    /** True if [ip] is a dotted-quad IPv4 with each octet in 0..255. */
    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all {
            val n = it.toIntOrNull() ?: return false
            n in 0..255
        }
    }
}