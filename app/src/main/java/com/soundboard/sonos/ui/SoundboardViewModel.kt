package com.soundboard.sonos.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundboard.sonos.data.Sound
import com.soundboard.sonos.data.SoundRepository
import com.soundboard.sonos.net.ClipServer
import com.soundboard.sonos.net.NetworkUtils
import com.soundboard.sonos.net.SonosController
import com.soundboard.sonos.net.SonosDevice
import com.soundboard.sonos.net.SonosDiscovery
import kotlinx.coroutines.launch

class SoundboardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SoundRepository(app)

    val sounds = mutableStateListOf<Sound>()
    val devices = mutableStateListOf<SonosDevice>()

    var selectedDevice by mutableStateOf<SonosDevice?>(null)
        private set
    var isScanning by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
    var clipVolume by mutableStateOf(40)
        private set
    val playingIds = mutableStateListOf<String>()

    private val multicastLock =
        (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("sonos-soundboard")
            ?.apply { setReferenceCounted(true) }

    private val server: ClipServer = ClipServer(0) { id ->
        repo.fileForId(id, sounds.toList())
    }
    private var serverPort: Int = -1

    init {
        sounds.addAll(repo.load())
        if (server.startSafely()) serverPort = server.listeningPort
        scan()
    }

    fun scan() {
        if (isScanning) return
        isScanning = true
        status = "Recherche des enceintes…"
        viewModelScope.launch {
            runCatching { multicastLock?.acquire() }
            try {
                val found = SonosDiscovery.discover()
                devices.clear()
                devices.addAll(found)
                if (selectedDevice == null || devices.none { it.udn == selectedDevice?.udn }) {
                    selectedDevice = devices.firstOrNull()
                }
                status = when {
                    devices.isEmpty() -> "Aucune enceinte Sonos trouvée sur le Wi-Fi"
                    else -> "${devices.size} enceinte(s) trouvée(s)"
                }
            } finally {
                runCatching { multicastLock?.release() }
                isScanning = false
            }
        }
    }

    fun selectDevice(device: SonosDevice) {
        selectedDevice = device
    }

    fun updateClipVolume(value: Int) {
        clipVolume = value.coerceIn(0, 100)
    }

    fun addSound(uri: Uri) {
        viewModelScope.launch {
            val current = sounds.toList()
            val updated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.add(uri, current)
            }
            sounds.clear()
            sounds.addAll(updated)
        }
    }

    fun renameSound(id: String, label: String) {
        val updated = repo.rename(id, label, sounds.toList())
        sounds.clear()
        sounds.addAll(updated)
    }

    fun removeSound(id: String) {
        val updated = repo.remove(id, sounds.toList())
        sounds.clear()
        sounds.addAll(updated)
    }

    fun play(sound: Sound) {
        val device = selectedDevice
        if (device == null) {
            status = "Sélectionne d'abord une enceinte"
            return
        }
        val ip = NetworkUtils.localIpv4()
        if (ip == null || serverPort <= 0) {
            status = "Connexion Wi-Fi requise"
            return
        }
        val clipUrl = "http://$ip:$serverPort/clip/${sound.id}"
        playingIds.add(sound.id)
        viewModelScope.launch {
            val result = SonosController.play(device, clipUrl, clipVolume)
            playingIds.remove(sound.id)
            status = if (result.success) {
                "${sound.label} → ${device.roomName} · ${result.message}"
            } else {
                "Échec : ${result.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { server.stop() }
    }
}
