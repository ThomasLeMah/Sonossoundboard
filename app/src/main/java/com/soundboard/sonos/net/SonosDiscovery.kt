package com.soundboard.sonos.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Discovers Sonos players on the local network using SSDP (UPnP multicast search),
 * then enriches each hit by fetching its device description XML.
 */
object SonosDiscovery {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    private val mSearch: ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $SEARCH_TARGET\r\n" +
            "\r\n"
        ).toByteArray()

    /**
     * Broadcasts an SSDP search and returns the set of Sonos players that answered
     * within [timeoutMs] milliseconds.
     */
    suspend fun discover(timeoutMs: Int = 3000): List<SonosDevice> = withContext(Dispatchers.IO) {
        val locations = LinkedHashSet<String>()
        val socket = DatagramSocket().apply {
            soTimeout = 800
            broadcast = true
        }
        try {
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(mSearch, mSearch.size, group, SSDP_PORT)
            // Send the query a few times; UDP is lossy.
            repeat(3) { runCatching { socket.send(packet) } }

            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)
                    parseLocation(text)?.let { locations.add(it) }
                } catch (e: SocketTimeoutException) {
                    // keep looping until the deadline
                }
            }
        } catch (e: Exception) {
            // return whatever we have
        } finally {
            runCatching { socket.close() }
        }

        // De-duplicate by host and fetch details.
        locations
            .map { it }
            .distinctBy { runCatching { java.net.URL(it).host }.getOrNull() ?: it }
            .mapNotNull { fetchDevice(it) }
            .distinctBy { it.udn }
            .sortedBy { it.roomName.lowercase() }
    }

    private fun parseLocation(response: String): String? {
        return response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.takeIf { it.startsWith("http") }
    }

    private fun fetchDevice(location: String): SonosDevice? {
        return try {
            val request = Request.Builder().url(location).build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val host = java.net.URL(location).host
                val room = extractTag(xml, "roomName") ?: extractTag(xml, "friendlyName") ?: host
                val udn = (extractTag(xml, "UDN") ?: return null).removePrefix("uuid:")
                val model = extractTag(xml, "modelName") ?: "Sonos"
                SonosDevice(roomName = room, ip = host, udn = udn, modelName = model)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Minimal, dependency-free extraction of the first <tag>…</tag> value. */
    private fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim().takeIf { it.isNotEmpty() }
    }
}
