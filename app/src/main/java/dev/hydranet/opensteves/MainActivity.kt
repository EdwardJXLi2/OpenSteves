package dev.hydranet.opensteves

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hydranet.opensteves.data.TourRepository
import dev.hydranet.opensteves.playback.PlaybackConnection
import dev.hydranet.opensteves.ui.TourApp
import dev.hydranet.opensteves.ui.theme.AppThemeMode
import dev.hydranet.opensteves.ui.theme.OpenStevesTheme

class MainActivity : ComponentActivity() {
    private lateinit var playback: PlaybackConnection
    private val openPlaying = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playback = PlaybackConnection(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYING, false)) openPlaying.intValue++
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var themeMode by remember {
                mutableStateOf(AppThemeMode.entries.firstOrNull {
                    it.name == preferences.getString("theme_mode", null)
                } ?: AppThemeMode.SYSTEM)
            }
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            SideEffect {
                val bars = if (darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            OpenStevesTheme(themeMode) {
                TourApp(TourRepository.get(this), playback, themeMode = themeMode, onThemeModeChange = {
                    themeMode = it
                    preferences.edit().putString("theme_mode", it.name).apply()
                }, openPlaying = openPlaying.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYING, false)) openPlaying.intValue++
    }

    override fun onDestroy() {
        playback.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_PLAYING = "dev.hydranet.opensteves.OPEN_PLAYING"
    }
}
