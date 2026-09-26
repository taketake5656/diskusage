package com.google.android.diskusage.opengl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.opengl.GLUtils
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.ui.FileSystemState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import timber.log.Timber

class RenderingThread(
    private val context: Context,
    private val eventHandler: FileSystemState,
) : AbstractRenderingThread() {
    private val indices: ShortBuffer = directBuffer(MAX_INDEXES * SIZEOF_SHORT).asShortBuffer()
    private val vertexBuffer: FloatBuffer = directBuffer(MAX_VERTEX * SIZEOF_FLOAT * 3).asFloatBuffer()
    private val texCoords: FloatBuffer = directBuffer(MAX_VERTEX * SIZEOF_FLOAT * 2).asFloatBuffer()
    private val textTexCoords: FloatBuffer =
        directBuffer(MAX_TEXT_VERTEXES * SIZEOF_FLOAT * 2).asFloatBuffer()

    lateinit var dirSquare: Square
        private set
    lateinit var fileSquare: Square
        private set
    lateinit var specialSquare: Square
        private set
    lateinit var smallSquare: SmallSquare
        private set
    lateinit var cursorSquare: CursorFrame
        private set

    private val matrix = FloatArray(16)

    private var currentBitmapMap: BitmapMap? = null
    private var editedBitmap: Bitmap? = null
    private var editedCanvas: Canvas? = null

    private var textHeight = 0
    private var textBaseline = 0f
    private val bitmaps = ArrayList<BitmapMap>()

    init {
        updateFonts(context)

        var vertex = 0
        repeat(MAX_RECTS) {
            indices.put(
                shortArrayOf(
                    vertex.toShort(), (1 + vertex).toShort(), (2 + vertex).toShort(),
                    vertex.toShort(), (2 + vertex).toShort(), (3 + vertex).toShort(),
                )
            )
            for (x in 0 until 4) {
                texCoords.put(vertexData[x][0])
                texCoords.put(vertexData[x][1])
            }
            vertex += 4
        }
        indices.position(0)
        texCoords.position(0)
        textTexCoords.position(0)
    }

    private fun updateFonts(context: Context) {
        val metrics = context.resources.displayMetrics
        val dpi = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 160f, metrics)
        val min = min(metrics.widthPixels, metrics.heightPixels)
        val minInch = min / dpi // my tablet: 5 inch height, my phone: 2 inc width
        Timber.d("updateFonts: Screen inch = %s", minInch)

        val defaultSize = textPaint.textSize
        textPaint.textSize = 20f

        // Atleast 4 times "Storage Card" should fit into the screen
        var textSize = 20 * min / (textPaint.measureText("Storage card") * 4)

        // 20 px font, seems confortable enough, if we end up with the font larger
        // than that, we may want to fit 2x more data.
        if (textSize > 20) {
            // In case we cannot fit 2x more data, we at least fit [1.0, 2.0]x more.
            textSize = (textSize / 2).coerceAtLeast(20f)
        }

        // For low DPI devices, font size should never go below 12 px (which seems to be default value).
        textSize = textSize.coerceAtLeast(defaultSize)

        // For very high DPI devices, we might want to check if the physical size of letters is sufficient
        // Let's say, 20 px font on 300 dpi devices seems readable enough:
        if (textSize / dpi < 20 / 300f) {
            textSize = 20f / 300f * dpi
        }

        textPaint.textSize = textSize
        textBaseline = -textPaint.ascent() + PADDING
        textHeight = (textPaint.descent() - textPaint.ascent() + 1 + 2 * PADDING).toInt()
        FileSystemEntry.updateFonts(textSize)
    }

    fun drawVertexes(out: FloatArray, pos: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        out[pos] = x0
        out[pos + 1] = y0
        out[pos + 3] = x1
        out[pos + 4] = y0
        out[pos + 6] = x1
        out[pos + 7] = y1
        out[pos + 9] = x0
        out[pos + 10] = y1
    }

    private fun drawElements(count: Int) {
        gl.glDrawElements(GL10.GL_TRIANGLES, count * 6, GL10.GL_UNSIGNED_SHORT, indices)
    }

    inner class Square internal constructor(resId: Int) {
        private var nrects = 0
        private val textureId = loadTexture(getBitmap(resId))
        private val vertexes = FloatArray(MAX_VERTEX * 3)

        fun draw(x0: Float, y0: Float, x1: Float, y1: Float) {
            drawVertexes(vertexes, nrects * 12, x0, y0, x1, y1)
            nrects++
            if (nrects >= MAX_RECTS) {
                flush()
            }
        }

        fun flush() {
            if (nrects == 0) return
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texCoords)
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
            vertexBuffer.put(vertexes, 0, nrects * 12)
            vertexBuffer.position(0)
            drawElements(nrects)
            nrects = 0
        }
    }

    inner class SmallSquare internal constructor(resId: Int) {
        private var nrects = 0
        private val textureId = loadTexture(getBitmap(resId))
        private val vertexes = FloatArray(MAX_VERTEX * 3)
        private val texSmallCoordsBuffer = directBuffer(MAX_VERTEX * SIZEOF_FLOAT * 2).asFloatBuffer()

        // 0 1  2 3  4 5  6 7
        // 0 0, 1 0, 1 n, 0 n
        private val texSmallCoords = FloatArray(MAX_VERTEX * 2).also {
            for (i in 0 until MAX_RECTS) {
                it[i * 8 + 2] = 1f
                it[i * 8 + 4] = 1f
            }
        }

        fun draw(x0: Float, y0: Float, x1: Float, y1: Float) {
            drawVertexes(vertexes, nrects * 12, x0, y0, x1, y1)
            texSmallCoords[nrects * 8 + 5] = (y1 - y0) / 4
            texSmallCoords[nrects * 8 + 7] = (y1 - y0) / 4
            nrects++
            if (nrects >= MAX_RECTS) {
                flush()
            }
        }

        fun flush() {
            if (nrects == 0) return
            texSmallCoordsBuffer.put(texSmallCoords, 0, nrects * 8)
            texSmallCoordsBuffer.position(0)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texSmallCoordsBuffer)
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
            vertexBuffer.put(vertexes, 0, nrects * 12)
            vertexBuffer.position(0)
            drawElements(nrects)
            nrects = 0
        }
    }

    inner class CursorFrame internal constructor() {
        private val white = loadTexture(getBitmap(R.drawable.white_gradient))
        private var dirty = false
        private val vertexes = FloatArray(4 * 4 * 3)

        private fun drawVertexes(
            pos: Int, x0: Float, y0: Float, xoff1: Float, yoff1: Float, xoff2: Float, yoff2: Float,
        ) {
            vertexes[pos] = x0
            vertexes[pos + 1] = y0
            vertexes[pos + 3] = x0 + xoff1
            vertexes[pos + 4] = y0 + yoff1
            vertexes[pos + 6] = x0 + xoff1 + xoff2
            vertexes[pos + 7] = y0 + yoff1 + yoff2
            vertexes[pos + 9] = x0 + xoff2
            vertexes[pos + 10] = y0 + yoff2
        }

        fun drawFrame(x0: Float, y0: Float, x1: Float, y1: Float) {
            drawVertexes(0, x0, y0, x1 - x0, 0f, 0f, 8f)
            drawVertexes(12, x0, y1, 0f, y0 - y1, 8f, 0f)
            drawVertexes(24, x1, y0, 0f, y1 - y0, -8f, 0f)
            drawVertexes(36, x1, y1, x0 - x1, 0f, 0f, -8f)
            dirty = true
        }

        fun flush() {
            if (!dirty) return
            dirty = false
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texCoords)
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE)
            gl.glBindTexture(GL10.GL_TEXTURE_2D, white)
            vertexBuffer.put(vertexes, 2 * 12, 2 * 12)
            vertexBuffer.position(0)
            drawElements(2)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texCoords)
            vertexBuffer.put(vertexes, 0, 2 * 12)
            vertexBuffer.position(0)
            drawElements(2)
            gl.glDisable(GL10.GL_BLEND)
        }
    }

    private fun newTextureId(): Int {
        val ids = IntArray(1)
        gl.glGenTextures(1, ids, 0)
        return ids[0]
    }

    /** Texture atlas with rendered labels. */
    internal inner class BitmapMap {
        val textPixelsArray = ArrayList<TextPixels>()
        private val textureId = newTextureId()
        var usage = 0
        var x = 0
        var y = 0
        private var buildX = 0
        private var buildY = 0
        private var bitmap: Bitmap? = null
        var canvas: Canvas? = null
            private set
        val texCoords = FloatArray(MAX_TEXT_TEXCOORDS)
        val vertexes = FloatArray(MAX_TEXT_VERTEXES * 3)
        var nrect = 0
        var inuse = false
            private set

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        init {
            bitmaps.add(this)
            edit()
        }

        private fun edit() {
            val canvas = editedCanvas?.apply {
                clipRect(Rect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE))
            } ?: run {
                val bitmap = createBitmap(TEXTURE_SIZE, TEXTURE_SIZE)
                editedBitmap = bitmap
                Canvas(bitmap).also { editedCanvas = it }
            }
            canvas.drawPaint(clearPaint)
            bitmap = editedBitmap
            this.canvas = canvas
            x = 1
            y = 0
            buildX = 1
            buildY = 0
        }

        fun reset() {
            flush()
            textPixelsArray.forEach { it.reset() }
            textPixelsArray.clear()
            edit()
        }

        fun flushNoDeps() {
            if (bitmap != null) {
                buildTexture()
            }
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, textTexCoords)
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
            textTexCoords.put(texCoords, 0, nrect * 8)
            vertexBuffer.put(vertexes, 0, nrect * 12)
            textTexCoords.position(0)
            vertexBuffer.position(0)
            drawElements(nrect)
            nrect = 0
            gl.glDisable(GL10.GL_BLEND)
        }

        fun flush() {
            flushSquares()
            flushNoDeps()
        }

        private fun buildTexture() {
            if (buildX == x && buildY == y) return
            buildX = x
            buildY = y
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bitmap, 0)
            gl.glTexEnvf(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE, GL10.GL_REPLACE.toFloat())
            gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_NEAREST)
            gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST)
        }

        fun commit() {
            buildTexture()
            bitmap = null
            canvas = null
            inuse = true
        }

        fun destroy() {
            textPixelsArray.forEach { it.reset() }
        }
    }

    private val currentBitmapMapOrCreate: BitmapMap
        get() = currentBitmapMap ?: BitmapMap().also { currentBitmapMap = it }

    // Avoid to hijack texture we just draw into.
    // We still have references in TextPixels in current stack
    // in function: TextPixels.draw()
    // and it is also bad to reuse the same texture as we don't
    // cache anything this way.
    private fun hasReusableBitmap(): Boolean = !bitmaps[0].inuse

    private fun getLeastUsedBitmap(): BitmapMap {
        val bitmapMap = bitmaps.removeAt(0)
        bitmaps.add(bitmapMap)
        bitmapMap.reset()
        return bitmapMap
    }

    private fun nextBitmapMap() {
        currentBitmapMap!!.commit()
        currentBitmapMap = if (bitmaps.size >= 40 && hasReusableBitmap()) {
            getLeastUsedBitmap()
        } else {
            BitmapMap()
        }
    }

    /** Label rendered into a texture atlas, possibly split into several parts. */
    internal class TextPixels private constructor(
        private val message: String,
        // Reminder of size after removing offset
        private var size: Int,
        private val offset: Int,
    ) {
        constructor(message: String) : this(message, 0, 0)

        private var bitmapMap: BitmapMap? = null
        private var mapX = 0
        private var mapY = 0
        private var mapSize = 0
        private var nextPixels: TextPixels? = null

        fun reset() {
            bitmapMap = null
            nextPixels = null
            size = 0
        }

        fun draw(rt: RenderingThread, x0: Float, y0: Float, elementWidth: Int) {
            val textHeight = rt.textHeight
            val textBaseline = rt.textBaseline
            if (size == 0) {
                size = (textPaint.measureText(message) + 1 + 2 * PADDING).toInt()
            }
            val atlas = bitmapMap ?: rt.currentBitmapMapOrCreate.also { map ->
                bitmapMap = map
                map.textPixelsArray.add(this)
                mapX = map.x + 1
                mapY = map.y
                val drawing = min((TEXTURE_SIZE - 2) - mapX, min(size, elementWidth + 20))
                // FIXME: allow 1 additional pixel on line break
                map.canvas!!.withSave {
                    clipRect(Rect(mapX - 1, mapY, mapX + drawing + 1, mapY + textHeight))
                    drawText(message, (mapX - offset + PADDING).toFloat(), mapY + textBaseline, textPaint)
                }
                map.x = mapX + drawing + 1
                if (map.x > TEXTURE_SIZE - 20) {
                    map.x = 1
                    map.y = mapY + textHeight
                    if (map.y > (TEXTURE_SIZE - 1) - textHeight) rt.nextBitmapMap()
                }
                mapSize = drawing
            }
            val todraw = min(size, elementWidth)
            val drawing = min(todraw, mapSize)
            atlas.usage += drawing
            val texX0 = mapX * DIV_TEX_SIZE
            val texY0 = mapY * DIV_TEX_SIZE
            val texX1 = (mapX + drawing) * DIV_TEX_SIZE
            val texY1 = (mapY + textHeight) * DIV_TEX_SIZE
            val nrect = atlas.nrect
            atlas.texCoords.apply {
                val off = nrect * 8
                this[off] = texX0; this[off + 1] = texY0
                this[off + 2] = texX1; this[off + 3] = texY0
                this[off + 4] = texX1; this[off + 5] = texY1
                this[off + 6] = texX0; this[off + 7] = texY1
            }
            rt.drawVertexes(atlas.vertexes, nrect * 12,
                x0, y0 - textBaseline, x0 + drawing, y0 + textHeight - textBaseline)

            atlas.nrect = nrect + 1
            if (atlas.nrect >= MAX_TEXT_DRAWS_PER_TEXTURE) {
                atlas.flush()
            }
            if (drawing != todraw) {
                val next = nextPixels
                    ?: TextPixels(message, size - drawing, offset + drawing).also { nextPixels = it }
                next.draw(rt, x0 + drawing, y0, elementWidth - drawing)
            }
        }
    }

    private fun getBitmap(resId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resId)!!
        val bitmap = createBitmap(16, 16)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        drawable.setBounds(0, 0, 16, 16)
        drawable.draw(canvas)
        return bitmap
    }

    private fun loadTexture(bitmap: Bitmap): Int {
        val textureId = newTextureId()
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bitmap, 0)
        gl.glTexEnvf(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE, GL10.GL_REPLACE.toFloat())
        bitmap.recycle()
        gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR)
        gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR)
        return textureId
    }

    private fun flushSquares() {
        smallSquare.flush()
        dirSquare.flush()
        fileSquare.flush()
        specialSquare.flush()
        cursorSquare.flush()
    }

    private fun flush() {
        flushSquares()
        bitmaps.forEach { it.flushNoDeps() }
    }

    override fun renderFrame(gl: GL10): Boolean {
        val color = Color.GRAY
        gl.glClearColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 1f)
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        gl.glLoadIdentity()
        gl.glScalef(0.5f, 0.5f, 1f)
        val renderRequested = eventHandler.onDrawGPU(this)
        flush()
        return renderRequested
    }

    override fun createResources(gl: GL10) {
        Timber.d("***** Surface Created *****")
        dirSquare = Square(R.drawable.dirbg_new)
        fileSquare = Square(R.drawable.filebg_new)
        specialSquare = Square(R.drawable.special)
        smallSquare = SmallSquare(R.drawable.small)
        cursorSquare = CursorFrame()
    }

    override fun releaseResources(gl: GL10) {
        Timber.d("***** Surface Destroyed *****")
        bitmaps.forEach { it.destroy() }
        bitmaps.clear()
        currentBitmapMap = null
    }

    override fun sizeChanged(gl: GL10, w: Int, h: Int) {
        Timber.d("***** Surface Size Changed *****")
        eventHandler.layout(w, h)

        // Init projection
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_FASTEST)
        gl.glViewport(0, 0, w, h)
        Timber.d("sizeChanged: Updated viewport = %s x %s", w, h)

        gl.glMatrixMode(GL10.GL_PROJECTION)
        gl.glLoadIdentity()
        //  0  4  8 12
        //  1  5  9 13
        //  2  6 10 14
        //  3  7 11 15
        matrix[0] = 4f / w
        matrix[5] = -4f / h
        matrix[10] = 1f
        matrix[15] = 1f
        matrix[12] = -1f
        matrix[13] = 1f
        gl.glLoadMatrixf(matrix, 0)
        gl.glMatrixMode(GL10.GL_MODELVIEW)

        gl.glEnable(GL10.GL_DITHER)
        gl.glEnable(GL10.GL_CULL_FACE)
        gl.glShadeModel(GL10.GL_SMOOTH)
        gl.glFrontFace(GL10.GL_CW)

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
        gl.glEnable(GL10.GL_TEXTURE_2D)
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer)
        eventHandler.draw300ms()
    }

    private companion object {
        val vertexData = arrayOf(
            floatArrayOf(0.1f, 0.2f, 0f),
            floatArrayOf(0.9f, 0.2f, 0f),
            floatArrayOf(0.9f, 0.9f, 0f),
            floatArrayOf(0.1f, 0.9f, 0f),
        )

        const val TEXTURE_SIZE = 1 shl 7
        const val DIV_TEX_SIZE = 1f / TEXTURE_SIZE
        const val MAX_RECTS = 100
        const val MAX_INDEXES = MAX_RECTS * 6
        const val MAX_VERTEX = MAX_RECTS * 4
        const val SIZEOF_SHORT = 2
        const val SIZEOF_FLOAT = 4
        const val MAX_TEXT_DRAWS_PER_TEXTURE = 100
        const val MAX_TEXT_VERTEXES = MAX_TEXT_DRAWS_PER_TEXTURE * 4
        const val MAX_TEXT_TEXCOORDS = MAX_TEXT_VERTEXES * 2
        const val PADDING = FileSystemEntry.PADDING

        val textPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL_AND_STROKE
            isAntiAlias = true
            setShadowLayer(PADDING.toFloat(), 1f, 1f, Color.BLACK)
        }

        fun directBuffer(capacity: Int): ByteBuffer =
            ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
    }
}
