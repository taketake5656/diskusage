package com.google.android.diskusage.opengl

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.diskusage.ui.FileSystemState
import com.google.android.diskusage.ui.FileSystemState.FileSystemView
import timber.log.Timber

class FileSystemViewGPU(
    context: Context,
    private val eventHandler: FileSystemState,
) : SurfaceView(context), FileSystemView, SurfaceHolder.Callback {
    // May be accessed from the super constructor
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var thread: RenderingThread

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        Timber.d("new FileSystemViewGPU")
        holder.setSizeFromLayout()
        holder.addCallback(this)
        eventHandler.setView(this)
        thread = RenderingThread(context, eventHandler)
        thread.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val event = eventHandler.multitouchHandler.newMyMotionEvent(ev)
        thread.addEvent { eventHandler.onTouchEvent(event) }
        return true
    }

    override fun runInRenderThread(r: Runnable) {
        thread.addEvent(r)
    }

    override fun requestRepaintGPU() {
        if (::thread.isInitialized) {
            thread.addEmptyEvent()
        }
    }

    override fun requestRepaint() {}

    override fun requestRepaint(l: Int, t: Int, r: Int, b: Int) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        thread.addEvent { eventHandler.onKeyDown(keyCode, event) }
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SEARCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Timber.d("Surface changed to: %s x %s", width, height)
        thread.surfaceChanged(width, height)
        requestRepaintGPU()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread.surfaceAvailable(holder, true)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        holder.removeCallback(this)
        thread.surfaceAvailable(holder, false)
    }

    override fun onDetachedFromWindow() {
        Timber.d("FileSystemViewGPU.onDetachedFromWindow")
        super.onDetachedFromWindow()
        thread.exit()
    }

    override fun invalidate() {
        super.invalidate()
        requestRepaintGPU()
    }

    override fun killRenderThread() {
        thread.exit()
    }
}
