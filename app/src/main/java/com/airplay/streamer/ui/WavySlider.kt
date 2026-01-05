package com.airplay.streamer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.sin

/**
 * A custom slider with an animated wavy/squiggly track for expressive Material Design 3
 * Similar to Android 13+ media controls wave animation
 */
class WavySlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var value: Float = 0.8f  // 0.0 to 1.0
    private var onValueChangeListener: ((Float, Boolean) -> Unit)? = null

    // Wave parameters
    private val waveAmplitude = 6f   // Height of waves in dp
    private val waveLength = 24f     // Length of one wave cycle in dp
    private val trackThickness = 4f  // Track stroke width in dp

    // Animation
    private var wavePhase = 0f
    private var waveAnimator: ValueAnimator? = null
    private val waveSpeed = 40f  // dp per second

    // Paints
    private val inactiveTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val wavePath = Path()
    private val density = resources.displayMetrics.density

    init {
        // Get Material colors
        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val colorSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
        val colorSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)

        inactiveTrackPaint.color = colorSurfaceVariant
        inactiveTrackPaint.strokeWidth = trackThickness * density

        activeTrackPaint.color = colorPrimary
        activeTrackPaint.strokeWidth = trackThickness * density

        thumbPaint.color = colorPrimary
        thumbBorderPaint.color = colorSurface
        thumbBorderPaint.strokeWidth = 3 * density

        startWaveAnimation()
    }

    private fun startWaveAnimation() {
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(0f, waveLength * density).apply {
            duration = ((waveLength * density / waveSpeed) * 1000).toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                wavePhase = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startWaveAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        waveAnimator?.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (48 * density).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 20 * density
        val trackWidth = width - 2 * padding
        val centerY = height / 2f
        val amplitude = waveAmplitude * density
        val wavelengthPx = waveLength * density

        // Draw inactive (full) wavy track
        drawWavyTrack(canvas, padding, trackWidth, centerY, amplitude, wavelengthPx, inactiveTrackPaint)

        // Draw active (progress) wavy track
        canvas.save()
        canvas.clipRect(0f, 0f, padding + trackWidth * value, height.toFloat())
        drawWavyTrack(canvas, padding, trackWidth, centerY, amplitude, wavelengthPx, activeTrackPaint)
        canvas.restore()

        // Draw thumb
        val thumbX = padding + trackWidth * value
        val thumbRadius = 10 * density

        // Border
        canvas.drawCircle(thumbX, centerY, thumbRadius + 2 * density, thumbBorderPaint)
        // Thumb fill
        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
    }

    private fun drawWavyTrack(canvas: Canvas, startX: Float, trackWidth: Float, centerY: Float, 
                              amplitude: Float, wavelength: Float, paint: Paint) {
        wavePath.reset()

        val steps = (trackWidth / 2).toInt().coerceAtLeast(50)
        for (i in 0..steps) {
            val x = startX + (trackWidth * i / steps)
            // Phase shifts the wave to create animation effect (wave moves toward thumb)
            val phase = ((x - startX + wavePhase) / wavelength) * 2 * Math.PI
            val y = centerY + (amplitude * sin(phase)).toFloat()

            if (i == 0) {
                wavePath.moveTo(x, y)
            } else {
                wavePath.lineTo(x, y)
            }
        }

        canvas.drawPath(wavePath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val padding = 20 * density
        val trackWidth = width - 2 * padding

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val newValue = ((event.x - padding) / trackWidth).coerceIn(0f, 1f)
                if (newValue != value) {
                    value = newValue
                    onValueChangeListener?.invoke(value, true)
                    invalidate()
                }
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setValue(newValue: Float) {
        value = newValue.coerceIn(0f, 1f)
        invalidate()
    }

    fun getValue(): Float = value

    fun addOnChangeListener(listener: (Float, Boolean) -> Unit) {
        onValueChangeListener = listener
    }
}
