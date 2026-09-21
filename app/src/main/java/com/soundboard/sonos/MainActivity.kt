package com.soundboard.sonos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.soundboard.sonos.ui.SoundboardScreen

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF06283B),
    surface = Color(0xFF1A1C22),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E6),
    onSurface = Color(0xFFE3E2E6),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = Color(0xFFEF9A9A)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SoundboardScreen()
                }
            }
        }
    }
}
