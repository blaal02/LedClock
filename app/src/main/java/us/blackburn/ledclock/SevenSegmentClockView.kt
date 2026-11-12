package us.blackburn.ledclock

import android.animation.ValueAnimator
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.text.format.DateFormat
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * Full-screen 7-segment LED clock:  HH:MM (large) + AM/PM (14-segment) over SS (1/3 size), with weather,
 * day/date and the next alarm below.
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

    // Ambient light handling. Raw readings are noisy (sensor noise, mains-powered lighting,
    // the screen's own light), so they're averaged between ticks, low-pass filtered in the log
    // domain, and only acted on past a dead band; changes then fade in rather than jump.
    private var smoothedLogLux: Float? = null
    private var rawLogLuxSum = 0f
    private var rawLogLuxCount = 0
    private var lastRawLogLux = 0f
    private var targetBrightness = 1.0f
    private var brightnessAnimator: ValueAnimator? = null
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
    private val arcRect = RectF()
    private var dateFormat: SimpleDateFormat? = settings.dateFormat.pattern?.let {
        SimpleDateFormat(it, Locale.getDefault())
    }

    /** Trigger time of the next system alarm clock, if any. */
    private var nextAlarm: Long? = null
    private var alarmFormat = timeFormat(settings.use24h)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshAlarm()
            invalidate()
        }
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
            updateAmbientBrightness()
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
        alarmFormat = timeFormat(newSettings.use24h)
        refreshAlarm()
        if (!newSettings.showWeather) weather = null
        if (!hasLightSensor) brightnessFactor = newSettings.minBrightness
        smoothedLogLux?.let { setBrightnessNow(brightnessForLogLux(it)) }
        applyColor()
        invalidate()
    }

    fun weatherPermissionGranted() = weatherProvider.hasPermission()
    fun startWeather() = weatherProvider.start()
    fun stopWeather() = weatherProvider.stop()

    /** Locale-appropriate hours:minutes; the 12-hour form always includes the AM/PM marker. */
    private fun timeFormat(use24h: Boolean) = SimpleDateFormat(
        DateFormat.getBestDateTimePattern(Locale.getDefault(), if (use24h) "Hm" else "hma"),
        Locale.getDefault()
    )

    private fun refreshAlarm() {
        nextAlarm = alarmManager?.nextAlarmClock?.triggerTime
    }

    /** The next alarm's trigger time, if it's enabled in settings and due within 24 hours. */
    private fun upcomingAlarm(): Long? {
        if (!settings.showAlarm) return null
        val at = nextAlarm ?: return null
        return at.takeIf { it - System.currentTimeMillis() in 0..ALARM_WINDOW_MS }
    }

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
        refreshAlarm()
        ContextCompat.registerReceiver(
            context, alarmReceiver,
            IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDetachedFromWindow() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.unregisterListener(this)
        context.unregisterReceiver(alarmReceiver)
        removeCallbacks(ticker)
        brightnessAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val logLux = ln(event.values[0].coerceAtLeast(0f) + 1f)
        lastRawLogLux = logLux
        if (smoothedLogLux == null) {
            // First reading: start at the right level straight away.
            smoothedLogLux = logLux
            setBrightnessNow(brightnessForLogLux(logLux))
            applyColor()
            invalidate()
        } else {
            rawLogLuxSum += logLux
            rawLogLuxCount++
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Called once per tick: folds the readings since the last tick into the smoothed level and
     * fades to a new brightness if it has moved past the dead band. Light sensors only report
     * on change, so with no new readings the last one keeps pulling the smoothed level along.
     */
    private fun updateAmbientBrightness() {
        val smoothed = smoothedLogLux ?: return
        val sample = if (rawLogLuxCount > 0) rawLogLuxSum / rawLogLuxCount else lastRawLogLux
        rawLogLuxSum = 0f
        rawLogLuxCount = 0
        val next = smoothed + LUX_SMOOTHING_ALPHA * (sample - smoothed)
        smoothedLogLux = next
        val target = brightnessForLogLux(next)
        if (abs(target - targetBrightness) > BRIGHTNESS_DEAD_BAND) animateBrightnessTo(target)
    }

    private fun setBrightnessNow(brightness: Float) {
        brightnessAnimator?.cancel()
        brightnessFactor = brightness
        targetBrightness = brightness
    }

    private fun animateBrightnessTo(target: Float) {
        targetBrightness = target
        brightnessAnimator?.cancel()
        brightnessAnimator = ValueAnimator.ofFloat(brightnessFactor, target).apply {
            duration = BRIGHTNESS_FADE_MS
            addUpdateListener {
                brightnessFactor = it.animatedValue as Float
                applyColor()
                invalidate()
            }
            start()
        }
    }

    /** Maps ln(lux + 1) to a brightness between the user's minimum and full (full at 500 lux). */
    private fun brightnessForLogLux(logLux: Float): Float {
        val minBrightness = settings.minBrightness
        val normalizedRatio = (logLux / ln(MAX_LUX + 1f)).coerceIn(0f, 1f)
        return minBrightness + normalizedRatio * (1.0f - minBrightness)
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
        // Row 1: [H][H] [:] [M][M]  gap  [AM/PM over s s] (1/3 size; AM/PM top aligned,
        // seconds bottom aligned)
        // In 12h mode the leading hour digit is only ever "1", so it shrinks to its right-hand
        // segments and the freed width goes to the remaining digits.
        val digitGap = 0.16f
        val colonWidth = 0.22f
        val secGap = 0.18f
        val leadWidth = if (settings.use24h) 1f else SEGMENT_THICKNESS
        // The slant pushes the bottom of the leading digit left of its box; reserve room for it.
        val slantOverhang = SLANT * 1.8f / 2f
        val showAmPm = settings.showAmPm && !settings.use24h
        val secDigitsW = if (settings.showSeconds) (2f + digitGap) / 3f else 0f
        // Two 14-segment characters, each as wide as a seconds digit.
        val amPmW = if (showAmPm) (2f + digitGap) / 3f else 0f
        val smallColumnW = maxOf(secDigitsW, amPmW)
        val columnWidth = if (smallColumnW > 0f) secGap + smallColumnW else 0f
        val totalUnitsW = slantOverhang + leadWidth + 3f + colonWidth + 4 * digitGap + columnWidth
        // The info row is dropped entirely when there's nothing to show in it.
        val showInfo = settings.showWeather || dateFormat != null || settings.showAlarm
        val totalUnitsH = if (showInfo) 1.8f + INFO_GAP + 0.35f else 1.8f // time + gap + info row
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

        glowPaint.maskFilter = BlurMaskFilter(st * 0.8f, BlurMaskFilter.Blur.NORMAL)
        if (showAmPm) {
            // Same width as a seconds digit, so a little shorter than one.
            val ch = sw / AMPM_ASPECT
            val ct = st * 0.7f
            val first = if (now.get(Calendar.AM_PM) == Calendar.AM) SEG14_LETTER_A else SEG14_LETTER_P
            drawChar14(canvas, x, y0, sw, ch, ct, first)
            drawChar14(canvas, x + sw + sgap, y0, sw, ch, ct, SEG14_LETTER_M)
        }
        if (settings.showSeconds) {
            val secY = y0 + h - sh
            drawDigit(canvas, x, secY, sw, sh, st, sec / 10); x += sw + sgap
            drawDigit(canvas, x, secY, sw, sh, st, sec % 10)
        }
        canvas.restore()

        if (showInfo) drawInfoRow(canvas, y0 + (1.8f + INFO_GAP) * s + 0.175f * s, s, areaL, areaW)
    }

    /**
     * Weather icon + temperature, day/date, then alarm icon + next alarm time, centred on
     * [rowCy] within [areaL]..+[areaW].
     */
    private fun drawInfoRow(canvas: Canvas, rowCy: Float, s: Float, areaL: Float, areaW: Float) {
        val dateText = dateFormat?.format(Date()) ?: ""
        val wx = if (settings.showWeather) weather else null
        val tempText = wx?.let { "${it.temp}°" } ?: ""
        val alarmText = upcomingAlarm()?.let { alarmFormat.format(Date(it)) } ?: ""

        // Measure at the nominal size, then shrink the whole row if it would overflow the width
        // (the digits, and so s, are larger in 12h mode).
        textPaint.textSize = 0.25f * s
        val spacerW = 0.30f * s
        val parts = listOfNotNull(
            if (wx != null) 0.60f * s + textPaint.measureText(tempText) else null,
            if (dateText.isNotEmpty()) textPaint.measureText(dateText) else null,
            if (alarmText.isNotEmpty()) 0.60f * s + textPaint.measureText(alarmText) else null,
        )
        if (parts.isEmpty()) return
        val rowW = parts.sum() + spacerW * (parts.size - 1)
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
        if (dateText.isNotEmpty()) {
            canvas.drawText(dateText, rx, baseline, textPaint)
            rx += textPaint.measureText(dateText) + spacerW * k
        }
        if (alarmText.isNotEmpty()) {
            drawAlarmIcon(canvas, rx + iconBox / 2f, rowCy, iconBox)
            rx += iconBox + 0.10f * s * k
            canvas.drawText(alarmText, rx, baseline, textPaint)
        }
    }

    /** Outline alarm clock: round face with hands, two bells on top and two feet. */
    private fun drawAlarmIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        strokePaint.strokeWidth = size * 0.07f
        val r = size * 0.28f
        val fy = cy + size * 0.04f
        c.drawCircle(cx, fy, r, strokePaint)
        c.drawLine(cx, fy, cx, fy - r * 0.6f, strokePaint)
        c.drawLine(cx, fy, cx + r * 0.45f, fy, strokePaint)
        val br = size * 0.13f
        val by = cy - size * 0.30f
        arcRect.set(cx - size * 0.32f - br, by - br, cx - size * 0.32f + br, by + br)
        c.drawArc(arcRect, 180f, 90f, false, strokePaint)
        arcRect.set(cx + size * 0.32f - br, by - br, cx + size * 0.32f + br, by + br)
        c.drawArc(arcRect, 270f, 90f, false, strokePaint)
        c.drawLine(cx - r * 0.6f, fy + r * 0.8f, cx - r * 0.95f, fy + r * 1.25f, strokePaint)
        c.drawLine(cx + r * 0.6f, fy + r * 0.8f, cx + r * 0.95f, fy + r * 1.25f, strokePaint)
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

    /**
     * 14-segment character: the 7-segment outline with the middle bar split in two, plus
     * centre verticals and four diagonals. [mask] uses the SEG14_* bits.
     */
    private fun drawChar14(c: Canvas, x: Float, y: Float, w: Float, h: Float, t: Float, mask: Int) {
        val l = x + t / 2f
        val r = x + w - t / 2f
        val cx = x + w / 2f
        val top = y + t / 2f
        val mid = y + h / 2f
        val bot = y + h - t / 2f
        val s = t * 0.12f
        val dt = t * 0.8f // diagonal thickness

        fun light(bit: Int) {
            if (mask and (1 shl bit) != 0) {
                c.drawPath(path, glowPaint)
                c.drawPath(path, onPaint)
            } else {
                c.drawPath(path, offPaint)
            }
        }
        buildSegment(true, l + s, r - s, top, t); light(SEG14_A)
        buildSegment(false, top + s, mid - s, r, t); light(SEG14_B)
        buildSegment(false, mid + s, bot - s, r, t); light(SEG14_C)
        buildSegment(true, l + s, r - s, bot, t); light(SEG14_D)
        buildSegment(false, mid + s, bot - s, l, t); light(SEG14_E)
        buildSegment(false, top + s, mid - s, l, t); light(SEG14_F)
        buildSegment(true, l + s, cx - s, mid, t); light(SEG14_G1)
        buildSegment(true, cx + s, r - s, mid, t); light(SEG14_G2)
        buildSegment(false, top + s, mid - s, cx, t); light(SEG14_I)
        buildSegment(false, mid + s, bot - s, cx, t); light(SEG14_L)
        buildDiagonal(l + t, top + t, cx - t * 0.6f, mid - t * 0.8f, dt); light(SEG14_H)
        buildDiagonal(r - t, top + t, cx + t * 0.6f, mid - t * 0.8f, dt); light(SEG14_J)
        buildDiagonal(l + t, bot - t, cx - t * 0.6f, mid + t * 0.8f, dt); light(SEG14_K)
        buildDiagonal(r - t, bot - t, cx + t * 0.6f, mid + t * 0.8f, dt); light(SEG14_N)
    }

    /** Straight bar of thickness [th] from (x1, y1) to (x2, y2). */
    private fun buildDiagonal(x1: Float, y1: Float, x2: Float, y2: Float, th: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        val nx = -(y2 - y1) / len * th / 2f
        val ny = (x2 - x1) / len * th / 2f
        path.reset()
        path.moveTo(x1 + nx, y1 + ny)
        path.lineTo(x2 + nx, y2 + ny)
        path.lineTo(x2 - nx, y2 - ny)
        path.lineTo(x1 - nx, y1 - ny)
        path.close()
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

        /** Width / height of a 14-segment AM/PM character (wider than digits, for the diagonals). */
        const val AMPM_ASPECT = 0.75f

        /** Vertical gap between the time row and the info row, in big-digit widths. */
        const val INFO_GAP = 0.16f

        // 14-segment bits: outline A-F as for 7 segments, split middle G1/G2, centre verticals
        // I (upper) and L (lower), diagonals H (upper-left), J (upper-right), K (lower-left),
        // N (lower-right).
        const val SEG14_A = 0
        const val SEG14_B = 1
        const val SEG14_C = 2
        const val SEG14_D = 3
        const val SEG14_E = 4
        const val SEG14_F = 5
        const val SEG14_G1 = 6
        const val SEG14_G2 = 7
        const val SEG14_H = 8
        const val SEG14_I = 9
        const val SEG14_J = 10
        const val SEG14_K = 11
        const val SEG14_L = 12
        const val SEG14_N = 13

        const val SEG14_LETTER_A = (1 shl SEG14_A) or (1 shl SEG14_B) or (1 shl SEG14_C) or
            (1 shl SEG14_E) or (1 shl SEG14_F) or (1 shl SEG14_G1) or (1 shl SEG14_G2)
        const val SEG14_LETTER_P = (1 shl SEG14_A) or (1 shl SEG14_B) or (1 shl SEG14_E) or
            (1 shl SEG14_F) or (1 shl SEG14_G1) or (1 shl SEG14_G2)
        const val SEG14_LETTER_M = (1 shl SEG14_B) or (1 shl SEG14_C) or (1 shl SEG14_E) or
            (1 shl SEG14_F) or (1 shl SEG14_H) or (1 shl SEG14_J)

        const val ALARM_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** Ambient light at which the display reaches full brightness. */
        const val MAX_LUX = 500f

        /** Per-second low-pass factor for ambient light: 1 - e^(-1s / 2s time constant). */
        val LUX_SMOOTHING_ALPHA = 1f - exp(-0.5f)

        /** Brightness changes smaller than this are ignored, so noise can't cause flicker. */
        const val BRIGHTNESS_DEAD_BAND = 0.04f

        const val BRIGHTNESS_FADE_MS = 600L
    }
}
