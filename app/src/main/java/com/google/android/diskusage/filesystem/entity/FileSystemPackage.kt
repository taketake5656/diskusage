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

package com.google.android.diskusage.filesystem.entity

import android.content.pm.ApplicationInfo
import timber.log.Timber

class FileSystemPackage private constructor(
    name: String,
    val pkg: String,
    private var codeSize: Long,
    private var dataSize: Long,
    private var cacheSize: Long,
    private val flags: Int,
) : FileSystemEntry(null, name) {
    private val publicChildren = mutableListOf<FileSystemRoot>()

    enum class ChildType {
        CODE,
        DATA,
        CACHE
    }

    fun applyFilter(blockSize: Long) {
        clearSizeStringCache()
        val entries = publicChildren + listOf(
            makeNode(null, "apk").initSizeInBytes(codeSize, blockSize),
            makeNode(null, "data").initSizeInBytes(dataSize, blockSize),
            makeNode(null, "cache").initSizeInBytes(cacheSize, blockSize),
        )
        setSizeInBlocks(entries.sumOf { it.sizeInBlocks }, blockSize)
        entries.forEach { it.parent = this }
        children = entries.sortedWith(COMPARE).toTypedArray()
    }

    override fun create(): FileSystemEntry =
        FileSystemPackage(name, pkg, codeSize, dataSize, cacheSize, flags)

    fun addPublicChild(child: FileSystemRoot, type: ChildType, blockSize: Long) {
        publicChildren += child
        val size = child.sizeInBlocks * blockSize
        when (type) {
            ChildType.CODE -> codeSize = subtract(codeSize, size, "Code")
            ChildType.DATA -> dataSize = subtract(dataSize, size, "Data")
            ChildType.CACHE -> cacheSize = subtract(cacheSize, size, "Cache")
        }
    }

    private fun subtract(total: Long, size: Long, what: String): Long {
        val result = total - size
        if (result < 0) {
            Timber.d("addPublicChild: %s size negative %s for %s", what, result, pkg)
            return 0
        }
        return result
    }

    companion object {
        fun make(
            name: String, pkg: String, codeSize: Long, dataSize: Long, cacheSize: Long, flags: Int,
        ): FileSystemPackage {
            val isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            // TODO: not sure what happens with apps on external storage
            val onExternalStorage = (flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
            return FileSystemPackage(
                name, pkg,
                codeSize = if (isSystemApp || onExternalStorage) 0 else codeSize,
                dataSize = dataSize - cacheSize,
                cacheSize = cacheSize,
                flags = flags,
            )
        }
    }
}
