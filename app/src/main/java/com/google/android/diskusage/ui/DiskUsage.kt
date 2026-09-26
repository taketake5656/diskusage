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

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.webkit.MimeTypeMap
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.android.diskusage.BuildConfig
import com.google.android.diskusage.R
import com.google.android.diskusage.core.NativeScanner
import com.google.android.diskusage.core.ProgressGenerator
import com.google.android.diskusage.core.Scanner
import com.google.android.diskusage.databinding.ActivityCommonBinding
import com.google.android.diskusage.datasource.fast.LegacyFileImpl
import com.google.android.diskusage.filesystem.Apps2SDLoader
import com.google.android.diskusage.filesystem.BackgroundDelete
import com.google.android.diskusage.filesystem.FileSystemStats
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemEntrySmall
import com.google.android.diskusage.filesystem.entity.FileSystemFreeSpace
import com.google.android.diskusage.filesystem.entity.FileSystemPackage
import com.google.android.diskusage.filesystem.entity.FileSystemRoot
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.filesystem.entity.FileSystemSystemSpace
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.utils.applySystemBarsPadding
import java.io.File
import java.io.IOException
import splitties.toast.toast
import timber.log.Timber

class DiskUsage : LoadableActivity() {
    // FIXME: wrap to direct requests to rendering thread
    var fileSystemState: FileSystemState? = null
        private set

    private lateinit var mountKey: String
    override val key: String
        get() = mountKey

    private var pathToDelete: String? = null

    val menu = DiskUsageMenu(this)
    private val viewModel: DiskUsageViewModel by viewModels()
    private val afterLoadActions = mutableListOf<Runnable>()

