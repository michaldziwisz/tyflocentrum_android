package net.tyflopodcast.tyflocentrum

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import net.tyflopodcast.tyflocentrum.ui.TyflocentrumApp
import net.tyflopodcast.tyflocentrum.ui.theme.TyflocentrumTheme

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
