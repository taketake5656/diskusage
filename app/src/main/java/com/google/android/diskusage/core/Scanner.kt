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

package com.google.android.diskusage.core

import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import com.google.android.diskusage.datasource.LegacyFile
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import java.io.IOException
import timber.log.Timber

/**
 * Builds the file system tree using Java file API. Used when the native
 * scanner is not available.
 */
class Scanner(
    private val maxDepth: Int,
    blockSize: Long,
    allocatedBlocks: Long,
    maxHeap: Int,
) : TreeScanner(blockSize, allocatedBlocks, maxHeap) {

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

    private fun stat(file: LegacyFile): StructStat? = try {
        Os.stat(file.canonicalPath)
    } catch (e: ErrnoException) {
        null
    } catch (e: IOException) {
        null
    }

    /**
     * Scans the directory and all its descendants. Size of the directory is
     * calculated as a sum of all its children.
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
     * Calculate size of the entry reading directory tree
     * @param file is file corresponding to this entry
     * @return size of entry in blocks
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
