package com.google.android.diskusage.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.google.android.diskusage.filesystem.entity.FileSystemEntry

/** Draws the file system tree. */
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        state.onTouchEvent(state.multitouchHandler.newMyMotionEvent(ev))
        return true
    }

    override fun onDraw(canvas: Canvas) {
        state.onDraw(canvas, skin)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        state.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        FileSystemEntry.updateFonts(skin.textPaint)
        state.layout(width, height)
    }
}
