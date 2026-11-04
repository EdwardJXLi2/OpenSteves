package dev.hydranet.opensteves

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.hydranet.opensteves.data.TourRepository
import dev.hydranet.opensteves.playback.PlaybackConnection
import dev.hydranet.opensteves.ui.TourApp
import dev.hydranet.opensteves.ui.theme.OpenStevesTheme

class MainActivity : ComponentActivity() {
    private lateinit var playback: PlaybackConnection
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playback = PlaybackConnection(this)
        setContent {
            OpenStevesTheme {
                TourApp(TourRepository.get(this), playback)
            }
        }
    }

    override fun onDestroy() {
        playback.close()
        super.onDestroy()
    }
}
