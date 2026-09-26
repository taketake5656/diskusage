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

import com.google.android.diskusage.datasource.fast.NativeScannerStream
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.mnt.MountPoint
import java.io.InputStream

/**
 * Builds the file system tree from the output of the native `scan` executable.
 *
 * The output is a stream of zero terminated fields, started with a zero byte:
 * - `D<name>\0<blocks>\0<bytes>\0` starts a directory, followed by its children
 *   and `Z` which ends the directory,
 * - `F<name>\0<blocks>\0<bytes>\0` is a file.
 *
 * Sizes in blocks are in 512 bytes units.
 */
class NativeScanner(
    blockSize: Long,
    allocatedBlocks: Long,
    maxHeap: Int,
    smallEntryName: SmallEntryName = SmallEntryName.DEFAULT,
) : TreeScanner(blockSize, allocatedBlocks, maxHeap, smallEntryName) {

    private lateinit var input: InputStream
    private val buffer = ByteArray(BUFFER_SIZE)
    private var offset = 0
    private var allocated = 0

    private fun move() {
        if (offset == 0) throw RuntimeException("Error: too large entity size")
        buffer.copyInto(buffer, 0, offset, allocated)
        allocated -= offset
        offset = 0
    }

    private fun read() {
        if (allocated == BUFFER_SIZE) {
            move()
        }
        val res = input.read(buffer, allocated, BUFFER_SIZE - allocated)
        if (res <= 0) {
            throw RuntimeException("Error: no more data")
        }
        allocated += res
    }

    private fun getByte(): Byte {
        while (offset >= allocated) {
            read()
        }
        return buffer[offset++]
    }

    private fun getLong(): Long {
        var res = 0L
        while (true) {
            val b = getByte()
            if (b.toInt() == 0) return res
            if (b < '0'.code.toByte() || b > '9'.code.toByte()) {
                throw RuntimeException("Error: number format error")
            }
            res = res * 10 + (b - '0'.code.toByte())
        }
    }

    private fun getString(): String {
        var startPos = offset
        while (true) {
            for (i in startPos until allocated) {
                if (buffer[i].toInt() == 0) {
                    val res = String(buffer, offset, i - offset, Charsets.UTF_8)
                    offset = i + 1
                    return res
                }
            }
            val scanned = allocated - offset
            read()
            startPos = offset + scanned
        }
    }

    private enum class Type {
        NONE,
        DIR,
        FILE
    }

    private fun getType(): Type = when (getByte().toInt().toChar()) {
        'D' -> Type.DIR
        'F' -> Type.FILE
        'Z' -> Type.NONE
        else -> throw RuntimeException("Error: incorrect entity type")
    }

    fun scan(mountPoint: MountPoint): FileSystemEntry =
        scan(NativeScannerStream.create(mountPoint.root, mountPoint.isRootRequired))

    fun scan(stream: InputStream): FileSystemEntry = stream.use {
        input = it
        // Skip anything printed before the start marker (e.g. by su)
        while (getByte().toInt() != 0) {
            // skip
        }
        if (getType() != Type.DIR) throw RuntimeException("Error: no mount point")
        val root = scanTree()
        restoreSmallLists()
        if (offset != allocated) {
            throw RuntimeException("Error: extra data, ${allocated - offset} bytes")
        }
        root
    }

    /** Scans the tree without recursion, which may be very deep. */
    private fun scanTree(): FileSystemEntry {
        val stack = ArrayDeque<DirectoryBuilder>()
        stack.addLast(startDirectory(null))
        while (true) {
            val dir = stack.last()
            when (getType()) {
                Type.DIR -> stack.addLast(startDirectory(dir))
                Type.FILE -> {
                    val file = makeNode(dir.node, getString())
                    val blocks = getLong() / blockSizeIn512Bytes
                    val bytes = getLong()
                    if (blocks == 0L) continue
                    file.initSizeInBytesAndBlocks(bytes, blocks)
                    pos += file.sizeInBlocks
                    lastCreatedFile = file
                    dir.addFile(file)
                }
                Type.NONE -> {
                    val scanned = stack.removeLast().finish()
                    val parent = stack.lastOrNull() ?: return scanned.node
                    parent.addDirectory(scanned)
                }
            }
        }
    }

    private fun startDirectory(parent: DirectoryBuilder?): DirectoryBuilder {
        val name = getString()
        val blocks = getLong() / blockSizeIn512Bytes
        /* bytes = */ getLong()
        val node = makeNode(parent?.node, name)
        return DirectoryBuilder(node, createdNodeSize, blocks)
    }

    private companion object {
        const val BUFFER_SIZE = 65536
    }
}
