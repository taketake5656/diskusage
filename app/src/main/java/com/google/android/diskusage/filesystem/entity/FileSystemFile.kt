package com.google.android.diskusage.filesystem.entity

class FileSystemFile private constructor(parent: FileSystemEntry?, name: String) :
    FileSystemEntry(parent, name) {

    override val isDeletable: Boolean
        get() = true

    override fun create(): FileSystemEntry = FileSystemFile(null, name)

    companion object {
        fun makeNode(parent: FileSystemEntry?, name: String): FileSystemEntry =
            FileSystemFile(parent, name)
    }
}
