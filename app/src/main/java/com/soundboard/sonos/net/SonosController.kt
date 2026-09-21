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
 *  2. **SOAP interrupt & resume** (UPnP, port 1400) — interrupts the selected speaker only
 *     (detaching it from its group first, if grouped), plays the clip, then resumes/rejoins.
 *
 * The audioClip probe is skipped for speakers known not to support it, and any speaker that
 * rejects it once is remembered for the rest of the session — so legacy speakers (Play:1/3/5
 * gen 1) never pay the probe cost twice.
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

    /** UDNs known not to support audioClip (so we skip the probe entirely). */
    private val noAudioClip = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Short-lived topology cache, keyed by the speaker's SOAP base URL. */
    private data class TopoCache(val at: Long, val groups: List<Topology.Group>)
    private val topoCache = HashMap<String, TopoCache>()
    private const val TOPO_TTL_MS = 6000L

    /**
     * @param clipUrl the http URL of the clip served by [ClipServer], reachable by the speaker.
     * @param clipVolume optional 0..100 volume for the clip (audioClip only).
     */
    suspend fun play(device: SonosDevice, clipUrl: String, clipVolume: Int? = null): Result =
        withContext(Dispatchers.IO) {
            if (!knownLegacy(device.modelName) && !noAudioClip.contains(device.udn)) {
                if (tryAudioClip(device, clipUrl, clipVolume)) {
                    return@withContext Result(true, Method.AUDIO_CLIP, "Joué par-dessus la musique")
                }
                noAudioClip.add(device.udn) // don't probe this speaker again this session
            }
            if (soapInterrupt(device, clipUrl)) {
                return@withContext Result(true, Method.SOAP, "Joué (interruption/reprise)")
            }
            Result(false, Method.NONE, "Impossible de joindre l'enceinte")
        }

    /** Legacy models that cannot mix audio (no audioClip); skip the probe for these. */
    private fun knownLegacy(model: String): Boolean {
        val m = model.uppercase()
        return listOf("PLAY:1", "PLAY:3", "PLAY:5", "PLAYBAR", "CONNECT", "ZP", "ZONEPLAYER")
            .any { m.contains(it) }
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

    private fun cachedGroups(soapBaseUrl: String): List<Topology.Group> {
        val now = System.currentTimeMillis()
        synchronized(topoCache) {
            topoCache[soapBaseUrl]?.let { if (now - it.at < TOPO_TTL_MS) return it.groups }
        }
        val fresh = Topology.groups(soapBaseUrl)
        synchronized(topoCache) { topoCache[soapBaseUrl] = TopoCache(now, fresh) }
        return fresh
    }

    private fun invalidateTopo(soapBaseUrl: String) {
        synchronized(topoCache) { topoCache.remove(soapBaseUrl) }
    }

    /**
     * Plays the clip on the *selected* speaker only, briefly interrupting it, then resumes.
     * If the speaker is in a multi-room group it is temporarily detached so the other rooms
     * keep playing, then re-joined once the clip finishes.
     */
    private suspend fun soapInterrupt(device: SonosDevice, clipUrl: String): Boolean {
        val soap = SoapClient(device.soapBaseUrl)
        val me = device.udn

        val myGroup = Topology.groupOf(cachedGroups(device.soapBaseUrl), me)
        val others = myGroup?.memberUdns?.filter { it != me }.orEmpty()

        if (others.isEmpty()) {
            // Standalone speaker: save its stream, play the clip, restore.
            return interruptStandalone(soap, clipUrl)
        }

        // Grouped: detach, then start the clip immediately (don't block on topology).
        val detached = soap.invoke(
            SoapClient.AV_TRANSPORT, "BecomeCoordinatorOfStandaloneGroup",
            listOf("InstanceID" to "0")
        )
        if (detached == null) return interruptStandalone(soap, clipUrl)
        invalidateTopo(device.soapBaseUrl)

        // The player needs a brief moment after leaving the group before it will accept
        // transport commands as a fresh standalone coordinator.
        delay(250)
        val ok = startClip(soap, clipUrl)

        // Work out which coordinator to rejoin. Fast path: if we were only a member, the
        // original coordinator still hosts the group — no extra query needed.
        val rejoin = if (myGroup!!.coordinatorUdn != me) {
            myGroup.coordinatorUdn
        } else {
            delay(150)
            Topology.groupOf(Topology.groups(device.soapBaseUrl), others.first())?.coordinatorUdn
                ?: others.first()
        }

        waitForClipEnd(soap)

        soap.invoke(
            SoapClient.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "x-rincon:$rejoin", "CurrentURIMetaData" to "")
        )
        invalidateTopo(device.soapBaseUrl)
        return ok
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

        val ok = startClip(soap, clipUrl)
        waitForClipEnd(soap)

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

    /**
     * Sets the clip URI and starts playback. Does not wait for the clip to finish.
     * Retries once, because a speaker can transiently reject the command right after a
     * grouping change.
     */
    private suspend fun startClip(soap: SoapClient, clipUrl: String): Boolean {
        repeat(2) { attempt ->
            val set = soap.invoke(
                SoapClient.AV_TRANSPORT, "SetAVTransportURI",
                listOf("InstanceID" to "0", "CurrentURI" to clipUrl, "CurrentURIMetaData" to "")
            )
            if (set != null) {
                soap.invoke(SoapClient.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
                return true
            }
            if (attempt == 0) delay(300)
        }
        return false
    }

    /** Waits (bounded) for the clip to finish so we can restore/rejoin cleanly. */
    private suspend fun waitForClipEnd(soap: SoapClient) {
        val instance = listOf("InstanceID" to "0")
        val maxWaitMs = 30_000L
        val start = System.currentTimeMillis()
        delay(250)
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val state = soap.extract(
                soap.invoke(SoapClient.AV_TRANSPORT, "GetTransportInfo", instance),
                "CurrentTransportState"
            )
            if (state == null || state == "STOPPED" || state == "PAUSED_PLAYBACK") break
            delay(300)
        }
    }
}
