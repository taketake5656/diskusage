package com.google.android.diskusage.opengl

import android.view.SurfaceHolder
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock
import timber.log.Timber

/**
 * Rendering thread which owns the EGL context and runs events posted from
 * other threads between the frames.
 */
abstract class AbstractRenderingThread : Thread() {
    abstract fun renderFrame(gl: GL10): Boolean
    abstract fun sizeChanged(gl: GL10, w: Int, h: Int)
    abstract fun createResources(gl: GL10)
    abstract fun releaseResources(gl: GL10)

    private class ExitException : RuntimeException()

    private val lock = ReentrantLock()
    private val eventAdded = lock.newCondition()
    private val events = ArrayDeque<Runnable>()

    /**
     * True when surfaceAvailable callback was received from surfaceHolder and
     * surfaceDestroyed wasn't yet received.
     * egl is initialized able to render.
     */
    private var surfaceAvailable = false

    /** Window geometry received by [surfaceChanged]. */
    private var sizeInitialized = false

    /** Repaint was requested due to unfinished animation on drawFrame(). */
    private var renderLoop = true

    /** Repaint was requested using [addEmptyEvent] call. */
    private var repaintEvent = false

    /** Stop rendering thread request received. */
    private var stopRenderingThread = false

    private lateinit var eglTools: EglTools

    lateinit var gl: GL10
        private set

    override fun run() {
        eglTools = EglTools()
        gl = eglTools.gl
        try {
            while (true) {
                runEvents()
                renderLoop = renderFrame(gl)
                eglTools.swapBuffers()
            }
        } catch (e: ExitException) {
            Timber.d("run: Rendering thread exited cleanly")
        } catch (e: InterruptedException) {
            Timber.e(e, "run: Rendering thread was interrupted")
        }
    }

    private fun runEvents() {
        while (true) {
            val event = lock.withLock {
                if (events.isEmpty()) {
                    if (stopRenderingThread && !surfaceAvailable) {
                        Timber.d("*** Rendering thread is about to finish. ***")
                        throw ExitException()
                    }
                    if (surfaceAvailable && sizeInitialized && !stopRenderingThread &&
                        (renderLoop || repaintEvent)
                    ) {
                        repaintEvent = false
                        return
                    }
                    eventAdded.await()
                    null
                } else {
                    events.removeFirst()
                }
            } ?: continue
            if (event is ControlEvent || !stopRenderingThread) {
                event.run()
            }
        }
    }

    fun addEvent(event: Runnable) {
        lock.withLock {
            events.addLast(event)
            eventAdded.signal()
        }
    }

    fun addEmptyEvent() {
        lock.withLock {
            repaintEvent = true
            eventAdded.signal()
        }
    }

    fun surfaceAvailable(holder: SurfaceHolder, available: Boolean) {
        addEvent(ControlEvent {
            surfaceAvailable = available
            if (available) {
                eglTools.initSurface(holder)
                createResources(gl)
            } else {
                eglTools.destroySurface()
                releaseResources(gl)
            }
        })
    }

    fun surfaceChanged(width: Int, height: Int) {
        addEvent(ControlEvent {
            sizeChanged(gl, width, height)
            sizeInitialized = width > 0 && height > 0
        })
    }

    fun exit() {
        addEvent(ControlEvent {
            stopRenderingThread = true
            releaseResources(gl)
        })
    }

    /** Events which are executed even after the stop request. */
    private class ControlEvent(private val action: () -> Unit) : Runnable {
        override fun run() = action()
    }

    private class EglTools {
        private val egl = EGLContext.getEGL() as EGL10
        private val eglDisplay: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        private val eglConfig: EGLConfig
        private val eglContext: EGLContext
        private var surface: EGLSurface? = null

        init {
            egl.eglInitialize(eglDisplay, IntArray(2))
            val configSpec = intArrayOf(EGL10.EGL_DEPTH_SIZE, 6, EGL10.EGL_NONE)
            val matchedConfigs = arrayOfNulls<EGLConfig>(1)
            egl.eglChooseConfig(eglDisplay, configSpec, matchedConfigs, 1, IntArray(1))
            eglConfig = matchedConfigs[0]!!
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, null)
        }

        val gl: GL10
            get() = eglContext.gl as GL10

        fun initSurface(holder: SurfaceHolder) {
            Timber.d("*** Init Surface ****")
            // Note: I haven't found how to avoid race condition with surfaceCreated
            // and surfaceDestroyed in SurfaceHolder.Callback and the renderer thread.
            try {
                val surface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, holder, null)
                this.surface = surface
                egl.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
            } catch (e: Exception) {
                Timber.e(e, "initSurface")
            }
        }

        fun destroySurface() {
            Timber.d("*** Destroy Surface ***")
            try {
                egl.eglMakeCurrent(
                    eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                egl.eglDestroySurface(eglDisplay, surface)
                egl.eglDestroyContext(eglDisplay, eglContext)
                egl.eglTerminate(eglDisplay)
            } catch (e: Exception) {
                Timber.e(e, "destroySurface")
            }
        }

        fun swapBuffers() {
            egl.eglSwapBuffers(eglDisplay, surface)
        }
    }
}
