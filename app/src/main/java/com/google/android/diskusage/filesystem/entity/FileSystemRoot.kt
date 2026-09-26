package com.google.android.diskusage.filesystem.entity

class FileSystemRoot private constructor(
    name: String,
    val rootPath: String,
    override val isDeletable: Boolean,
) : FileSystemEntry(null, name) {

    override fun create(): FileSystemEntry = FileSystemRoot(name, rootPath, isDeletable)

    // don't match name
    override fun filter(pattern: CharSequence, blockSize: Long): FileSystemEntry? =
        filterChildren(pattern, blockSize)

    fun getByAbsolutePath(path: String): FileSystemEntry? {
        val rootPathWithSlash = withSlash(rootPath)
        val pathWithSlash = withSlash(path)
        if (pathWithSlash == rootPathWithSlash) {
            return getEntryByName(path, true)
        }
        if (pathWithSlash.startsWith(rootPathWithSlash)) {
            return getEntryByName(path.substring(rootPathWithSlash.length), true)
        }
        return children!!.firstNotNullOfOrNull { (it as? FileSystemRoot)?.getByAbsolutePath(path) }
    }

    companion object {
        fun makeNode(name: String, rootPath: String, deletable: Boolean) =
            FileSystemRoot(name, rootPath, deletable)

        private fun withSlash(path: String): String =
            if (path.isNotEmpty() && !path.endsWith('/')) "$path/" else path
    }
}
