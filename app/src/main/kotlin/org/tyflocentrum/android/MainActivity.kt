package org.tyflocentrum.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.tyflocentrum.android.ui.TyflocentrumApp
import org.tyflocentrum.android.ui.theme.TyflocentrumTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TyflocentrumTheme {
                TyflocentrumApp(
                    appContainer = (application as TyflocentrumApplication).appContainer
                )
            }
        }
    }
}
