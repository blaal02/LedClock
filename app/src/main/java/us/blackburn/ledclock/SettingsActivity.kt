package us.blackburn.ledclock

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Clock configuration, opened by long-pressing the clock. Changes are saved immediately. */
class SettingsActivity : Activity() {

    private lateinit var settings: ClockSettings
    private val swatches = mutableListOf<SwatchView>()
    private lateinit var wheel: ColorWheelView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings_title)
        settings = ClockSettings.load(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        // ---- Colour ----
        content.addView(header(R.string.section_color))
        val swatchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ClockSettings.PRESET_COLORS.forEachIndexed { i, c ->
            swatchRow.addView(SwatchView(this, c).apply {
                contentDescription = getString(ClockSettings.PRESET_NAMES[i])
                setOnClickListener { selectColor(i) }
            }, swatchParams())
        }
        swatchRow.addView(SwatchView(this, null).apply {
            contentDescription = getString(R.string.color_custom)
            setOnClickListener { selectColor(ClockSettings.CUSTOM) }
        }, swatchParams())
        swatches += (0 until swatchRow.childCount).map { swatchRow.getChildAt(it) as SwatchView }
        content.addView(HorizontalScrollView(this).apply { addView(swatchRow) })

        wheel = ColorWheelView(this) { c ->
            settings = settings.copy(customColor = c)
            save()
            updateSwatches()
        }
        wheel.color = settings.customColor
        content.addView(wheel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })
        updateSwatches()

        // ---- Orientation ----
        content.addView(header(R.string.section_orientation))
        content.addView(radioGroup(
            labels = ClockOrientation.entries.map { getString(it.labelRes) },
            checked = settings.orientation.ordinal
        ) { i ->
            settings = settings.copy(orientation = ClockOrientation.entries[i])
            save()
        })

        // ---- Display ----
        content.addView(header(R.string.section_display))
        lateinit var amPmSwitch: Switch
        content.addView(switch(R.string.setting_24h, settings.use24h) {
            settings = settings.copy(use24h = it)
            save()
            amPmSwitch.isEnabled = !it
        })
        // AM/PM only applies to the 12-hour clock.
        amPmSwitch = switch(R.string.setting_ampm, settings.showAmPm) {
            settings = settings.copy(showAmPm = it)
            save()
        }.apply { isEnabled = !settings.use24h }
        content.addView(amPmSwitch)
        content.addView(switch(R.string.setting_seconds, settings.showSeconds) {
            settings = settings.copy(showSeconds = it)
            save()
        })
        content.addView(switch(R.string.setting_weather, settings.showWeather) {
            settings = settings.copy(showWeather = it)
            save()
        })
        content.addView(switch(R.string.setting_alarm, settings.showAlarm) {
            settings = settings.copy(showAlarm = it)
            save()
        })

        // ---- Brightness ----
        content.addView(header(R.string.section_brightness))
        content.addView(minBrightnessSlider())

        // ---- Date format (labelled with today's date as an example) ----
        content.addView(header(R.string.section_date))
        val today = Date()
        content.addView(radioGroup(
            labels = DateFormatOption.entries.map { option ->
                option.pattern?.let { SimpleDateFormat(it, Locale.getDefault()).format(today) }
                    ?: getString(R.string.date_none)
            },
            checked = settings.dateFormat.ordinal
        ) { i ->
            settings = settings.copy(dateFormat = DateFormatOption.entries[i])
            save()
        })

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }
        // The toolbar lives in our layout (theme has no window action bar), because the
        // framework action bar doesn't reliably keep content below it when edge-to-edge.
        val toolbar = Toolbar(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        // Edge-to-edge (enforced from API 35): keep clear of the system bars and cutouts. The
        // bottom inset goes on the scroll view so content can scroll behind the nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true) // back arrow; handled in onOptionsItemSelected
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save() = settings.save(this)

    /** Slider for the darkness brightness level, in whole percent from the floor up to 100%. */
    private fun minBrightnessSlider(): View {
        val floor = (ClockSettings.MIN_BRIGHTNESS_FLOOR * 100).toInt()
        val label = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        val labelRes = if (ClockSettings.hasLightSensor(this)) R.string.setting_min_brightness
        else R.string.setting_fixed_brightness
        fun showLabel(percent: Int) {
            label.text = getString(labelRes, percent)
        }
        val startPercent = (settings.minBrightness * 100).toInt()
        showLabel(startPercent)
        val slider = SeekBar(this).apply {
            max = 100 - floor
            progress = startPercent - floor
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    showLabel(progress + floor)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {
                    settings = settings.copy(minBrightness = (bar.progress + floor) / 100f)
                    save()
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun selectColor(index: Int) {
        settings = settings.copy(colorIndex = index)
        save()
        updateSwatches()
    }

    private fun updateSwatches() {
        swatches.forEachIndexed { i, swatch ->
            val index = if (i < ClockSettings.PRESET_COLORS.size) i else ClockSettings.CUSTOM
            swatch.isSelected = index == settings.colorIndex
            swatch.customColor = settings.customColor
        }
        wheel.visibility = if (settings.colorIndex == ClockSettings.CUSTOM) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ views

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun swatchParams() = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
        marginEnd = dp(10)
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun header(textRes: Int) = TextView(this).apply {
        setText(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.rgb(60, 255, 90))
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun switch(textRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            setText(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = checked
            minHeight = dp(48)
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

    private fun radioGroup(labels: List<String>, checked: Int, onChange: (Int) -> Unit) =
        RadioGroup(this).apply {
            val ids = labels.map { label ->
                RadioButton(context).apply {
                    id = View.generateViewId()
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    minHeight = dp(48)
                }.also { addView(it) }.id
            }
            check(ids[checked])
            setOnCheckedChangeListener { _, id -> onChange(ids.indexOf(id)) }
        }
}

/** Round colour swatch. A null [fill] draws a rainbow "custom" swatch showing [customColor]. */
private class SwatchView(context: Context, private val fill: Int?) : View(context) {

    var customColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rainbow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        rainbow.shader = SweepGradient(w / 2f, h / 2f, hues, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (fill != null) {
            paint.color = fill
            canvas.drawCircle(cx, cy, r * 0.72f, paint)
        } else {
            canvas.drawCircle(cx, cy, r * 0.72f, rainbow)
            paint.color = customColor
            canvas.drawCircle(cx, cy, r * 0.42f, paint)
        }
        if (isSelected) canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)
    }
}
