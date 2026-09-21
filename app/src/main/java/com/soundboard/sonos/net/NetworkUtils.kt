package com.soundboard.sonos.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Returns the phone's own IPv4 address on the local (Wi-Fi) network, or null if it
     * can't be determined. This is the address the Sonos speakers use to fetch clips
     * from the embedded [ClipServer].
     */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                // Prefer wlan/wifi interfaces, but accept any usable one as a fallback.
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
