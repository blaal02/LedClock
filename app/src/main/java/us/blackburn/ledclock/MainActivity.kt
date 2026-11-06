package us.blackburn.ledclock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : Activity() {

    private lateinit var clock: SevenSegmentClockView
    private var askedForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        clock = SevenSegmentClockView(this)
        clock.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        setContentView(clock)
    }

    override fun onStart() {
        super.onStart()
        val settings = ClockSettings.load(this)
        requestedOrientation = settings.orientation.activityValue
        clock.applySettings(settings)
        if (!settings.showWeather) {
            clock.stopWeather()
        } else if (clock.weatherPermissionGranted()) {
            clock.startWeather()
        } else if (!askedForPermission) {
            askedForPermission = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onStop() {
        clock.stopWeather()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            ClockSettings.load(this).showWeather
        ) clock.startWeather()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
