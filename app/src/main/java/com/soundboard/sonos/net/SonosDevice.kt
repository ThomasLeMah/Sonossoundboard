package com.soundboard.sonos.net

/**
 * A discovered Sonos player on the local network.
 *
 * @param roomName    Human-readable room / zone name (e.g. "Salon").
 * @param ip          The player's LAN IP address.
 * @param udn         Unique device name, e.g. "RINCON_XXXXXXXXXXXX01400" (no "uuid:" prefix).
 * @param modelName   Reported model, used to hint audioClip support.
 */
data class SonosDevice(
    val roomName: String,
    val ip: String,
    val udn: String,
    val modelName: String
) {
    /** The base URL of the local SOAP control endpoints (UPnP, no auth). */
    val soapBaseUrl: String get() = "http://$ip:1400"

    /** The base URL of the local control API (audioClip lives here, HTTPS + self-signed cert). */
    val localApiBaseUrl: String get() = "https://$ip:1443/api/v1"
}
