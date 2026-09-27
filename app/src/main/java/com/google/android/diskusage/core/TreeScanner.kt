/*
 * DiskUsage - displays sdcard usage on android.
 * (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2026 taketake5656
 *   2026: written for this fork, based on the original code
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
 * @file TreeScanner.kt
 * @brief スキャナ共通の、ヒープの予算内でのツリー構築。
 */
package com.google.android.diskusage.core

import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemEntrySmall
import com.google.android.diskusage.filesystem.entity.FileSystemFile
import java.util.PriorityQueue
import timber.log.Timber

/**
 * @brief 小さなファイルやディレクトリをまとめた項目の名前を作る。
 *
 * 表示用の文字列はリソースから作るため、外から差し込めるようにしている。
 */
fun interface SmallEntryName {
    /**
     * @brief 名前を作る。
     * @param numDirs まとめたディレクトリの数
     * @param numFiles まとめたファイルの数
     * @return 項目の名前
     */
    fun get(numDirs: Int, numFiles: Int): String

    companion object {
        /** @brief 英語の既定の名前(テストで使う)。 */
        val DEFAULT = SmallEntryName { numDirs, numFiles ->
            when {
                numDirs == 0 -> "<$numFiles files>"
                numFiles == 0 -> "<$numDirs dirs>"
                else -> "<$numDirs dirs and $numFiles files>"
            }
        }
    }
}

/** @brief スキャンの進捗を知らせる。 */
interface ProgressGenerator {
    /** @brief 最後に作ったファイルの項目(進捗ダイアログにファイル名を出すのに使う)。 */
    val lastCreatedFile: FileSystemEntry?
    /** @brief ここまでにスキャンしたファイルの合計サイズ(ブロック数)。 */
    val pos: Long
}

/**
 * @brief ヒープの予算内でファイルシステムのツリーを作る、スキャナ共通の基底クラス。
 *
 * ディレクトリ内の小さなファイルは 1 つの FileSystemEntrySmall にまとめる。
 * スキャンの最後に、ヒープの予算が許す分だけ元に戻す。
 *
 * @param blockSize 表示のブロックサイズ(バイト)
 * @param allocatedBlocks ストレージの使用済みブロック数(まとめる項目のしきい値の計算に使う)
 * @param maxHeapSize ツリーに使ってよいヒープの量(バイト)
 * @param smallEntryName まとめた項目の名前
 */
