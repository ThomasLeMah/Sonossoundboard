package com.soundboard.sonos.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends a sound clip to a Sonos player.
 *
 * Two strategies, tried in order:
 *  1. **audioClip** (local control API, port 1443) — plays the clip *over* the music with
 *     automatic ducking, then the music continues. Supported on modern (S2) speakers.
 *  2. **SOAP interrupt & resume** (UPnP, port 1400) — saves the current track, plays the
 *     clip, then restores and resumes the music. Works on every Sonos, but briefly pauses
 *     the music.
 */
object SonosController {

    /**
     * Well-known development API key accepted by the Sonos local control API for audioClip
     * on the LAN. If a speaker rejects it, playback transparently falls back to SOAP.
     */
    private const val LOCAL_API_KEY = "123e4567-e89b-12d3-a456-426655440000"
    private const val APP_ID = "com.soundboard.sonos"

    enum class Method { AUDIO_CLIP, SOAP, NONE }

    data class Result(val success: Boolean, val method: Method, val message: String)

    /**
     * @param clipUrl the http URL of the clip served by [ClipServer], reachable by the speaker.
     * @param clipVolume optional 0..100 volume for the clip (audioClip only).
     */
    suspend fun play(device: SonosDevice, clipUrl: String, clipVolume: Int? = null): Result =
        withContext(Dispatchers.IO) {
            if (tryAudioClip(device, clipUrl, clipVolume)) {
                return@withContext Result(true, Method.AUDIO_CLIP, "Joué par-dessus la musique")
            }
            if (soapInterrupt(device, clipUrl)) {
                return@withContext Result(true, Method.SOAP, "Joué (interruption/reprise)")
            }
            Result(false, Method.NONE, "Impossible de joindre l'enceinte")
        }

    private fun tryAudioClip(device: SonosDevice, clipUrl: String, clipVolume: Int?): Boolean {
        return try {
            val volumeField = clipVolume?.let { ",\"volume\":${it.coerceIn(0, 100)}" } ?: ""
            val json = """{"name":"Soundboard","appId":"$APP_ID","streamUrl":"$clipUrl"$volumeField}"""
            val url = "${device.localApiBaseUrl}/players/${device.udn}/audioClip"
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Sonos-Api-Key", LOCAL_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            Http.localApiClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Plays the clip on the *selected* speaker only, briefly interrupting it, then resumes.
     *
     * If the speaker is part of a multi-room group, it is temporarily detached so the other
     * rooms keep playing the music uninterrupted, then re-joined once the clip has finished.
     */
    private suspend fun soapInterrupt(device: SonosDevice, clipUrl: String): Boolean {
        val soap = SoapClient(device.soapBaseUrl)
        val me = device.udn

        val myGroup = Topology.groupOf(Topology.groups(device.soapBaseUrl), me)
        val others = myGroup?.memberUdns?.filter { it != me }.orEmpty()

        return if (others.isEmpty()) {
            // Standalone speaker: save its stream, play the clip, restore.
            interruptStandalone(soap, clipUrl)
        } else {
            // Grouped speaker: detach, play only here, then rejoin the group.
            interruptWithinGroup(soap, device, others)?.let { rejoin ->
                val ok = playClipAndWait(soap, clipUrl)
                soap.invoke(
                    SoapClient.AV_TRANSPORT, "SetAVTransportURI",
                    listOf("InstanceID" to "0", "CurrentURI" to "x-rincon:$rejoin", "CurrentURIMetaData" to "")
                )
                ok
            } ?: interruptStandalone(soap, clipUrl)
        }
    }

    /** Detaches this speaker from its group and returns the coordinator to rejoin afterwards. */
    private suspend fun interruptWithinGroup(
        soap: SoapClient,
        device: SonosDevice,
        others: List<String>
    ): String? {
        soap.invoke(
            SoapClient.AV_TRANSPORT, "BecomeCoordinatorOfStandaloneGroup",
            listOf("InstanceID" to "0")
        ) ?: return null
        delay(300)
        // After detaching, find which coordinator now hosts the remaining members.
        val after = Topology.groups(device.soapBaseUrl)
        return Topology.groupOf(after, others.first())?.coordinatorUdn ?: others.first()
    }

    private suspend fun interruptStandalone(soap: SoapClient, clipUrl: String): Boolean {
        val instance = listOf("InstanceID" to "0")
        val posInfo = soap.invoke(SoapClient.AV_TRANSPORT, "GetPositionInfo", instance)
        val savedUri = soap.extract(posInfo, "TrackURI")
        val savedMeta = soap.extract(posInfo, "TrackMetaData") ?: ""
        val savedTime = soap.extract(posInfo, "RelTime")
        val savedState = soap.extract(
            soap.invoke(SoapClient.AV_TRANSPORT, "GetTransportInfo", instance),
            "CurrentTransportState"
        )

        val ok = playClipAndWait(soap, clipUrl)

        if (!savedUri.isNullOrEmpty()) {
            soap.invoke(
                SoapClient.AV_TRANSPORT, "SetAVTransportURI",
                listOf("InstanceID" to "0", "CurrentURI" to savedUri, "CurrentURIMetaData" to savedMeta)
            )
            if (!savedTime.isNullOrEmpty()) {
                soap.invoke(
                    SoapClient.AV_TRANSPORT, "Seek",
                    listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to savedTime)
                )
            }
            if (savedState == "PLAYING" || savedState == "TRANSITIONING") {
                soap.invoke(SoapClient.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
            }
        }
        return ok
    }

    /** Sets the clip URI, plays it, and waits (bounded) for it to finish. */
    private suspend fun playClipAndWait(soap: SoapClient, clipUrl: String): Boolean {
        val instance = listOf("InstanceID" to "0")
        val set = soap.invoke(
            SoapClient.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to clipUrl, "CurrentURIMetaData" to "")
        ) ?: return false
        soap.invoke(SoapClient.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1")) ?: return false

        val maxWaitMs = 30_000L
        val start = System.currentTimeMillis()
        delay(400)
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val state = soap.extract(
                soap.invoke(SoapClient.AV_TRANSPORT, "GetTransportInfo", instance),
                "CurrentTransportState"
            )
            if (state == null || state == "STOPPED" || state == "PAUSED_PLAYBACK") break
            delay(500)
        }
        return set.isNotEmpty()
    }
}
