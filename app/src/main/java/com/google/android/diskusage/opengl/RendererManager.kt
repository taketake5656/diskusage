package com.google.android.diskusage.opengl

import android.content.Context
import androidx.core.content.edit
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.ui.DiskUsage
import com.google.android.diskusage.ui.FileSystemState
import com.google.android.diskusage.ui.FileSystemViewCPU

/** Switches between the OpenGL and the Canvas based renderers. */
class RendererManager(private val diskusage: DiskUsage) {
    private var hwRenderer = false
    private var rendererChanged = false

    private val prefs
        get() = diskusage.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun switchRenderer(root: FileSystemSuperRoot?) {
        val state = diskusage.fileSystemState ?: return
        state.killRenderThread()
        hwRenderer = !hwRenderer
        rendererChanged = true
        makeView(state, root)
    }

    fun makeView(eventHandler: FileSystemState, root: FileSystemSuperRoot?) {
        val view = if (hwRenderer) {
            FileSystemViewGPU(diskusage, eventHandler)
        } else {
            FileSystemViewCPU(diskusage, eventHandler)
        }
        diskusage.menu.wrapAndSetContentView(view, root)
        view.requestFocus()
    }

    fun onResume() {
        hwRenderer = prefs.getBoolean(HW_RENDERER, true)
    }

    fun onPause() {
        if (rendererChanged) {
            prefs.edit { putBoolean(HW_RENDERER, hwRenderer) }
        }
    }

    private companion object {
        const val HW_RENDERER = "hw_renderer"
    }
}
