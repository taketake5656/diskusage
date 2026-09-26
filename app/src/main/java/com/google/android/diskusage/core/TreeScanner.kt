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

import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemEntrySmall
import com.google.android.diskusage.filesystem.entity.FileSystemFile
import java.util.PriorityQueue
import timber.log.Timber

/** Reports the scan progress. */
interface ProgressGenerator {
    val lastCreatedFile: FileSystemEntry?
    val pos: Long
}

/**
 * Builds the file system tree within a heap budget.
 *
 * Small files of a directory are aggregated into a single [FileSystemEntrySmall].
 * They are restored at the end of the scan if the heap budget allows it.
 */
abstract class TreeScanner(
    protected val blockSize: Long,
    allocatedBlocks: Long,
    private val maxHeapSize: Int,
) : ProgressGenerator {
    protected val blockSizeIn512Bytes = blockSize / 512
    private val sizeThreshold = (allocatedBlocks shl FileSystemEntry.BLOCK_OFFSET) / (maxHeapSize / 2)

    private var heapSize = 0
    private val smallLists = PriorityQueue<SmallList>()

    /** Estimated heap size of the node created by the last [makeNode] call. */
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

    private class SmallList(
        val parent: FileSystemEntry,
        val children: Array<FileSystemEntry>,
        val heapSize: Int,
        blocks: Long,
    ) : Comparable<SmallList> {
        private val spaceEfficiency = blocks / heapSize.toFloat()

        override fun compareTo(other: SmallList): Int =
            spaceEfficiency.compareTo(other.spaceEfficiency)
    }

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

    /** Scanned directory with its heap size and number of dirs and files inside. */
    protected class ScannedNode(
        val node: FileSystemEntry,
        val heapSize: Int,
        val numDirs: Int,
        val numFiles: Int,
    )

    /** Collects children of a directory being scanned. */
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

        fun addFile(file: FileSystemEntry) = add(file, createdNodeSize, dirs = 0, files = 1)

        fun addDirectory(dir: ScannedNode) = add(dir.node, dir.heapSize, dir.numDirs, dir.numFiles)

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
                val msg = when {
                    numDirsSmall == 0 -> "<$numFilesSmall files>"
                    numFilesSmall == 0 -> "<$numDirsSmall dirs>"
                    else -> "<$numDirsSmall dirs and $numFilesSmall files>"
                }
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

    /** Restores small files which fit into the heap budget. */
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
