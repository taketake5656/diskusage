package com.google.android.diskusage.opengl

import com.google.android.diskusage.filesystem.entity.FileSystemEntry

/** Cached size string and label textures of a [FileSystemEntry]. */
class DrawingCache(private val entry: FileSystemEntry) {
    private var cachedSizeString: String? = null
    private var textPixels: RenderingThread.TextPixels? = null
    private var sizePixels: RenderingThread.TextPixels? = null

    val sizeString: String
        get() = cachedSizeString ?: entry.sizeString().also { cachedSizeString = it }

    fun resetSizeString() {
        cachedSizeString = null
        sizePixels = null
    }

    fun drawText(rt: RenderingThread, x0: Float, y0: Float, elementWidth: Int) {
        val pixels = textPixels ?: RenderingThread.TextPixels(entry.name).also { textPixels = it }
        pixels.draw(rt, x0, y0, elementWidth)
    }

    fun drawSize(rt: RenderingThread, x0: Float, y0: Float, elementWidth: Int) {
        val pixels = sizePixels ?: RenderingThread.TextPixels(sizeString).also { sizePixels = it }
        pixels.draw(rt, x0, y0, elementWidth)
    }
}
