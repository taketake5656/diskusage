/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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
 * @file FileSystemView.kt
 * @brief ファイルシステムのツリーを描画するビュー。
 */
package com.google.android.diskusage.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.google.android.diskusage.filesystem.entity.FileSystemEntry

/**
 * @brief ファイルシステムのツリーを描画するビュー。
 *
 * 描画と入力の処理は FileSystemState に任せ、見た目は TreeSkin で決める。
 *
 * @param context Context
 * @param state 表示状態と操作を扱うオブジェクト
 */
@SuppressLint("ViewConstructor")
class FileSystemView(
    context: Context,
    private val state: FileSystemState,
) : View(context) {

    private val skin = TreeSkin(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.GRAY)
        state.setView(this)
    }

    /**
     * @brief タッチ操作を FileSystemState に渡す。
     * @param ev タッチイベント
     * @return 常に true(イベントを消費する)
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        state.onTouchEvent(state.multitouchHandler.newMyMotionEvent(ev))
        return true
    }

    /**
     * @brief ツリーを描画する。
     * @param canvas 描画先
     */
    override fun onDraw(canvas: Canvas) {
        state.onDraw(canvas, skin)
    }

    /**
     * @brief キー操作を FileSystemState に渡す。
     * @param keyCode キーコード
     * @param event キーイベント
     * @return 処理したら true
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        state.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    /**
     * @brief 大きさが決まったら文字の大きさと表示範囲を更新する。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        FileSystemEntry.updateFonts(skin.textPaint)
        state.layout(width, height)
    }
}
