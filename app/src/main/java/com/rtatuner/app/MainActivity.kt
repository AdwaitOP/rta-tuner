package com.rtatuner.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rtatuner.app.ui.AudioCaptureViewModel
import com.rtatuner.app.ui.DebugScreen

class MainActivity : ComponentActivity() {

    private val audioViewModel: AudioCaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during audio tuning / measurement sessions
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F1115),
                    surface = Color(0xFF161A22),
                    primary = Color(0xFF00E676),
                    error = Color(0xFFFF1744)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DebugScreen(viewModel = audioViewModel)
                }
            }
        }
    }
}
