package us.blackburn.ledclock

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherData(val code: Int, val isDay: Boolean, val temp: Int)

/** Fetches current conditions from Open-Meteo using the device's coarse location. */
class WeatherProvider(
    private val context: Context,
    private val onUpdate: (WeatherData) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            locateAndFetch()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun hasPermission() = context.checkSelfPermission(
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !hasPermission()) return
        running = true
        handler.post(refresh)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun locateAndFetch() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) {
            fetch(last.latitude, last.longitude)
            return
        }
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return
        }
        @Suppress("DEPRECATION")
        lm.requestSingleUpdate(provider, object : LocationListener {
            override fun onLocationChanged(location: Location) = fetch(location.latitude, location.longitude)
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }, Looper.getMainLooper())
    }

    private fun fetch(lat: Double, lon: Double) {
        val unit = if (Locale.getDefault().country == "US") "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit"
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                val data = WeatherData(
                    code = cur.getInt("weather_code"),
                    isDay = cur.getInt("is_day") == 1,
                    temp = cur.getDouble("temperature_2m").roundToInt()
                )
                handler.post { if (running) onUpdate(data) }
            } catch (_: Exception) {
                // keep showing the last good reading
            }
        }.start()
    }

    companion object {
        private const val REFRESH_MS = 15 * 60 * 1000L
    }
}
