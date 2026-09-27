/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
 * Copyright (C) 2026 taketake5656
 *   2026: converted to Kotlin and modified (see the Git history)
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
 * @file DiskUsage.kt
 * @brief ストレージの使用状況を表示するメイン画面。
 */
package com.google.android.diskusage.ui

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
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
import com.google.android.diskusage.core.SmallEntryName
import com.google.android.diskusage.databinding.ActivityCommonBinding
import com.google.android.diskusage.datasource.fast.LegacyFileImpl
import com.google.android.diskusage.datasource.fast.RootDeniedException
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

/**
 * @brief ストレージの使用状況をツリーで表示するメイン画面。
 *
 * ストレージをスキャンし(アプリの容量も含めることがある)、ツリーの表示、ファイルを開く、
 * 削除、再スキャンを行う。対象のストレージは Intent の KEY_KEY で受け取る。
 */
class DiskUsage : LoadableActivity() {
    /** @brief ツリーの表示状態(読み込みが終わるまでは null)。 */
    // FIXME: wrap to direct requests to rendering thread
    var fileSystemState: FileSystemState? = null
        private set

    private lateinit var mountKey: String
    /** @brief 表示しているストレージの識別子。 */
    override val key: String
        get() = mountKey

    /** @brief 確認画面で削除が承認された項目のパス(読み込み後に削除する)。 */
    private var pathToDelete: String? = null

    /** @brief ツールバーのメニュー。 */
    val menu = DiskUsageMenu(this)
    private val viewModel: DiskUsageViewModel by viewModels()
    /** @brief 読み込みが終わったら実行する処理(表示状態の復元など)。 */
    private val afterLoadActions = mutableListOf<Runnable>()

