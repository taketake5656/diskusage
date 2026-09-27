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

/**
 * @file FileSystemEntry.kt
 * @brief ファイルシステムのツリーの項目と、その描画。
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

/**
 * @brief ファイルシステムのツリーの項目(ディレクトリ、または派生クラスでファイルなど)。
 *
 * 子はサイズの大きい順に並べる。サイズはブロック数と表示用のバイト数を 1 つの Long に
 * 詰めて持つ(encodedSize)。ツリー全体の描画もこのクラスで行う。
 *
 * @param parent 親の項目(ルートなら null)
 * @param name 名前
 */
open class FileSystemEntry protected constructor(
    var parent: FileSystemEntry?,
    var name: String,
) {
    /**
     * @brief サイズ。そのまま描画と並べ替えに使える形にしてある。
     *
     * 上位 40 ビットがブロック数、下位 24 ビットが表示用のサイズ(単位の種類と、その単位での値)。
     * 詳しい形式は下のコメントを参照。
     */
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

    /** @brief 子の項目(サイズの大きい順)。ファイルなら null。 */
    var children: Array<FileSystemEntry>? = null

    private var cachedSizeString: String? = null

    /** @brief サイズ(ブロック数)。 */
    val sizeInBlocks: Long
        get() = encodedSize shr BLOCK_OFFSET

    /** @brief 描画に使うサイズ(ブロック数を上位ビットに置いた値。表示用の下位ビットは除く)。 */
    val sizeForRendering: Long
        get() = encodedSize and BLOCK_MASK.inv()

    /** @brief 削除できるかどうか。 */
    open val isDeletable: Boolean
        get() = false

    /** @brief 含まれるファイルの数(ディレクトリは数えない)。 */
    open val numFiles: Int
        get() {
            val children = children ?: return 1
            val hasFile = children.any { it.children == null }
            return children.sumOf { it.numFiles } + if (hasFile) 1 else 0
        }

    /**
     * @brief サイズの表示文字列を返す。
     * @return 「1.23 MiB」などの文字列
     */
    fun sizeString(): String = calcSizeStringFromEncoded(encodedSize)

    /**
     * @brief サイズの表示文字列を返す(描画用にキャッシュする)。
     * @return サイズの表示文字列
     */
    private fun cachedSizeString(): String =
        cachedSizeString ?: sizeString().also { cachedSizeString = it }

    /** @brief サイズの表示文字列のキャッシュを捨てる(サイズを変えたときに呼ぶ)。 */
    fun clearSizeStringCache() {
        cachedSizeString = null
    }

    /**
     * @brief サイズをブロック数で設定する。
     * @param blocks ブロック数
     * @param blockSize ブロックサイズ(バイト)
     */
    fun setSizeInBlocks(blocks: Long, blockSize: Long) {
        encodedSize = (blocks shl BLOCK_OFFSET) or makeBytesPart(blocks * blockSize)
    }

    /**
     * @brief サイズをバイト数で設定する(ブロック数は切り上げ)。
     * @param bytes バイト数
     * @param blockSize ブロックサイズ(バイト)
     * @return この項目
     */
    fun initSizeInBytes(bytes: Long, blockSize: Long): FileSystemEntry = apply {
        val blocks = (bytes + blockSize - 1) / blockSize
        encodedSize = (blocks shl BLOCK_OFFSET) or makeBytesPart(bytes)
    }

    /**
     * @brief サイズをバイト数とブロック数で設定する。
     * @param bytes 表示するバイト数
     * @param blocks ブロック数
     * @return この項目
     */
    fun initSizeInBytesAndBlocks(bytes: Long, blocks: Long): FileSystemEntry = apply {
        encodedSize = (blocks shl BLOCK_OFFSET) or makeBytesPart(bytes)
    }

    /**
     * @brief 子を設定し、サイズを子の合計にする。
     * @param children 子(並べ替え済みのもの)。null ならファイルとして扱う
     * @param blockSize ブロックサイズ(バイト)
     * @return この項目
     */
    fun setChildren(children: Array<FileSystemEntry>?, blockSize: Long): FileSystemEntry = apply {
        this.children = children
        if (children == null) return@apply
        children.forEach { it.parent = this }
        setSizeInBlocks(children.sumOf { it.sizeInBlocks }, blockSize)
    }

    /**
     * @brief 同じ種類・名前の空の項目を作る(コピー用。派生クラスで上書きする)。
     * @return 親と子を持たない新しい項目
     */
    open fun create(): FileSystemEntry = FileSystemEntry(null, name)

    /** @brief 検索のスレッドが割り込まれたときに投げる例外。 */
    class SearchInterruptedException : RuntimeException()

    /**
     * @brief 子孫も含めて項目をコピーする。
     * @return コピー
     * @throws SearchInterruptedException スレッドが割り込まれたとき
     */
    fun copy(): FileSystemEntry {
        if (Thread.interrupted()) throw SearchInterruptedException()
        val copy = create()
        copy.children = children?.map { it.copy().apply { parent = copy } }?.toTypedArray()
        copy.encodedSize = encodedSize
        return copy
    }

    /**
     * @brief 子を検索語で絞り込んだコピーを作る。
     * @param pattern 検索語(小文字)
     * @param blockSize ブロックサイズ(バイト)
     * @return 一致した子だけを持つコピー。一致がなければ null
     */
    fun filterChildren(pattern: CharSequence, blockSize: Long): FileSystemEntry? {
        val children = children ?: return null
        val filtered = children.mapNotNull { it.filter(pattern, blockSize) }.toTypedArray()
        if (filtered.isEmpty()) return null
        filtered.sortWith(COMPARE)
        return create().setChildren(filtered, blockSize)
    }

    /**
     * @brief 検索語で絞り込んだコピーを作る。名前が一致すれば中身ごと残す。
     * @param pattern 検索語(小文字)
     * @param blockSize ブロックサイズ(バイト)
     * @return 絞り込んだコピー。一致がなければ null
     */
    open fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? {
        if (name.lowercase().contains(pattern)) {
            return copy()
        }
        return filterChildren(pattern, blockSize)
    }

    /**
     * @brief 直接の子が children の何番目かを返す。
     * @param directChild 直接の子
     * @return children の中の位置
     * @throws IllegalStateException 子でないとき
     */
    fun getIndexOf(directChild: FileSystemEntry): Int {
        val index = children?.indexOfFirst { it === directChild } ?: -1
        if (index == -1) throw IllegalStateException("${directChild.name} is not a child of $name")
        return index
    }

    /** @brief 同じ親の中で次の項目。最後ならこの項目自身。 */
    val next: FileSystemEntry
        get() {
            val siblings = parent!!.children!!
            return siblings.getOrNull(parent!!.getIndexOf(this) + 1) ?: this
        }

    /** @brief 同じ親の中で前の項目。先頭ならこの項目自身。 */
    val prev: FileSystemEntry
        get() {
            val siblings = parent!!.children!!
            return siblings.getOrNull(parent!!.getIndexOf(this) - 1) ?: this
        }

    /**
     * @brief この項目をルートとしてツリーを描画する(項目、特別な項目、カーソル)。
     * @param canvas 描画先
     * @param skin 見た目
     * @param bounds 描画する範囲(画面座標)
     * @param cursor カーソル
     * @param viewTop 表示範囲の上端(描画単位)
     * @param viewDepth 表示範囲の左端の深さ
     * @param yscale 描画単位から画面座標への縦の倍率
     * @param screenHeight 画面の高さ
     * @param numSpecialEntries 末尾にある特別な項目(空き容量など)の数
     */
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
     * @brief 画面の描画範囲を、ツリーの座標(ワールド座標)に変換したもの。
     *
     * 倍率の変換: 画面の y = yscale * ワールドの y。
     * 位置の変換: 画面の y = yscale * (ワールドの y - ルートの位置)。
     *
     * x 座標: xoffset は現在の項目の画面上の位置で、範囲は現在の項目の座標。
     * y 座標: yoffset は現在の項目の画面上の位置で、範囲は現在の項目からのワールド座標。
     *
     * @param bounds 描画する範囲(画面座標)
     * @param viewTop 表示範囲の上端(描画単位)
     * @param viewDepth 表示範囲の左端の深さ
     * @param yscale 縦の倍率
     */
    private class ViewClip(bounds: Rect, viewTop: Long, viewDepth: Float, yscale: Float) {
        private val viewLeft = (viewDepth * elementWidth).toInt()
        val top = (bounds.top / yscale).toLong() + viewTop
        val bottom = (bounds.bottom / yscale).toLong() + viewTop
        val left = (bounds.left + viewLeft).toLong()
        val xoffset = -viewLeft.toFloat()
        val yoffset = -viewTop * yscale
    }

    /**
     * @brief タイトルバーに出す文字列(名前、サイズ、子の数)を返す。
     * @return タイトルの文字列
     */
    fun toTitleString(): String {
        val sizeString = sizeString()
        val children = children
        return when {
            !children.isNullOrEmpty() -> dirNameSizeNumDirs.format(name, sizeString, children.size)
            sizeInBlocks == 0L -> dirEmpty.format(name)
            else -> dirNameSize.format(name, sizeString)
        }
    }

    /**
     * @brief マウントポイントからの相対パスを返す(最上位の 2 つの項目は含めない)。
     * @return `/` 区切りのパス
     */
    fun path2(): String =
        generateSequence(this) { it.parent }
            .map { it.name }
            .toList()
            .dropLast(2)
            .asReversed()
            .joinToString("/")

    /**
     * @brief 絶対パスを返す(最も近い FileSystemRoot のパスを起点にする)。
     * @return 絶対パス
     */
    fun absolutePath(): String {
        if (this is FileSystemRoot) {
            return rootPath
        }
        return (parent?.absolutePath() ?: "") + "/" + name
    }

    /**
     * @brief この項目から見た entry の深さを返す。
     * @param entry 子孫の項目
     * @return 直接の子なら 1、孫なら 2、…
     */
    fun depth(entry: FileSystemEntry): Int =
        generateSequence(entry) { it.parent }.takeWhile { it !== this }.count()

    /**
     * @brief この項目をルートとして、指定した深さと位置にある項目を探す。
     * @param maxDepth 探す最大の深さ
     * @param offset ルートの先頭からの位置(描画単位)
     * @return 条件に最も近い項目
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
     * @brief この項目の先頭から cursor の項目の先頭までの位置(描画単位のワールド座標)を返す。
     * @param cursor 子孫の項目
     * @return 位置
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

    /**
     * @brief この項目を親から取り除き、祖先のサイズを減らして並べ直す。
     * @param blockSize ブロックサイズ(バイト)
     */
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

    /**
     * @brief 子を追加し、自身と祖先のサイズを増やす。
     * @param newEntry 追加する項目
     * @param blockSize ブロックサイズ(バイト)
     */
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
     * @brief パスをたどって項目を探す。
     * @param path この項目からの相対パス
     * @param exactMatch 完全一致で探すかどうか(現在はどちらでも完全一致で探す)
     * @return 見つかった項目。なければ null
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
        /** @brief 文字のアセント(ベースラインから上端まで、負の値)。 */
        var ascent = 0f
            private set

        /** @brief 文字のディセント(ベースラインから下端まで)。 */
        var descent = 0f
            private set

        /** @brief 文字の高さ。FileSystemState からも使う。 */
        var fontSize = 0f
            private set

        /** @brief 1 階層分の項目の幅。画面の大きさが変わったときに FileSystemState が設定する。 */
        var elementWidth = 0

        /** @brief 削除のアニメーション中の項目(小さくても縞にせずに描く)。 */
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
        /** @brief encodedSize の中のブロック数の位置(ビット)。 */
        const val BLOCK_OFFSET = 24
        private const val BLOCK_MASK = (1L shl BLOCK_OFFSET) - 1

        private const val KB = 1024L
        private const val MB = 1024L * KB
        private const val GB = 1024L * MB

        /** @brief サイズの大きい順に並べるための比較。 */
        val COMPARE: Comparator<FileSystemEntry> =
            Comparator { a, b -> b.encodedSize.compareTo(a.encodedSize) }

        /**
         * @brief ディレクトリの項目を作る。
         * @param parent 親の項目
         * @param name 名前
         * @return 作成した項目
         */
        fun makeNode(parent: FileSystemEntry?, name: String) = FileSystemEntry(parent, name)

        /**
         * @brief バイト数を encodedSize の下位ビット(単位の種類と、その単位での値)にする。
         * @param size バイト数
         * @return 下位ビットの値
         */
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

        /** @brief Int と Long のビット和。 */
        private infix fun Int.or(other: Long): Long = toLong() or other

        /**
         * @brief encodedSize からサイズの表示文字列を作る。
         * @param encodedSize 項目のサイズ
         * @return 「1.23 MiB」などの文字列
         */
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
         * @brief バイト数からサイズの表示文字列を作る。
         *
         * 削除の確認画面のファイル一覧などで使う。
         *
         * @param size バイト数
         * @return 表示文字列
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

        /**
         * @brief サイズとタイトルの書式をリソースから読み込む(初回だけ)。
         * @param context Context
         */
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

        /**
         * @brief ラベルの配置に使う文字の寸法を更新する。
         * @param textPaint 文字の描画設定
         */
        fun updateFonts(textPaint: Paint) {
            ascent = textPaint.ascent()
            descent = textPaint.descent()
            fontSize = descent - ascent
        }

        /**
         * @brief ラベルの縦の中心を返す。
         *
         * 項目の中央に置くが、項目が画面から一部はみ出すときは画面内に収める。
         *
         * @param top 項目の上端
         * @param bottom 項目の下端
         * @param screenHeight 画面の高さ
         * @return ラベルの中心の y 座標
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

        /** @brief 項目の中でのラベルの横の位置。 */
        private const val LABEL_OFFSET = 6

        /**
         * @brief 項目の幅に収まるように名前を切り詰める。
         * @param entry 項目
         * @param paint 文字の描画設定
         * @return 切り詰めた名前
         */
        private fun clippedName(entry: FileSystemEntry, paint: Paint): String {
            val maxWidth = (elementWidth - LABEL_OFFSET - 3).toFloat()
            val cliplen = paint.breakText(entry.name, true, maxWidth, null)
            return entry.name.substring(0, cliplen)
        }

        /**
         * @brief 項目の名前と、余裕があればサイズを描く。
         * @param canvas 描画先
         * @param skin 見た目
         * @param entry 項目
         * @param left 左端
         * @param top 上端
         * @param bottom 下端
         * @param screenHeight 画面の高さ
         */
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

        /**
         * @brief 表示上のルートの末尾にある特別な項目(空き容量など)を描く。
         * @param entries 最上位の項目(先頭が表示上のルート)
         * @param canvas 描画先
         * @param skin 見た目
         * @param xoffset0 最上位の項目の画面上の x 座標
         * @param yoffset0 最上位の項目の画面上の y 座標
         * @param yscale 縦の倍率
         * @param clipLeft0 描画範囲の左端
         * @param clipTop 描画範囲の上端
         * @param clipBottom 描画範囲の下端
         * @param screenHeight 画面の高さ
         * @param numSpecial 特別な項目の数
         */
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
         * @brief 末尾 numSpecial 個の特別な項目(空き容量とシステムの領域)を順にたどる。
         *
         * 描画範囲の外にあるものは飛ばす。
         *
         * @param children 子の項目
         * @param yoffset0 先頭の子の画面上の y 座標
         * @param yscale 縦の倍率
         * @param clipTop 描画範囲の上端
         * @param clipBottom 描画範囲の下端
         * @param numSpecial 特別な項目の数
         * @param draw 各項目を描く処理(項目、上端、下端)
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

        /**
         * @brief 項目とその子孫を再帰的に描く。
         *
         * 描画範囲の外は飛ばし、高さが 4 ピクセル未満になったら残りをまとめて縞で塗る。
         *
         * @param parentSize0 親のサイズ(描画単位)
         * @param entries 描く項目(兄弟)
         * @param canvas 描画先
         * @param skin 見た目
         * @param xoffset 項目の画面上の x 座標
         * @param yoffset0 先頭の項目の画面上の y 座標
         * @param yscale 縦の倍率
         * @param clipLeft 描画範囲の左端
         * @param clipTop 描画範囲の上端
         * @param clipBottom 描画範囲の下端
         * @param clipRight 描画範囲の右端(画面座標)
         * @param screenHeight 画面の高さ
         */
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
