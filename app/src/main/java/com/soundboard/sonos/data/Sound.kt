package com.soundboard.sonos.data

/**
 * A soundboard pad backed by an imported audio file stored in the app's private storage.
 *
 * @param id       Stable unique id (also the clip id served over HTTP).
 * @param label    User-facing name shown on the pad.
 * @param fileName File name inside the app's `sounds/` directory.
 * @param colorArgb Pad accent color.
 */
data class Sound(
    val id: String,
    val label: String,
    val fileName: String,
    val colorArgb: Long
)
