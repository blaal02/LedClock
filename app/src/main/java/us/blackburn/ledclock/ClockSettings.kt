package us.blackburn.ledclock

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager

enum class ClockOrientation(val labelRes: Int, val activityValue: Int) {
    SYSTEM(R.string.orientation_system, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR),
    LANDSCAPE(R.string.orientation_landscape, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    PORTRAIT(R.string.orientation_portrait, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    REVERSE_LANDSCAPE(R.string.orientation_reverse_landscape, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    REVERSE_PORTRAIT(R.string.orientation_reverse_portrait, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
}

/** Date line formats; [pattern] is a SimpleDateFormat pattern, or null to hide the date. */
enum class DateFormatOption(val pattern: String?) {
    NONE(null),
    LONG("EEEE, MMMM d, yyyy"),
    LONG_DAY_FIRST("EEEE d MMMM yyyy"),
    SHORT("EEE, MMM d"),
    NUMERIC_DMY("dd/MM/yyyy"),
    NUMERIC_MDY("MM/dd/yyyy"),
    ISO("yyyy-MM-dd"),
}

/** User preferences for the clock, persisted in SharedPreferences. */
data class ClockSettings(
    /** Index into [PRESET_COLORS], or [CUSTOM] to use [customColor]. */
    val colorIndex: Int = 1,
    val customColor: Int = PRESET_COLORS[1],
    val orientation: ClockOrientation = ClockOrientation.SYSTEM,
    val use24h: Boolean = false,
    val showSeconds: Boolean = true,
    val showWeather: Boolean = true,
    val dateFormat: DateFormatOption = DateFormatOption.LONG,
    /**
     * Brightness used in darkness (0..1); the light sensor scales up from here to full.
     * Without a light sensor this is the fixed brightness.
     */
    val minBrightness: Float = 0.20f,
) {
    val color: Int get() = if (colorIndex == CUSTOM) customColor else PRESET_COLORS[colorIndex]

    fun save(context: Context) {
        prefs(context).edit()
            .putInt(KEY_COLOR_INDEX, colorIndex)
            .putInt(KEY_CUSTOM_COLOR, customColor)
            .putString(KEY_ORIENTATION, orientation.name)
            .putBoolean(KEY_24H, use24h)
            .putBoolean(KEY_SECONDS, showSeconds)
            .putBoolean(KEY_WEATHER, showWeather)
            .putString(KEY_DATE_FORMAT, dateFormat.name)
            .putFloat(KEY_MIN_BRIGHTNESS, minBrightness)
            .apply()
    }

    companion object {
        const val CUSTOM = -1

        val PRESET_COLORS = intArrayOf(
            Color.rgb(255, 40, 30), Color.rgb(60, 255, 90), Color.rgb(40, 200, 255),
            Color.rgb(255, 170, 0), Color.rgb(255, 255, 255)
        )
        val PRESET_NAMES = intArrayOf(
            R.string.color_red, R.string.color_green, R.string.color_blue,
            R.string.color_amber, R.string.color_white
        )

        private const val PREFS = "clock_settings"
        private const val KEY_COLOR_INDEX = "color_index"
        private const val KEY_CUSTOM_COLOR = "custom_color"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_24H = "use_24h"
        private const val KEY_SECONDS = "show_seconds"
        private const val KEY_WEATHER = "show_weather"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_MIN_BRIGHTNESS = "min_brightness"

        const val MIN_BRIGHTNESS_FLOOR = 0.05f
        private const val NO_SENSOR_DEFAULT_BRIGHTNESS = 0.80f

        fun hasLightSensor(context: Context): Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): ClockSettings {
            val p = prefs(context)
            val d = if (hasLightSensor(context)) ClockSettings()
            else ClockSettings(minBrightness = NO_SENSOR_DEFAULT_BRIGHTNESS)
            return ClockSettings(
                colorIndex = p.getInt(KEY_COLOR_INDEX, d.colorIndex)
                    .let { if (it == CUSTOM || it in PRESET_COLORS.indices) it else d.colorIndex },
                customColor = p.getInt(KEY_CUSTOM_COLOR, d.customColor),
                orientation = runCatching {
                    ClockOrientation.valueOf(p.getString(KEY_ORIENTATION, null)!!)
                }.getOrDefault(d.orientation),
                use24h = p.getBoolean(KEY_24H, d.use24h),
                showSeconds = p.getBoolean(KEY_SECONDS, d.showSeconds),
                showWeather = p.getBoolean(KEY_WEATHER, d.showWeather),
                dateFormat = runCatching {
                    DateFormatOption.valueOf(p.getString(KEY_DATE_FORMAT, null)!!)
                }.getOrDefault(d.dateFormat),
                minBrightness = p.getFloat(KEY_MIN_BRIGHTNESS, d.minBrightness)
                    .coerceIn(MIN_BRIGHTNESS_FLOOR, 1f),
            )
        }
    }
}
