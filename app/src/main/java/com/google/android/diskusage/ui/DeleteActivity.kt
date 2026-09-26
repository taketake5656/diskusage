/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.diskusage.R
import com.google.android.diskusage.databinding.DeleteViewBinding
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import com.google.android.diskusage.ui.common.FileInfo
import com.google.android.diskusage.ui.common.FileInfoAdapter
import com.google.android.diskusage.utils.applySystemBarsPadding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Asks for confirmation to delete a directory, showing the files inside. */
class DeleteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileSystemEntry.setupStrings(this)
        val path = intent.getStringExtra(DiskUsage.DELETE_PATH_KEY)
        val absolutePath = intent.getStringExtra(DiskUsage.DELETE_ABSOLUTE_PATH_KEY)
        Timber.d("onCreate: %s -> %s", path, absolutePath)

        val binding = DeleteViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsPadding()
        val sizeString = intent.getStringExtra(SIZE_KEY)
        val count = intent.getIntExtra(NUM_FILES_KEY, 0)
        binding.summary.text = getString(R.string.delete_summary, count, sizeString)
        binding.list.layoutManager = LinearLayoutManager(this)

        if (absolutePath != null) {
            lifecycleScope.launch {
                val infos = withContext(Dispatchers.IO) { listFiles(File(absolutePath)) }
                binding.list.adapter = FileInfoAdapter(infos)
            }
        }
        setResult(DiskUsage.RESULT_DELETE_CANCELED)
    }

    private fun listFiles(root: File): List<FileInfo> =
        root.walkTopDown().mapNotNull { file ->
            when {
                file.isFile -> FileInfo(FileSystemEntry.calcSizeString(file.length()), file.name)
                file.isDirectory -> FileInfo("", file.name)
                else -> null
            }
        }.toList()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ask_for_delete_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.ask_cancel -> setResult(DiskUsage.RESULT_DELETE_CANCELED)
            R.id.ask_delete -> setResult(
                DiskUsage.RESULT_DELETE_CONFIRMED,
                Intent().putExtra(DiskUsage.DELETE_PATH_KEY,
                    intent.getStringExtra(DiskUsage.DELETE_PATH_KEY)),
            )
            else -> return super.onOptionsItemSelected(item)
        }
        finish()
        return true
    }

    companion object {
        const val NUM_FILES_KEY = "numFiles"
        const val SIZE_KEY = "size"
    }
}
