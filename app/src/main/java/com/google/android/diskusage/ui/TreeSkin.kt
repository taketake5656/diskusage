package com.google.android.diskusage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import androidx.core.graphics.get
import androidx.core.graphics.withSave
import com.google.android.diskusage.R
import kotlin.math.min
import timber.log.Timber

/**
 * Look of the file system tree: textured entries, glowing cursor frame and
 * shadowed labels.
 */
class TreeSkin(context: Context) {
    private val dirTexture = loadTexture(context, R.drawable.dirbg_new)
    private val fileTexture = loadTexture(context, R.drawable.filebg_new)
    private val specialTexture = loadTexture(context, R.drawable.special)

    /** Part of the 16x16 textures which is stretched over an entry. */
    private val textureSrc = Rect(2, 3, 14, 14)
    private val texturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** Horizontal stripes for the entries too small to be displayed. */
    private val smallShader = BitmapShader(
        loadTexture(context, R.drawable.small), Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    private val smallMatrix = Matrix()
    private val smallPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = smallShader }

    /** Glowing band of the cursor frame, fading inside. */
    private val cursorPaint = Paint().apply {
        shader = cursorGradient(loadTexture(context, R.drawable.white_gradient))
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    val textPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        isAntiAlias = true
        setShadowLayer(TEXT_PADDING, 1f, 1f, Color.BLACK)
    }

    init {
        updateFonts(context)
    }

    fun drawDir(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) =
        drawTexture(canvas, dirTexture, left, top, right, bottom)

    fun drawFile(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) =
        drawTexture(canvas, fileTexture, left, top, right, bottom)

    fun drawSpecial(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) =
        drawTexture(canvas, specialTexture, left, top, right, bottom)

    private fun drawTexture(
        canvas: Canvas, texture: Bitmap, left: Float, top: Float, right: Float, bottom: Float,
    ) {
        dst.set(left, top, right, bottom)
        canvas.drawBitmap(texture, textureSrc, dst, texturePaint)
    }

    fun drawSmall(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        // Two texels per stripe period
        smallMatrix.setScale(1f, SMALL_STRIPE_PERIOD / 2)
        smallMatrix.postTranslate(left, top)
        smallShader.setLocalMatrix(smallMatrix)
        canvas.drawRect(left, top, right, bottom, smallPaint)
    }

    fun drawCursor(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val width = right - left
        val height = bottom - top
        drawCursorBand(canvas, left, top, 0f, width)
        drawCursorBand(canvas, left, bottom, -90f, height)
        drawCursorBand(canvas, right, top, 90f, height)
        drawCursorBand(canvas, right, bottom, 180f, width)
    }

    /** Draws a band along the frame edge starting at ([x], [y]), glowing inside. */
    private fun drawCursorBand(canvas: Canvas, x: Float, y: Float, degrees: Float, length: Float) {
        canvas.withSave {
            translate(x, y)
            rotate(degrees)
            drawRect(0f, 0f, length, CURSOR_WIDTH, cursorPaint)
        }
    }

    /** Sets the label size, so that the labels are readable on any screen. */
    private fun updateFonts(context: Context) {
        val metrics = context.resources.displayMetrics
        val dpi = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 160f, metrics)
        val min = min(metrics.widthPixels, metrics.heightPixels)
        Timber.d("updateFonts: Screen inch = %s", min / dpi)

        val defaultSize = textPaint.textSize
        textPaint.textSize = 20f
        // At least 4 times "Storage Card" should fit into the screen
        var textSize = 20 * min / (textPaint.measureText("Storage card") * 4)
        // 20 px font seems comfortable enough, if we end up with the font larger
        // than that, we may want to fit 2x more data, or at least [1.0, 2.0]x more.
        if (textSize > 20) {
            textSize = (textSize / 2).coerceAtLeast(20f)
        }
        // For low DPI devices, font size should never go below the default.
        textSize = textSize.coerceAtLeast(defaultSize)
        // 20 px font on 300 dpi devices seems readable enough
        if (textSize / dpi < 20 / 300f) {
            textSize = 20f / 300f * dpi
        }
        textPaint.textSize = textSize
    }

    private companion object {
        /** Offset of the label text inside an entry. */
        const val TEXT_PADDING = 4f
        const val CURSOR_WIDTH = 8f
        const val SMALL_STRIPE_PERIOD = 4f

        fun loadTexture(context: Context, resId: Int): Bitmap =
            BitmapFactory.decodeResource(context.resources, resId,
                BitmapFactory.Options().apply { inScaled = false })

        /** Gradient of the band, sampled from the middle column of the texture. */
        fun cursorGradient(texture: Bitmap): Shader {
            val x = texture.width / 2
            val from = texture.height * 0.2f
            val to = texture.height * 0.9f
            val stops = 8
            val colors = IntArray(stops) {
                val y = (from + (to - from) * it / (stops - 1)).toInt()
                texture[x, y.coerceAtMost(texture.height - 1)]
            }
            val positions = FloatArray(stops) { it / (stops - 1f) }
            return LinearGradient(0f, 0f, 0f, CURSOR_WIDTH, colors, positions, Shader.TileMode.CLAMP)
        }
    }
}