abstract class TreeScanner(
    protected val blockSize: Long,
    allocatedBlocks: Long,
    private val maxHeapSize: Int,
    private val smallEntryName: SmallEntryName,
) : ProgressGenerator {
    protected val blockSizeIn512Bytes = blockSize / 512
    private val sizeThreshold = (allocatedBlocks shl FileSystemEntry.BLOCK_OFFSET) / (maxHeapSize / 2)

    private var heapSize = 0
    private val smallLists = PriorityQueue<SmallList>()

    /** @brief 最後の makeNode で作った項目のヒープ使用量の見積もり(バイト)。 */
    protected var createdNodeSize = 0
        private set

    final override var pos = 0L
        protected set
    final override var lastCreatedFile: FileSystemEntry? = null
        protected set

    init {
        Timber.d("%s: allocatedBlocks %s", javaClass.simpleName, allocatedBlocks)
        Timber.d("%s: maxHeap %s", javaClass.simpleName, maxHeapSize)
        Timber.d("%s: sizeThreshold = %s", javaClass.simpleName,
            sizeThreshold / (1 shl FileSystemEntry.BLOCK_OFFSET).toFloat())
    }

    /**
     * @brief まとめた小さな項目の、元に戻す候補。
     *
     * ヒープあたりのサイズ(空間効率)が低いものから先に候補から外す。
     *
     * @param parent まとめた項目の親ディレクトリ
     * @param children まとめた項目
     * @param heapSize 元に戻すのに必要なヒープの量(バイト)
     * @param blocks まとめた項目の合計サイズ(ブロック数)
     */
    private class SmallList(
        val parent: FileSystemEntry,
        val children: Array<FileSystemEntry>,
        val heapSize: Int,
        blocks: Long,
    ) : Comparable<SmallList> {
        private val spaceEfficiency = blocks / heapSize.toFloat()

        /** @brief 空間効率で比較する。 */
        override fun compareTo(other: SmallList): Int =
            spaceEfficiency.compareTo(other.spaceEfficiency)
    }

    /**
     * @brief 項目を作り、ヒープ使用量を加算する。
     *
     * 予算を超えたら、空間効率の低い候補から元に戻すのをあきらめる。
     *
     * @param parent 親の項目
     * @param name 名前
     * @return 作成した項目
     */
    protected fun makeNode(parent: FileSystemEntry?, name: String): FileSystemEntry {
        createdNodeSize = (4 /* ref in FileSystemEntry[] */
            + 16 /* FileSystemEntry */
            + 8 + 10 /* aproximation of size string */
            + 8 /* name header */
            + name.length * 2) /* name length */
        heapSize += createdNodeSize
        while (heapSize > maxHeapSize && smallLists.isNotEmpty()) {
            val removed = smallLists.remove()
            heapSize -= removed.heapSize
        }
        return FileSystemFile.makeNode(parent, name)
    }

    /**
     * @brief スキャンし終えたディレクトリと、そのヒープ使用量、中のディレクトリとファイルの数。
     * @param node ディレクトリの項目
     * @param heapSize ヒープ使用量(バイト)
     * @param numDirs 中のディレクトリの数(自身を含む)
     * @param numFiles 中のファイルの数
     */
    protected class ScannedNode(
        val node: FileSystemEntry,
        val heapSize: Int,
        val numDirs: Int,
        val numFiles: Int,
    )

    /**
     * @brief スキャン中のディレクトリの子を集める。
     * @param node ディレクトリの項目
     * @param nodeHeapSize ディレクトリ自身のヒープ使用量(バイト)
     * @param selfBlocks ディレクトリ自身のブロック数
     */
    protected inner class DirectoryBuilder(
        val node: FileSystemEntry,
        private var nodeHeapSize: Int,
        private val selfBlocks: Long,
    ) {
        private var numDirs = 1
        private var numFiles = 0
        private var heapSizeSmall = 0
        private var numFilesSmall = 0
        private var numDirsSmall = 0
        private var smallBlocks = 0L
        private var blocks = 0L
        private val children = ArrayList<FileSystemEntry>()
        private val smallChildren = ArrayList<FileSystemEntry>()

        /**
         * @brief 直前の makeNode で作ったファイルを子に加える。
         * @param file ファイルの項目
         */
        fun addFile(file: FileSystemEntry) = add(file, createdNodeSize, dirs = 0, files = 1)

        /**
         * @brief スキャンし終えたディレクトリを子に加える。
         * @param dir ディレクトリ
         */
        fun addDirectory(dir: ScannedNode) = add(dir.node, dir.heapSize, dir.numDirs, dir.numFiles)

        /**
         * @brief 子を加える。ヒープ使用量に比べてサイズが小さいものは、まとめる候補にする。
         * @param child 子の項目
         * @param childHeapSize 子のヒープ使用量(バイト)
         * @param dirs 子に含まれるディレクトリの数
         * @param files 子に含まれるファイルの数
         */
        private fun add(child: FileSystemEntry, childHeapSize: Int, dirs: Int, files: Int) {
            val childBlocks = child.sizeInBlocks
            blocks += childBlocks
            if (childHeapSize * sizeThreshold > child.encodedSize) {
                smallChildren += child
                heapSizeSmall += childHeapSize
                numFilesSmall += files
                numDirsSmall += dirs
                smallBlocks += childBlocks
            } else {
                children += child
                nodeHeapSize += childHeapSize
                numFiles += files
                numDirs += dirs
            }
        }

        /**
         * @brief ディレクトリのサイズを確定し、子を並べ替えて設定する。
         *
         * 小さな子は、ディレクトリ全体でも小さければそのまま残し、そうでなければ
         * FileSystemEntrySmall にまとめて末尾に置く(後で元に戻す候補にする)。
         *
         * @return スキャンし終えたディレクトリ
         */
        fun finish(): ScannedNode {
            node.setSizeInBlocks(blocks + selfBlocks, blockSize)
            numDirs += numDirsSmall
            numFiles += numFilesSmall

            var smallFilesEntry: FileSystemEntry? = null
            if ((heapSizeSmall + nodeHeapSize) * sizeThreshold <= node.encodedSize ||
                smallChildren.isEmpty()
            ) {
                children += smallChildren
                nodeHeapSize += heapSizeSmall
            } else {
                val msg = smallEntryName.get(numDirsSmall, numFilesSmall)
                // for heap accounting
                makeNode(node, msg)
                smallFilesEntry = FileSystemEntrySmall(node, msg, numFilesSmall + numDirsSmall)
                smallFilesEntry.setSizeInBlocks(smallBlocks, blockSize)
                children += smallFilesEntry
                nodeHeapSize += createdNodeSize
                smallLists += SmallList(node, smallChildren.toTypedArray(), heapSizeSmall, smallBlocks)
            }

            if (children.isNotEmpty()) {
                // Sort children and keep small files last in the array.
                val sorted = children.toTypedArray()
                val small = smallFilesEntry
                if (small == null) {
                    sorted.sortWith(FileSystemEntry.COMPARE)
                } else {
                    val smallSize = small.encodedSize
                    small.encodedSize = -1
                    sorted.sortWith(FileSystemEntry.COMPARE)
                    small.encodedSize = smallSize
                }
                node.children = sorted
            }
            return ScannedNode(node, nodeHeapSize, numDirs, numFiles)
        }
    }

    /** @brief ヒープの予算に収まる分だけ、まとめた小さな項目を元に戻す。 */
    protected fun restoreSmallLists() {
        var extraHeap = 0
        for (list in smallLists) {
            val oldChildren = list.parent.children!!
            val newChildren = list.children + oldChildren.filter { it !is FileSystemEntrySmall }
            newChildren.sortWith(FileSystemEntry.COMPARE)
            list.parent.children = newChildren
            extraHeap += list.heapSize
        }
        Timber.d("allocated %s B of extra heap", extraHeap)
    }
}
