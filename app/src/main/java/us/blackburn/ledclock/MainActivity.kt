package us.blackburn.ledclock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private var locationIntro: AlertDialog? = null

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
        } else if (!ClockSettings.locationIntroShown(this)) {
            showLocationIntro()
        } else if (!askedForPermission) {
            requestLocationPermission()
        }
    }

    override fun onDestroy() {
        locationIntro?.dismiss()
        super.onDestroy()
    }

    /**
     * First-run explanation shown before the system prompt. Declining turns weather off;
     * re-enabling it in settings asks for the permission again.
     */
    private fun showLocationIntro() {
        if (locationIntro?.isShowing == true) return
        locationIntro = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.location_intro_title)
            .setMessage(R.string.location_intro_message)
            .setCancelable(false)
            .setPositiveButton(R.string.location_intro_continue) { _, _ ->
                ClockSettings.setLocationIntroShown(this)
                requestLocationPermission()
            }
            .setNegativeButton(R.string.location_intro_decline) { _, _ ->
                ClockSettings.setLocationIntroShown(this)
                val settings = ClockSettings.load(this).copy(showWeather = false)
                settings.save(this)
                clock.applySettings(settings)
            }
            .show()
    }

    private fun requestLocationPermission() {
        askedForPermission = true
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
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
