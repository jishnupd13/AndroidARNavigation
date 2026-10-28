package com.app.androidnativearcore

import android.content.Intent
import android.net.Uri
import android.os.Build
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
    /** A data bundle (.zip) the app was opened with or shared, waiting for the user to confirm the import. */
    private val incomingBundle = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not again after turning the phone: the intent is still the one the app was started with.
        if (savedInstanceState == null) incomingBundle.value = bundleIn(intent)
        enableEdgeToEdge()
        setContent {
            AndroidNativeARCoreTheme {
                // rememberSaveable: turning the phone does not show the splash again.
                var showSplash by rememberSaveable { mutableStateOf(true) }
                Crossfade(targetState = showSplash, label = "splash") { splash ->
                    if (splash) {
                        SplashScreen(onFinished = { showSplash = false })
                    } else {
                        WayFinderDemoApp(incomingBundle.value, onIncomingHandled = { incomingBundle.value = null })
                    }
                }
            }
        }
    }

    /** The app is already open (launch mode singleTask) and a file manager opens or shares a bundle with it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bundleIn(intent)?.let { incomingBundle.value = it }
    }

    /** The .zip in an "Open with" (VIEW) or "Share" (SEND) intent, or null for an ordinary launch. */
    private fun bundleIn(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}
