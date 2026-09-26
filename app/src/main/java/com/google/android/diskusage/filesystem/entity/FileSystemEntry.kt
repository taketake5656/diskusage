/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.google.android.diskusage.filesystem.entity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.diskusage.R
import com.google.android.diskusage.ui.Cursor
import com.google.android.diskusage.ui.TreeSkin
import timber.log.Timber

open class FileSystemEntry protected constructor(
    var parent: FileSystemEntry?,
    var name: String,
) {
    // The size suitable for painting without any operations (and sorting)
    // Bit layout:
    // 40 bits      | 24 bits
    // sizeInBlocks | reminder
    // reminder is encoded file size information suitable for formating sizeString.
    // reminder:
    // 3 bits         | 21 bits  (2**18 = 44,040,192)
    // sizeMultiplier | size in multiplier of bytes
    // sizeMultiplier:
    // 000 = multiplier=1,              format=(n_bytes "%d bytes", size)
    // 001 = multiplier=1024,           format=(n_kilobytes "%d KiB", size)
    // 010 = multiplier=1024,           format=(n_megabytes "%5.2f MiB", size / 1024.f)
    // 011 = multiplier=1024,           format=(n_megabytes10 "%5.1f MiB", size / 1024.f)
    // 100 = multiplier=1024*1024,      format=(n_megabytes100 "%d MiB", size)
    // 101 = multiplier=1024*1024,      format=(n_gigabytes "%5.2f GiB", size/ 1024.f)
    // 110 = multiplier=1024*1024,      format=(n_gigabytes10 "%5.1f GiB", size/ 1024.f)
    // 111 = multiplier=1024*1024*1024, format=(n_gigabytes100 "%d GiB", size)

    // Ranges for sizeMultipliers:
    // 0: sz < 1024:               "%4.0f bytes", sz
    // 1: sz < 1024 * 1024:        "%4.0f KiB", sz * (1f / 1024)
    // 2: sz < 1024 * 1024 * 10:   "%5.2f MiB", sz * (1f / 1024 / 1024)
    // 3: sz < 1024 * 1024 * 200:  "%5.1f MiB", sz * (1f / 1024 / 1024)
    // 4: sz >= 1024 * 1024 * 200: "%4.0f MiB", sz * (1f / 1024 / 1024)
    var encodedSize: Long = 0

    var children: Array<FileSystemEntry>? = null

    private var cachedSizeString: String? = null

    val sizeInBlocks: Long
        get() = encodedSize shr BLOCK_OFFSET

    val sizeForRendering: Long
        get() = encodedSize and BLOCK_MASK.inv()

    open val isDeletable: Boolean
        get() = false

    /** Number of files, not directories. */
    open val numFiles: Int
        get() {
            val children = children ?: return 1
            val hasFile = children.any { it.children == null }
            return children.sumOf { it.numFiles } + if (hasFile) 1 else 0
        }

    fun sizeString(): String = calcSizeStringFromEncoded(encodedSize)

    /** Size string, cached for painting. */
    private fun cachedSizeString(): String =
        cachedSizeString ?: sizeString().also { cachedSizeString = it }

    fun clearSizeStringCache() {
        cachedSizeString = null
    }

    fun setSizeInBlocks(blocks: Long, blockSize: Long) {
        encodedSize = (blocks shl BLOCK_OFFSET) or makeBytesPart(blocks * blockSize)
    }

    fun initSizeInBytes(bytes: Long, blockSize: Long): FileSystemEntry = apply {
        val blocks = (bytes + blockSize - 1) / blockSize
        encodedSize = (blocks shl BLOCK_OFFSET) or makeBytesPart(bytes)
    }

    fun initSizeInBytesAndBlocks(bytes: Long, blocks: Long): FileSystemEntry = apply {
        encodedSize = (blocks shl BLOCK_OFFSET) or makeBytesPart(bytes)
    }

    fun setChildren(children: Array<FileSystemEntry>?, blockSize: Long): FileSystemEntry = apply {
        this.children = children
        if (children == null) return@apply
        children.forEach { it.parent = this }
        setSizeInBlocks(children.sumOf { it.sizeInBlocks }, blockSize)
    }

    open fun create(): FileSystemEntry = FileSystemEntry(null, name)

    class SearchInterruptedException : RuntimeException()

    fun copy(): FileSystemEntry {
        if (Thread.interrupted()) throw SearchInterruptedException()
        val copy = create()
        copy.children = children?.map { it.copy().apply { parent = copy } }?.toTypedArray()
        copy.encodedSize = encodedSize
        return copy
    }

    fun filterChildren(pattern: CharSequence, blockSize: Long): FileSystemEntry? {
        val children = children ?: return null
        val filtered = children.mapNotNull { it.filter(pattern, blockSize) }.toTypedArray()
        if (filtered.isEmpty()) return null
        filtered.sortWith(COMPARE)
        return create().setChildren(filtered, blockSize)
    }

    open fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? {
        if (name.lowercase().contains(pattern)) {
            return copy()
        }
        return filterChildren(pattern, blockSize)
    }

    /**
     * Find index of directChild in 'children' field of this entry.
     * @return index of the directChild in 'children' field.
     */
    fun getIndexOf(directChild: FileSystemEntry): Int {
        val index = children?.indexOfFirst { it === directChild } ?: -1
        if (index == -1) throw IllegalStateException("${directChild.name} is not a child of $name")
        return index
    }

    /**
     * Find entry which follows this entry in its the parent.
     * @return next entry in the same parent or this entry if there is no more entries
     */
    val next: FileSystemEntry
        get() {
            val siblings = parent!!.children!!
            return siblings.getOrNull(parent!!.getIndexOf(this) + 1) ?: this
        }

    /**
     * Find entry which precedes this entry in its the parent.
     * @return previous entry in the same parent or this entry if the entry is first
     */
    val prev: FileSystemEntry
        get() {
            val siblings = parent!!.children!!
            return siblings.getOrNull(parent!!.getIndexOf(this) - 1) ?: this
        }

    fun paint(
        canvas: Canvas, skin: TreeSkin, bounds: Rect, cursor: Cursor, viewTop: Long,
        viewDepth: Float, yscale: Float, screenHeight: Int, numSpecialEntries: Int,
    ) {
        val clip = ViewClip(bounds, viewTop, viewDepth, yscale)
        val children = children!!
        paint(sizeForRendering, children, canvas, skin, clip.xoffset, clip.yoffset, yscale,
            clip.left, clip.top, clip.bottom, bounds.right.toFloat(), screenHeight)
        paintSpecial(children, canvas, skin, clip.xoffset, clip.yoffset, yscale,
            clip.left, clip.top, clip.bottom, screenHeight, numSpecialEntries)

        // paint position
        val cursorLeft = cursor.depth * elementWidth + clip.xoffset
        val cursorTop = (cursor.top - viewTop) * yscale
        val cursorRight = cursorLeft + elementWidth
        val cursorBottom = cursorTop + cursor.position.sizeForRendering * yscale
        skin.drawCursor(canvas, cursorLeft, cursorTop, cursorRight, cursorBottom)
    }

    /**
     * Converts the screen clip area to world coordinates.
     *
     * Scale conversion: window_y = yscale * world_y.
     * Offset conversion: window_y = yscale * (world_y - rootOffset).
     *
     * X coords: xoffset is the screen position of current object, clip is in
     * coords of the current object. Y coords: yoffset is the screen position of
     * current object, clip is in world coords relative to current object.
     */
    private class ViewClip(bounds: Rect, viewTop: Long, viewDepth: Float, yscale: Float) {
        private val viewLeft = (viewDepth * elementWidth).toInt()
        val top = (bounds.top / yscale).toLong() + viewTop
        val bottom = (bounds.bottom / yscale).toLong() + viewTop
        val left = (bounds.left + viewLeft).toLong()
        val xoffset = -viewLeft.toFloat()
        val yoffset = -viewTop * yscale
    }

    fun toTitleString(): String {
        val sizeString = sizeString()
        val children = children
        return when {
            !children.isNullOrEmpty() -> dirNameSizeNumDirs.format(name, sizeString, children.size)
            sizeInBlocks == 0L -> dirEmpty.format(name)
            else -> dirNameSize.format(name, sizeString)
        }
    }

    /** Path relative to the mount point, without the two top level entries. */
    fun path2(): String =
        generateSequence(this) { it.parent }
            .map { it.name }
            .toList()
            .dropLast(2)
            .asReversed()
            .joinToString("/")

    fun absolutePath(): String {
        if (this is FileSystemRoot) {
            return rootPath
        }
        return (parent?.absolutePath() ?: "") + "/" + name
    }

    /**
     * Find depth of 'entry' in current element.
     * @return 1 for depth equal 1 and so on
     */
    fun depth(entry: FileSystemEntry): Int =
        generateSequence(entry) { it.parent }.takeWhile { it !== this }.count()

    /**
     * Find and return entry on specified depth and offset in this entry used as root.
     * @param maxDepth maximum depth to find entry
     * @return nearest entry to the specified conditions
     */
    fun findEntry(maxDepth: Int, offset: Long): FileSystemEntry {
        var currOffset = 0L
        var entry = this
        var children = children
        repeat(maxDepth) {
            for (e in children!!) {
                val size = e.sizeForRendering
                if (currOffset + size < offset) {
                    currOffset += size
                    continue
                }
                // found entry
                entry = e
                children = e.children ?: return entry
                break
            }
        }
        return entry
    }

    /**
     * Returns offset in bytes (world coordinates) from start of this
     * object to the start of 'cursor' object.
     */
    fun getOffset(cursor: FileSystemEntry): Long {
        var offset = 0L
        var current = cursor
        while (current !== this) {
            val dir = current.parent!!
            offset += dir.children!!
                .takeWhile { it !== current }
                .sumOf { it.sizeForRendering }
            current = dir
        }
        return offset
    }

    // FIXME: no resort needed
    fun remove(blockSize: Long) {
        val parent = parent!!
        val siblings = parent.children!!
        // FIXME: the entry was not found somehow
        if (siblings.none { it === this }) return
        parent.children = siblings.filter { it !== this }.toTypedArray()

        val blocks = sizeInBlocks
        var p: FileSystemEntry? = parent
        while (p != null) {
            p.setSizeInBlocks(p.sizeInBlocks - blocks, blockSize)
            p.clearSizeStringCache()
            p.children!!.sortWith(COMPARE)
            p = p.parent
        }
    }

    fun insert(newEntry: FileSystemEntry, blockSize: Long) {
        val children = children!! + newEntry
        children.sortWith(COMPARE)
        this.children = children
        newEntry.parent = this
        val blocks = newEntry.sizeInBlocks
        var p: FileSystemEntry? = this
        while (p != null) {
            p.setSizeInBlocks(p.sizeInBlocks + blocks, blockSize)
            p.clearSizeStringCache()
            p = p.parent
        }
    }

    /**
     * Walks through the path and finds the specified entry, null otherwise.
     */
    open fun getEntryByName(path: String, exactMatch: Boolean): FileSystemEntry? {
        Timber.d("getEntryByName: getEntryForName = %s", path)
        var entry: FileSystemEntry = this
        // Like java.lang.String.split(), ignore trailing empty elements
        val names = path.split("/").let { if (path.isEmpty()) it else it.dropLastWhile(String::isEmpty) }
        for (name in names) {
            entry = entry.children?.find { it.name == name } ?: return null
        }
        return entry
    }

    companion object {
        var ascent = 0f
            private set

        var descent = 0f
            private set

        /** Font size. Also accessed from FileSystemView. */
        var fontSize = 0f
            private set

        /** Width of one element. Setup from FileSystemView when geometry changes. */
        var elementWidth = 0

        var deletedEntry: FileSystemEntry? = null

        private lateinit var nBytes: String
        private lateinit var nKilobytes: String
        private lateinit var nMegabytes: String
        private lateinit var nMegabytes10: String
        private lateinit var nMegabytes100: String
        private lateinit var nGigabytes: String
        private lateinit var nGigabytes10: String
        private lateinit var nGigabytes100: String
        private lateinit var dirNameSizeNumDirs: String
        private lateinit var dirEmpty: String
        private lateinit var dirNameSize: String

        private const val MULTIPLIER_SHIFT = 18
        private const val MULTIPLIER_MASK = 7 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_BYTES = 0
        private const val MULTIPLIER_KBYTES = 1 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_MBYTES = 2 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_MBYTES10 = 3 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_MBYTES100 = 4 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_GBYTES = 5 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_GBYTES10 = 6 shl MULTIPLIER_SHIFT
        private const val MULTIPLIER_GBYTES100 = 7 shl MULTIPLIER_SHIFT
        private const val SIZE_MASK = (1 shl MULTIPLIER_SHIFT) - 1

        // will take for a while to make this break
        // 16Mb block size on mobile device... probably in year 2020.
        // probably 32 bits for maximum number of block will break before ~2016
        const val BLOCK_OFFSET = 24
        private const val BLOCK_MASK = (1L shl BLOCK_OFFSET) - 1

        private const val KB = 1024L
        private const val MB = 1024L * KB
        private const val GB = 1024L * MB

        /** For sorting according to size, largest first. */
        val COMPARE: Comparator<FileSystemEntry> =
            Comparator { a, b -> b.encodedSize.compareTo(a.encodedSize) }

        fun makeNode(parent: FileSystemEntry?, name: String) = FileSystemEntry(parent, name)

        private fun makeBytesPart(size: Long): Long = when {
            size < KB -> size
            size < MB -> MULTIPLIER_KBYTES or (size shr 10)
            size < 10 * MB -> MULTIPLIER_MBYTES or (size shr 10)
            size < 200 * MB -> MULTIPLIER_MBYTES10 or (size shr 10)
            size < GB -> MULTIPLIER_MBYTES100 or (size shr 20)
            size < 10 * GB -> MULTIPLIER_GBYTES or (size shr 20)
            size < 200 * GB -> MULTIPLIER_GBYTES10 or (size shr 20)
            else -> MULTIPLIER_GBYTES100 or (size shr 30)
        }

        private infix fun Int.or(other: Long): Long = toLong() or other

        fun calcSizeStringFromEncoded(encodedSize: Long): String {
            val size = SIZE_MASK and encodedSize.toInt()
            return when (MULTIPLIER_MASK and encodedSize.toInt()) {
                MULTIPLIER_BYTES -> nBytes.format(size)
                MULTIPLIER_KBYTES -> nKilobytes.format(size)
                MULTIPLIER_MBYTES -> nMegabytes.format(size * (1f / 1024))
                MULTIPLIER_MBYTES10 -> nMegabytes10.format(size * (1f / 1024))
                MULTIPLIER_MBYTES100 -> nMegabytes100.format(size)
                MULTIPLIER_GBYTES -> nGigabytes.format(size * (1f / 1024))
                MULTIPLIER_GBYTES10 -> nGigabytes10.format(size * (1f / 1024))
                MULTIPLIER_GBYTES100 -> nGigabytes100.format(size)
                else -> ""
            }
        }

        /**
         * Calculate size string for specified file length in bytes.
         *
         * Currently used by delete activity preview file list loader.
         *
         * @param size file size in bytes
         * @return formated size string
         */
        fun calcSizeString(size: Long): String {
            val sz = size.coerceAtLeast(0).toFloat()
            return when {
                sz < 1024 -> nBytes.format(sz.toInt())
                sz < 1024 * 1024 -> nKilobytes.format((sz * (1f / 1024)).toInt())
                sz < 1024 * 1024 * 10 -> nMegabytes.format(sz * (1f / 1024 / 1024))
                sz < 1024 * 1024 * 200 -> nMegabytes10.format(sz * (1f / 1024 / 1024))
                else -> nMegabytes100.format((sz * (1f / 1024 / 1024)).toInt())
            }
        }

        fun setupStrings(context: Context) {
            if (::nBytes.isInitialized) return
            nBytes = context.getString(R.string.n_bytes)
            nKilobytes = context.getString(R.string.n_kilobytes)
            nMegabytes = context.getString(R.string.n_megabytes)
            nMegabytes10 = context.getString(R.string.n_megabytes10)
            nMegabytes100 = context.getString(R.string.n_megabytes100)
            nGigabytes = context.getString(R.string.n_gigabytes)
            nGigabytes10 = context.getString(R.string.n_gigabytes10)
            nGigabytes100 = context.getString(R.string.n_gigabytes100)
            dirNameSizeNumDirs = context.getString(R.string.dir_name_size_num_dirs)
            dirEmpty = context.getString(R.string.dir_empty)
            dirNameSize = context.getString(R.string.dir_name_size)
        }

        /** Updates the font metrics used for the layout of labels. */
        fun updateFonts(textPaint: Paint) {
            ascent = textPaint.ascent()
            descent = textPaint.descent()
            fontSize = descent - ascent
        }

        /**
         * Vertical position of the label center: in the middle of the entry, but
         * kept on the screen when the entry is partially visible.
         */
        private fun labelCenter(top: Float, bottom: Float, screenHeight: Int): Float {
            val fontSize = fontSize
            val pos = (top + bottom) * 0.5f
            return when {
                pos < fontSize -> if (bottom > 2 * fontSize) fontSize else bottom - fontSize
                pos > screenHeight - fontSize ->
                    if (top < screenHeight - 2 * fontSize) screenHeight - fontSize else top + fontSize
                else -> pos
            }
        }

        /** Horizontal position of labels inside an entry. */
        private const val LABEL_OFFSET = 6

        private fun clippedName(entry: FileSystemEntry, paint: Paint): String {
            val maxWidth = (elementWidth - LABEL_OFFSET - 3).toFloat()
            val cliplen = paint.breakText(entry.name, true, maxWidth, null)
            return entry.name.substring(0, cliplen)
        }

        /** Draws the name and, if there is space, the size of the entry. */
        private fun drawLabel(
            canvas: Canvas, skin: TreeSkin, entry: FileSystemEntry,
            left: Float, top: Float, bottom: Float, screenHeight: Int,
        ) {
            val paint = skin.textPaint
            val x = left + LABEL_OFFSET
            if (bottom - top > fontSize * 2) {
                val pos = labelCenter(top, bottom, screenHeight)
                canvas.drawText(clippedName(entry, paint), x, pos - descent, paint)
                canvas.drawText(entry.cachedSizeString(), x, pos - ascent, paint)
            } else if (bottom - top > fontSize) {
                canvas.drawText(clippedName(entry, paint), x, (top + bottom - ascent - descent) / 2, paint)
            }
        }

        // Copy pasted from paint() and changed to lower overhead on generic drawing code
        private fun paintSpecial(
            entries: Array<FileSystemEntry>, canvas: Canvas, skin: TreeSkin,
            xoffset0: Float, yoffset0: Float, yscale: Float,
            clipLeft0: Long, clipTop: Long, clipBottom: Long,
            screenHeight: Int, numSpecial: Int,
        ) {
            // Deep one level in hierarchy:
            val children = entries[0].children!!
            val xoffset = xoffset0 + elementWidth
            val clipLeft = clipLeft0 - elementWidth
            forEachSpecial(children, yoffset0, yscale, clipTop, clipBottom, numSpecial) { c, top, bottom ->
                if (clipLeft < elementWidth) {
                    skin.drawSpecial(canvas, xoffset, top, xoffset + elementWidth, bottom)
                    drawLabel(canvas, skin, c, xoffset, top, bottom, screenHeight)
                }
            }
        }

        /**
         * Walks the special entries (free and system space) which are the last
         * [numSpecial] children, skipping the ones outside of the clip area.
         */
        private inline fun forEachSpecial(
            children: Array<FileSystemEntry>, yoffset0: Float, yscale: Float,
            clipTop: Long, clipBottom: Long, numSpecial: Int,
            draw: (entry: FileSystemEntry, top: Float, bottom: Float) -> Unit,
        ) {
            var yoffset = yoffset0
            var childClipTop = clipTop
            var childClipBottom = clipBottom
            val len = children.size

            // Fast skip ordinary entries, FIXME: make root node special node with extra
            // field to get rid of this
            for (i in 0 until len - numSpecial) {
                val csize = children[i].sizeForRendering
                if (childClipBottom < 0) return
                childClipTop -= csize
                childClipBottom -= csize
                yoffset += csize * yscale
            }

            for (i in len - numSpecial until len) {
                val c = children[i]
                val csize = c.sizeForRendering
                val top = yoffset
                val bottom = top + csize * yscale
                if (childClipTop > csize) {
                    childClipTop -= csize
                    childClipBottom -= csize
                    yoffset = bottom
                    continue
                }
                if (childClipBottom < 0) return
                draw(c, top, bottom)
                childClipTop -= csize
                childClipBottom -= csize
                yoffset = bottom
            }
        }

        private fun paint(
            parentSize0: Long, entries: Array<FileSystemEntry>, canvas: Canvas, skin: TreeSkin,
            xoffset: Float, yoffset0: Float, yscale: Float,
            clipLeft: Long, clipTop: Long, clipBottom: Long, clipRight: Float, screenHeight: Int,
        ) {
            var parentSize = parentSize0
            var yoffset = yoffset0
            val childClipLeft = clipLeft - elementWidth
            var childClipTop = clipTop
            var childClipBottom = clipBottom
            val childXoffset = xoffset + elementWidth

            for (c in entries) {
                val csize = c.sizeForRendering
                parentSize -= csize
                val top = yoffset
                var bottom = top + csize * yscale

                if (childClipTop > csize) {
                    childClipTop -= csize
                    childClipBottom -= csize
                    yoffset = bottom
                    continue
                }
                if (childClipBottom < 0) return

                // Children are not visible right of the screen
                if (childXoffset < clipRight) {
                    c.children?.let {
                        paint(csize, it, canvas, skin, childXoffset, yoffset, yscale,
                            childClipLeft, childClipTop, childClipBottom, clipRight, screenHeight)
                    }
                }

                if (bottom - top < 4 && deletedEntry !== c) {
                    bottom += parentSize * yscale
                    skin.drawSmall(canvas, xoffset, top, childXoffset, bottom)
                    return
                }

                if (clipLeft < elementWidth) {
                    if (c.children == null) {
                        skin.drawFile(canvas, xoffset, top, childXoffset, bottom)
                    } else {
                        skin.drawDir(canvas, xoffset, top, childXoffset, bottom)
                    }
                    drawLabel(canvas, skin, c, xoffset, top, bottom, screenHeight)
                }

                childClipTop -= csize
                childClipBottom -= csize
                yoffset = bottom
            }
        }
    }
}
