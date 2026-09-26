package com.google.android.diskusage.filesystem.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class FileSystemEntryTest {
    private lateinit var superRoot: FileSystemSuperRoot
    private lateinit var root: FileSystemRoot
    private lateinit var dirA: FileSystemEntry
    private lateinit var dirB: FileSystemEntry
    private lateinit var a1: FileSystemEntry
    private lateinit var a2: FileSystemEntry
    private lateinit var b1: FileSystemEntry
    private lateinit var c: FileSystemEntry

    private fun file(name: String, bytes: Long) =
        FileSystemFile.makeNode(null, name).initSizeInBytes(bytes, BLOCK_SIZE)

    private fun dir(name: String, vararg children: FileSystemEntry) =
        FileSystemEntry.makeNode(null, name).setChildren(sorted(*children), BLOCK_SIZE)

    private fun sorted(vararg children: FileSystemEntry) =
        arrayOf(*children).also { it.sortWith(FileSystemEntry.COMPARE) }

    @Before
    fun setUp() {
        a1 = file("a1.bin", 100 * BLOCK_SIZE)
        a2 = file("a2.txt", 50 * BLOCK_SIZE - 1)
        b1 = file("b1.bin", 10 * BLOCK_SIZE)
        c = file("c.jpg", 30 * BLOCK_SIZE)
        dirA = dir("A", a1, a2)
        dirB = dir("B", b1)
        root = FileSystemRoot.makeNode("Storage", "/sdcard", false)
        root.setChildren(sorted(dirA, dirB, c), BLOCK_SIZE)
        superRoot = FileSystemSuperRoot(BLOCK_SIZE)
        superRoot.setChildren(arrayOf<FileSystemEntry>(root), BLOCK_SIZE)
    }

    @Test
    fun sizesAreSummedUp() {
        assertEquals(50, a2.sizeInBlocks)
        assertEquals(150, dirA.sizeInBlocks)
        assertEquals(190, root.sizeInBlocks)
        assertEquals(190, superRoot.sizeInBlocks)
        assertSame(root, dirA.parent)
        assertEquals(listOf(dirA, c, dirB), root.children!!.toList())
    }

    @Test
    fun encodedSize() {
        assertEquals((100L shl 24) or (1L shl 18) or 400, a1.encodedSize)
        assertEquals((1L shl 24) or 1000, file("small", 1000).encodedSize)
        val huge = file("huge", 300L * 1024 * 1024 * 1024)
        assertEquals(300L * 1024 * 1024 * 1024 / BLOCK_SIZE, huge.sizeInBlocks)
        assertEquals(7L shl 18 or 300, huge.encodedSize and ((1L shl 24) - 1))
    }

    @Test
    fun paths() {
        assertEquals("A/a1.bin", a1.path2())
        assertEquals("/sdcard/A/a1.bin", a1.absolutePath())
        assertEquals("/sdcard", root.absolutePath())
        assertSame(a1, superRoot.getEntryByName("A/a1.bin", true))
        assertNull(superRoot.getEntryByName("A/missing", true))
        assertSame(b1, superRoot.getByAbsolutePath("/sdcard/B/b1.bin"))
        assertNull(superRoot.getByAbsolutePath("/other/B"))
    }

    @Test
    fun geometry() {
        assertEquals(3, superRoot.depth(a2))
        assertEquals(0, superRoot.getOffset(root))
        assertEquals(dirA.getSizeForRendering(), superRoot.getOffset(c))
        assertEquals(a1.getSizeForRendering(), superRoot.getOffset(a2))
        assertSame(a2, superRoot.findEntry(3, a1.getSizeForRendering() + 1))
        assertSame(c, superRoot.findEntry(2, dirA.getSizeForRendering() + 1))
        assertEquals(7, root.getNumFiles())
    }

    @Test
    fun removeAndInsert() {
        a1.remove(BLOCK_SIZE)
        assertEquals(listOf(a2), dirA.children!!.toList())
        assertEquals(50, dirA.sizeInBlocks)
        assertEquals(90, root.sizeInBlocks)
        assertEquals(listOf(dirA, c, dirB), root.children!!.toList())

        dirB.insert(file("big", 500 * BLOCK_SIZE), BLOCK_SIZE)
        assertEquals(510, dirB.sizeInBlocks)
        assertEquals(590, superRoot.sizeInBlocks)
    }

    @Test
    fun filter() {
        val filtered = superRoot.filter("bin", BLOCK_SIZE) as FileSystemSuperRoot
        val filteredRoot = filtered.children!!.single()
        assertEquals(listOf("A", "B"), filteredRoot.children!!.map { it.name })
        assertEquals(listOf("a1.bin"), filteredRoot.children!![0].children!!.map { it.name })
        assertEquals(110, filtered.sizeInBlocks)
        assertNull(superRoot.filter("nothing", BLOCK_SIZE))
    }

    @Test
    fun copy() {
        val copy = dirA.copy()
        assertNotSame(dirA, copy)
        assertEquals(dirA.encodedSize, copy.encodedSize)
        assertEquals(listOf("a1.bin", "a2.txt"), copy.children!!.map { it.name })
        assertSame(copy, copy.children!![0].parent)
    }

    companion object {
        private const val BLOCK_SIZE = 4096L
    }
}
