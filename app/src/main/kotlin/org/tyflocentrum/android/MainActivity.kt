package org.tyflocentrum.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import org.tyflocentrum.android.ui.TyflocentrumApp
import org.tyflocentrum.android.ui.theme.TyflocentrumTheme

class MainActivity : FragmentActivity() {
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
