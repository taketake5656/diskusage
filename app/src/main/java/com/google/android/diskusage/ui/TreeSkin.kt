/**
 * @file TreeSkin.kt
 * @brief ツリーの見た目(項目のテクスチャ、カーソルの枠、文字)。
 */
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
 * @brief ファイルシステムのツリーの見た目。
 *
 * テクスチャを貼った項目、光るカーソルの枠、影付きの文字で、旧 OpenGL 版の見た目を再現する。
 *
 * @param context テクスチャと画面の大きさの取得に使う Context
 */
class TreeSkin(context: Context) {
    private val dirTexture = loadTexture(context, R.drawable.dirbg_new)
    private val fileTexture = loadTexture(context, R.drawable.filebg_new)
    private val specialTexture = loadTexture(context, R.drawable.special)

    /** @brief 16x16 のテクスチャのうち、項目の大きさに引き伸ばす部分。 */
    private val textureSrc = Rect(2, 3, 14, 14)
    private val texturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** @brief 小さすぎて個別に表示できない項目に使う横縞。 */
    private val smallShader = BitmapShader(
        loadTexture(context, R.drawable.small), Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    private val smallMatrix = Matrix()
    private val smallPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = smallShader }

    /** @brief カーソルの枠の、内側に向かって薄くなる光る帯(加算合成)。 */
    private val cursorPaint = Paint().apply {
        shader = cursorGradient(loadTexture(context, R.drawable.white_gradient))
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    /** @brief 項目の名前とサイズの文字の描画設定(白、黒い影付き)。 */
    val textPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        isAntiAlias = true
        setShadowLayer(TEXT_PADDING, 1f, 1f, Color.BLACK)
    }

    init {
        updateFonts(context)
    }

    /**
     * @brief ディレクトリの項目を描く。
     * @param canvas 描画先
     * @param left 左端
     * @param top 上端
     * @param right 右端
     * @param bottom 下端
     */
    fun drawDir(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) =
        drawTexture(canvas, dirTexture, left, top, right, bottom)

    /**
     * @brief ファイルの項目を描く。
     * @param canvas 描画先
     * @param left 左端
     * @param top 上端
     * @param right 右端
     * @param bottom 下端
     */
    fun drawFile(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) =
        drawTexture(canvas, fileTexture, left, top, right, bottom)

    /**
     * @brief 特別な項目(空き容量、アプリなど)を描く。
     * @param canvas 描画先
     * @param left 左端
     * @param top 上端
     * @param right 右端
     * @param bottom 下端
     */
    fun drawSpecial(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) =
        drawTexture(canvas, specialTexture, left, top, right, bottom)

    /**
     * @brief テクスチャを矩形に引き伸ばして描く。
     * @param canvas 描画先
     * @param texture テクスチャ
     * @param left 左端
     * @param top 上端
     * @param right 右端
     * @param bottom 下端
     */
    private fun drawTexture(
        canvas: Canvas, texture: Bitmap, left: Float, top: Float, right: Float, bottom: Float,
    ) {
        dst.set(left, top, right, bottom)
        canvas.drawBitmap(texture, textureSrc, dst, texturePaint)
    }

    /**
     * @brief 小さすぎて個別に表示できない項目の範囲を横縞で塗る。
     * @param canvas 描画先
     * @param left 左端
     * @param top 上端
     * @param right 右端
     * @param bottom 下端
     */
    fun drawSmall(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        // Two texels per stripe period
        smallMatrix.setScale(1f, SMALL_STRIPE_PERIOD / 2)
        smallMatrix.postTranslate(left, top)
        smallShader.setLocalMatrix(smallMatrix)
        canvas.drawRect(left, top, right, bottom, smallPaint)
    }

    /**
     * @brief カーソルの枠を描く(4 辺それぞれに内側へ光る帯)。
     * @param canvas 描画先
     * @param left 左端
     * @param top 上端
     * @param right 右端
     * @param bottom 下端
     */
    fun drawCursor(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val width = right - left
        val height = bottom - top
        drawCursorBand(canvas, left, top, 0f, width)
        drawCursorBand(canvas, left, bottom, -90f, height)
        drawCursorBand(canvas, right, top, 90f, height)
        drawCursorBand(canvas, right, bottom, 180f, width)
    }

    /**
     * @brief (x, y) から始まる枠の 1 辺に沿って、内側へ光る帯を描く。
     * @param canvas 描画先
     * @param x 始点の x 座標
     * @param y 始点の y 座標
     * @param degrees 帯の向き(度)
     * @param length 帯の長さ
     */
    private fun drawCursorBand(canvas: Canvas, x: Float, y: Float, degrees: Float, length: Float) {
        canvas.withSave {
            translate(x, y)
            rotate(degrees)
            drawRect(0f, 0f, length, CURSOR_WIDTH, cursorPaint)
        }
    }

    /**
     * @brief どの画面でも読めるように、文字の大きさを画面の大きさと密度から決める。
     * @param context 画面の情報の取得に使う Context
     */
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
        /** @brief 項目の中での文字の余白(影のぼかしの半径にも使う)。 */
        const val TEXT_PADDING = 4f
        /** @brief カーソルの枠の帯の幅。 */
        const val CURSOR_WIDTH = 8f
        /** @brief 小さい項目の横縞の周期。 */
        const val SMALL_STRIPE_PERIOD = 4f

        /**
         * @brief テクスチャを画面密度で拡大せずに読み込む。
         * @param context Context
         * @param resId drawable のリソース ID
         * @return 読み込んだビットマップ
         */
        fun loadTexture(context: Context, resId: Int): Bitmap =
            BitmapFactory.decodeResource(context.resources, resId,
                BitmapFactory.Options().apply { inScaled = false })

        /**
         * @brief テクスチャの中央の列から色を取り、帯のグラデーションを作る。
         * @param texture 元のテクスチャ
         * @return 帯の幅方向のグラデーション
         */
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
