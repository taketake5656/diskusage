/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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
 * @file Scanner.kt
 * @brief Java のファイル API によるスキャナ(ネイティブスキャナが使えないときの代替)。
 */
package com.google.android.diskusage.core

import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import com.google.android.diskusage.datasource.LegacyFile
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import java.io.IOException
import timber.log.Timber

/**
 * @brief Java のファイル API でファイルシステムのツリーを作る。
 *
 * ネイティブスキャナが使えないときに使う。
 *
 * @param maxDepth 走査する最大の深さ。これより深い部分はサイズだけを合計する
 * @param blockSize 表示のブロックサイズ(バイト)
 * @param allocatedBlocks ストレージの使用済みブロック数(まとめる項目のしきい値の計算に使う)
 * @param maxHeap ツリーに使ってよいヒープの量(バイト)
 * @param smallEntryName 小さなファイルをまとめた項目の名前
 */
class Scanner(
    private val maxDepth: Int,
    blockSize: Long,
    allocatedBlocks: Long,
    maxHeap: Int,
    smallEntryName: SmallEntryName = SmallEntryName.DEFAULT,
) : TreeScanner(blockSize, allocatedBlocks, maxHeap, smallEntryName) {

    /**
     * @brief ディレクトリをスキャンしてツリーを作る。
     * @param file スキャンするディレクトリ
     * @return ツリーのルート
     * @throws IOException ディレクトリが見つからないとき
     */
    fun scan(file: LegacyFile): FileSystemEntry {
        val stat = try {
            Os.stat(file.canonicalPath)
        } catch (e: ErrnoException) {
            throw IOException("Failed to find root folder", e)
        }
        val root = scanDirectory(null, file, 0, stat.st_blocks / blockSizeIn512Bytes)
        restoreSmallLists()
        return root.node
    }

    /**
     * @brief ファイルの stat を取得する。
     * @param file 対象のファイル
     * @return stat の結果。取得できなければ null
     */
    private fun stat(file: LegacyFile): StructStat? = try {
        Os.stat(file.canonicalPath)
    } catch (e: ErrnoException) {
        null
    } catch (e: IOException) {
        null
    }

    /**
     * @brief ディレクトリとその子孫をすべてスキャンする。
     *
     * ディレクトリのサイズは、自身のブロックと子のサイズの合計になる。
     *
     * @param parent 親の項目
     * @param file スキャンするディレクトリ
     * @param depth ルートからの深さ
     * @param selfBlocks ディレクトリ自身のブロック数
     * @return 作成した項目と、そのヒープ使用量・ディレクトリ数・ファイル数
     */
    private fun scanDirectory(
        parent: FileSystemEntry?, file: LegacyFile, depth: Int, selfBlocks: Long,
    ): ScannedNode {
        val node = makeNode(parent, file.name.orEmpty())
        val nodeHeapSize = createdNodeSize

        if (depth == maxDepth) {
            node.setSizeInBlocks(calculateSize(file), blockSize)
            // FIXME: get num of dirs and files
            return ScannedNode(node, nodeHeapSize, numDirs = 1, numFiles = 0)
        }

        val names = try {
            file.list()
        } catch (e: SecurityException) {
            Timber.d(e, "list files")
            null
        } ?: return ScannedNode(node, nodeHeapSize, numDirs = 1, numFiles = 0)

        val dir = DirectoryBuilder(node, nodeHeapSize, selfBlocks)
        for (name in names) {
            val childFile = file.getChild(name) ?: continue
            val stat = stat(childFile) ?: continue
            val blocks = stat.st_blocks / blockSizeIn512Bytes
            if (childFile.isFile) {
                val child = makeNode(node, childFile.name.orEmpty())
                child.initSizeInBytesAndBlocks(stat.st_size, blocks)
                pos += child.sizeInBlocks
                lastCreatedFile = child
                dir.addFile(child)
            } else {
                dir.addDirectory(scanDirectory(node, childFile, depth + 1, blocks))
            }
        }
        return dir.finish()
    }

    /**
     * @brief ディレクトリのツリーをたどって項目のサイズを計算する(項目は作らない)。
     * @param file 項目に対応するファイル
     * @return 項目のサイズ(ブロック数)。リンクは 0
     */
    private fun calculateSize(file: LegacyFile): Long {
        if (file.isLink) return 0
        if (file.isFile) {
            return stat(file)?.st_blocks ?: 0
        }
        val list = try {
            file.listFiles()
        } catch (e: SecurityException) {
            Timber.e(e, "calculateSize: list files")
            null
        } ?: return 0
        return 1 + list.sumOf { calculateSize(it) }
    }
}
