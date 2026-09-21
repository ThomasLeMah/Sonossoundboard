package com.soundboard.sonos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soundboard.sonos.data.Sound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundboardScreen(vm: SoundboardViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.status) {
        vm.status?.let {
            snackbar.showSnackbar(it)
            vm.status = null
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.addSound(uri) }

    var renameTarget by remember { mutableStateOf<Sound?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Sonos Soundboard", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.scan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescanner")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch("audio/*") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Ajouter un son") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            DevicePicker(vm)
            VolumeRow(vm)

            if (vm.sounds.isEmpty()) {
                EmptyState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vm.sounds, key = { it.id }) { sound ->
                        SoundPad(
                            sound = sound,
                            playing = vm.playingIds.contains(sound.id),
                            onTap = { vm.play(sound) },
                            onLongPress = { renameTarget = sound }
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            sound = target,
            onDismiss = { renameTarget = null },
            onRename = { vm.renameSound(target.id, it); renameTarget = null },
            onDelete = { vm.removeSound(target.id); renameTarget = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(vm: SoundboardViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = 8.dp)) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Speaker, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = "  " + (vm.selectedDevice?.roomName
                    ?: if (vm.isScanning) "Recherche…" else "Aucune enceinte"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (vm.devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Aucune enceinte — rescanner") },
                    onClick = { expanded = false; vm.scan() }
                )
            }
            vm.devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text("${device.roomName}  ·  ${device.ip}") },
                    onClick = { vm.selectDevice(device); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun VolumeRow(vm: SoundboardViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text("Volume SFX", fontSize = 13.sp)
        Slider(
            value = vm.clipVolume.toFloat(),
            onValueChange = { vm.setClipVolume(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Text("${vm.clipVolume}", fontSize = 13.sp)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SoundPad(
    sound: Sound,
    playing: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val base = Color(sound.colorArgb)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(base, RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = sound.label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
        if (playing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Aucun son pour l'instant.\nAppuie sur « Ajouter un son » pour importer un MP3/WAV.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RenameDialog(
    sound: Sound,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var text by remember { mutableStateOf(sound.label) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier le pad") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Nom") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(text.trim().ifEmpty { sound.label }) }) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
