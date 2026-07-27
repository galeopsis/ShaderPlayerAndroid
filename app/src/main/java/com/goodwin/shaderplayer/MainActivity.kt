package com.goodwin.shaderplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.goodwin.shaderplayer.ui.ShaderPlayerScreen
import com.goodwin.shaderplayer.ui.ShaderPlayerViewModel
import com.goodwin.shaderplayer.ui.theme.ShaderPlayerTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Единственная Activity приложения; весь UI построен на Jetpack Compose. */
class MainActivity : ComponentActivity() {
    private val viewModel: ShaderPlayerViewModel by viewModels()

    private var isFullscreen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleViewIntent(intent)

        isFullscreen = savedInstanceState?.getBoolean(KEY_FULLSCREEN)
            ?: (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        applyFullscreenMode(isFullscreen)

        lifecycleScope.launch {
            viewModel.restartRequests.collect { restartApplication() }
        }

        setContent {
            ShaderPlayerTheme {
                ShaderPlayerScreen(
                    viewModel = viewModel,
                    isFullscreen = isFullscreen,
                    onFullscreenChange = ::updateFullscreen,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // В альбомной ориентации плеер автоматически занимает весь экран.
        updateFullscreen(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            applyFullscreenMode(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_FULLSCREEN, isFullscreen)
        super.onSaveInstanceState(outState)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.openShader(uri)
    }

    /** Полностью перезапускает процесс, чтобы Android заново выбрал GLES/ANGLE-драйвер. */
    private fun restartApplication() {
        val restartIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            restartIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 300L,
            pendingIntent,
        )
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    /** Включает или выключает полноэкранный режим и обновляет Compose-интерфейс. */
    private fun updateFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        applyFullscreenMode(enabled)
    }

    /** Скрывает системные панели в fullscreen и возвращает их при выходе. */
    private fun applyFullscreenMode(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val KEY_FULLSCREEN = "fullscreen"
    }
}
