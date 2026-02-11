package com.rice.solarismatrice

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    fun getLocalIpv4(): String? {
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

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all {
            val n = it.toIntOrNull() ?: return false
            n in 0..255
        }
    }
}