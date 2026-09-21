package com.soundboard.sonos.net

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * A small embedded HTTP server that streams the imported sound files to the Sonos
 * speakers over the LAN. Sonos never plays a local file directly — it fetches a URL —
 * so the phone hosts the clips itself at http://<phone-ip>:<port>/clip/<id>.
 *
 * @param port      TCP port to listen on.
 * @param resolve   Maps a clip id (from the URL) to the backing file, or null if unknown.
 */
class ClipServer(
    port: Int,
    private val resolve: (String) -> File?
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (!uri.startsWith("/clip/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        val id = uri.removePrefix("/clip/").substringBefore('?')
        val file = resolve(id)
        if (file == null || !file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown clip")
        }

        val mime = mimeFor(file.name)
        val length = file.length()
        val rangeHeader = session.headers["range"]

        // Basic single-range support; Sonos usually fetches the whole (short) clip.
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            val start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = spec.getOrNull(1)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            if (start in 0 until length && end >= start) {
                val contentLength = end - start + 1
                val stream = FileInputStream(file).apply { skip(start) }
                val resp = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mime, stream, contentLength
                )
                resp.addHeader("Content-Range", "bytes $start-$end/$length")
                resp.addHeader("Accept-Ranges", "bytes")
                return resp
            }
        }

        val resp = newFixedLengthResponse(
            Response.Status.OK, mime, FileInputStream(file), length
        )
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a", "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    /** Starts the server if not already running. Returns true on success. */
    fun startSafely(): Boolean = try {
        if (!wasStarted()) start(SOCKET_READ_TIMEOUT, false)
        true
    } catch (e: Exception) {
        false
    }
}
