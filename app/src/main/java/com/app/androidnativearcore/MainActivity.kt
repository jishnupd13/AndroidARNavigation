package com.app.androidnativearcore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.app.androidnativearcore.ui.theme.AndroidNativeARCoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidNativeARCoreTheme {
                // rememberSaveable: turning the phone does not show the splash again.
                var showSplash by rememberSaveable { mutableStateOf(true) }
                Crossfade(targetState = showSplash, label = "splash") { splash ->
                    if (splash) SplashScreen(onFinished = { showSplash = false }) else WayFinderDemoApp()
                }
            }
        }
    }
}