    private val deleteConfirmation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_DELETE_CONFIRMED) {
                pathToDelete = result.data?.getStringExtra(DELETE_PATH_KEY)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("DiskUsage.onCreate()")
        setContentView(ActivityCommonBinding.inflate(layoutInflater).root)
        menu.onCreate(viewModel)
        applySystemBarsPadding()
        // KEYCODE_BACK is no longer dispatched to views with predictive back
        onBackPressedDispatcher.addCallback(this) { finishOnBack() }

        val key = intent.getStringExtra(KEY_KEY)
        val mountPoint = key?.let { MountPoint.getForKey(this, it) }
        if (key == null || mountPoint == null) {
            // Just close instead of crashing later
            finish()
            return
        }
        mountKey = key
        val receivedState = intent.getBundleExtra(STATE_KEY)
        Timber.d("DiskUsage.onCreate(), rootPath = %s, receivedState = %s",
            mountPoint.root, receivedState)
        if (receivedState != null) onRestoreInstanceState(receivedState)
    }

    fun applyPatternNewRoot(newRoot: FileSystemSuperRoot?) {
        if (newRoot != null) {
            fileSystemState?.replaceRootKeepCursor(newRoot)
        }
    }

    override fun onResume() {
        super.onResume()
        pkgRemoved?.let { pkg ->
            // Check if package removed
            if (!isPackageInstalled(pkg.pkg)) {
                fileSystemState?.removeEntry(pkg)
            }
            pkgRemoved = null
        }
        loadFiles({ root, isCached ->
            val state = FileSystemState(this, root)
            fileSystemState = state
            val view = FileSystemView(this, state)
            menu.wrapAndSetContentView(view, root)
            view.requestFocus()
            state.startZoomAnimation(null, !isCached)

            afterLoadActions.forEach { it.run() }
            afterLoadActions.clear()
            pathToDelete?.let {
                pathToDelete = null
                continueDelete(it)
            }
        }, false)
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    override fun onPause() {
        super.onPause()
        fileSystemState?.let { state ->
            val savedState = Bundle()
            state.saveState(savedState)
            afterLoadActions += Runnable { fileSystemState?.restoreState(savedState) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        this.menu.setupToolbarMenu(menu)
        return true
    }

    private fun viewPackage(pkg: FileSystemPackage) {
        Timber.d("Show package = %s", pkg.pkg)
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${pkg.pkg}".toUri()))
        // FIXME: reload package data instead of just removing it
        pkgRemoved = pkg
    }

    private fun continueDelete(path: String) {
        val entry = fileSystemState?.masterRoot?.getEntryByName(path, true)
        if (entry != null) {
            BackgroundDelete.startDelete(this, entry)
        } else {
            toast("Oops. Can't find directory to be deleted.")
        }
    }

    fun askForDeletion(entry: FileSystemEntry) {
        val path = entry.path2()
        val fullPath = entry.absolutePath()
        Timber.d("Deletion requested for %s", path)

        if (entry is FileSystemEntrySmall) {
            toast("Delete directory instead")
            return
        }
        if (entry.children.isNullOrEmpty()) {
            if (entry is FileSystemPackage) {
                pkgRemoved = entry
                BackgroundDelete.startDelete(this, entry)
                return
            }

            // Delete single file or directory
            val title = if (File(fullPath).isDirectory) {
                getString(R.string.ask_to_delete_directory, path)
            } else {
                getString(R.string.ask_to_delete_file, path)
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setPositiveButton(R.string.button_delete) { _, _ -> BackgroundDelete.startDelete(this, entry) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            deleteConfirmation.launch(Intent(this, DeleteActivity::class.java).apply {
                putExtra(DELETE_PATH_KEY, path)
                putExtra(DELETE_ABSOLUTE_PATH_KEY, fullPath)
                putExtra(DeleteActivity.NUM_FILES_KEY, entry.numFiles)
                putExtra(KEY_KEY, key)
                putExtra(DeleteActivity.SIZE_KEY, entry.sizeString())
            })
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun view(selected: FileSystemEntry) {
        val entry = if (selected is FileSystemEntrySmall) selected.parent!! else selected
        if (entry is FileSystemPackage) {
            viewPackage(entry)
            return
        }
        (entry.parent as? FileSystemPackage)?.let {
            viewPackage(it)
            return
        }

        val path = entry.absolutePath()
        val file = File(path)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file)
        } else {
            file.toUri()
        }

        if (file.isDirectory) {
            // Go on with default file manager
            val intents = listOf(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "inode/directory"),
                Intent("org.openintents.action.VIEW_DIRECTORY").setData(uri),
                Intent("org.openintents.action.PICK_DIRECTORY").setData(uri)
                    .putExtra("org.openintents.extra.TITLE", getString(R.string.title_in_oi_file_manager))
                    .putExtra("org.openintents.extra.BUTTON_TEXT",
                        getString(R.string.button_text_in_oi_file_manager)),
                // old Astro
                Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT)
                    .setDataAndType(uri, "vnd.android.cursor.item/com.metago.filemanager.dir"),
            )
            if (intents.none { tryStartActivity(it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                toast(R.string.no_viewer_found)
            }
            return
        }

        val extension = entry.name.substringAfterLast('.', "").lowercase()
        Timber.d("name: %s path: %s extension: %s", entry.name, path, extension)
        if (extension.isNotEmpty()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            Timber.d("extension: %s mime: %s", extension, mime)
            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setDataAndType(uri, mime ?: "binary/octet-stream")
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (tryStartActivity(intent)) return
            Timber.e("Can't open viewer for %s", path)
        }
        toast(R.string.no_viewer_found)
    }

    fun rescan() {
        loadFiles({ newRoot, isCached ->
            fileSystemState?.startZoomAnimation(newRoot, !isCached)
        }, true)
    }

    fun finishOnBack() {
        if (!menu.readyToFinish()) {
            return
        }
        val outState = Bundle()
        onSaveInstanceState(outState)
        setResult(0, Intent().putExtra(STATE_KEY, outState).putExtra(KEY_KEY, key))
        finish()
    }

    fun setSelectedEntity(position: FileSystemEntry) {
        menu.update(position)
        title = getString(R.string.title_for_path, position.toTitleString())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishOnBack()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = fileSystemState ?: return
        state.saveState(outState)
        menu.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        Timber.d("DiskUsage.onRestoreInstanceState(), rootPath = %s",
            savedInstanceState.getString(KEY_KEY))
        val state = fileSystemState
        if (state != null) {
            state.restoreState(savedInstanceState)
        } else {
            afterLoadActions += Runnable {
                fileSystemState?.restoreState(savedInstanceState)
            }
        }
        menu.onRestoreInstanceState(savedInstanceState)
    }

    private val memoryQuota: Int
        get() {
            val totalMem = getSystemService<ActivityManager>()!!.memoryClass * 1024 * 1024
            val numMountPoints = MountPoint.getMountPoints(this).size
            return totalMem / (numMountPoints + 1)
        }

    /** Periodically updates the progress dialog while scanning. */
    private inline fun <T> withProgress(
        scanner: ProgressGenerator,
        stats: FileSystemStats,
        scan: () -> T,
    ): T {
        val progressUpdater = object : Runnable {
            private var file: FileSystemEntry? = null

            override fun run() {
                persistentState.loading?.let { dialog ->
                    dialog.setMax(stats.busyBlocks)
                    val lastFile = scanner.lastCreatedFile
                    if (lastFile != null && lastFile !== file) {
                        dialog.setProgress(scanner.pos, lastFile)
                    }
                    file = lastFile
                }
                handler.postDelayed(this, 50)
            }
        }
        handler.post(progressUpdater)
        try {
            return scan()
        } finally {
            handler.removeCallbacks(progressUpdater)
        }
    }

    override fun scan(): FileSystemSuperRoot {
        val mountPoint = MountPoint.getForKey(this, key)!!
        val stats = FileSystemStats(mountPoint)
        val heap = memoryQuota

        val rootElement = try {
            val scanner = NativeScanner(stats.blockSize, stats.busyBlocks, heap)
            withProgress(scanner, stats) { scanner.scan(mountPoint) }
        } catch (e: Exception) {
            if (e !is RuntimeException && e !is IOException) throw e
            Timber.w(e, "Native scanner failed, falling back to Java scanner")
            val scanner = Scanner(20, stats.blockSize, stats.busyBlocks, heap)
            withProgress(scanner, stats) {
                scanner.scan(LegacyFileImpl.createRoot(mountPoint.root))
            }
        }

        var entries = rootElement.children.orEmpty().toMutableList()

        if (mountPoint.hasApps) {
            val media = FileSystemRoot.makeNode(getString(R.string.graph_media), mountPoint.root, false)
            media.setChildren(entries.toTypedArray(), stats.blockSize)
            entries = mutableListOf(media)

            loadApps2SD(stats.blockSize)?.let { apps ->
                val sortedApps = moveAppData(apps, media, stats.blockSize)
                entries += FileSystemEntry.makeNode(null, getString(R.string.graph_apps))
                    .setChildren(sortedApps.toTypedArray(), stats.blockSize)
            }
        }

        val visibleBlocks = entries.sumOf { it.sizeInBlocks }
        val systemBlocks = stats.totalBlocks - stats.freeBlocks - visibleBlocks
        entries.sortWith(FileSystemEntry.COMPARE)
        if (systemBlocks > 0) {
            entries += FileSystemSystemSpace(getString(R.string.graph_system_data),
                systemBlocks * stats.blockSize, stats.blockSize)
            entries += FileSystemFreeSpace(getString(R.string.graph_free_space),
                stats.freeBlocks * stats.blockSize, stats.blockSize)
        } else {
            val freeBlocks = stats.freeBlocks + systemBlocks
            if (freeBlocks > 0) {
                entries += FileSystemFreeSpace(getString(R.string.graph_free_space),
                    freeBlocks * stats.blockSize, stats.blockSize)
            }
        }

        val root = FileSystemRoot.makeNode(mountPoint.title, mountPoint.root, false)
            .setChildren(entries.toTypedArray(), stats.blockSize)
        return FileSystemSuperRoot(stats.blockSize).apply {
            setChildren(arrayOf(root), stats.blockSize)
        }
    }

    private fun loadApps2SD(blockSize: Long): List<FileSystemPackage>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Apps2SDLoader(this).load(blockSize) else null
    } catch (t: Throwable) {
        Timber.e(t, "loadApps2SD: Problem loading apps2sd info")
        null
    }

    private class AppDir(val dirs: List<File?>, val name: String, val type: FileSystemPackage.ChildType)

    /**
     * Moves the directories of the apps, which are found in the media part of
     * the tree, into the app entries.
     * @return apps sorted by the updated size
     */
    private fun moveAppData(
        apps: List<FileSystemPackage>, media: FileSystemRoot, blockSize: Long,
    ): List<FileSystemPackage> {
        @Suppress("DEPRECATION")
        val appDirs = listOf(
            AppDir(listOf(cacheDir), "Cache", FileSystemPackage.ChildType.CACHE),
            AppDir(listOf(codeCacheDir), "CodeCache", FileSystemPackage.ChildType.CACHE),
            AppDir(listOf(externalCacheDir), "ExternalCache", FileSystemPackage.ChildType.CACHE),
            AppDir(listOf(ContextCompat.getDataDir(this)), "Data", FileSystemPackage.ChildType.DATA),
            AppDir(listOf(filesDir), "InternalFiles", FileSystemPackage.ChildType.DATA),
            AppDir(listOf(getExternalFilesDir(null)), "Files", FileSystemPackage.ChildType.DATA),
            AppDir(externalMediaDirs.toList(), "MediaFiles", FileSystemPackage.ChildType.DATA),
            AppDir(obbDirs.toList(), "Obb", FileSystemPackage.ChildType.CODE),
        )
        for (appDir in appDirs) {
            for (app in apps) {
                for (dir in appDir.dirs) {
                    val path = try {
                        dir?.canonicalPath?.replace(packageName, app.pkg)
                    } catch (e: IOException) {
                        null
                    } ?: continue
                    moveIntoPackage(app, media, path, appDir.name, appDir.type, blockSize)
                }
            }
        }
        apps.forEach { it.applyFilter(blockSize) }
        return apps.sortedWith(FileSystemEntry.COMPARE)
    }

    private fun moveIntoPackage(
        pkg: FileSystemPackage, root: FileSystemRoot, path: String, newName: String,
        type: FileSystemPackage.ChildType, blockSize: Long,
    ) {
        val e = root.getByAbsolutePath(path) ?: return
        e.remove(blockSize)
        val newRoot = FileSystemRoot.makeNode(newName, path, true)
        newRoot.setChildren(e.children, blockSize)
        pkg.addPublicChild(newRoot, type, blockSize)
    }

    fun searchRequest() {
        menu.searchRequest()
    }

    companion object {
        const val RESULT_DELETE_CONFIRMED = 10
        const val RESULT_DELETE_CANCELED = 11

        const val STATE_KEY = "state"
        const val KEY_KEY = "key"

        const val DELETE_PATH_KEY = "path"
        const val DELETE_ABSOLUTE_PATH_KEY = "absolute_path"
    }
}
