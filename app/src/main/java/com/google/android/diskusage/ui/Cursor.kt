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
 * @file Cursor.kt
 * @brief ツリー上の選択中の項目(カーソル)。
 */
package com.google.android.diskusage.ui

import com.google.android.diskusage.filesystem.entity.FileSystemEntry

/**
 * @brief 選択中の項目と、そのツリー上の位置。
 *
 * 位置は、ルートの先頭からの縦方向のオフセット(描画単位)と階層の深さで表す。
 *
 * @param state 移動を通知する表示状態
 * @param root ツリーのルート(最初の子を初期位置にする)
 */
class Cursor internal constructor(state: FileSystemState, private val root: FileSystemEntry) {
    /** @brief 選択中の項目。 */
    var position: FileSystemEntry
        private set
    /** @brief 選択中の項目の上端の、ルートからのオフセット(描画単位)。 */
    var top = 0L
        private set
    /** @brief 選択中の項目の深さ(ルートの子が 0)。 */
    var depth = 0
        private set

    init {
        val children = root.children
        check(!children.isNullOrEmpty()) { "no place for position" }
        position = children[0]
        updateTitle(state)
    }

    /**
     * @brief カーソルの移動を表示状態に通知する(タイトルの更新など)。
     * @param state 表示状態
     */
    private fun updateTitle(state: FileSystemState) {
        state.onCursorMoved(position)
    }

    /**
     * @brief 位置を変更し、再描画と通知を行う。
     * @param state 表示状態
     * @param change 位置を変更する処理
     */
    private inline fun move(state: FileSystemState, change: () -> Unit) {
        change()
        state.requestRepaint()
        updateTitle(state)
    }

    /**
     * @brief 次の兄弟の項目に移動する(最後なら何もしない)。
     * @param state 表示状態
     */
    internal fun down(state: FileSystemState) {
        val newCursor = position.next
        if (newCursor === position) return
        move(state) {
            top += position.sizeForRendering
            position = newCursor
        }
    }

    /**
     * @brief 前の兄弟の項目に移動する(先頭なら何もしない)。
     * @param state 表示状態
     */
    internal fun up(state: FileSystemState) {
        val newCursor = position.prev
        if (newCursor === position) return
        move(state) {
            top -= newCursor.sizeForRendering
            position = newCursor
        }
    }

    /**
     * @brief 最初の子の項目に移動する(子がなければ何もしない)。
     * @param state 表示状態
     */
    internal fun right(state: FileSystemState) {
        val children = position.children
        if (children.isNullOrEmpty()) return
        move(state) {
            position = children[0]
            depth++
        }
    }

    /**
     * @brief 親の項目に移動する(親がルートなら何もしない)。
     * @param state 表示状態
     */
    internal fun left(state: FileSystemState) {
        val parent = position.parent
        if (parent == null || parent === root) return
        move(state) {
            position = parent
            top = root.getOffset(position)
            depth--
        }
    }

    /**
     * @brief 指定した項目に移動し、深さとオフセットを計算し直す。
     * @param state 表示状態
     * @param newPosition 移動先の項目(ルート以外)
     */
    internal fun set(state: FileSystemState, newPosition: FileSystemEntry) {
        check(newPosition !== root) { "will break zoomOut()" }
        move(state) {
            position = newPosition
            depth = root.depth(position) - 1
            top = root.getOffset(position)
        }
    }
}
