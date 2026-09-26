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

package com.google.android.diskusage.ui

import com.google.android.diskusage.filesystem.entity.FileSystemEntry

/** Selected entry and its position in the tree. */
class Cursor internal constructor(state: FileSystemState, private val root: FileSystemEntry) {
    var position: FileSystemEntry
        private set
    var top = 0L
        private set
    var depth = 0
        private set

    init {
        val children = root.children
        check(!children.isNullOrEmpty()) { "no place for position" }
        position = children[0]
        updateTitle(state)
    }

    private fun updateTitle(state: FileSystemState) {
        state.onCursorMoved(position)
    }

    private inline fun move(state: FileSystemState, change: () -> Unit) {
        change()
        state.requestRepaint()
        updateTitle(state)
    }

    internal fun down(state: FileSystemState) {
        val newCursor = position.next
        if (newCursor === position) return
        move(state) {
            top += position.sizeForRendering
            position = newCursor
        }
    }

    internal fun up(state: FileSystemState) {
        val newCursor = position.prev
        if (newCursor === position) return
        move(state) {
            top -= newCursor.sizeForRendering
            position = newCursor
        }
    }

    internal fun right(state: FileSystemState) {
        val children = position.children
        if (children.isNullOrEmpty()) return
        move(state) {
            position = children[0]
            depth++
        }
    }

    internal fun left(state: FileSystemState) {
        val parent = position.parent
        if (parent == null || parent === root) return
        move(state) {
            position = parent
            top = root.getOffset(position)
            depth--
        }
    }

    internal fun set(state: FileSystemState, newPosition: FileSystemEntry) {
        check(newPosition !== root) { "will break zoomOut()" }
        move(state) {
            position = newPosition
            depth = root.depth(position) - 1
            top = root.getOffset(position)
        }
    }
}
