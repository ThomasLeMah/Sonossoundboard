package com.soundboard.sonos.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Tiny SOAP client for the Sonos UPnP control endpoints (port 1400, no authentication).
 * This is the mechanism used for the "interrupt & resume" fallback on speakers that do
 * not support audioClip.
 */
class SoapClient(private val baseUrl: String) {

    data class Service(val controlPath: String, val serviceType: String)

    companion object {
        val AV_TRANSPORT = Service(
            "/MediaRenderer/AVTransport/Control",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
        val RENDERING_CONTROL = Service(
            "/MediaRenderer/RenderingControl/Control",
            "urn:schemas-upnp-org:service:RenderingControl:1"
        )
        val ZONE_GROUP_TOPOLOGY = Service(
            "/ZoneGroupTopology/Control",
            "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        )
    }

    /** Outcome of a SOAP call: [ok] plus enough detail to explain a failure. */
    data class Reply(val ok: Boolean, val httpCode: Int, val body: String?, val error: String?) {
        /** Short, human-readable failure reason, or null on success. */
        fun reason(action: String): String? {
            if (ok) return null
            if (error != null) return "$action: $error"
            val upnp = body?.let { extractUpnp(it, "errorCode") }
            return if (upnp != null) "$action: HTTP $httpCode / UPnP $upnp" else "$action: HTTP $httpCode"
        }

        private fun extractUpnp(xml: String, tag: String): String? {
            val open = "<$tag>"; val close = "</$tag>"
            val s = xml.indexOf(open); if (s < 0) return null
            val e = xml.indexOf(close, s + open.length); if (e < 0) return null
            return xml.substring(s + open.length, e).trim()
        }
    }

    /**
     * Invokes a SOAP [action] on [service] with the given [args] (in order).
     * Returns the raw response body, or null on any HTTP/network failure.
     */
    fun invoke(service: Service, action: String, args: List<Pair<String, String>>): String? =
        call(service, action, args).let { if (it.ok) it.body else null }

    /** Like [invoke], but returns the full [Reply] so callers can report failures. */
    fun call(service: Service, action: String, args: List<Pair<String, String>>): Reply {
        val argXml = args.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" }
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="${service.serviceType}">$argXml</u:$action></s:Body>
</s:Envelope>"""

        return try {
            val request = Request.Builder()
                .url(baseUrl + service.controlPath)
                .addHeader("SOAPACTION", "\"${service.serviceType}#$action\"")
                .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                Reply(resp.isSuccessful, resp.code, body, null)
            }
        } catch (e: Exception) {
            Reply(false, 0, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    fun extract(xml: String?, tag: String): String? {
        if (xml == null) return null
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return unescape(xml.substring(start + open.length, end))
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
