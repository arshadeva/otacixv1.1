package id.oscarmandala.otacix

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.oscarmandala.otacix.ui.LocalTier
import id.oscarmandala.otacix.ui.LockLyricsScreen
import id.oscarmandala.otacix.ui.OtacixTheme

/**
 * Layar kunci lirik. Tampil DI ATAS layar kunci sistem (showWhenLocked) tanpa membukanya:
 * geser ke atas -> minta buka kunci (PIN/sidik jari) -> activity ditutup. Otomatis tertutup saat HP berhasil dibuka.
 */
class LockLyricsActivity : ComponentActivity() {
    private var resumed by mutableStateOf(false)

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        OtacixApp.player.connect()
        ContextCompat.registerReceiver(this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            OtacixTheme {
                CompositionLocalProvider(LocalTier provides OtacixApp.tier) {
                    LockLyricsScreen(resumed = resumed, onUnlock = { unlock() })
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userPresent) }
        super.onDestroy()
    }

    private fun unlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissError() { finish() }
            })
        } else finish()
    }

    companion object {
        const val NOTIF_ID = 7311
        const val CHANNEL = "lock_lyrics"
    }
}