    /** @brief 削除の確認画面を開き、承認されたら削除するパスを覚える。 */
    private val deleteConfirmation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_DELETE_CONFIRMED) {
                pathToDelete = result.data?.getStringExtra(DELETE_PATH_KEY)
            }
        }

    /**
     * @brief 対象のストレージを取り出し、受け取った表示状態を復元する準備をする。
     *
     * ストレージが見つからなければ画面を閉じる。
     */
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

    /**
     * @brief 検索で絞り込んだツリーに表示を切り替える(カーソルはなるべく保つ)。
     * @param newRoot 新しいツリー(null なら何もしない)
     */
    fun applyPatternNewRoot(newRoot: FileSystemSuperRoot?) {
        if (newRoot != null) {
            fileSystemState?.replaceRootKeepCursor(newRoot)
        }
    }

    /**
     * @brief ツリーを読み込んで表示する。
     *
     * アプリの詳細設定から戻ったときは、アンインストールされたアプリをツリーから除く。
     * 読み込み後に、保留していた表示状態の復元と削除を行う。
     */
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

    /**
     * @brief アプリがインストールされているかを調べる。
     * @param pkg パッケージ名
     * @return インストールされていれば true
     */
    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    /** @brief 表示状態を保存し、次の読み込み後に復元するようにする。 */
    override fun onPause() {
        super.onPause()
        fileSystemState?.let { state ->
            val savedState = Bundle()
            state.saveState(savedState)
            afterLoadActions += Runnable { fileSystemState?.restoreState(savedState) }
        }
    }

    /** @brief ツールバーのメニューを作る。 */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        this.menu.setupToolbarMenu(menu)
        return true
    }

    /**
     * @brief アプリの詳細設定を開く(戻ったときにアンインストールされたかを確認する)。
     * @param pkg アプリの項目
     */
    private fun viewPackage(pkg: FileSystemPackage) {
        Timber.d("Show package = %s", pkg.pkg)
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${pkg.pkg}".toUri()))
        // FIXME: reload package data instead of just removing it
        pkgRemoved = pkg
    }

    /**
     * @brief 確認画面で承認された項目を削除する。
     * @param path ルートからの相対パス
     */
    private fun continueDelete(path: String) {
        val entry = fileSystemState?.masterRoot?.getEntryByName(path, true)
        if (entry != null) {
            BackgroundDelete.startDelete(this, entry)
        } else {
            toast(R.string.directory_to_delete_not_found)
        }
    }

    /**
     * @brief 削除してよいかを確認してから削除する。
     *
     * 中身のあるディレクトリは確認画面(DeleteActivity)で中のファイルを見せ、
     * それ以外はダイアログで確認する。まとめた項目は削除できない。
     *
     * @param entry 削除する項目
     */
    fun askForDeletion(entry: FileSystemEntry) {
        val path = entry.path2()
        val fullPath = entry.absolutePath()
        Timber.d("Deletion requested for %s", path)

        if (entry is FileSystemEntrySmall) {
            toast(R.string.delete_directory_instead)
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

    /**
     * @brief 画面を開く。
     * @param intent 開く Intent
     * @return 開けたら true、対応するアプリがなければ false
     */
    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /**
     * @brief 項目を他のアプリで開く。
     *
     * アプリ(とその中の項目)は詳細設定を、ディレクトリはファイルマネージャーを、
     * ファイルは拡張子の MIME タイプに対応するアプリを開く。まとめた項目は親を開く。
     *
     * @param selected 開く項目
     */
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
        val uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file)
            } else {
                file.toUri()
            }
        } catch (e: IllegalArgumentException) {
            // Not covered by provider_paths.xml
            Timber.e(e, "Can't share %s", path)
            toast(R.string.no_viewer_found)
            return
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

    /** @brief ストレージをスキャンし直し、新しいツリーに切り替える。 */
    fun rescan() {
        loadFiles({ newRoot, isCached ->
            fileSystemState?.startZoomAnimation(newRoot, !isCached)
        }, true)
    }

    /**
     * @brief 「戻る」で画面を閉じる。検索中なら先に検索を閉じる。
     *
     * 表示状態を結果として返し、選択画面から次に開いたときに復元できるようにする。
     */
    fun finishOnBack() {
        if (!menu.readyToFinish()) {
            return
        }
        val outState = Bundle()
        onSaveInstanceState(outState)
        setResult(0, Intent().putExtra(STATE_KEY, outState).putExtra(KEY_KEY, key))
        finish()
    }

    /**
     * @brief 選択中の項目が変わったときに、メニューとタイトルを更新する。
     * @param position 選択中の項目
     */
    fun setSelectedEntity(position: FileSystemEntry) {
        menu.update(position)
        title = getString(R.string.title_for_path, position.toTitleString())
    }

    /** @brief ツールバーの「上へ」で画面を閉じる。 */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishOnBack()
        }
        return super.onOptionsItemSelected(item)
    }

    /** @brief 表示状態と検索語を保存する。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = fileSystemState ?: return
        state.saveState(outState)
        menu.onSaveInstanceState(outState)
    }

    /** @brief 表示状態(まだ読み込み中なら読み込み後に)と検索語を復元する。 */
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

    /** @brief 1 つのストレージのツリーに使ってよいヒープの量(バイト)。ストレージの数で分け合う。 */
    private val memoryQuota: Int
        get() {
            val totalMem = getSystemService<ActivityManager>()!!.memoryClass * 1024 * 1024
            val numMountPoints = MountPoint.getMountPoints(this).size
            return totalMem / (numMountPoints + 1)
        }

    /**
     * @brief スキャンを実行し、その間 50 ms ごとに進捗ダイアログを更新する。
     * @param scanner 進捗を知らせるスキャナ
     * @param blocksToScan スキャンで見つかる見込みのブロック数(進捗の母数)
     * @param phaseEnd この段階が進捗バーのどこまでを使うか(0.0〜1.0)
     * @param scan スキャンの処理
     * @return スキャンの結果
     */
    private inline fun <T> withProgress(
        scanner: ProgressGenerator,
        blocksToScan: Long,
        phaseEnd: Double,
        scan: () -> T,
    ): T {
        val progressUpdater = object : Runnable {
            private var file: FileSystemEntry? = null
            private var phaseStarted = false

            override fun run() {
                persistentState.loading?.let { dialog ->
                    if (!phaseStarted) {
                        dialog.startPhase(phaseEnd)
                        phaseStarted = true
                    }
                    dialog.setMax(blocksToScan)
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

    /** @brief 小さなファイルをまとめた項目の名前(表示言語の文字列)。 */
    private val smallEntryName = SmallEntryName { numDirs, numFiles ->
        when {
            numDirs == 0 -> getString(R.string.small_files, numFiles)
            numFiles == 0 -> getString(R.string.small_dirs, numDirs)
            else -> getString(R.string.small_dirs_and_files, numDirs, numFiles)
        }
    }

    /**
     * @brief ファイルのスキャンで見つかる見込みのブロック数を返す。
     *
     * 内部共有ストレージは /data の一部でしかないので、ファイルシステムの使用済みブロック数では
     * 多すぎる。そこで StorageStatsManager の外部ストレージの容量を使う。
     *
     * @param mountPoint スキャンするストレージ
     * @param stats ストレージのブロック使用量
     * @return ブロック数
     */
    private fun estimateBlocksToScan(mountPoint: MountPoint, stats: FileSystemStats): Long {
        if (mountPoint.hasApps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val bytes = getSystemService<StorageStatsManager>()!!
                    .queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
                    .totalBytes
                if (bytes > 0) return bytes / stats.blockSize
            } catch (e: Exception) {
                Timber.w(e, "Failed to get the size of the external storage")
            }
        }
        return stats.busyBlocks
    }

    /**
     * @brief ストレージをスキャンして、表示するツリーを作る(バックグラウンドのスレッドで呼ばれる)。
     *
     * ネイティブスキャナで失敗したら Java 版スキャナで代わりにスキャンする
     * (root が拒否されたときはダイアログで知らせる)。アプリの容量も表示するストレージでは
     * ファイルを「メディア」にまとめ、アプリの一覧を加える。最後にシステムの領域と空き容量を加える。
     *
     * @return ツリーのルート
     */
    override fun scan(): FileSystemSuperRoot {
        val mountPoint = MountPoint.getForKey(this, key)!!
        val stats = FileSystemStats(mountPoint)
        val heap = memoryQuota
        val blocksToScan = estimateBlocksToScan(mountPoint, stats)
        // The apps are loaded after the files and take some time as well
        val filesPhaseEnd = if (mountPoint.hasApps) FILES_PHASE_WITH_APPS else 1.0

        val rootElement = try {
            val scanner = NativeScanner(stats.blockSize, stats.busyBlocks, heap, smallEntryName)
            withProgress(scanner, blocksToScan, filesPhaseEnd) { scanner.scan(mountPoint) }
        } catch (e: Exception) {
            if (e !is RuntimeException && e !is IOException) throw e
            Timber.w(e, "Native scanner failed, falling back to Java scanner")
            if (e is RootDeniedException) {
                handler.post {
                    if (!isFinishing) {
                        AlertDialog.Builder(this)
                            .setMessage(R.string.root_denied)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            val scanner = Scanner(20, stats.blockSize, stats.busyBlocks, heap, smallEntryName)
            withProgress(scanner, blocksToScan, filesPhaseEnd) {
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

    /**
     * @brief アプリの容量を読み込む(Android 8 以降)。
     * @param blockSize 表示のブロックサイズ
     * @return アプリの項目。読み込めなければ null
     */
    private fun loadApps2SD(blockSize: Long): List<FileSystemPackage>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Apps2SDLoader(this).load(blockSize) else null
    } catch (t: Throwable) {
        Timber.e(t, "loadApps2SD: Problem loading apps2sd info")
        null
    }

    /**
     * @brief アプリ専用ディレクトリの種類。
     * @param dirs このアプリでの場所(パッケージ名を置き換えて他のアプリの場所にする)
     * @param name アプリの項目の中での表示名
     * @param type アプリのどの容量に含まれるか
     */
    private class AppDir(val dirs: List<File?>, val name: String, val type: FileSystemPackage.ChildType)

    /**
     * @brief ツリーの「メディア」の中にあるアプリ専用ディレクトリを、アプリの項目の中に移す。
     * @param apps アプリの項目
     * @param media メディアの項目
     * @param blockSize 表示のブロックサイズ
     * @return 移した後のサイズで並べ替えたアプリの項目
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

    /**
     * @brief パスの項目をツリーから取り除き、アプリの項目の子にする。
     * @param pkg アプリの項目
     * @param root 探すツリー
     * @param path 移すディレクトリの絶対パス(ツリーになければ何もしない)
     * @param newName アプリの項目の中での表示名
     * @param type アプリのどの容量に含まれるか
     * @param blockSize 表示のブロックサイズ
     */
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

    /** @brief 検索キーが押されたときの処理をメニューに渡す。 */
    fun searchRequest() {
        menu.searchRequest()
    }

    companion object {
        /** @brief 削除の確認画面の結果: 削除する。 */
        const val RESULT_DELETE_CONFIRMED = 10
        /** @brief 削除の確認画面の結果: 取り消し。 */
        const val RESULT_DELETE_CANCELED = 11

        /** @brief Intent の extra: 表示状態の Bundle。 */
        const val STATE_KEY = "state"
        /** @brief Intent の extra: ストレージの識別子(MountPoint.key)。 */
        const val KEY_KEY = "key"

        /**
         * @brief アプリも読み込むときに、ファイルのスキャンが進捗バーで受け持つ割合。
         *
         * アプリの読み込みの方が時間がかかる。POCO F6 でキャッシュのない状態で、
         * 731 個のアプリに 12 秒、23 GiB のファイルに 1.6 秒だった。
         */
        private const val FILES_PHASE_WITH_APPS = 0.25

        /** @brief Intent の extra: 削除する項目のルートからの相対パス。 */
        const val DELETE_PATH_KEY = "path"
        /** @brief Intent の extra: 削除する項目の絶対パス。 */
        const val DELETE_ABSOLUTE_PATH_KEY = "absolute_path"
    }
}
