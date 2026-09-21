package com.soundboard.sonos.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists the soundboard pads: audio files are copied into the app's private
 * `sounds/` directory and their metadata is stored in `sounds.json`.
 */
class SoundRepository(private val context: Context) {

    private val soundsDir: File = File(context.filesDir, "sounds").apply { mkdirs() }
    private val indexFile: File = File(context.filesDir, "sounds.json")

    private val palette = listOf(
        0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFFB8C00,
        0xFF8E24AA, 0xFF00ACC1, 0xFFD81B60, 0xFF6D4C41
    )

    fun load(): MutableList<Sound> {
        if (!indexFile.exists()) return mutableListOf()
        return try {
            val array = JSONArray(indexFile.readText())
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                Sound(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    fileName = o.getString("fileName"),
                    colorArgb = o.optLong("color", 0xFF1E88E5)
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(sounds: List<Sound>) {
        val array = JSONArray()
        sounds.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("label", s.label)
                    .put("fileName", s.fileName)
                    .put("color", s.colorArgb)
            )
        }
        indexFile.writeText(array.toString())
    }

    fun fileFor(sound: Sound): File = File(soundsDir, sound.fileName)

    fun fileForId(id: String, current: List<Sound>): File? =
        current.firstOrNull { it.id == id }?.let { fileFor(it) }

    /**
     * Copies [uri] into private storage and appends a new pad. Returns the updated list.
     */
    fun add(uri: Uri, existing: List<Sound>): List<Sound> {
        val id = UUID.randomUUID().toString()
        val displayName = queryDisplayName(uri) ?: "son"
        val ext = displayName.substringAfterLast('.', "").ifEmpty { guessExtension(uri) }
        val fileName = "$id.${ext.ifEmpty { "mp3" }}"
        val target = File(soundsDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return existing

        val label = displayName.substringBeforeLast('.').take(24).ifBlank { "Son" }
        val sound = Sound(
            id = id,
            label = label,
            fileName = fileName,
            colorArgb = palette[existing.size % palette.size]
        )
        val updated = existing + sound
        save(updated)
        return updated
    }

    fun rename(id: String, newLabel: String, existing: List<Sound>): List<Sound> {
        val updated = existing.map { if (it.id == id) it.copy(label = newLabel.take(24)) else it }
        save(updated)
        return updated
    }

    fun remove(id: String, existing: List<Sound>): List<Sound> {
        existing.firstOrNull { it.id == id }?.let { runCatching { fileFor(it).delete() } }
        val updated = existing.filterNot { it.id == id }
        save(updated)
        return updated
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun guessExtension(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: return "mp3"
        return when {
            type.contains("mpeg") -> "mp3"
            type.contains("wav") -> "wav"
            type.contains("ogg") -> "ogg"
            type.contains("aac") || type.contains("mp4") -> "m4a"
            type.contains("flac") -> "flac"
            else -> "mp3"
        }
    }
}
