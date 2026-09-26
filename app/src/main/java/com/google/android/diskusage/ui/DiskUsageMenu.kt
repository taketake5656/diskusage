package com.google.android.diskusage.ui

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Html
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import androidx.core.graphics.toColorInt
import androidx.core.view.forEach
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.AboutDialogBinding
import com.google.android.diskusage.datasource.SearchManager
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.filesystem.entity.FileSystemSpecial
import com.google.android.diskusage.filesystem.entity.FileSystemSuperRoot
import com.google.android.diskusage.filesystem.mnt.MountPoint
import com.google.android.diskusage.utils.AppIconCache.getOrLoadBitmap
import com.google.android.diskusage.utils.item
import splitties.resources.styledColor
import timber.log.Timber

class DiskUsageMenu(val diskusage: DiskUsage) {
    var masterRoot: FileSystemSuperRoot? = null
    private var searchPattern: String? = null
    private val searchManager by lazy { SearchManager(this) }
    private var selectedEntity: FileSystemEntry? = null
    private var searchView: SearchView? = null
    private var origSearchBackground: Drawable? = null
    private lateinit var viewModel: DiskUsageViewModel

    fun onCreate(viewModel: DiskUsageViewModel) {
        this.viewModel = viewModel
//        val actionBar = checkNotNull(diskusage.actionBar)
//        actionBar.setDisplayHomeAsUpEnabled(true)
    }

    /** Closes the search first, if it is open. */
    fun readyToFinish(): Boolean {
        val searchView = searchView
        if (searchView == null || searchView.isIconified) return true
        searchView.setQuery("", false)
        searchView.isIconified = true
        return false
    }

    fun searchRequest() {
    }

    private fun setupSearchMenuItem(menu: Menu): MenuItem {
        val iconTint = diskusage.styledColor(android.R.attr.colorControlNormal)
        return menu.item(
            R.string.button_search,
            android.R.drawable.ic_search_category_default,
            iconTint,
            true
        ).apply {
            actionView = SearchView(diskusage).also {
                searchView = it
                origSearchBackground = it.background
                if (searchPattern != null) {
                    it.isIconified = false
                    it.setQuery(searchPattern, false)
                }
                it.setOnCloseListener {
                    Timber.d("Search process closed")
                    searchPattern = null
                    diskusage.applyPatternNewRoot(masterRoot)
                    false
                }
                it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        onQueryTextChange(query)
                        return false
                    }

                    override fun onQueryTextChange(newText: String): Boolean {
                        Timber.d("Search query changed to: %s", newText)
                        searchPattern = newText
                        applyPattern(searchPattern)
                        return true
                    }
                })
            }
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search", searchPattern)
    }

    fun onRestoreInstanceState(inState: Bundle) {
        searchPattern = inState.getString("search")
    }

    fun wrapAndSetContentView(view: View?, newRoot: FileSystemSuperRoot?) {
        masterRoot = newRoot
        updateMenu()
        diskusage.setContentView(view)
        diskusage.invalidateOptionsMenu()
    }

    fun applyPattern(searchQuery: String?) {
        if (searchQuery == null || masterRoot == null) return

        if (searchQuery.isEmpty()) {
            searchManager.cancelSearch()
            finishedSearch(masterRoot, searchQuery)
        } else {
            searchManager.search(searchQuery)
        }
    }

    fun finishedSearch(newRoot: FileSystemSuperRoot?, searchQuery: String?): Boolean {
        return if (newRoot != null) {
            searchView?.background = origSearchBackground
            diskusage.applyPatternNewRoot(newRoot)
            true
        } else {
            searchView?.setBackgroundColor("#FFDDDD".toColorInt())
            diskusage.applyPatternNewRoot(masterRoot)
            false
        }
    }

    fun update(position: FileSystemEntry?) {
        this.selectedEntity = position
        updateMenu()
    }

    fun setupToolbarMenu(menu: Menu) {
        setupSearchMenuItem(menu)

        menu.item(R.string.button_show, showAsAction = true) {
            selectedEntity?.let { diskusage.view(it) }
        }.apply {
            viewModel.showButton.observe(diskusage) {
                isVisible = it
            }
        }

        menu.item(R.string.button_rescan) {
            diskusage.rescan()
        }.apply {
            viewModel.rescanButton.observe(diskusage) {
                isVisible = it
            }
        }

        menu.item(R.string.button_delete) {
            selectedEntity?.let { diskusage.askForDeletion(it) }
        }.apply {
            viewModel.deleteButton.observe(diskusage) {
                isVisible = it
            }
        }

        viewModel.toolbarActionButtonVisible.observe(diskusage) {
            menu.forEach { item -> item.isVisible = it }
        }

        menu.forEach { it.isVisible = false }

        menu.item(R.string.action_about) {
            val binding =
                AboutDialogBinding.inflate(
                    LayoutInflater.from(
                        diskusage
                    ), null, false
                )
            binding.sourceCode.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(
                    diskusage.getString(
                        R.string.about_view_source_code,
                        "<b><a href=\"https://github.com/taketake5656/diskusage\">GitHub</a></b>"
                    ),
                    Html.FROM_HTML_MODE_LEGACY
                )
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(
                    diskusage.getString(
                        R.string.about_view_source_code,
                        "<b><a href=\"https://github.com/taketake5656/diskusage\">GitHub</a></b>"
                    )
                )
            }
            binding.icon.setImageBitmap(
                getOrLoadBitmap(
                    diskusage,
                    diskusage.applicationInfo,
                    Process.myUid() / 100000,
                    diskusage.resources.getDimensionPixelSize(R.dimen.default_app_icon_size)
                )
            )
            try {
                binding.versionName.text = diskusage.packageManager.getPackageInfo(
                    diskusage.packageName, 0
                ).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.e(e, "Package '${diskusage.packageName}' not found")
            }
            AlertDialog.Builder(diskusage)
                .setView(binding.root)
                .show()
        }
        updateMenu()
    }

    private fun updateMenu() {
        val state = diskusage.fileSystemState
        if (state == null) {
            viewModel.hideToolBarActionButton()
            return
        }

        viewModel.enableRescanButton()
        viewModel.showToolbarActionButton()

        val selected = selectedEntity
        val view = selected != null &&
            selected !== state.masterRoot.children?.get(0) &&
            selected !is FileSystemSpecial
        if (view) {
            viewModel.enableShowButton()
        } else {
            viewModel.disableShowButton()
        }

        val fileOrNotSearching = searchPattern == null || selected?.children == null
        val mountPoint = MountPoint.getForKey(diskusage, diskusage.key)
        if (view && selected.isDeletable && fileOrNotSearching &&
            mountPoint?.isDeleteSupported == true
        ) {
            viewModel.enableDeleteButton()
        } else {
            viewModel.disableDeleteButton()
        }
    }
}
