package us.blackburn.ledclock

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Full-screen 7-segment LED clock:  HH:MM (large) + SS (1/3 size), day/date and weather below.
 * Appearance is driven by [applySettings]. Content stays clear of any display cutout (camera).
 * Ambient light sensor automatically adjusts display brightness.
 */
class SevenSegmentClockView(context: Context) : View(context), SensorEventListener {

    private enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    // Segment bits: A(top) B(top-right) C(bottom-right) D(bottom) E(bottom-left) F(top-left) G(middle)
    private val digitMasks = intArrayOf(
        0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110,
        0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111
    )

    private var settings = ClockSettings()
    private var brightnessFactor = 1.0f
    private var lastLux: Float? = null
    private val hasLightSensor = ClockSettings.hasLightSensor(context)

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val path = Path()
    private var dateFormat: SimpleDateFormat? = settings.dateFormat.pattern?.let {
        SimpleDateFormat(it, Locale.getDefault())
    }

    /** Display-cutout safe insets; the layout is fitted inside the remaining area. */
    private var cutout = Insets.NONE

    private var weather: WeatherData? = null
    private val weatherProvider = WeatherProvider(context) {
        weather = it
        invalidate()
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L) + 5)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for BlurMaskFilter glow / Path.op
        applyColor()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            invalidate()
            insets
        }
    }

    fun applySettings(newSettings: ClockSettings) {
        settings = newSettings
        dateFormat = newSettings.dateFormat.pattern?.let { SimpleDateFormat(it, Locale.getDefault()) }
        if (!newSettings.showWeather) weather = null
        if (!hasLightSensor) brightnessFactor = newSettings.minBrightness
        lastLux?.let { brightnessFactor = brightnessForLux(it) }
        applyColor()
        invalidate()
    }

    fun weatherPermissionGranted() = weatherProvider.hasPermission()
    fun startWeather() = weatherProvider.start()
    fun stopWeather() = weatherProvider.stop()

    private fun applyColor() {
        val c = settings.color
        val bf = brightnessFactor.coerceIn(settings.minBrightness, 1.0f)
        val r = (Color.red(c) * bf).toInt().coerceIn(0, 255)
        val g = (Color.green(c) * bf).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) * bf).toInt().coerceIn(0, 255)
        val dimmedColor = Color.rgb(r, g, b)

        onPaint.color = dimmedColor
        glowPaint.color = dimmedColor
        val offAlpha = (28 * bf).toInt().coerceIn(5, 255)
        offPaint.color = Color.argb(offAlpha, r, g, b)

        // Text & weather icon stroke: maintain crisp readability at full opacity
        val textBf = brightnessFactor.coerceIn(settings.minBrightness, 1.0f)
        val tr = (Color.red(c) * textBf).toInt().coerceIn(0, 255)
        val tg = (Color.green(c) * textBf).toInt().coerceIn(0, 255)
        val tb = (Color.blue(c) * textBf).toInt().coerceIn(0, 255)
        textPaint.color = Color.rgb(tr, tg, tb)
        strokePaint.color = Color.rgb(tr, tg, tb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDetachedFromWindow() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.unregisterListener(this)
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            lastLux = lux
            val target = brightnessForLux(lux)

            if (abs(target - brightnessFactor) > 0.01f) {
                brightnessFactor = target
                applyColor()
                invalidate()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Log-scales ambient light to a brightness between the user's minimum and full. */
    private fun brightnessForLux(lux: Float): Float {
        val minBrightness = settings.minBrightness
        val maxBrightness = 1.0f
        val maxLux = 500f
        val normalizedRatio = (ln(lux.coerceAtLeast(0f) + 1f) / ln(maxLux + 1f)).coerceIn(0f, 1f)
        return minBrightness + normalizedRatio * (maxBrightness - minBrightness)
    }

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance()
        var hour = now.get(Calendar.HOUR_OF_DAY)
        if (!settings.use24h) {
            hour %= 12
            if (hour == 0) hour = 12
        }
        val minute = now.get(Calendar.MINUTE)
        val sec = now.get(Calendar.SECOND)

        // ---- Layout, in units of one big-digit width (s) ----
        // Row 1: [H][H] [:] [M][M]  gap  [s][s] (seconds at 1/3 size, bottom aligned)
        // In 12h mode the leading hour digit is only ever "1", so it shrinks to its right-hand
        // segments and the freed width goes to the remaining digits.
        val digitGap = 0.16f
        val colonWidth = 0.22f
        val secGap = 0.18f
        val leadWidth = if (settings.use24h) 1f else SEGMENT_THICKNESS
        // The slant pushes the bottom of the leading digit left of its box; reserve room for it.
        val slantOverhang = SLANT * 1.8f / 2f
        val secondsWidth = if (settings.showSeconds) secGap + (2f + digitGap) / 3f else 0f
        val totalUnitsW = slantOverhang + leadWidth + 3f + colonWidth + 4 * digitGap + secondsWidth
        // The info row is dropped entirely when there's nothing to show in it.
        val showInfo = settings.showWeather || dateFormat != null
        val totalUnitsH = if (showInfo) 1.8f + 0.10f + 0.35f else 1.8f // time + gap + info row
        // Fit inside the area not covered by a camera cutout.
        val areaL = cutout.left.toFloat()
        val areaT = cutout.top.toFloat()
        val areaW = (width - cutout.left - cutout.right).toFloat()
        val areaH = (height - cutout.top - cutout.bottom).toFloat()
        val s = min(areaW * 0.94f / totalUnitsW, areaH * 0.88f / totalUnitsH)

        val w = s
        val h = 1.8f * s
        val gap = digitGap * s
        val colonW = colonWidth * s
        val t = w * SEGMENT_THICKNESS
        val sw = w / 3f
        val sh = h / 3f
        val st = t / 3f * 1.15f
        val sgap = gap / 3f

        val x0 = areaL + (areaW - totalUnitsW * s) / 2f + slantOverhang * s
        val y0 = areaT + (areaH - totalUnitsH * s) / 2f

        // ---- Time row (slanted like LED displays) ----
        canvas.save()
        canvas.skew(-SLANT, 0f)
        canvas.translate(SLANT * (y0 + h / 2f), 0f)

        var x = x0
        glowPaint.maskFilter = BlurMaskFilter(t * 0.8f, BlurMaskFilter.Blur.NORMAL)

        val h1 = hour / 10
        if (h1 > 0) {
            drawDigit(canvas, x, y0, leadWidth * s, h, t, h1, drawOffSegments = false)
        }
        x += leadWidth * s + gap
        drawDigit(canvas, x, y0, w, h, t, hour % 10); x += w + gap
        drawColon(canvas, x, y0, colonW, h, t); x += colonW + gap
        drawDigit(canvas, x, y0, w, h, t, minute / 10); x += w + gap
        drawDigit(canvas, x, y0, w, h, t, minute % 10); x += w + secGap * s

        if (settings.showSeconds) {
            glowPaint.maskFilter = BlurMaskFilter(st * 0.8f, BlurMaskFilter.Blur.NORMAL)
            val secY = y0 + h - sh
            drawDigit(canvas, x, secY, sw, sh, st, sec / 10); x += sw + sgap
            drawDigit(canvas, x, secY, sw, sh, st, sec % 10)
        }
        canvas.restore()

        if (showInfo) drawInfoRow(canvas, y0 + (1.8f + 0.10f) * s + 0.175f * s, s, areaL, areaW)
    }

    /** Weather icon + temperature, then day/date, centred on [rowCy] within [areaL]..+[areaW]. */
    private fun drawInfoRow(canvas: Canvas, rowCy: Float, s: Float, areaL: Float, areaW: Float) {
        val dateText = dateFormat?.format(Date()) ?: ""
        val wx = if (settings.showWeather) weather else null
        val tempText = wx?.let { "${it.temp}°" } ?: ""

        // Measure at the nominal size, then shrink the whole row if it would overflow the width
        // (the digits, and so s, are larger in 12h mode).
        textPaint.textSize = 0.25f * s
        val spacerW = if (wx != null && dateText.isNotEmpty()) 0.30f * s else 0f
        val weatherW = if (wx != null) 0.60f * s + textPaint.measureText(tempText) else 0f
        val rowW = weatherW + spacerW + textPaint.measureText(dateText)
        val k = if (rowW > areaW * 0.94f) areaW * 0.94f / rowW else 1f

        textPaint.textSize = 0.25f * s * k
        val fm = textPaint.fontMetrics
        val baseline = rowCy - (fm.ascent + fm.descent) / 2f
        val iconBox = 0.50f * s * k

        var rx = areaL + (areaW - rowW * k) / 2f
        if (wx != null) {
            drawWeatherIcon(canvas, rx + iconBox / 2f, rowCy, iconBox, kindFor(wx.code), wx.isDay)
            rx += iconBox + 0.10f * s * k
            canvas.drawText(tempText, rx, baseline, textPaint)
            rx += textPaint.measureText(tempText) + spacerW * k
        }
        canvas.drawText(dateText, rx, baseline, textPaint)
    }

    // ------------------------------------------------------------------ digits

    private fun drawDigit(
        c: Canvas, x: Float, y: Float, w: Float, h: Float, t: Float, d: Int,
        drawOffSegments: Boolean = true
    ) {
        val mask = digitMasks[d]
        val l = x + t / 2f
        val r = x + w - t / 2f
        val top = y + t / 2f
        val mid = y + h / 2f
        val bot = y + h - t / 2f
        val s = t * 0.12f

        fun seg(bit: Int, horizontal: Boolean, a: Float, b: Float, fixed: Float) {
            buildSegment(horizontal, a, b, fixed, t)
            if (mask and (1 shl bit) != 0) {
                c.drawPath(path, glowPaint)
                c.drawPath(path, onPaint)
            } else if (drawOffSegments) {
                c.drawPath(path, offPaint)
            }
        }
        seg(0, true, l + s, r - s, top)
        seg(1, false, top + s, mid - s, r)
        seg(2, false, mid + s, bot - s, r)
        seg(3, true, l + s, r - s, bot)
        seg(4, false, mid + s, bot - s, l)
        seg(5, false, top + s, mid - s, l)
        seg(6, true, l + s, r - s, mid)
    }

    private fun buildSegment(horizontal: Boolean, a: Float, b: Float, fixed: Float, t: Float) {
        val ht = t / 2f
        path.reset()
        if (horizontal) {
            path.moveTo(a, fixed)
            path.lineTo(a + ht, fixed - ht)
            path.lineTo(b - ht, fixed - ht)
            path.lineTo(b, fixed)
            path.lineTo(b - ht, fixed + ht)
            path.lineTo(a + ht, fixed + ht)
        } else {
            path.moveTo(fixed, a)
            path.lineTo(fixed + ht, a + ht)
            path.lineTo(fixed + ht, b - ht)
            path.lineTo(fixed, b)
            path.lineTo(fixed - ht, b - ht)
            path.lineTo(fixed - ht, a + ht)
        }
        path.close()
    }

    private fun drawColon(c: Canvas, x: Float, y: Float, w: Float, h: Float, t: Float) {
        val cx = x + w / 2f
        val r = t * 0.6f
        for (cy in floatArrayOf(y + h * 0.3f, y + h * 0.7f)) {
            c.drawCircle(cx, cy, r, glowPaint)
            c.drawCircle(cx, cy, r, onPaint)
        }
    }

    // ----------------------------------------------------------------- weather

    /** Maps WMO weather codes (Open-Meteo) to an icon kind. */
    private fun kindFor(code: Int) = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        in 51..67, in 80..82 -> Kind.RAIN
        in 71..77, 85, 86 -> Kind.SNOW
        in 95..99 -> Kind.STORM
        else -> Kind.CLOUDY
    }

    private fun drawWeatherIcon(c: Canvas, cx: Float, cy: Float, size: Float, kind: Kind, isDay: Boolean) {
        strokePaint.strokeWidth = size * 0.07f
        when (kind) {
            Kind.CLEAR -> if (isDay) drawSun(c, cx, cy, size) else drawMoon(c, cx, cy, size)
            Kind.PARTLY -> {
                if (isDay) drawSun(c, cx - 0.14f * size, cy - 0.14f * size, size * 0.75f)
                else drawMoon(c, cx - 0.14f * size, cy - 0.14f * size, size * 0.75f)
                drawCloud(c, cx + 0.08f * size, cy + 0.1f * size, size * 0.85f)
            }
            Kind.CLOUDY -> drawCloud(c, cx, cy, size * 1.1f)
            Kind.FOG -> {
                val lens = floatArrayOf(0.55f, 0.45f, 0.55f, 0.35f)
                for (i in lens.indices) {
                    val y = cy + (i - 1.5f) * 0.14f * size
                    c.drawLine(cx - lens[i] * size / 2f, y, cx + lens[i] * size / 2f, y, strokePaint)
                }
            }
            Kind.RAIN -> {
                drawCloud(c, cx, cy - 0.12f * size, size)
                for (i in -1..1) {
                    val x = cx + i * 0.18f * size
                    c.drawLine(x + 0.04f * size, cy + 0.16f * size, x - 0.03f * size, cy + 0.30f * size, strokePaint)
                }
            }
            Kind.SNOW -> {
                drawCloud(c, cx, cy - 0.12f * size, size)
                for (i in -1..1) {
                    c.drawCircle(cx + i * 0.18f * size, cy + 0.25f * size, size * 0.04f, onPaint)
                }
            }
            Kind.STORM -> {
                drawCloud(c, cx, cy - 0.12f * size, size)
                path.reset()
                path.moveTo(cx + 0.05f * size, cy + 0.10f * size)
                path.lineTo(cx - 0.06f * size, cy + 0.24f * size)
                path.lineTo(cx + 0.04f * size, cy + 0.24f * size)
                path.lineTo(cx - 0.05f * size, cy + 0.38f * size)
                c.drawPath(path, strokePaint)
            }
        }
    }

    private fun drawSun(c: Canvas, cx: Float, cy: Float, size: Float) {
        c.drawCircle(cx, cy, size * 0.17f, strokePaint)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            c.drawLine(
                cx + cos * size * 0.27f, cy + sin * size * 0.27f,
                cx + cos * size * 0.40f, cy + sin * size * 0.40f, strokePaint
            )
        }
    }

    private fun drawMoon(c: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.3f
        val moon = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val cut = Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.35f, r * 0.9f, Path.Direction.CW) }
        moon.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(moon, onPaint)
    }

    private fun drawCloud(c: Canvas, cx: Float, cy: Float, size: Float) {
        val p = Path()
        p.addRoundRect(
            RectF(cx - 0.30f * size, cy - 0.02f * size, cx + 0.30f * size, cy + 0.20f * size),
            0.11f * size, 0.11f * size, Path.Direction.CW
        )
        p.op(Path().apply { addCircle(cx - 0.10f * size, cy - 0.04f * size, 0.15f * size, Path.Direction.CW) }, Path.Op.UNION)
        p.op(Path().apply { addCircle(cx + 0.12f * size, cy + 0.00f * size, 0.12f * size, Path.Direction.CW) }, Path.Op.UNION)
        c.drawPath(p, blackFill)   // hides anything behind the cloud (e.g. the sun)
        c.drawPath(p, strokePaint)
    }

    private companion object {
        /** Segment thickness as a fraction of a big digit's width. */
        const val SEGMENT_THICKNESS = 0.17f

        /** Horizontal skew of the time row. */
        const val SLANT = 0.08f
    }
}
