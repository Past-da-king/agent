package com.past9.phoneaos.screen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.past9.phoneaos.App
import com.past9.phoneaos.ui.screens.WatchHost
import com.past9.phoneaos.ui.theme.AppTheme

/**
 * The full-screen watch view of the background screen, as a small task of its own. It opens over whatever he was
 * doing without bringing the main app forward, and Hide or Back removes it, so he is back where he was (a video
 * still playing) while the job carries on. It asks for no audio and no focus beyond its own window.
 */
class ScreenWatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val st = App.graph(this).settings.state.value
        setContent {
            AppTheme(accent = st.accent, mascot = st.mascot) {
                WatchHost(st.agentName.ifBlank { "Your agent" }) { finishAndRemoveTask() }
            }
        }
    }

    companion object {
        fun open(context: Context) { runCatching { context.startActivity(Intent(context, ScreenWatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
