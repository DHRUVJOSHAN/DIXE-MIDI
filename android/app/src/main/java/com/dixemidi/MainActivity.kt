package com.dixemidi

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dixemidi.midi.MidiConnectionManager
import com.dixemidi.midi.MidiStateController
import com.dixemidi.ui.DixeControllerView

class MainActivity : AppCompatActivity() {

    private lateinit var connectionManager: MidiConnectionManager
    private lateinit var stateController: MidiStateController
    private lateinit var controllerView: DixeControllerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        connectionManager = MidiConnectionManager(applicationContext, channel = 0)
        val prefs = applicationContext.getSharedPreferences("dixe_midi_prefs", MODE_PRIVATE)
        stateController = MidiStateController(
            connectionManager = connectionManager,
            onStateChanged = {
                if (::controllerView.isInitialized) {
                    controllerView.invalidate()
                }
            },
            initialChannel = 0,
            prefs = prefs
        )

        controllerView = DixeControllerView(this, stateController)
        setContentView(controllerView)
    }

    override fun onStart() {
        super.onStart()
        connectionManager.start()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
        )
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        stateController.releaseActiveTouches()
        super.onPause()
    }

    override fun onStop() {
        stateController.releaseAll()
        connectionManager.stop()
        super.onStop()
    }
}
