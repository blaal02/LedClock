package us.blackburn.ledclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Hue/saturation colour wheel (full brightness). Drag to pick; reports via [onColorChanged]. */
class ColorWheelView(
    context: Context,
    private val onColorChanged: (Int) -> Unit
) : View(context) {

    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val density = resources.displayMetrics.density
    private val selectorRadius = 12f * density
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            hsv[2] = 1f
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (260 * density).toInt()
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val size = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) want
        else min(want, available)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - selectorRadius - 2f * density
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        huePaint.shader = SweepGradient(cx, cy, hues, null)
        satPaint.shader = RadialGradient(
            cx, cy, radius.coerceAtLeast(1f), Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        selectorRing.strokeWidth = 3f * density
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)

        val angle = Math.toRadians(hsv[0].toDouble())
        val dist = hsv[1] * radius
        val sx = cx + (cos(angle) * dist).toFloat()
        val sy = cy + (sin(angle) * dist).toFloat()
        selectorFill.color = color
        canvas.drawCircle(sx, sy, selectorRadius, selectorFill)
        canvas.drawCircle(sx, sy, selectorRadius, selectorRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true) // don't let the ScrollView steal drags
                pick(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> pick(event.x, event.y)
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun pick(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        hsv[0] = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
        hsv[1] = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        hsv[2] = 1f
        invalidate()
        onColorChanged(color)
    }
}
